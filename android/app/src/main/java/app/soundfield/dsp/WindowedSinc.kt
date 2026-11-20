package app.soundfield.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 32-tap, 256-phase Kaiser-windowed sinc interpolator shared by the audio render paths. */
object WindowedSinc {
    const val TAPS = 32
    const val HALF = TAPS / 2
    const val PHASES = 256
    /**
     * Не задержка фильтра (sinc с нулевой фазой), а запас чтения назад: рендер берёт кадры до позиции − HALF,
     * поэтому источник должен держать их готовыми. Имя совпадает с C ABI (SF_SINC_GROUP_DELAY_FRAMES).
     */
    const val GROUP_DELAY_FRAMES = HALF
    private const val BETA = 8.6
    private val table = Array(PHASES) { phase ->
        val fraction = phase.toDouble() / PHASES
        val row = DoubleArray(TAPS) { tap ->
            val x = tap - HALF + 1 - fraction
            val windowPos = x / HALF
            val window = if (abs(windowPos) >= 1.0) 0.0 else
                i0(BETA * sqrt(1.0 - windowPos * windowPos)) / i0(BETA)
            val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
            sinc * window
        }
        val sum = row.sum()
        FloatArray(TAPS) { (row[it] / sum).toFloat() }
    }

    /** Call with an inline reader in the render loop; a missing tap makes the result missing. */
    inline fun interpolate(position: Double, read: (Long) -> Float): Float {
        if (!position.isFinite()) return Float.NaN
        var center = floor(position).toLong()
        var phase = ((position - center) * PHASES).roundToInt()
        if (phase == PHASES) { center++; phase = 0 }
        var sum = 0f
        val first = center - HALF + 1
        for (tap in 0 until TAPS) {
            val sample = read(first + tap)
            if (sample.isNaN()) return Float.NaN
            sum += sample * coefficient(phase, tap)
        }
        return sum
    }

    @PublishedApi
    internal fun coefficient(phase: Int, tap: Int): Float = table[phase][tap]

    private fun i0(x: Double): Double {
        val y = x * x / 4.0
        var term = 1.0
        var sum = 1.0
        for (k in 1..24) {
            term *= y / (k * k)
            sum += term
            if (term < sum * 1e-16) break
        }
        return sum
    }
}
