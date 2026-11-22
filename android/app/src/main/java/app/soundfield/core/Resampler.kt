package app.soundfield.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
 * PCM-файл, публикуемый декодером частями. Реалтайм-чтение никогда не ждёт декодер:
 * ещё не готовый сегмент возвращает тишину. Server отправляет эту же тишину в UDP,
 * поэтому обе стороны сохраняют одну временную шкалу. Размер трека не ограничен Int.MAX_VALUE.
 *
 * Сегменты могут появляться не по порядку: при перемотке за готовую часть [focus] просит декодер
 * перейти к новому месту, а пропущенные участки он дозаполняет позже. [readyFrames] — конец
 * непрерывного готового участка от точки [focus] (от начала трека, пока перемотки не было).
 */
class SegmentedPcmSource(
    private val directory: File,
    override val title: String,
    override val artist: String,
    estimatedFrames: Long,
) : AudioSource, TrackMeters {
    override val mode = SourceMode.MUSIC
    /** Максимальный true-peak по уже декодированной части (растёт по мере декодирования), NaN — пока нет данных. */
    @Volatile override var truePeakDbtp: Double = Double.NaN
    /** Громкость известна только у полностью декодированного трека (частичная ввела бы в заблуждение). */
    @Volatile override var loudnessLufs: Double = Double.NaN
    @Volatile override var totalFrames: Long = estimatedFrames.coerceAtLeast(0)
        private set
    @Volatile private var runEnd: Long = 0
    @Volatile private var focusFrame: Long = 0
    override val readyFrames: Long get() = if (complete) totalFrames else runEnd
    @Volatile override var complete: Boolean = false
        private set
    private val segments = ConcurrentHashMap<Long, MappedByteBuffer>()
    private val requestedSegment = AtomicLong(NO_REQUEST)

    fun publish(index: Long, file: File, frames: Int) {
        require(frames in 1..SEGMENT_FRAMES)
        RandomAccessFile(file, "r").use { raf ->
            val bytes = frames.toLong() * BYTES_PER_FRAME
            val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, bytes)
            mapped.order(ByteOrder.LITTLE_ENDIAN)
            segments[index] = mapped
        }
        val ready = index * SEGMENT_FRAMES + frames
        synchronized(this) {
            // Некоторые контейнеры/потоки не сообщают длительность заранее. До конца декодирования
            // показываем уже доступную длину; finish() заменит её точным числом кадров.
            if (!complete) totalFrames = maxOf(totalFrames, ready)
            recomputeRun()
        }
    }

    fun finish(actualFrames: Long) {
        synchronized(this) {
            totalFrames = actualFrames
            complete = true
            runEnd = actualFrames
        }
    }

    /** Воспроизведение переходит к [frame]: готовый участок считается от него, декодер переходит сюда, если здесь пусто. */
    fun focus(frame: Long) {
        synchronized(this) {
            focusFrame = frame.coerceAtLeast(0)
            recomputeRun()
        }
        if (!complete) requestedSegment.set(focusFrame / SEGMENT_FRAMES)
    }

    /** Сегмент, к которому просили перейти после последнего вызова, или null. Для потока декодера. */
    fun takeRequestedSegment(): Long? = requestedSegment.getAndSet(NO_REQUEST).takeIf { it != NO_REQUEST }

    fun hasSegment(index: Long): Boolean = segments.containsKey(index)

    /** Первый недекодированный сегмент в [from, untilExclusive) или null. */
    fun firstMissingSegment(from: Long, untilExclusive: Long): Long? {
        var i = from.coerceAtLeast(0)
        while (i < untilExclusive) { if (!segments.containsKey(i)) return i; i++ }
        return null
    }

    /** Конец последнего опубликованного сегмента (кадр, исключительно). */
    fun publishedEndFrame(): Long = segments.entries.maxOfOrNull { it.key * SEGMENT_FRAMES + segmentFrames(it.value) } ?: 0L

    private fun segmentFrames(mapped: MappedByteBuffer): Long = mapped.limit() / BYTES_PER_FRAME

    private fun recomputeRun() {
        if (complete) { runEnd = totalFrames; return }
        var i = focusFrame / SEGMENT_FRAMES
        var mapped = segments[i] ?: run { runEnd = i * SEGMENT_FRAMES; return }
        while (segmentFrames(mapped) == SEGMENT_FRAMES.toLong()) {
            mapped = segments[i + 1] ?: break
            i++
        }
        runEnd = i * SEGMENT_FRAMES + segmentFrames(mapped)
    }

    fun progress(): Float {
        if (complete) return 1f
        val expected = (totalFrames + SEGMENT_FRAMES - 1) / SEGMENT_FRAMES
        return if (expected <= 0) 0f else (segments.size.toDouble() / expected).toFloat().coerceIn(0f, 1f)
    }

    override fun sample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= totalFrames || ch !in 0..1) return 0f
        val index = frame / SEGMENT_FRAMES
        val mapped = segments[index] ?: return 0f
        val within = (frame % SEGMENT_FRAMES).toInt()
        val offset = (within * BYTES_PER_FRAME + ch * Pcm24.BYTES).toInt()
        if (offset + Pcm24.BYTES > mapped.limit()) return 0f
        return Pcm24.readLe(mapped, offset)
    }

    companion object {
        private const val NO_REQUEST = -1L
        const val SEGMENT_FRAMES = 5 * SF.SAMPLE_RATE
        const val BYTES_PER_FRAME = 6L          // стерео, 24 бит little-endian, интерлив
    }
}
