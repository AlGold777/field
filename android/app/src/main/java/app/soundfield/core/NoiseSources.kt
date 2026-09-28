package app.soundfield.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/** Детерминированный полосовой розовый шум с заданным RMS и ограниченным пиком. */
object PinkNoise {
    fun make(count: Int, lowHz: Double, highHz: Double, seed: Int = 0x13579BDF): FloatArray {
        var state = seed
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
        val aLow = (1.0 - exp(-2 * PI * lowHz / SF.SAMPLE_RATE)).toFloat()
        val aHigh = (1.0 - exp(-2 * PI * highHz / SF.SAMPLE_RATE)).toFloat()
        var low = 0f; var high = 0f
        val out = FloatArray(count)
        var power = 0.0
        var peak = 0f
        for (i in out.indices) {
            state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
            val white = (((state.toLong() and 0xffffffffL).toDouble() / 4294967295.0) * 2.0 - 1.0).toFloat()
            b0 = 0.99886f * b0 + white * 0.0555179f
            b1 = 0.99332f * b1 + white * 0.0750759f
            b2 = 0.96900f * b2 + white * 0.1538520f
            b3 = 0.86650f * b3 + white * 0.3104856f
            b4 = 0.55000f * b4 + white * 0.5329522f
            b5 = -0.7616f * b5 - white * 0.0168980f
            val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f) * 0.11f
            b6 = white * 0.115926f
            high += aHigh * (pink - high)
            low += aLow * (pink - low)
            val sample = high - low
            out[i] = sample
            power += sample.toDouble() * sample
            peak = maxOf(peak, kotlin.math.abs(sample))
        }
        if (out.isEmpty()) return out
        val rms = sqrt(power / out.size).coerceAtLeast(1e-9)
        val scale = minOf(0.1 / rms, 0.39810717 / maxOf(peak.toDouble(), 1e-9))
        for (i in out.indices) out[i] = (out[i] * scale).toFloat()
        return out
    }
}

class CenterCheckSource : AudioSource {
    override val mode = SourceMode.CENTER_CHECK
    override val title = "Проверка центра"
    override val artist = "Коррелированный розовый шум · оба телефона"
    override val totalFrames = (SF.SAMPLE_RATE * 7.5).toLong()
    private val noise = PinkNoise.make(SF.SAMPLE_RATE * 2, 200.0, 8_000.0)
    override fun sample(frame: Long, ch: Int): Float = if (frame < 0) 0f else {
        val pos = frame % (SF.SAMPLE_RATE * 5L / 2)
        if (pos < SF.SAMPLE_RATE * 2L) noise[(pos % noise.size).toInt()] else 0f
    }
}

class LevelMatchSource : AudioSource {
    override val mode = SourceMode.LEVEL_MATCH
    override val title = "Выравнивание уровня на слух"
    override val artist = "Сигнал L и R звучит по очереди"
    override val totalFrames = Long.MAX_VALUE
    private val tone = PinkNoise.make((SF.SAMPLE_RATE * 1.5).toInt(), 500.0, 4_000.0)
    private val active = SF.SAMPLE_RATE * 3L / 2
    private val pause = SF.SAMPLE_RATE / 4L
    private val fadeFrames = SF.SAMPLE_RATE * 20L / 1_000L
    private val cycle = active * 2 + pause * 2

    private fun sampleAt(frame: Long): Float {
        val fadeIn = frame.toFloat() / fadeFrames
        val fadeOut = (active - 1L - frame).toFloat() / fadeFrames
        val envelope = minOf(1f, fadeIn, fadeOut).coerceAtLeast(0f)
        return tone[(frame % tone.size).toInt()] * envelope
    }

    override fun sample(frame: Long, ch: Int): Float {
        if (frame < 0) return 0f
        val pos = frame % cycle
        return when {
            pos < active && ch == Ch.L -> sampleAt(pos)
            pos >= active + pause && pos < active * 2 + pause && ch == Ch.R -> sampleAt(pos - active - pause)
            else -> 0f
        }
    }
}
