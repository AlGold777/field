package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun forwardMatchesDirectDft() {
        val size = 64
        val random = Random(0x0DF7)
        val real = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
        val imag = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
        val expectedReal = DoubleArray(size); val expectedImag = DoubleArray(size)
        for (k in 0 until size) for (n in 0 until size) {
            val angle = -2.0 * Math.PI * k * n / size
            expectedReal[k] += real[n] * Math.cos(angle) - imag[n] * Math.sin(angle)
            expectedImag[k] += real[n] * Math.sin(angle) + imag[n] * Math.cos(angle)
        }
        Radix2Fft(size).transform(real, imag)
        for (k in 0 until size) {
            assertEquals("real[$k]", expectedReal[k], real[k], 1e-9)
            assertEquals("imag[$k]", expectedImag[k], imag[k], 1e-9)
        }
    }

    @Test fun pureToneLandsInItsBin() {
        val size = 256; val bin = 19
        val real = DoubleArray(size) { Math.cos(2.0 * Math.PI * bin * it / size) }
        val imag = DoubleArray(size)
        Radix2Fft(size).transform(real, imag)
        val magnitude = DoubleArray(size) { Math.hypot(real[it], imag[it]) }
        val peak = (0 until size / 2).maxBy { magnitude[it] }
        assertEquals(bin, peak)
        assertEquals(size / 2.0, magnitude[bin], 1e-9)
        assertTrue((0 until size / 2).filter { it != bin }.all { magnitude[it] < 1e-9 })
    }
}
