package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** A3.1: формула усиления нормализации (общие векторы с iOS) и громкость ядра через хост-библиотеку. */
class LoudnessNormalizationTest {
    private fun vectorsText(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "tools/loudness_gain_vectors.json")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        error("tools/loudness_gain_vectors.json не найден")
    }

    private fun num(s: String) = if (s == "null") Double.NaN else s.toDouble()

    @Test fun gainFormulaMatchesSharedVectors() {
        val re = Regex("""\{"lufs":\s*(-?[\d.]+|null),\s*"tp":\s*(-?[\d.]+|null),\s*"gain_db":\s*(-?[\d.]+)\}""")
        val all = re.findAll(vectorsText()).toList()
        assertTrue("векторов: ${all.size}", all.size >= 11)
        for (m in all) {
            val (l, tp, g) = m.destructured
            assertEquals(m.value, g.toDouble(), TruePeakMeter.normalizationGainDb(num(l), num(tp)), 1e-9)
        }
    }

    @Test fun nativeLoudnessOfReferenceSine() {
        val path = System.getProperty("sfdsp.host.lib")
        assumeTrue("host libsfdsp not built", path != null && File(path).isFile && SfDsp.loadFrom(path))
        val n = 48_000 * 20
        val a = Math.pow(10.0, -23.0 / 20.0)
        val x = FloatArray(n * 2) { (a * sin(2 * PI * 997.0 * (it / 2) / 48_000.0)).toFloat() }
        TruePeakMeter().use {
            it.process(x, n)
            assertEquals(-23.0, it.loudnessLufs, 0.1)
            assertEquals(-23.0, it.truePeakDbtp, 0.2)
        }
    }

    @Test fun silenceHasNoLoudness() {
        val path = System.getProperty("sfdsp.host.lib")
        assumeTrue("host libsfdsp not built", path != null && File(path).isFile && SfDsp.loadFrom(path))
        TruePeakMeter().use {
            it.process(FloatArray(2 * 48_000 * 2), 48_000 * 2)
            assertTrue(it.loudnessLufs.isNaN())
        }
    }
}
