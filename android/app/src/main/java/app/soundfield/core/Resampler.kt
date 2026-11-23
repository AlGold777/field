package app.soundfield.core

import java.nio.ByteOrder
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Запасной потоковый стерео-ресемплер (24 отвода, окно Блэкмана) на случай, если библиотека ядра недоступна
 * (32-битное устройство без libsfdsp). Основной путь — SampleRateConverter поверх ядра (A1.2).
 */
class StreamResampler(private val inRate: Int, private val outRate: Int, private val emit: (Float, Float) -> Unit) {
    private val half = 12
    private val taps = half * 2
    private val g = gcd(inRate, outRate)
    private val phases = outRate / g                 // 44,1→48: 160 фаз
    private val step = inRate / g                    // 44,1→48: 147
    private val table: Array<FloatArray>
    private var bufL = FloatArray(8192)
    private var bufR = FloatArray(8192)
    private var base = 0L        // абсолютный индекс bufL[0]
    private var count = 0        // сколько кадров в буфере
    private var outIndex = 0L    // следующий выходной кадр
    private var ended = false

    init {
        require(inRate > 0 && outRate > 0)
        require(phases <= 8192) { "Неподдерживаемая частота дискретизации $inRate" }
        val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.94
        table = Array(phases) { p ->
            val frac = p.toDouble() / phases
            val row = DoubleArray(taps) { j ->
                val t = (j - half + 1) - frac
                val x = PI * cutoff * t
                val sinc = if (t == 0.0) 1.0 else sin(x) / x
                val w = 0.42 + 0.5 * cos(PI * t / half) + 0.08 * cos(2 * PI * t / half)   // Блэкман
                cutoff * sinc * (if (kotlin.math.abs(t) >= half) 0.0 else w)
            }
            val sum = row.sum()
            FloatArray(taps) { (row[it] / sum).toFloat() }   // единичное усиление на DC
        }
    }

    fun push(l: Float, r: Float) {
        if (count == bufL.size) compact()
        bufL[count] = l; bufR[count] = r; count++
        if (count >= taps * 4) drain()
    }

    /** Конец входа: дообработать хвост (за концом — тишина). */
    fun finish() { ended = true; drain() }

    private fun drain() {
        val inputEnd = base + count
        while (true) {
            val num = outIndex * step
            val ip = num / phases                 // целая часть позиции во входе
            val phase = (num % phases).toInt()
            val first = ip - half + 1
            val last = ip + half
            if (!ended && last >= inputEnd) break
            val totalIn = if (ended) inputEnd else Long.MAX_VALUE
            if (ended && ip >= totalIn) break
            val row = table[phase]
            var sl = 0f; var sr = 0f
            for (j in 0 until taps) {
                val a = first + j
                if (a < base || a >= inputEnd) continue
                val k = (a - base).toInt()
                sl += bufL[k] * row[j]; sr += bufR[k] * row[j]
            }
            emit(sl, sr)
            outIndex++
        }
    }

    private fun compact() {
        // оставляем кадры, ещё нужные для следующего выходного отсчёта
        val needFrom = (outIndex * step / phases) - half + 1
        val drop = (needFrom - base).coerceIn(0L, count.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(bufL, drop, bufL, 0, count - drop)
            System.arraycopy(bufR, drop, bufR, 0, count - drop)
            count -= drop; base += drop
        }
        if (count == bufL.size) { bufL = bufL.copyOf(bufL.size * 2); bufR = bufR.copyOf(bufR.size * 2) }
    }

