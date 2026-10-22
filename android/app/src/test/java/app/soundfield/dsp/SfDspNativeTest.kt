package app.soundfield.dsp

import app.soundfield.core.PlayoutCorrector
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/** Сверка C++-ядра (хост-сборка libsfdsp) с Kotlin-реализацией: расхождение ≤ −120 dB (ТЗ §1.1). */
class SfDspNativeTest {
    @Before fun loadNative() {
        val path = System.getProperty("sfdsp.host.lib")
        assumeTrue("host libsfdsp not built", path != null && java.io.File(path).isFile && SfDsp.loadFrom(path))
    }

    private fun relativeDb(diff: DoubleArray, ref: DoubleArray): Double {
        val e = diff.sumOf { it * it }
        val s = ref.sumOf { it * it }
        return if (e == 0.0) -300.0 else 10 * log10(e / s)
    }

    @Test fun versionAndAbi() {
        assertEquals(SfDsp.EXPECTED_ABI, SfDsp.abiVersion())
        assertTrue(SfDsp.version().matches(Regex("""\d+\.\d+\.\d+""")))
    }

    @Test fun tableMatchesKotlin() {
        var worst = 0.0
        for (phase in 0 until WindowedSinc.PHASES) for (tap in 0 until WindowedSinc.TAPS) {
            worst = maxOf(worst, abs(SfDsp.sincCoefficient(phase, tap) - WindowedSinc.coefficient(phase, tap)).toDouble())
        }
        assertTrue("max coefficient difference $worst", worst <= 1e-7)
    }

    @Test fun interpolationMatchesKotlin() {
        val rnd = Random(5)
        val src = FloatArray(20_000) { (rnd.nextDouble() * 1.6 - 0.8).toFloat() }
        val kotlinOut = DoubleArray(10_000)
        val nativeOut = DoubleArray(10_000)
        for (i in kotlinOut.indices) {
            val position = 100.0 + i * 1.9 + rnd.nextDouble()
            kotlinOut[i] = WindowedSinc.interpolate(position) { src[it.toInt()] }.toDouble()
            nativeOut[i] = SfDsp.interpolate(src, 0, src.size, position).toDouble()
        }
        val diff = DoubleArray(kotlinOut.size) { nativeOut[it] - kotlinOut[it] }
        val db = relativeDb(diff, kotlinOut)
        assertTrue("interpolation mismatch $db dB", db <= -120.0)
    }

    @Test fun missingTapsGiveNaNLikeKotlin() {
        val src = FloatArray(100) { 1f }
        assertTrue(SfDsp.interpolate(src, 0, 100, 5.0).isNaN())
        assertTrue(WindowedSinc.interpolate(5.0) { if (it in 0..99) 1f else Float.NaN }.isNaN())
        assertTrue(!SfDsp.interpolate(src, 0, 100, 50.5).isNaN())
    }

    @Test fun playoutCorrectorMatchesKotlinPositions() {
        val kotlinSide = PlayoutCorrector()
        NativePlayout(48_000, 960).use { nativeSide ->
            val rnd = Random(11)
            var ideal = 50_000.0
            var worst = 0.0
            repeat(6_000) { block ->
                val n = if (block % 7 == 0) 1_024 else 480
                // дрейф 80 ppm + шум оценки ≈ 30 мкс + редкие скачки > порога жёсткого прыжка
                var target = ideal + rnd.nextGaussian() * 1.5
                if (block == 3_000) target += 5_000.0
                kotlinSide.plan(target, n)
                nativeSide.plan(target, n)
                for (i in intArrayOf(0, n / 2, n - 1)) worst = maxOf(worst, abs(kotlinSide.position(i) - nativeSide.position(i)))
                assertEquals(kotlinSide.syncErrorUs, nativeSide.syncErrorUs, 1e-9)
                ideal += n * 1.00008
            }
            assertTrue("position difference $worst frames", worst <= 1e-9)
            assertEquals(kotlinSide.hardJumps, nativeSide.hardJumps)
            assertEquals(kotlinSide.slewEvents, nativeSide.slewEvents)
            assertTrue(nativeSide.hardJumps >= 1)
        }
    }

    @Test fun renderedBlocksMatchKotlinRender() {
        val sr = 48_000
        val src = FloatArray(sr * 2) { (0.7 * sin(2 * Math.PI * 997.0 * it / sr) + 0.2 * sin(2 * Math.PI * 7_123.0 * it / sr)).toFloat() }
        val kotlinSide = PlayoutCorrector()
        val kotlinOut = ArrayList<Double>()
        val nativeOut = ArrayList<Double>()
        NativePlayout(sr, 960).use { nativeSide ->
            val tmp = FloatArray(480)
            var ideal = 200.0
            repeat(150) {
                kotlinSide.plan(ideal, 480)
                for (i in 0 until 480) kotlinOut += WindowedSinc.interpolate(kotlinSide.position(i)) { f ->
                    if (f in 0 until src.size) src[f.toInt()] else Float.NaN
                }.toDouble().let { if (it.isNaN()) 0.0 else it }
                nativeSide.render(src, 0, src.size, ideal, 480, tmp)
                for (i in 0 until 480) nativeOut += tmp[i].toDouble().let { if (it.isNaN()) 0.0 else it }
                ideal += 480 * 1.00005
            }
        }
        val ref = kotlinOut.toDoubleArray()
        val db = relativeDb(DoubleArray(ref.size) { nativeOut[it] - ref[it] }, ref)
        assertTrue("render mismatch $db dB", db <= -120.0)
    }
}
