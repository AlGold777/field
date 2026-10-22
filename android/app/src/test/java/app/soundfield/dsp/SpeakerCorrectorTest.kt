package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Коррекция динамика через хост-сборку libsfdsp (как в [SfDspNativeTest]). */
class SpeakerCorrectorTest {
    private val sr = 48_000
    private val ch = 2

    @Before fun loadNative() {
        val path = System.getProperty("sfdsp.host.lib")
        assumeTrue("host libsfdsp not built", path != null && File(path).isFile && SfDsp.loadFrom(path))
    }

    private fun highpass250() = Profile(
        key = "test_hp", synthetic = false, match = emptyList(), preampDb = 0.0, fLowHz = 250.0,
        sections = listOf(Section("highpass", 250.0, 0.7071, null)), allpassVsPartner = emptyMap(),
    )

    private fun sine(freqHz: Double, frames: Int, amp: Double = 0.5): FloatArray =
        FloatArray(frames * ch) { i -> (amp * sin(2 * PI * freqHz * (i / ch) / sr)).toFloat() }

    private fun processAll(c: SpeakerCorrector, io: FloatArray, block: Int = 480) {
        val frames = io.size / ch
        var f = 0
        while (f < frames) {
            val n = minOf(block, frames - f)
            c.process(io, f, n)
            f += n
        }
    }

    /** RMS канала 0 по последним [tail] кадрам. */
    private fun rmsTail(x: FloatArray, tail: Int): Double {
        var s = 0.0
        for (i in x.size - tail * ch until x.size step ch) s += x[i].toDouble() * x[i]
        return sqrt(s / tail)
    }

    private fun sameBits(a: FloatArray, b: FloatArray, from: Int = 0): Boolean =
        (from until a.size).all { a[it].toRawBits() == b[it].toRawBits() }

    @Test fun emptyChainIsBitExact() {
        val rnd = Random(3)
        val src = FloatArray(sr / 4 * ch) { (rnd.nextFloat() * 2 - 1) }
        val io = src.copyOf()
        SpeakerCorrector(sr, ch).use { c ->
            assertTrue(c.isNative)
            processAll(c, io)
        }
        assertTrue("empty chain must not change samples", sameBits(src, io))
    }

    @Test fun highpassCutsBassAndKeepsTreble() {
        val frames = sr  // 1 с: переходный процесс затухает, измеряем последние 0,5 с
        val tail = sr / 2
        SpeakerCorrector(sr, ch).use { c ->
            assertTrue(c.setProfile(highpass250(), null))
            val bass = sine(25.0, frames)
            processAll(c, bass)
            val bassDb = 20 * log10(rmsTail(bass, tail) / rmsTail(sine(25.0, frames), tail))
            assertTrue("25 Hz attenuation $bassDb dB", bassDb < -30.0)
        }
        SpeakerCorrector(sr, ch).use { c ->
            assertTrue(c.setProfile(highpass250(), null))
            val treble = sine(5_000.0, frames)
            processAll(c, treble)
            val trebleDb = 20 * log10(rmsTail(treble, tail) / rmsTail(sine(5_000.0, frames), tail))
            assertTrue("5 kHz change $trebleDb dB", abs(trebleDb) < 0.5)
        }
    }

    @Test fun setProfileNullRemovesCorrection() {
        val frames = sr / 2
        val tail = sr / 4
        SpeakerCorrector(sr, ch).use { c ->
            assertTrue(c.setProfile(highpass250(), null))
            processAll(c, sine(25.0, frames))
            assertTrue(c.setProfile(null, null))
            // кроссфейд 50 мс; после него выход должен совпасть с входом бит-в-бит
            val src = sine(1_000.0, frames)
            val io = src.copyOf()
            processAll(c, io)
            assertTrue("correction must be gone after setProfile(null)", sameBits(src, io, from = (frames - tail) * ch))
        }
    }
}
