package app.soundfield.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Потоковый стерео-ресемплер с окном-sinc (24 отвода, полифазная таблица без приближений по фазе).
 * Заменяет линейную интерполяцию: при 44,1 → 48 кГц линейная давала заметные зеркальные
 * искажения в верхних частотах. Работает на входе произвольными порциями, память — O(порции).
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
 * Трек, декодированный в файл (48 кГц, стерео, int16 LE) и отображённый в память (mmap).
 * Раньше весь трек держался в куче Java (для 10-минутного файла на пике декодирования ~400 МБ) —
 * длинные файлы роняли приложение с OutOfMemoryError. Отображённый файл кучу не занимает.
 */
class MappedPcmSource(file: File, override val title: String, override val artist: String) : AudioSource {
    private val buf: MappedByteBuffer
    override val mode = SourceMode.MUSIC
    override val totalFrames: Long

    init {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            require(size in 4..Int.MAX_VALUE.toLong()) { "Трек слишком длинный" }
            buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            totalFrames = size / 4
        }
    }

    override fun sample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= totalFrames) return 0f
        return buf.getShort(((frame * 2 + ch) * 2).toInt()) / 32768f
    }
}
