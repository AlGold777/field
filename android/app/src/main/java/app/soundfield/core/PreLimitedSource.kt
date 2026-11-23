package app.soundfield.core

import app.soundfield.dsp.LimiterStats
import app.soundfield.dsp.PeakLimiter
import app.soundfield.dsp.TruePeakMeter
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
) : AudioSource, PrefetchableAudioSource, AutoCloseable, TrackMeters {
    override val truePeakDbtp: Double get() = (inner as? TrackMeters)?.truePeakDbtp ?: Double.NaN
    override val loudnessLufs: Double get() = (inner as? TrackMeters)?.loudnessLufs ?: Double.NaN
    override val mode get() = inner.mode
    override val title get() = inner.title
    override val artist get() = inner.artist
    override val totalFrames get() = inner.totalFrames
    /** Источник под предлимитером (Engine сравнивает по ссылке, чтобы не создавать второй экземпляр). */
    val innerSource: AudioSource get() = inner
    override val firstReadyFrame: Long get() = retainedFrom
    override val readyFrames: Long get() = minOf(inner.readyFrames, processedHead)
    override val complete: Boolean get() = inner.complete && processedHead >= inner.totalFrames

    private val capacity = cacheSeconds.coerceAtLeast(2) * SF.SAMPLE_RATE
    private val cache = FloatArray(capacity * 2)
    private val limiter = PeakLimiter(SF.SAMPLE_RATE, 2, ceilingDbtp)
    private val latency = limiter.latencyFrames
    private val running = AtomicBoolean(true)
    private val requestedHead = AtomicLong(minOf(inner.totalFrames, startAtFrame + SF.SAMPLE_RATE).coerceAtLeast(startAtFrame))
    private val restartAt = AtomicLong(NO_RESTART)
    @Volatile private var processedHead = maxOf(0L, startAtFrame - WARMUP_FRAMES)
    @Volatile private var retainedFrom = startAtFrame
    @Volatile private var generation = 0L
    private var nextInput = processedHead
    private var stallSinceMs = 0L
    /** Сколько раз цепочка выходила из застревания на вытесненном входном кадре (телеметрия). */
    val stallRecoveries = AtomicLong(0)
    private val buffer = FloatArray(CHUNK_FRAMES * 2)
    /**
     * Текущее статическое усиление. Цель — нормализация громкости (A3.1, [TruePeakMeter.normalizationGainDb]); пока громкость
     * неизвестна (трек ещё декодируется) — min(0, −1,55 − tp). Уменьшение применяется сразу (в пределах блока), увеличение —
     * не быстрее [MAX_RISE_DB_PER_S]: если анализ закончился посреди трека, уровень «подъезжает», а не прыгает.
     */
    private var currentDb = initialGainDb()
    private var currentGain = Math.pow(10.0, currentDb / 20.0)
    @Volatile var staticGainDb = 0.0
        private set
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
        require(startAtFrame >= 0)   // длина потокового трека может уменьшиться (конец файла раньше оценки)
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
        val target = frameExclusive.coerceAtMost(inner.totalFrames).coerceAtLeast(startAtFrame)
        // sample() зовёт это на каждый отвод интерполятора из аудиопотока: вниз по цепочке — только когда голова ушла вперёд.
        var advanced = false
        while (true) {
            val previous = requestedHead.get()
            if (target <= previous) break
            if (requestedHead.compareAndSet(previous, target)) { advanced = true; break }
        }
        if (advanced) (inner as? PrefetchableAudioSource)?.requestThrough((target + latency + CHUNK_FRAMES).coerceAtMost(inner.totalFrames))
    }

    override fun sample(frame: Long, ch: Int): Float {
        if (ch !in 0..1 || frame < startAtFrame || frame >= inner.totalFrames) return 0f
        requestThrough(frame + 1)
        val value = readPrepared(frame, ch)
        if (value.isNaN()) {
            missCount.incrementAndGet()
            if (frame < retainedFrom || frame - processedHead >= capacity) restartAt.set(frame)
        }
        return value
    }

    override fun readPrepared(frame: Long, ch: Int): Float {
        if (ch !in 0..1 || frame < startAtFrame || frame >= inner.totalFrames) return 0f
        val revision = generation
        if ((revision and 1L) != 0L) return Float.NaN
        if (frame >= processedHead || frame < retainedFrom) {
            // Читатель ушёл вперёд дальше окна кэша: перезапуск с его кадра (иначе NaN навсегда).
            // Отстающих читателей (frame < retainedFrom) не трогаем — их догоняет сам отправитель.
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
                val wanted = requestedHead.get()
                // выходной кадр o готов после того, как на вход поданы кадры до o + latency
                val inputLimit = if (sourceComplete) minOf(wanted, total) + latency else minOf(wanted + latency, ready)
                var progressed = false
                while (running.get() && nextInput < inputLimit && restartAt.get() == NO_RESTART) {
                    // Блок не пересекает границу трека цепочки: усиление нормализации меняется ровно на ней.
                    val boundary = (inner as? PositionalTrackMeters)?.nextTrackStartAfter(nextInput) ?: Long.MAX_VALUE
                    val end = minOf(nextInput + CHUNK_FRAMES, inputLimit, boundary)
                    val n = (end - nextInput).toInt()
                    var available = 0
                    while (available < n) {
                        val i = available
                        val f = nextInput + i
                        if (f < total && f < ready) {
                            val l = inner.readPrepared(f, 0); val r = inner.readPrepared(f, 1)
                            if (!l.isFinite() || !r.isFinite()) break
                            buffer[i * 2] = l; buffer[i * 2 + 1] = r
                        } else { buffer[i * 2] = 0f; buffer[i * 2 + 1] = 0f }
                        available++
                    }
                    if (available == 0) {
                        // Кадр входа готов, но внутренний кэш его уже не отдаёт (вытеснен или перезапуск):
                        // через STALL_RECOVERY_MS просим внутренний источник начать с него и подаём тишину,
                        // чтобы цепочка не замолчала навсегда (до 1.11.7 это делал sample()).
                        val f = nextInput
                        if (f >= total || f >= ready) { stallSinceMs = 0L; break }
                        val now = System.nanoTime() / 1_000_000L
                        if (stallSinceMs == 0L) stallSinceMs = now
                        if (now - stallSinceMs < STALL_RECOVERY_MS) break
                        stallSinceMs = 0L
                        stallRecoveries.incrementAndGet()
                        inner.sample(f, 0)
                        java.util.Arrays.fill(buffer, 0, n * 2, 0f)
                        available = n
                    } else stallSinceMs = 0L
                    applyStaticGain(available, nextInput)
                    limiter.process(buffer, 0, available)
                    // Invalidate overwritten slots before exposing new PCM in them.
                    retainedFrom = maxOf(retainedFrom, minOf(total, nextInput + available - latency) - capacity)
                    var written = processedHead
                    for (i in 0 until available) {
                        val o = nextInput + i - latency
                        if (o < processedHead || o >= total) continue
                        val index = ((o % capacity) * 2L).toInt()
                        cache[index] = buffer[i * 2]; cache[index + 1] = buffer[i * 2 + 1]
                        written = o + 1
                    }
                    nextInput += available
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

    /**
     * Статическое усиление по true-peak трека (метаданные импорта) — до предлимитера: громкий мастеринг делается тише целиком,
     * а не ослабляется лимитером в каждом пике. Пока трек декодируется, tp — максимум по уже готовой части (усиление только
     * уменьшается); смена цели сглаживается линейно на блок (21 мс). Предлимитер остаётся страховкой (Spatial/EQ могут поднять пики).
     */
    private fun applyStaticGain(n: Int, frame: Long) {
        val positional = inner as? PositionalTrackMeters
        val goal = if (positional != null) TruePeakMeter.normalizationGainDb(positional.loudnessAt(frame), positional.truePeakAt(frame))
            else TruePeakMeter.normalizationGainDb(loudnessLufs, truePeakDbtp)
        // Новый трек цепочки (переход без паузы): своя цель сразу, переход сглажен на блок; «подъезд» — только внутри трека.
        val key = positional?.trackKeyAt(frame) ?: 0L
        val newTrack = key != gainTrackKey
        gainTrackKey = key
        val db = if (goal <= currentDb || newTrack) goal else minOf(goal, currentDb + MAX_RISE_DB_PER_S * n / SF.SAMPLE_RATE)
        currentDb = db
        staticGainDb = db
        val target = Math.pow(10.0, db / 20.0)
        if (target == 1.0 && currentGain == 1.0) return
        val from = currentGain
        for (i in 0 until n) {
            val g = if (target == from) target else from + (target - from) * (i + 1) / n
            buffer[i * 2] = (buffer[i * 2] * g).toFloat(); buffer[i * 2 + 1] = (buffer[i * 2 + 1] * g).toFloat()
        }
        currentGain = target
    }

    private var gainTrackKey = Long.MIN_VALUE

    private fun initialGainDb(): Double {
        val positional = inner as? PositionalTrackMeters
        return if (positional != null) TruePeakMeter.normalizationGainDb(positional.loudnessAt(startAtFrame), positional.truePeakAt(startAtFrame))
            else TruePeakMeter.normalizationGainDb(loudnessLufs, truePeakDbtp)
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
        currentDb = initialGainDb()   // после перемотки без «подъезда»
        gainTrackKey = Long.MIN_VALUE
        currentGain = Math.pow(10.0, currentDb / 20.0)
        nextInput = from
        // Нечётное поколение — границы кэша меняются: читатели отбрасывают значение (seqlock).
        generation++
        retainedFrom = maxOf(startAtFrame, from)
        processedHead = from
        generation++
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
        private const val STALL_RECOVERY_MS = 200L
        /** Максимальная скорость подъёма усиления нормализации, dB в секунду. */
        const val MAX_RISE_DB_PER_S = 3.0
    }
}
