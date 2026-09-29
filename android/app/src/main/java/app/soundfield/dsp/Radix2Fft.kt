package app.soundfield.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 complex FFT with a precomputed bit-reversal and twiddle table. */
class Radix2Fft(val size: Int) {
    private val bitReverse = IntArray(size)
    private val twiddleReal = DoubleArray(size / 2)
    private val twiddleImag = DoubleArray(size / 2)

    init {
        require(size >= 2 && size.countOneBits() == 1) { "FFT size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) bitReverse[i] = Integer.reverse(i) ushr (32 - bits)
        for (i in twiddleReal.indices) {
            val angle = -2.0 * PI * i / size
            twiddleReal[i] = cos(angle)
            twiddleImag[i] = sin(angle)
        }
    }

    /** The input arrays are mutated. No storage is allocated by this method. */
    fun transform(real: DoubleArray, imag: DoubleArray, inverse: Boolean = false) {
        require(real.size == size && imag.size == size)
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val q = imag[i]; imag[i] = imag[j]; imag[j] = q
            }
        }
        var length = 2
        while (length <= size) {
            val half = length ushr 1
            val tableStep = size / length
            var base = 0
            while (base < size) {
                var j = 0
                while (j < half) {
                    val ti = j * tableStep
                    val wr = twiddleReal[ti]
                    val wi = if (inverse) -twiddleImag[ti] else twiddleImag[ti]
                    val odd = base + j + half
                    val even = base + j
                    val tr = wr * real[odd] - wi * imag[odd]
                    val tiValue = wr * imag[odd] + wi * real[odd]
                    real[odd] = real[even] - tr
                    imag[odd] = imag[even] - tiValue
                    real[even] += tr
                    imag[even] += tiValue
                    j++
                }
                base += length
            }
            length = length shl 1
        }
        if (inverse) {
            val scale = 1.0 / size
            for (i in 0 until size) { real[i] *= scale; imag[i] *= scale }
        }
    }
}
