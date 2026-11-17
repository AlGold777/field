package app.soundfield.core

import app.soundfield.dsp.Radix2Fft
import app.soundfield.dsp.SpatialDspConfig
import app.soundfield.dsp.SpatialLoudnessCompensator
import app.soundfield.dsp.SceneRoom
import app.soundfield.dsp.SpatialProcessor
import app.soundfield.dsp.XtcFilterDesigner
import app.soundfield.dsp.XtcFilterSet
import app.soundfield.dsp.XtcProcessor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Потоковый STFT→ISTFT-источник сцены: ширина и глубина (STFT-стадия + комната), затем компенсатор громкости и XTC. */
class SpatialSource(
    private val inner: AudioSource,
    private val startAtFrame: Long = 0L,
    cacheSeconds: Int = DEFAULT_CACHE_SECONDS,
    spatialConfig: SpatialDspConfig = SpatialDspConfig(),
    private val xtcDesign: CompletableFuture<XtcFilterSet>? = null,
) : AudioSource, AutoCloseable, PrefetchableAudioSource, TrackMeters {
    override val truePeakDbtp: Double get() = (inner as? TrackMeters)?.truePeakDbtp ?: Double.NaN
    override val loudnessLufs: Double get() = (inner as? TrackMeters)?.loudnessLufs ?: Double.NaN
    private val bypass = !spatialConfig.enabled
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
    /** Комната работает только при глубине > 0: иначе посыла нет вовсе (ни обратных БПФ, ни аккумуляторов). */
    private val roomEnabled = spatialConfig.enabled && spatialConfig.depth > 0.0
    private val room = if (roomEnabled) SceneRoom(spatialConfig.depth) else null
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
    // L и R идут одним комплексным БПФ (L + jR): 3 БПФ на шаг вместо 6.
    private val packRe = DoubleArray(FFT_SIZE)
    private val packIm = DoubleArray(FFT_SIZE)
    private val realL = DoubleArray(FFT_SIZE)
    private val imagL = DoubleArray(FFT_SIZE)
    private val realR = DoubleArray(FFT_SIZE)
    private val imagR = DoubleArray(FFT_SIZE)
    private val accumulationCapacity = nextPowerOfTwo(FFT_SIZE + HOP_SIZE)
    private val accumulationMask = accumulationCapacity - 1
    private val accumulationL = DoubleArray(accumulationCapacity)
    private val accumulationR = DoubleArray(accumulationCapacity)
    private val accumulationWeight = DoubleArray(accumulationCapacity)
    private val sendRealL = DoubleArray(if (roomEnabled) FFT_SIZE else 0)
    private val sendImagL = DoubleArray(if (roomEnabled) FFT_SIZE else 0)
    private val sendRealR = DoubleArray(if (roomEnabled) FFT_SIZE else 0)
    private val sendImagR = DoubleArray(if (roomEnabled) FFT_SIZE else 0)
    private val sendAccumulationL = DoubleArray(if (roomEnabled) accumulationCapacity else 0)
    private val sendAccumulationR = DoubleArray(if (roomEnabled) accumulationCapacity else 0)
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
        // sample() зовёт это на каждый отвод интерполятора из аудиопотока: вниз по цепочке — только когда голова ушла вперёд.
        var advanced = false
        while (true) {
            val previous = requestedHead.get()
            if (target <= previous) break
            if (requestedHead.compareAndSet(previous, target)) { advanced = true; break }
        }
        if (!advanced) return
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
                val tailFrames = xtcAhead
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
                while (!bypass && workerRunning.get() && nextFrameStart - spatialProcessor.lookaheadHops * HOP_SIZE < processingTarget &&
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
        room?.reset()
        java.util.Arrays.fill(sendAccumulationL, 0.0)
        java.util.Arrays.fill(sendAccumulationR, 0.0)
        xtcProcessor?.reset()
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
            packRe[i] = (if (left.isNaN()) 0f else left) * window[i]
            packIm[i] = (if (right.isNaN()) 0f else right) * window[i]
        }
        fft.transform(packRe, packIm)
        for (k in 0 until FFT_SIZE) {
            val m = (FFT_SIZE - k) and (FFT_SIZE - 1)
            realL[k] = 0.5 * (packRe[k] + packRe[m]); imagL[k] = 0.5 * (packIm[k] - packIm[m])
            realR[k] = 0.5 * (packIm[k] + packIm[m]); imagR[k] = 0.5 * (packRe[m] - packRe[k])
        }
        val produced = if (roomEnabled) spatialProcessor.process(realL, imagL, realR, imagR, sendRealL, sendImagL, sendRealR, sendImagR)
            else spatialProcessor.process(realL, imagL, realR, imagR)
        if (!produced) return null
        packSpectra(realL, imagL, realR, imagR)
        fft.transform(realL, imagL, inverse = true) // после обратного БПФ: realL — L, imagL — R
        if (roomEnabled) {
            packSpectra(sendRealL, sendImagL, sendRealR, sendImagR)
            fft.transform(sendRealL, sendImagL, inverse = true)
        }
        val outputStart = frameStart - spatialProcessor.lookaheadHops * HOP_SIZE
        for (i in 0 until FFT_SIZE) {
            val frame = outputStart + i
            if (frame < 0L || frame >= inner.totalFrames) continue
            val index = (frame.toInt() and accumulationMask)
            val w = window[i]
            accumulationL[index] += realL[i] * w
            accumulationR[index] += imagL[i] * w
            accumulationWeight[index] += w * w
            if (roomEnabled) {
                sendAccumulationL[index] += sendRealL[i] * w
                sendAccumulationR[index] += sendImagL[i] * w
            }
        }
        return outputStart
    }

    /** Спектры двух вещественных сигналов → спектр L + jR (на месте, поэлементно). */
    private fun packSpectra(reL: DoubleArray, imL: DoubleArray, reR: DoubleArray, imR: DoubleArray) {
        for (k in 0 until FFT_SIZE) {
            val zr = reL[k] - imR[k]
            val zi = imL[k] + reR[k]
            reL[k] = zr; imL[k] = zi
        }
    }

    private fun publishHop(frameStart: Long) {
        val xtc = xtcProcessor
        val tailFrames = if (xtc != null) XTC_INPUT_AHEAD_FRAMES else 0L
        val blockEnd = minOf(frameStart + HOP_SIZE, inner.totalFrames + tailFrames)
        val blockStart = maxOf(frameStart, 0L)
        for (frame in blockStart until blockEnd) {
            val rawLeft: Float
            val rawRight: Float
            var sendLeft = 0f
            var sendRight = 0f
            if (frame < inner.totalFrames) {
                val accumulationIndex = frame.toInt() and accumulationMask
                val weight = accumulationWeight[accumulationIndex]
                rawLeft = if (weight > 1e-12) (accumulationL[accumulationIndex] / weight).toFloat() else 0f
                rawRight = if (weight > 1e-12) (accumulationR[accumulationIndex] / weight).toFloat() else 0f
                if (roomEnabled) {
                    if (weight > 1e-12) {
                        sendLeft = (sendAccumulationL[accumulationIndex] / weight).toFloat()
                        sendRight = (sendAccumulationR[accumulationIndex] / weight).toFloat()
                    }
                    sendAccumulationL[accumulationIndex] = 0.0
                    sendAccumulationR[accumulationIndex] = 0.0
                }
                accumulationL[accumulationIndex] = 0.0
                accumulationR[accumulationIndex] = 0.0
                accumulationWeight[accumulationIndex] = 0.0
            } else {
                rawLeft = 0f; rawRight = 0f
            }
            val inputLeft = if (frame in 0 until inner.totalFrames) inner.sample(frame, 0).takeUnless { it.isNaN() } ?: 0f else 0f
            val inputRight = if (frame in 0 until inner.totalFrames) inner.sample(frame, 1).takeUnless { it.isNaN() } ?: 0f else 0f
            var processedLeft = rawLeft
            var processedRight = rawRight
            if (room != null) {
                room.process(sendLeft, sendRight)
                processedLeft += room.wetL.toFloat()
                processedRight += room.wetR.toFloat()
            }
            loudnessCompensator.observe(inputLeft, inputRight, processedLeft, processedRight)
            val compensation = loudnessCompensator.gain()
            val compensatedLeft = (processedLeft * compensation).toFloat()
            val compensatedRight = (processedRight * compensation).toFloat()
            if (xtc == null) publishFrame(frame, compensatedLeft, compensatedRight)
            else xtc.push(frame, compensatedLeft, compensatedRight)
        }
    }

    private fun processXtcFrame(frame: Long, left: Float, right: Float) = publishFrame(frame, left, right)

    /** Пиковое ограничение — не здесь: выход Spatial проходит сцепленный предлимитер Server ([PreLimitedSource], A1.4). */
    private fun publishFrame(frame: Long, left: Float, right: Float) {
        if (frame in startAtFrame until inner.totalFrames) {
            val cacheIndex = ((frame % cacheCapacityFrames) * 2L).toInt()
            cache[cacheIndex] = left
            cache[cacheIndex + 1] = right
        }
        if (frame >= 0L && frame + 1 > processedHead)
            processedHead = minOf(frame + 1, inner.totalFrames)
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
