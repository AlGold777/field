package app.soundfield.core

import app.soundfield.dsp.Radix2Fft
import app.soundfield.dsp.SpatialDspConfig
import app.soundfield.dsp.SpatialLoudnessCompensator
import app.soundfield.dsp.SpatialPeakLimiter
import app.soundfield.dsp.SpatialReflectionProcessor
import app.soundfield.dsp.SpatialProcessor
import app.soundfield.dsp.XtcFilterDesigner
import app.soundfield.dsp.XtcFilterSet
import app.soundfield.dsp.XtcProcessor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Streaming STFT→ISTFT source. S2 deliberately applies the identity transform. */
class SpatialSource(
    private val inner: AudioSource,
    private val startAtFrame: Long = 0L,
    cacheSeconds: Int = DEFAULT_CACHE_SECONDS,
    spatialConfig: SpatialDspConfig = SpatialDspConfig(),
    private val xtcDesign: CompletableFuture<XtcFilterSet>? = null,
) : AudioSource, AutoCloseable, PrefetchableAudioSource {
    private val bypass = !spatialConfig.enabled &&
        !(spatialConfig.reflectionsEnabled && spatialConfig.reflectionTaps.isNotEmpty())
    override val mode get() = inner.mode
    override val title get() = inner.title
    override val artist get() = inner.artist
    override val totalFrames get() = inner.totalFrames
    override val readyFrames: Long get() = minOf(inner.readyFrames, processedHead)
    override val complete: Boolean get() = inner.complete && processedHead >= inner.totalFrames
    val decodedReadyFrames: Long get() = inner.readyFrames

    val cacheCapacityFrames: Int = (cacheSeconds.coerceAtLeast(1) * SF.SAMPLE_RATE)
    private val cache = FloatArray(cacheCapacityFrames * 2)
    private val fft = Radix2Fft(FFT_SIZE)
    private val spatialProcessor = SpatialProcessor(spatialConfig)
    private val reflectionProcessor = if (spatialConfig.reflectionsEnabled && spatialConfig.reflectionTaps.isNotEmpty())
        SpatialReflectionProcessor(spatialConfig.reflectionTaps, strength = spatialConfig.strength) else null
    private val peakLimiter = SpatialPeakLimiter()
    @Volatile private var xtcProcessor: XtcProcessor? = null
    @Volatile var xtcApplied: Boolean = false
        private set
    @Volatile var xtcDesignTimedOut: Boolean = false
        private set
    private val loudnessCompensator = SpatialLoudnessCompensator(
        enabled = spatialConfig.enabled && spatialConfig.preserveLoudness,
        fixedCompensationDb = spatialConfig.fixedLoudnessCompensationDb,
    )
    private val window = DoubleArray(FFT_SIZE) { i -> sqrt(0.5 - 0.5 * cos(2.0 * PI * i / FFT_SIZE)) }
    private val realL = DoubleArray(FFT_SIZE)
    private val imagL = DoubleArray(FFT_SIZE)
    private val realR = DoubleArray(FFT_SIZE)
    private val imagR = DoubleArray(FFT_SIZE)
    private val accumulationCapacity = nextPowerOfTwo(FFT_SIZE + HOP_SIZE)
    private val accumulationMask = accumulationCapacity - 1
    private val accumulationL = DoubleArray(accumulationCapacity)
    private val accumulationR = DoubleArray(accumulationCapacity)
    private val accumulationWeight = DoubleArray(accumulationCapacity)
    private val workerRunning = java.util.concurrent.atomic.AtomicBoolean(true)
    private val requestedHead = java.util.concurrent.atomic.AtomicLong(
        minOf(inner.totalFrames, startAtFrame + cacheCapacityFrames).coerceAtLeast(startAtFrame),
    )

    @Volatile private var processedHead = initialProcessingHead()
    @Volatile var processingError: Throwable? = null
        private set
    @Volatile var realtimeFactor = 0.0
        private set
    val sideMidDb: Double get() = spatialProcessor.sideMidDb
    val correlation: Double get() = spatialProcessor.correlation
    val limiterReductionDb: Double get() = peakLimiter.maxReductionDb
    val loudnessCompensationDb: Double get() = loudnessCompensator.compensationDb
    private val missCount = java.util.concurrent.atomic.AtomicLong()
    val misses: Long get() = missCount.get()
    private val restartCount = java.util.concurrent.atomic.AtomicLong()
    val restarts: Long get() = restartCount.get()
    private val restartAtFrame = java.util.concurrent.atomic.AtomicLong(NO_RESTART)
    private var nextFrameStart = initialFrameStart()
    private val thread: Thread

    init {
        require(startAtFrame in 0..inner.totalFrames)
        require(cacheCapacityFrames >= HOP_SIZE)
        thread = Thread(::processLoop, "sf-spatial").apply {
            priority = minOf(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1)
            isDaemon = true
            start()
        }
    }

    /** Ask the processor to make this exclusive frame position available. Safe from consumer threads. */
    override fun requestThrough(frameExclusive: Long) {
        val target = frameExclusive.coerceIn(startAtFrame, inner.totalFrames)
        while (true) {
            val previous = requestedHead.get()
            if (target <= previous || requestedHead.compareAndSet(previous, target)) break
        }
        val inputTarget = (target + if (xtcDesign != null) XTC_INPUT_AHEAD_FRAMES else 0L)
            .coerceAtMost(inner.totalFrames)
        (inner as? PrefetchableAudioSource)?.requestThrough(inputTarget)
    }

    override fun sample(frame: Long, ch: Int): Float {
        if (ch !in 0..1 || frame < startAtFrame || frame >= inner.totalFrames) return 0f
        requestThrough(frame + 1)
        var head = processedHead
        val firstCached = maxOf(startAtFrame, head - cacheCapacityFrames)
        if (frame < firstCached || frame >= head) {
            missCount.incrementAndGet()
            if (frame < firstCached || frame - head >= cacheCapacityFrames) scheduleRestart(frame)
            return Float.NaN
        }
        val index = ((frame % cacheCapacityFrames) * 2L + ch).toInt()
        return cache[index]
    }

    private fun processLoop() {
        try {
            xtcProcessor = xtcDesign?.let { future ->
                try {
                    val filters = future.get(XTC_DESIGN_WAIT_MS, TimeUnit.MILLISECONDS)
                    xtcApplied = true
                    XtcProcessor(filters, ::processXtcFrame)
                } catch (_: java.util.concurrent.TimeoutException) {
                    xtcDesignTimedOut = true
                    null
                } catch (_: Exception) {
                    null
                }
            }
            while (workerRunning.get()) {
                applyPendingRestart()
                val ready = inner.readyFrames.coerceAtLeast(0L)
                val sourceComplete = inner.complete
                val wanted = requestedHead.get()
                val xtc = xtcProcessor
                val xtcAhead = if (xtc != null) XTC_INPUT_AHEAD_FRAMES else 0L
                val tailFrames = peakLimiter.lookaheadFrames.toLong() + xtcAhead
                val processingTarget = (wanted + tailFrames).coerceAtMost(inner.totalFrames + tailFrames)
                var progressed = false
                if (bypass) {
                    val end = minOf(wanted, ready, inner.totalFrames)
                    val processingStartedAt = System.nanoTime()
                    var frame = processedHead
                    while (workerRunning.get() && frame < end) {
                        val cacheIndex = ((frame % cacheCapacityFrames) * 2L).toInt()
                        cache[cacheIndex] = inner.sample(frame, 0).takeUnless { it.isNaN() } ?: 0f
                        cache[cacheIndex + 1] = inner.sample(frame, 1).takeUnless { it.isNaN() } ?: 0f
                        frame++
                    }
                    if (frame > processedHead) {
                        val processingNs = (System.nanoTime() - processingStartedAt).coerceAtLeast(1L)
                        val instantFactor = (frame - processedHead).toDouble() * 1e9 / (processingNs * SF.SAMPLE_RATE)
                        realtimeFactor = if (realtimeFactor <= 0.0) instantFactor
                            else realtimeFactor * 0.9 + instantFactor * 0.1
                        processedHead = frame
                        progressed = true
                    }
                }
                while (!bypass && workerRunning.get() && nextFrameStart - spatialProcessor.lookaheadHops * HOP_SIZE - peakLimiter.lookaheadFrames < processingTarget &&
                    nextFrameStart - spatialProcessor.lookaheadHops * HOP_SIZE < inner.totalFrames + tailFrames &&
                    (sourceComplete || nextFrameStart + FFT_SIZE <= ready)) {
                    if (restartAtFrame.get() != NO_RESTART) break
                    val frameStartedAt = System.nanoTime()
                    val outputStart = processFrame(nextFrameStart)
                    if (outputStart != null) publishHop(outputStart)
                    nextFrameStart += HOP_SIZE
                    val processingNs = (System.nanoTime() - frameStartedAt).coerceAtLeast(1L)
                    val instantFactor = HOP_SIZE.toDouble() * 1e9 / (processingNs * SF.SAMPLE_RATE)
                    realtimeFactor = if (realtimeFactor <= 0.0) instantFactor
                        else realtimeFactor * 0.9 + instantFactor * 0.1
                    progressed = true
                }
                if (!progressed) Thread.sleep(2)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            processingError = t
            workerRunning.set(false)
        }
    }

    private fun scheduleRestart(frame: Long) {
        val target = frame.coerceIn(startAtFrame, inner.totalFrames)
        restartAtFrame.set(target)
        requestThrough(target + 1)
    }

    private fun applyPendingRestart() {
        val target = restartAtFrame.getAndSet(NO_RESTART)
        if (target == NO_RESTART) return
        val warmupStart = maxOf(startAtFrame, target - WARMUP_FRAMES)
        nextFrameStart = aligned(warmupStart) - 3L * HOP_SIZE
        java.util.Arrays.fill(accumulationL, 0.0)
        java.util.Arrays.fill(accumulationR, 0.0)
        java.util.Arrays.fill(accumulationWeight, 0.0)
        spatialProcessor.reset()
        reflectionProcessor?.reset()
        xtcProcessor?.reset()
        peakLimiter.reset()
        loudnessCompensator.reset()
        processedHead = warmupStart
        realtimeFactor = 0.0
        restartCount.incrementAndGet()
    }

    private fun initialProcessingHead(): Long = maxOf(0L, startAtFrame - WARMUP_FRAMES)

    private fun initialFrameStart(): Long = aligned(maxOf(0L, startAtFrame - WARMUP_FRAMES)) - 3L * HOP_SIZE

    private fun processFrame(frameStart: Long): Long? {
        for (i in 0 until FFT_SIZE) {
            val frame = frameStart + i
            val left = if (frame < 0L || frame >= inner.totalFrames) 0f else inner.sample(frame, 0)
            val right = if (frame < 0L || frame >= inner.totalFrames) 0f else inner.sample(frame, 1)
            realL[i] = (if (left.isNaN()) 0f else left) * window[i]
            imagL[i] = 0.0
            realR[i] = (if (right.isNaN()) 0f else right) * window[i]
            imagR[i] = 0.0
        }
        fft.transform(realL, imagL)
        fft.transform(realR, imagR)
        if (!spatialProcessor.process(realL, imagL, realR, imagR)) return null
        fft.transform(realL, imagL, inverse = true)
        fft.transform(realR, imagR, inverse = true)
        val outputStart = frameStart - spatialProcessor.lookaheadHops * HOP_SIZE
        for (i in 0 until FFT_SIZE) {
            val frame = outputStart + i
            if (frame < 0L || frame >= inner.totalFrames) continue
            val index = (frame.toInt() and accumulationMask)
            val w = window[i]
            accumulationL[index] += realL[i] * w
            accumulationR[index] += realR[i] * w
            accumulationWeight[index] += w * w
        }
        return outputStart
    }

    private fun publishHop(frameStart: Long) {
        val xtc = xtcProcessor
        val tailFrames = peakLimiter.lookaheadFrames.toLong() + if (xtc != null) XTC_INPUT_AHEAD_FRAMES else 0L
        val blockEnd = minOf(frameStart + HOP_SIZE, inner.totalFrames + tailFrames)
        val blockStart = maxOf(frameStart, 0L)
        for (frame in blockStart until blockEnd) {
            val rawLeft: Float
            val rawRight: Float
            if (frame < inner.totalFrames) {
                val accumulationIndex = frame.toInt() and accumulationMask
                val weight = accumulationWeight[accumulationIndex]
                rawLeft = if (weight > 1e-12) (accumulationL[accumulationIndex] / weight).toFloat() else 0f
                rawRight = if (weight > 1e-12) (accumulationR[accumulationIndex] / weight).toFloat() else 0f
                accumulationL[accumulationIndex] = 0.0
                accumulationR[accumulationIndex] = 0.0
                accumulationWeight[accumulationIndex] = 0.0
            } else {
                rawLeft = 0f; rawRight = 0f
            }
            val inputLeft = if (frame in 0 until inner.totalFrames) inner.sample(frame, 0).takeUnless { it.isNaN() } ?: 0f else 0f
            val inputRight = if (frame in 0 until inner.totalFrames) inner.sample(frame, 1).takeUnless { it.isNaN() } ?: 0f else 0f
            val reflected = reflectionProcessor?.process(inputLeft, inputRight) ?: 0f
            val processedLeft = rawLeft + reflected
            val processedRight = rawRight - reflected
            loudnessCompensator.observe(inputLeft, inputRight, processedLeft, processedRight)
            val compensation = loudnessCompensator.gain()
            val compensatedLeft = (processedLeft * compensation).toFloat()
            val compensatedRight = (processedRight * compensation).toFloat()
            if (xtc == null) processLimited(frame, compensatedLeft, compensatedRight)
            else xtc.push(frame, compensatedLeft, compensatedRight)
        }
    }

    private fun processXtcFrame(frame: Long, left: Float, right: Float) = processLimited(frame, left, right)

    private fun processLimited(frame: Long, left: Float, right: Float) {
        if (!peakLimiter.process(frame, left, right)) return
        val outputFrame = peakLimiter.outputFrame
        if (outputFrame in startAtFrame until inner.totalFrames) {
            val cacheIndex = ((outputFrame % cacheCapacityFrames) * 2L).toInt()
            cache[cacheIndex] = peakLimiter.outputLeft
            cache[cacheIndex + 1] = peakLimiter.outputRight
        }
        if (outputFrame >= 0L && outputFrame + 1 > processedHead)
            processedHead = minOf(outputFrame + 1, inner.totalFrames)
    }

    override fun close() {
        if (!workerRunning.getAndSet(false)) return
        thread.interrupt()
        if (Thread.currentThread() !== thread) thread.join(250)
    }

    private fun aligned(frame: Long): Long = frame - frame % HOP_SIZE

    companion object {
        const val FFT_SIZE = 2_048
        const val HOP_SIZE = 512
        const val WARMUP_FRAMES = SF.SAMPLE_RATE
        const val DEFAULT_CACHE_SECONDS = 40
        private val XTC_INPUT_AHEAD_FRAMES = (XtcFilterDesigner.DELAY_FRAMES + XtcProcessor.BLOCK_FRAMES + SF.SAMPLE_RATE / 200).toLong()
        private const val NO_RESTART = Long.MIN_VALUE
        /** Фильтр обычно уже рассчитан заранее (Engine.prewarmXtc); ждём холодный расчёт, а не пропускаем XTC на весь трек. */
        private const val XTC_DESIGN_WAIT_MS = 4_000L

        private fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) result = result shl 1
            return result
        }
    }
}

