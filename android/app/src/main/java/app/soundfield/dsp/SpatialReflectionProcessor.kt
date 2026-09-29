package app.soundfield.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Causal band-limited early reflections added equally and oppositely to L/R. */
class SpatialReflectionProcessor(
    taps: List<SpatialReflectionTap>,
    sampleRate: Int = 48_000,
    strength: Double = 1.0,
) {
    private val delays: IntArray
    private val gains: DoubleArray
    private val history: FloatArray
    private var cursor = 0
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val count = taps.size * 2
        delays = IntArray(count)
        gains = DoubleArray(count)
        var maxDelay = 1
        var index = 0
        taps.forEach { tap ->
            val gain = 0.5 * 10.0.pow(tap.gainDb * strength / 20.0)
            for (delayMs in doubleArrayOf(tap.leftMs, tap.rightMs)) {
                val delay = (delayMs * sampleRate / 1_000.0).toInt().coerceAtLeast(1)
                delays[index] = delay
                gains[index] = gain
                maxDelay = max(maxDelay, delay)
                index++
            }
        }
        history = FloatArray(maxDelay + 1)
        // RBJ band-pass, centered at sqrt(500 * 6000) Hz, spanning 500 Hz–6 kHz.
        val center = sqrt(500.0 * 6_000.0)
        val q = center / (6_000.0 - 500.0)
        val omega = 2.0 * PI * center / sampleRate
        val alpha = sin(omega) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = alpha / a0; b1 = 0.0; b2 = -alpha / a0
        a1 = -2.0 * cos(omega) / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(left: Float, right: Float): Float {
        val side = (left - right) * 0.5
        val filtered = b0 * side + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = side; y2 = y1; y1 = filtered
        history[cursor] = filtered.toFloat()
        var reflected = 0.0
        for (i in delays.indices) {
            var at = cursor - delays[i]
            if (at < 0) at += history.size
            reflected += history[at] * gains[i]
        }
        cursor++
        if (cursor == history.size) cursor = 0
        return reflected.toFloat()
    }

    fun reset() {
        history.fill(0f); cursor = 0
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}