    companion object {
        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

/**
 * Трек, декодируемый на лету в скользящее окно сегментов по 5 с (48 кГц, стерео, 24 бит) в памяти.
 * Реалтайм-чтение никогда не ждёт декодер: ещё не готовый сегмент возвращает тишину. Server отправляет
 * эту же тишину в UDP, поэтому обе стороны сохраняют одну временную шкалу. Длина трека не ограничена:
 * в памяти только окно вокруг позиции воспроизведения ([AHEAD_SEGMENTS] вперёд, [BEHIND_SEGMENTS] назад).
 *
 * Позицию сообщает движок ([focus]); декодер (поток в [app.soundfield.audio.AudioFileDecoder]) заполняет
 * первый недостающий сегмент окна, при перемотке переходит к новому месту. [readyFrames] — конец
 * непрерывного готового участка от позиции. Короткий трек, целиком поместившийся в окно, становится
 * [complete] и больше не вытесняется.
 */
class SegmentedPcmSource(
    override val title: String,
    override val artist: String,
    estimatedFrames: Long,
) : AudioSource, TrackMeters, AutoCloseable {
    override val mode = SourceMode.MUSIC
    /** True-peak трека: из анализа файла, пока его нет — максимум по уже декодированному; NaN — нет данных. */
    @Volatile override var truePeakDbtp: Double = Double.NaN
    /** Громкость известна только из анализа всего файла (частичная ввела бы в заблуждение). */
    @Volatile override var loudnessLufs: Double = Double.NaN
    /** Пик и громкость взяты из анализа всего файла ([applyAnalysis]); декодер их больше не трогает. */
    @Volatile var analyzed: Boolean = false
        private set
    @Volatile override var totalFrames: Long = estimatedFrames.coerceAtLeast(0)
        private set
    /** Точная длина известна: декодер дошёл до конца файла (или её дал индекс файла). */
    @Volatile var endKnown: Boolean = false
        private set
    @Volatile private var runEnd: Long = 0
    @Volatile private var focusFrame: Long = 0
    override val readyFrames: Long get() = if (complete) totalFrames else runEnd
    @Volatile override var complete: Boolean = false
        private set
    /** Декодер остановлен ([close]) или завершился ошибкой ([failure]). */
    @Volatile var closed: Boolean = false
        private set
    @Volatile var failure: Throwable? = null
        private set
    private val segments = ConcurrentHashMap<Long, ByteBuffer>()
    private val wake = Object()
    private var onClose: (() -> Unit)? = null

    /** Кадр, вокруг которого держится окно. */
    val playheadFrame: Long get() = focusFrame
    val playheadSegment: Long get() = focusFrame / SEGMENT_FRAMES
    /** Номер сегмента за последним (исключительно), если длина известна; иначе Long.MAX_VALUE. */
    val endSegmentExclusive: Long get() =
        if (endKnown) (totalFrames + SEGMENT_FRAMES - 1) / SEGMENT_FRAMES else Long.MAX_VALUE
    val residentSegments: Int get() = segments.size

    /** Сегмент [index] из [frames] кадров ([data] — 24 бит LE интерлив, позиция 0, limit = frames·6). */
    fun publish(index: Long, data: ByteBuffer, frames: Int) {
        require(frames in 1..SEGMENT_FRAMES)
        require(data.limit().toLong() == frames * BYTES_PER_FRAME)
        data.order(ByteOrder.LITTLE_ENDIAN)
        segments[index] = data
        val ready = index * SEGMENT_FRAMES + frames
        synchronized(this) {
            // Длительность из метаданных бывает занижена: до конца файла показываем не меньше декодированного.
            if (!endKnown) totalFrames = maxOf(totalFrames, ready)
            checkComplete()
            if (!complete) evictOutsideWindow()
            recomputeRun()
        }
    }

    /** Декодер дошёл до конца файла: [actualFrames] — точная длина. */
    fun markEnd(actualFrames: Long) {
        synchronized(this) {
            totalFrames = actualFrames.coerceAtLeast(0)
            endKnown = true
            segments.keys.filter { it * SEGMENT_FRAMES >= totalFrames }.forEach { segments.remove(it) }
            checkComplete()
            recomputeRun()
        }
    }

    /** Результат анализа файла (один раз на файл, хранится постоянно). */
    fun applyAnalysis(truePeakDbtp: Double, loudnessLufs: Double) {
        this.truePeakDbtp = truePeakDbtp
        this.loudnessLufs = loudnessLufs
        analyzed = true
    }

    /** Совместимость: трек целиком декодирован (тесты, короткие источники). */
    fun finish(actualFrames: Long) {
        markEnd(actualFrames)
        synchronized(this) { complete = true; runEnd = totalFrames }
    }

    /** Воспроизведение сейчас у [frame] (перемотка или ход шкалы): окно и готовый участок считаются от него. */
    fun focus(frame: Long) {
        val f = frame.coerceAtLeast(0)
        if (f == focusFrame) return
        synchronized(this) {
            focusFrame = f
            recomputeRun()
        }
        synchronized(wake) { wake.notifyAll() }
    }

    fun hasSegment(index: Long): Boolean = segments.containsKey(index)

    /** Первый недекодированный сегмент в [from, untilExclusive) или null. */
    fun firstMissingSegment(from: Long, untilExclusive: Long): Long? {
        var i = from.coerceAtLeast(0)
        while (i < untilExclusive) { if (!segments.containsKey(i)) return i; i++ }
        return null
    }

    /** Следующий сегмент, который нужен окну, или null — окно заполнено. Для потока декодера. */
    fun nextWantedSegment(): Long? {
        if (complete) return null
        val first = playheadSegment
        return firstMissingSegment(first, minOf(first + AHEAD_SEGMENTS, endSegmentExclusive))
    }

    /** Поток декодера ждёт смены позиции (не дольше [timeoutMs]). */
    fun awaitDemand(timeoutMs: Long) {
        synchronized(wake) { if (!closed && nextWantedSegment() == null) wake.wait(timeoutMs) }
    }

    /** Конец последнего опубликованного сегмента (кадр, исключительно). */
    fun publishedEndFrame(): Long = segments.entries.maxOfOrNull { it.key * SEGMENT_FRAMES + segmentFrames(it.value) } ?: 0L

    /** Вызывается декодером при запуске: что сделать при [close]. */
    fun setOnClose(action: () -> Unit) { onClose = action }

    /** Декодер завершился с ошибкой: дальше окно не пополняется. */
    fun fail(error: Throwable) { failure = error; closed = true; synchronized(wake) { wake.notifyAll() } }

    /** Остановить декодер. Уже готовые сегменты остаются читаемыми (их держит этот объект). */
    override fun close() {
        if (closed) return
        closed = true
        synchronized(wake) { wake.notifyAll() }
        onClose?.invoke()
    }

    private fun segmentFrames(data: ByteBuffer): Long = data.limit() / BYTES_PER_FRAME

    private fun checkComplete() {
        if (complete || !endKnown) return
        val last = endSegmentExclusive
        if ((0 until last).all { segments.containsKey(it) }) { complete = true; runEnd = totalFrames }
    }

    private fun evictOutsideWindow() {
        val from = playheadSegment - BEHIND_SEGMENTS
        val to = playheadSegment + AHEAD_SEGMENTS
        segments.keys.filter { it < from || it > to }.forEach { segments.remove(it) }
    }

    private fun recomputeRun() {
        if (complete) { runEnd = totalFrames; return }
        var i = focusFrame / SEGMENT_FRAMES
        var data = segments[i] ?: run { runEnd = i * SEGMENT_FRAMES; return }
        while (segmentFrames(data) == SEGMENT_FRAMES.toLong()) {
            data = segments[i + 1] ?: break
            i++
        }
        runEnd = i * SEGMENT_FRAMES + segmentFrames(data)
    }

    override fun sample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= totalFrames || ch !in 0..1) return 0f
        val index = frame / SEGMENT_FRAMES
        val data = segments[index] ?: return 0f
        val within = (frame % SEGMENT_FRAMES).toInt()
        val offset = (within * BYTES_PER_FRAME + ch * Pcm24.BYTES).toInt()
        if (offset + Pcm24.BYTES > data.limit()) return 0f
        return Pcm24.readLe(data, offset)
    }

    companion object {
        const val SEGMENT_FRAMES = 5 * SF.SAMPLE_RATE
        const val BYTES_PER_FRAME = 6L          // стерео, 24 бит little-endian, интерлив
        /** Сколько сегментов держать впереди позиции (40 с; декодер MP3/AAC идёт в десятки раз быстрее реального времени). */
        const val AHEAD_SEGMENTS = 8L
        /** Сколько сегментов оставлять позади позиции (10 с: короткая перемотка назад без ожидания). */
        const val BEHIND_SEGMENTS = 2L
    }
}
