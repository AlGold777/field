package app.soundfield.core

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Скорость воспроизведения без смены высоты тона поверх [inner] ([TimeStretcher] в рабочем потоке, результат — в кольцевом кэше).
 * Аудиопоток и сетевой отправитель только читают кэш ([sample] / [readPrepared]), ничего не считая; NaN — кадр ещё не готов.
 *
 * Домен выходных кадров совпадает с доменом входа в точке [startFrame]: выходной кадр o соответствует входному
 * startFrame + (o − startFrame)·speed ([inputFrameOf], [outputFrameOf]); внутри окна поиска WSOLA положение
 * плавает до ±15 мс. Кадры ниже [startFrame] — тишина. Если читатель ушёл за окно кэша, поток перезапускается
 * с его кадра (после перезапуска сдвиг поиска может отличаться от прежнего; обе стороны читают один кэш).
 */
class SpeedSource(
    private val inner: AudioSource,
    val speed: Double,
    val startFrame: Long,
    cacheSeconds: Int = DEFAULT_CACHE_SECONDS,
) : AudioSource, PrefetchableAudioSource, AutoCloseable, TrackMeters, PositionalTrackMeters {
    // Нормализация по месту трека (переход без паузы): кадр выхода переводится в кадр входа.
    override fun truePeakAt(frame: Long): Double = (inner as? PositionalTrackMeters)?.truePeakAt(inputFrameOf(frame)) ?: truePeakDbtp
    override fun loudnessAt(frame: Long): Double = (inner as? PositionalTrackMeters)?.loudnessAt(inputFrameOf(frame)) ?: loudnessLufs
    override fun trackKeyAt(frame: Long): Long = (inner as? PositionalTrackMeters)?.trackKeyAt(inputFrameOf(frame)) ?: 0L
    override fun nextTrackStartAfter(frame: Long): Long {
        val next = (inner as? PositionalTrackMeters)?.nextTrackStartAfter(inputFrameOf(frame)) ?: Long.MAX_VALUE
        return if (next == Long.MAX_VALUE) next else outputFrameOf(next)
    }
    override val truePeakDbtp: Double get() = (inner as? TrackMeters)?.truePeakDbtp ?: Double.NaN
    override val loudnessLufs: Double get() = (inner as? TrackMeters)?.loudnessLufs ?: Double.NaN
    override val mode get() = inner.mode
    override val title get() = inner.title
    override val artist get() = inner.artist
    /** Источник под обёрткой. */
    val innerSource: AudioSource get() = inner

    override val totalFrames: Long get() = startFrame + ((inner.totalFrames - startFrame) / speed).toLong()
    override val firstReadyFrame: Long get() = retainedFrom
    override val readyFrames: Long get() {
        val byInput = if (inner.complete) totalFrames else startFrame + ((inner.readyFrames - startFrame) / speed).toLong()
        return minOf(processedHead, byInput)
    }
    override val complete: Boolean get() = inner.complete && processedHead >= totalFrames

    /** Входной кадр, соответствующий выходному o. */
    fun inputFrameOf(o: Long): Long = startFrame + Math.round((o - startFrame) * speed)
    /** Выходной кадр, соответствующий входному i. */
    fun outputFrameOf(i: Long): Long = startFrame + Math.round((i - startFrame) / speed)

    private val stretcher = TimeStretcher(speed, SF.SAMPLE_RATE)
    private val capacity = cacheSeconds.coerceAtLeast(2) * SF.SAMPLE_RATE
    private val cache = FloatArray(capacity * 2)
    private val running = AtomicBoolean(true)
    private val requestedHead = AtomicLong(initialHead())
    private val restartAt = AtomicLong(NO_RESTART)
    @Volatile private var processedHead = startFrame
    @Volatile private var retainedFrom = startFrame
    @Volatile private var generation = 0L
    // состояние рабочего потока
    private var nextInput = startFrame
    private var outPos = startFrame
    private var flushed = false
    private var inputRequested = 0L
    private var stallSinceMs = 0L
    private val buffer = FloatArray(CHUNK_FRAMES * 2)
    private val missCount = AtomicLong()
    @Volatile var processingError: Throwable? = null
        private set
    private val thread: Thread

    /** Число обращений к ещё не готовому кадру (диагностика). */
    val misses: Long get() = missCount.get()

    init {
        require(speed in 0.25..4.0 && startFrame >= 0)
        thread = Thread(::processLoop, "sf-speed").apply {
            priority = minOf(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1)
            isDaemon = true
            start()
        }
    }

    private fun initialHead() = minOf(totalFrames, startFrame + SF.SAMPLE_RATE).coerceAtLeast(startFrame)

    override fun requestThrough(frameExclusive: Long) {
        val target = frameExclusive.coerceAtMost(totalFrames).coerceAtLeast(startFrame)
        var advanced = false
        while (true) {
            val previous = requestedHead.get()
            if (target <= previous) break
            if (requestedHead.compareAndSet(previous, target)) { advanced = true; break }
        }
        if (advanced) (inner as? PrefetchableAudioSource)?.requestThrough(inputGoal(target))
    }

    private fun inputGoal(outFrame: Long): Long =
        (inputFrameOf(outFrame) + stretcher.latencyFrames + CHUNK_FRAMES).coerceAtMost(inner.totalFrames)

    override fun sample(frame: Long, ch: Int): Float {
        if (ch !in 0..1 || frame < startFrame || frame >= totalFrames) return 0f
        requestThrough(frame + 1)
        val value = readPrepared(frame, ch)
        if (value.isNaN()) {
            missCount.incrementAndGet()
            if (frame < retainedFrom || frame - processedHead >= capacity) restartAt.set(frame)
        }
        return value
    }

    override fun readPrepared(frame: Long, ch: Int): Float {
        if (ch !in 0..1 || frame < startFrame || frame >= totalFrames) return 0f
        val revision = generation
        if ((revision and 1L) != 0L) return Float.NaN
        if (frame >= processedHead || frame < retainedFrom) {
            if (frame - processedHead >= capacity) restartAt.set(frame)
            return Float.NaN
        }
        val value = cache[((frame % capacity) * 2L + ch).toInt()]
        return if (revision == generation && frame >= retainedFrom) value else Float.NaN
    }

    private fun processLoop() {
        try {
            while (running.get()) {
                applyPendingRestart()
                val total = inner.totalFrames
                val ready = inner.readyFrames.coerceAtLeast(0L)
                val sourceComplete = inner.complete
                val goal = inputGoal(requestedHead.get())
                if (goal > inputRequested) {
                    inputRequested = goal
                    (inner as? PrefetchableAudioSource)?.requestThrough(goal)
                }
                val limit = if (sourceComplete) goal else minOf(goal, ready)
                var progressed = false
                while (running.get() && !flushed && nextInput < limit && restartAt.get() == NO_RESTART) {
                    val n = minOf(CHUNK_FRAMES.toLong(), limit - nextInput).toInt()
                    var available = 0
                    while (available < n) {
                        val f = nextInput + available
                        val l = inner.readPrepared(f, 0); val r = inner.readPrepared(f, 1)
                        if (!l.isFinite() || !r.isFinite()) break
                        buffer[available * 2] = l; buffer[available * 2 + 1] = r
                        available++
                    }
                    if (available == 0) {
                        // кадр объявлен готовым, но внутренний кэш его не отдаёт: через 200 мс просим начать с него
                        val now = System.nanoTime() / 1_000_000L
                        if (stallSinceMs == 0L) stallSinceMs = now
                        if (now - stallSinceMs >= STALL_RECOVERY_MS) { stallSinceMs = 0L; inner.sample(nextInput, 0) }
                        break
                    }
                    stallSinceMs = 0L
                    write(stretcher.process(buffer, available))
                    nextInput += available
                    progressed = true
                }
                if (!flushed && sourceComplete && nextInput >= total && restartAt.get() == NO_RESTART) {
                    write(stretcher.flush())
                    // округление длины: добиваем тишиной до объявленного конца
                    val end = totalFrames
                    if (outPos < end) write(FloatArray(((end - outPos) * 2).toInt()))
                    processedHead = end
                    flushed = true
                    progressed = true
                }
                if (!progressed) Thread.sleep(2)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            processingError = t
            running.set(false)
        }
    }

    /** Кладёт готовые кадры (интерлив) в кэш с выходной позиции [outPos]. */
    private fun write(pcm: FloatArray) {
        val end = totalFrames
        val count = (minOf(pcm.size / 2L, end - outPos)).toInt()
        if (count <= 0) return
        // сначала отзываем перезаписываемые слоты, потом открываем новые
        retainedFrom = maxOf(retainedFrom, outPos + count - capacity)
        for (i in 0 until count) {
            val index = (((outPos + i) % capacity) * 2L).toInt()
            cache[index] = pcm[i * 2]; cache[index + 1] = pcm[i * 2 + 1]
        }
        outPos += count
        processedHead = outPos
    }

    private fun applyPendingRestart() {
        val target = restartAt.getAndSet(NO_RESTART)
        if (target == NO_RESTART) return
        val r = target.coerceIn(startFrame, totalFrames)
        stretcher.reset()
        nextInput = inputFrameOf(r).coerceIn(0L, inner.totalFrames)
        outPos = r
        flushed = false
        inputRequested = 0L
        stallSinceMs = 0L
        requestedHead.set(minOf(totalFrames, r + SF.SAMPLE_RATE).coerceAtLeast(r))
        // нечётное поколение — границы кэша меняются: читатели отбрасывают значение (seqlock)
        generation++
        retainedFrom = r
        processedHead = r
        generation++
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        thread.interrupt()
        if (Thread.currentThread() !== thread) thread.join(250)
    }

    companion object {
        const val DEFAULT_CACHE_SECONDS = 20
        private const val CHUNK_FRAMES = 2_048
        private const val NO_RESTART = Long.MIN_VALUE
        private const val STALL_RECOVERY_MS = 200L
    }
}
