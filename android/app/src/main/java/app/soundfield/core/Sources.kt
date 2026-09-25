package app.soundfield.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Источник со случайным доступом по номеру кадра (48 кГц). ch: 0 = L, 1 = R. */
interface AudioSource {
    val mode: SourceMode
    val title: String
    val artist: String
    val totalFrames: Long
    fun sample(frame: Long, ch: Int): Float
}

/** Декодированный стереофайл (int16 interleaved). */
class PcmSource(
    private val pcm: ShortArray, override val title: String, override val artist: String,
) : AudioSource {
    override val mode = SourceMode.MUSIC
    override val totalFrames = (pcm.size / 2).toLong()
    override fun sample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= totalFrames) return 0f
        return pcm[(frame * 2 + ch).toInt()] / 32768f
    }
}

object Chirp {
    const val LENGTH_S = 1024.0 / 48_000.0   // ~21 мс
    const val F_LOW = 1_500.0
    const val F_HIGH = 7_500.0

    /** Линейный чирп с окном Ханна. up = true: F_LOW→F_HIGH, иначе наоборот. */
    fun make(up: Boolean, sampleRate: Int): FloatArray {
        val n = (LENGTH_S * sampleRate).toInt()
        val f0 = if (up) F_LOW else F_HIGH
        val f1 = if (up) F_HIGH else F_LOW
        val T = n.toDouble() / sampleRate
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val phase = 2 * PI * (f0 * t + (f1 - f0) * t * t / (2 * T))
            val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (sin(phase) * w).toFloat()
        }
    }
}

/**
 * Сигнал акустической калибровки. Канал Point: чирп ВВЕРХ в 0.5 + k с; канал Server: чирп ВНИЗ в 1.0 + k с.
 * Разные чирпы почти не коррелируют между собой, поэтому в записи микрофона их можно найти
 * независимо, даже если рассогласование велико.
 */
class CalibrationSource(private val pointCh: Int) : AudioSource {
    override val mode = SourceMode.CALIBRATION
    override val title = "Калибровка задержки"
    override val artist = "SoundField"
    override val totalFrames = (SF.SAMPLE_RATE * DURATION_S).toLong()
    private val up = Chirp.make(true, SF.SAMPLE_RATE)
    private val down = Chirp.make(false, SF.SAMPLE_RATE)

    override fun sample(frame: Long, ch: Int): Float {
        val isPoint = ch == pointCh
        val base = ((if (isPoint) POINT_FIRST_S else SERVER_FIRST_S) * SF.SAMPLE_RATE).toLong()
        if (frame < base) return 0f
        val k = (frame - base) / SF.SAMPLE_RATE
        val off = ((frame - base) % SF.SAMPLE_RATE).toInt()
        val tpl = if (isPoint) up else down
        return if (k < COUNT && off < tpl.size) tpl[off] * 0.8f else 0f
    }

    companion object {
        const val COUNT = 6
        const val POINT_FIRST_S = 0.5
        const val SERVER_FIRST_S = 1.0
        const val DURATION_S = 6.8
    }
}

/** Метроном для ручной подстройки: короткий щелчок одновременно в обоих каналах каждые 0.5 с. */
class MetronomeSource : AudioSource {
    override val mode = SourceMode.METRONOME
    override val title = "Метроном"
    override val artist = "Сдвигайте ползунок, пока щелчки не сольются в один"
    override val totalFrames = SF.SAMPLE_RATE * 600L
    private val period = SF.SAMPLE_RATE / 2
    private val click = FloatArray(480) { i ->
        val t = i.toDouble() / SF.SAMPLE_RATE
        (sin(2 * PI * 2_000 * t) * exp(-t / 0.002) * 0.9).toFloat()
    }
    override fun sample(frame: Long, ch: Int): Float {
        if (frame < 0) return 0f
        val off = (frame % period).toInt()
        return if (off < click.size) click[off] else 0f
    }
}

/** Проверка каналов: три сигнала слева (880 Гц), затем три справа (1320 Гц). */
class ChannelTestSource : AudioSource {
    override val mode = SourceMode.CHANNEL_TEST
    override val title = "Проверка каналов"
    override val artist = "Сначала левый, затем правый"
    override val totalFrames = (SF.SAMPLE_RATE * 3.8).toLong()
    override fun sample(frame: Long, ch: Int): Float {
        val t = frame.toDouble() / SF.SAMPLE_RATE
        val starts = if (ch == Ch.L) doubleArrayOf(0.3, 0.8, 1.3) else doubleArrayOf(2.1, 2.6, 3.1)
        val f = if (ch == Ch.L) 880.0 else 1_320.0
        for (s in starts) {
            val dt = t - s
            if (dt in 0.0..0.25) {
                val env = minOf(1.0, dt / 0.01) * minOf(1.0, (0.25 - dt) / 0.03)
                return (sin(2 * PI * f * dt) * env * 0.6).toFloat()
            }
        }
        return 0f
    }
}

/** Минимальный WAV-парсер: PCM 16 бит, моно/стерео; приводит к 48 кГц стерео. */
object Wav {
    class Decoded(val pcm: ShortArray, val sampleRate: Int)

    fun decode(bytes: ByteArray): Decoded {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Не WAV" }
        var pos = 12
        var channels = 0; var rate = 0; var bits = 0; var fmt = 0
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> { fmt = b.getShort(body).toInt(); channels = b.getShort(body + 2).toInt()
                            rate = b.getInt(body + 4); bits = b.getShort(body + 14).toInt() }
                "data" -> { dataOff = body; dataLen = minOf(len, bytes.size - body) }
            }
            if (dataOff >= 0 && channels > 0) break
            pos = body + len + (len and 1)
        }
        require(fmt == 1 && bits == 16 && channels in 1..2 && dataOff >= 0) { "Нужен PCM 16 бит" }
        val frames = dataLen / (2 * channels)
        var st = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val l = b.getShort(dataOff + (f * channels) * 2)
            val r = if (channels == 2) b.getShort(dataOff + (f * channels + 1) * 2) else l
            st[f * 2] = l; st[f * 2 + 1] = r
        }
        if (rate != SF.SAMPLE_RATE) st = resample(st, rate, SF.SAMPLE_RATE)
        return Decoded(st, SF.SAMPLE_RATE)
    }

    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        val inFrames = src.size / 2
        val outFrames = (inFrames.toLong() * to / from).toInt()
        val out = ShortArray(outFrames * 2)
        for (i in 0 until outFrames) {
            val x = i.toDouble() * from / to
            val i0 = x.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val fr = x - i0
            for (c in 0..1) out[i * 2 + c] = (src[i0 * 2 + c] * (1 - fr) + src[i1 * 2 + c] * fr).toInt().toShort()
        }
        return out
    }
}