/** Keeps old audio before a scheduled update and crossfades to a freshly warmed source. */
class CrossfadeAudioSource(
    val previous: AudioSource,
    val next: AudioSource,
    val startFrame: Long,
    fadeFrames: Int = SF.SAMPLE_RATE / 20,
) : AudioSource, PrefetchableAudioSource {
    private val fadeLength = fadeFrames.coerceAtLeast(1).toLong()
    override val mode get() = next.mode
    override val title get() = next.title
    override val artist get() = next.artist
    override val totalFrames get() = next.totalFrames
    override val readyFrames get() = if (next.readyFrames >= startFrame + fadeLength)
        next.readyFrames else minOf(previous.readyFrames, next.readyFrames)
    override val complete get() = next.complete && readyFrames >= totalFrames

    override fun requestThrough(frameExclusive: Long) {
        (previous as? PrefetchableAudioSource)?.requestThrough(frameExclusive)
        (next as? PrefetchableAudioSource)?.requestThrough(frameExclusive)
    }

    override fun sample(frame: Long, ch: Int): Float {
        if (frame < startFrame) return previous.sample(frame, ch)
        val mix = ((frame - startFrame).toDouble() / fadeLength).coerceIn(0.0, 1.0).toFloat()
        if (mix >= 1f) return next.sample(frame, ch)
        val old = previous.sample(frame, ch)
        val fresh = next.sample(frame, ch)
        if (old.isNaN() || fresh.isNaN()) return Float.NaN
        return old * (1f - mix) + fresh * mix
    }
}
