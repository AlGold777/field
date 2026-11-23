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
    /** Oldest retained frame. A ring may evict frames below this position. */
    val firstReadyFrame: Long get() = 0L
    /** Exclusive upper bound of available frames. */
    val readyFrames: Long get() = totalFrames
    val complete: Boolean get() = true
    fun sample(frame: Long, ch: Int): Float
    /** Read without advancing or restarting a worker. Callers prepare demand separately. */
    fun readPrepared(frame: Long, ch: Int): Float = sample(frame, ch)
}

/** Источник знает true-peak трека (анализ при импорте); NaN — неизвестно или анализ ещё идёт (значение — максимум по уже декодированному). */
interface TrackMeters {
    val truePeakDbtp: Double
    /** Интегральная громкость трека BS.1770-4, LUFS; NaN — неизвестна или анализ не завершён (A3.1). */
    val loudnessLufs: Double get() = Double.NaN
}

/**
 * Несколько треков на одной шкале ([TrackChain]): пик и громкость зависят от места. Нормализация
 * (предлимитер) берёт их по кадру и меняет усиление ровно на границе трека, а не по позиции воспроизведения.
 */
interface PositionalTrackMeters {
    fun truePeakAt(frame: Long): Double
    fun loudnessAt(frame: Long): Double
    /** Одинаков для кадров одного трека, различается у соседних треков. */
    fun trackKeyAt(frame: Long): Long
    /** Первый кадр следующего трека после [frame] или Long.MAX_VALUE. */
    fun nextTrackStartAfter(frame: Long): Long = Long.MAX_VALUE
}

/** Sources with a forward worker can precompute frames before a consumer reads them. */
interface PrefetchableAudioSource {
    fun requestThrough(frameExclusive: Long)
}

/** Декодированный стереофайл в памяти (float32, интерлив L/R, 48 кГц): встроенный тестовый трек и лаборатория. */
class PcmSource(
    private val pcm: FloatArray, override val title: String, override val artist: String,
) : AudioSource, TrackMeters {
    override val mode = SourceMode.MUSIC
    override val totalFrames = (pcm.size / 2).toLong()
    /** true-peak считается лениво одним проходом ядра (встроенный трек, лаборатория). */
    private val analysis by lazy {
        app.soundfield.dsp.TruePeakMeter().use { it.process(pcm, pcm.size / 2); Pair(it.truePeakDbtp, it.loudnessLufs) }
    }
    override val truePeakDbtp: Double get() = analysis.first
    override val loudnessLufs: Double get() = analysis.second
    override fun sample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= totalFrames) return 0f
        return pcm[(frame * 2 + ch).toInt()]
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
    // предвычислено: sample() вызывается на аудиопотоке для каждого кадра
    private val startsL = doubleArrayOf(0.3, 0.8, 1.3)
    private val startsR = doubleArrayOf(2.1, 2.6, 3.1)
    override fun sample(frame: Long, ch: Int): Float {
        val t = frame.toDouble() / SF.SAMPLE_RATE
        val starts = if (ch == Ch.L) startsL else startsR
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

/** Two-second-spaced-independent probe pairs: L chirp, then R chirp 100 ms later. */
class SyncProbeSource : AudioSource {
    override val mode = SourceMode.SYNC_PROBE
    override val title = "Замер синхронизации"
    override val artist = "Внутренний режим разработчика"
    override val totalFrames = SF.SAMPLE_RATE * TRIALS.toLong()
    private val chirp = makeChirp()

    override fun sample(frame: Long, ch: Int): Float {
        if (frame < 0 || frame >= totalFrames || ch !in Ch.L..Ch.R) return 0f
        val trial = frame / SF.SAMPLE_RATE
        val offset = (frame % SF.SAMPLE_RATE).toInt()
        val start = if (ch == Ch.L) 0 else SECOND_CHIRP_OFFSET_FRAMES
        val inChirp = offset - start
        return if (trial < TRIALS && inChirp in chirp.indices) chirp[inChirp] else 0f
    }

    companion object {
        const val TRIALS = 120
        const val CHIRP_FRAMES = 960
        const val SECOND_CHIRP_OFFSET_FRAMES = 4_800

        fun makeChirp(): FloatArray {
            val n = CHIRP_FRAMES
            val duration = n.toDouble() / SF.SAMPLE_RATE
            val f0 = 1_000.0
            val f1 = 6_000.0
            return FloatArray(n) { i ->
                val t = i.toDouble() / SF.SAMPLE_RATE
                val phase = 2.0 * PI * (f0 * t + (f1 - f0) * t * t / (2.0 * duration))
                val window = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
                (0.4 * sin(phase) * window).toFloat()
            }
        }
    }
}

/** Минимальный WAV-парсер: PCM 8/16/24/32 бит и float32, моно/стерео; приводит к 48 кГц стерео float (ресемплер ядра). */
object Wav {
    class Decoded(val pcm: FloatArray, val sampleRate: Int)

    fun decode(bytes: ByteArray): Decoded {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Не WAV" }
        var pos = 12L
        var channels = 0; var rate = 0; var bits = 0; var fmt = 0
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val p = pos.toInt()
            val id = String(bytes, p, 4)
            val len = b.getInt(p + 4)
            val body = p + 8
            when (id) {
                "fmt " -> {
                    require(len >= 16 && body + 16 <= bytes.size) { "Повреждённый WAV: короткий fmt" }
                    fmt = b.getShort(body).toInt() and 0xFFFF
                    channels = b.getShort(body + 2).toInt() and 0xFFFF
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt() and 0xFFFF
                    // WAVE_FORMAT_EXTENSIBLE: реальный формат — первые 2 байта GUID подформата
                    if (fmt == 0xFFFE) {
                        require(len >= 40 && body + 40 <= bytes.size) { "Повреждённый WAV: короткий fmt" }
                        fmt = b.getShort(body + 24).toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    // потоковые WAV иногда пишут отрицательную длину data: читаем до конца файла
                    val avail = bytes.size - body
                    dataOff = body; dataLen = if (len < 0 || len > avail) avail else len
                }
                else -> require(len >= 0) { "Повреждённый WAV: отрицательная длина чанка" }
            }
            if (dataOff >= 0 && channels > 0) break
            pos = body.toLong() + len.toLong().coerceAtLeast(0L) + (len and 1)
        }
        val isFloat = fmt == 3 && bits == 32
        require((fmt == 1 && bits in intArrayOf(8, 16, 24, 32) || isFloat) && channels in 1..2 && rate > 0 && dataOff >= 0) {
            "Нужен PCM 8/16/24/32 бит или float32"
        }
        val bytesPer = bits / 8
        val frames = dataLen / (bytesPer * channels)
        fun at(frame: Int, c: Int): Float {
            val o = dataOff + (frame * channels + c) * bytesPer
            return when {
                isFloat -> b.getFloat(o)
                bits == 16 -> b.getShort(o) / 32768f
                bits == 24 -> (((bytes[o + 2].toInt() shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o].toInt() and 0xFF))) / 8388608f
                bits == 32 -> b.getInt(o) / 2147483648f
                else -> ((bytes[o].toInt() and 0xFF) - 128) / 128f
            }
        }
        val st = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val l = at(f, 0)
            st[f * 2] = l; st[f * 2 + 1] = if (channels == 2) at(f, 1) else l
        }
        return Decoded(if (rate != SF.SAMPLE_RATE) SampleRateConverter.convertAll(st, frames, rate, SF.SAMPLE_RATE) else st, SF.SAMPLE_RATE)
    }
}
