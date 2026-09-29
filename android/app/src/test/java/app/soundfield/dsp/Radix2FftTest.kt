package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class Radix2FftTest {
    @Test fun forwardThenInverseRestoresRealAndImaginarySamples() {
        val size = 2_048
        val random = Random(0x51F1)
        val real = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
        val imag = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
        val originalReal = real.copyOf()
        val originalImag = imag.copyOf()
        val fft = Radix2Fft(size)

        fft.transform(real, imag)
        fft.transform(real, imag, inverse = true)

        for (i in 0 until size) {
            assertEquals("real[$i]", originalReal[i], real[i], 1e-12)
            assertEquals("imag[$i]", originalImag[i], imag[i], 1e-12)
        }
    }
}
