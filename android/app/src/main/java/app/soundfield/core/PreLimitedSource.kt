package app.soundfield.core

import app.soundfield.dsp.LimiterStats
import app.soundfield.dsp.PeakLimiter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Сцепленный предлимитер Server (A1.4): −1,5 dBTP, упреждение 1,5 мс, общее усиление для L и R.
 * Стоит на Server до разделения каналов — и локальный канал, и канал для сети (UDP/TCP) читают один и тот же
 * ограниченный сигнал, поэтому стереокартина не смещается. Источник последовательно прогоняется через лимитер ядра
 * в рабочем потоке, результат лежит в кольцевом кэше (как у [SpatialSource]); аудиопоток ничего не считает.
 *
 * Номера кадров не сдвигаются: выход кадра f — это вход кадра f, ослабленный по сигналу вокруг него (упреждение
 * компенсируется внутри). После перемотки лимитер заново прогревается на WARMUP_FRAMES до цели.
 */
class PreLimitedSource(
    private val inner: AudioSource,
    private val startAtFrame: Long = 0L,
    cacheSeconds: Int = DEFAULT_CACHE_SECONDS,
    ceilingDbtp: Double = PRE_LIMITER_CEILING_DBTP,
) : AudioSource, PrefetchableAudioSource, AutoCloseable {
    override val mode get() = inner.mode
    override val title get() = inner.title
    override val artist get() = inner.artist
    override val totalFrames get() = inner.totalFrames
    /** Источник под предлимитером (Engine сравнивает по ссылке, чтобы не создавать второй экземпляр). */
    val innerSource: AudioSource get() = inner
    override val readyFrames: Long get() = minOf(inner.readyFrames, processedHead)
    override val complete: Boolean get() = inner.complete && processedHead >= inner.totalFrames

    private val capacity = cacheSeconds.coerceAtLeast(2) * SF.SAMPLE_RATE
    private val cache = FloatArray(capacity * 2)
    private val limiter = PeakLimiter(SF.SAMPLE_RATE, 2, ceilingDbtp)
    private val latency = limiter.latencyFrames
    private val running = AtomicBoolean(true)
    private val requestedHead = AtomicLong(minOf(inner.totalFrames, startAtFrame + capacity).coerceAtLeast(startAtFrame))
    private val restartAt = AtomicLong(NO_RESTART)
    @Volatile private var processedHead = maxOf(0L, startAtFrame - WARMUP_FRAMES)
    private var nextInput = processedHead
    private val buffer = FloatArray(CHUNK_FRAMES * 2)
    private val statBlocks = AtomicLong()
    private val statActive = AtomicLong()
    private val statMaxMilliDb = AtomicLong()
    private val missCount = AtomicLong()
    /** Максимальное ослабление за время жизни источника, dB (диагностика). */
    @Volatile var maxReductionDb = 0.0
        private set
    @Volatile var processingError: Throwable? = null
        private set
    private val thread: Thread

    /** Число обращений к ещё не готовому кадру (диагностика). */
    val misses: Long get() = missCount.get()
    val isNativeLimiter: Boolean get() = limiter.isNative

    init {
        require(startAtFrame in 0..inner.totalFrames)
        thread = Thread(::processLoop, "sf-prelimit").apply {
            priority = minOf(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1)
            isDaemon = true
            start()
        }
    }

    /** Статистика предлимитера с прошлого вызова: доля блоков с ослаблением > 0,5 dB и максимум (для телеметрии). */
    fun takeStats(): LimiterStats =
        LimiterStats(statBlocks.getAndSet(0), statActive.getAndSet(0), statMaxMilliDb.getAndSet(0) / 1_000.0)

    override fun requestThrough(frameExclusive: Long) {
        val target = frameExclusive.coerceIn(startAtFrame, inner.totalFrames)
        while (true) {
            val previous = requestedHead.get()
            if (target <= previous || requestedHead.compareAndSet(previous, target)) break
        }
        (inner as? PrefetchableAudioSource)?.requestThrough((target + latency + CHUNK_FRAMES).coerceAtMost(inner.totalFrames))
    }

    override fun sample(frame: Long, ch: Int): Float {
        if (ch !in 0..1 || frame < startAtFrame || frame >= inner.totalFrames) return 0f
        requestThrough(frame + 1)
        val head = processedHead
        val firstCached = maxOf(0L, head - capacity)
        if (frame < firstCached || frame >= head) {
            missCount.incrementAndGet()
            if (frame < firstCached || frame - head >= capacity) restartAt.set(frame)
            return Float.NaN
        }
        return cache[((frame % capacity) * 2L + ch).toInt()]
    }

    private fun processLoop() {
        try {
            while (running.get()) {
                applyPendingRestart()
                val total = inner.totalFrames
                val ready = inner.readyFrames.coerceAtLeast(0L)
                val sourceComplete = inner.complete
                val wanted = requestedHead.get()
                // выходной кадр o готов после того, как на вход поданы кадры до o + latency
                val inputLimit = if (sourceComplete) minOf(wanted, total) + latency else minOf(wanted + latency, ready)
                var progressed = false
                while (running.get() && nextInput < inputLimit && restartAt.get() == NO_RESTART) {
                    val end = minOf(nextInput + CHUNK_FRAMES, inputLimit)
                    val n = (end - nextInput).toInt()
                    for (i in 0 until n) {
                        val f = nextInput + i
                        if (f < total && f < ready) {
                            val l = inner.sample(f, 0); val r = inner.sample(f, 1)
                            buffer[i * 2] = if (l.isNaN()) 0f else l; buffer[i * 2 + 1] = if (r.isNaN()) 0f else r
                        } else { buffer[i * 2] = 0f; buffer[i * 2 + 1] = 0f }
                    }
                    limiter.process(buffer, 0, n)
                    var written = processedHead
                    for (i in 0 until n) {
                        val o = nextInput + i - latency
                        if (o < processedHead || o >= total) continue
                        val index = ((o % capacity) * 2L).toInt()
                        cache[index] = buffer[i * 2]; cache[index + 1] = buffer[i * 2 + 1]
                        written = o + 1
                    }
                    nextInput = end
                    if (written > processedHead) processedHead = written
                    collectStats()
                    progressed = true
                }
                if (!progressed) Thread.sleep(2)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            processingError = t
            running.set(false)
        } finally {
            limiter.close()   // освобождает нативную память только после остановки рабочего потока
        }
    }

    private fun collectStats() {
        val s = limiter.takeStats()
        statBlocks.addAndGet(s.blocks); statActive.addAndGet(s.activeBlocks)
        val milli = (s.maxReductionDb * 1_000.0).toLong()
        statMaxMilliDb.accumulateAndGet(milli) { old, next -> maxOf(old, next) }
        if (s.maxReductionDb > maxReductionDb) maxReductionDb = s.maxReductionDb
    }

    private fun applyPendingRestart() {
        val target = restartAt.getAndSet(NO_RESTART)
        if (target == NO_RESTART) return
        val from = maxOf(0L, target - WARMUP_FRAMES)
        limiter.reset()
        nextInput = from
        processedHead = from
        requestedHead.set(minOf(inner.totalFrames, maxOf(requestedHead.get(), target + 1)))
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        thread.interrupt()
        if (Thread.currentThread() !== thread) thread.join(250)
    }

    companion object {
        const val DEFAULT_CACHE_SECONDS = 20
        const val WARMUP_FRAMES = SF.SAMPLE_RATE
        private const val CHUNK_FRAMES = 1_024
        private const val NO_RESTART = Long.MIN_VALUE
    }
}
