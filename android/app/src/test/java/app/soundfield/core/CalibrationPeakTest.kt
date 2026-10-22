package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Выбор главного пика в CalibrationAnalyzer.findPeak: прямой путь, отражения, шум.
 * Запись синтезируется по схеме CalibrationSource: Server — чирп вниз, Point — чирп вверх.
 */
class CalibrationPeakTest {

    private class Echo(val delayS: Double, val amp: Float)

    @Test fun cleanSignalGivesKnownDelay() {
        val late = 23.4
        val r = analyze(synth(late))
        assertTrue(r.ok)
        assertEquals(late, r.lateMs, 0.05)
    }

    @Test fun strongerReflectionAfterOneMsIsIgnored() {
        val late = 23.4
        // отражение ×1,5 через 9 мс: огибающая максимума — на отражении, прямой путь — 67% максимума
        val r = analyze(synth(late, pointEchoes = listOf(Echo(0.009, 1.5f))))
        assertEquals(late, r.lateMs, 0.1)
    }

    @Test fun weakerEarlierReflectionIsIgnored() {
        val late = 23.4
        // отражение ×0,3 за 3 мс до прямого пути: ниже 50% максимума, не выбирается
        val r = analyze(synth(late, pointEchoes = listOf(Echo(-0.003, 0.3f))))
        assertEquals(late, r.lateMs, 0.1)
    }

    @Test fun silenceIsNotOk() {
        val r = analyze(FloatArray((SR * REC_S).toInt()))
        assertFalse(r.ok)
    }

    @Test fun noiseOnlyIsNotOk() {
        val r = analyze(synth(0.0, noise = 0.05f, withChirps = false))
        assertFalse(r.ok)
    }

    private fun analyze(rec: FloatArray) =
        CalibrationAnalyzer.analyze(rec, SR, recStartServerNs = 0L, anchorNs = ANCHOR_NS, trimNs = 0L)

    /** Дробная задержка линейной интерполяцией (как в coretest): лепестки у каждого канала свои. */
    private fun place(rec: FloatArray, tpl: FloatArray, t: Double, amp: Float) {
        val pos = t * SR
        val i0 = Math.floor(pos).toInt()
        val fr = (pos - i0).toFloat()
        for (j in tpl.indices) {
            val v = tpl[j] * amp
            if (i0 + j + 1 < rec.size) {
                rec[i0 + j] += v * (1 - fr)
                rec[i0 + j + 1] += v * fr
            }
        }
    }

    private fun synth(
        lateMs: Double,
        pointEchoes: List<Echo> = emptyList(),
        noise: Float = 0f,
        withChirps: Boolean = true,
    ): FloatArray {
        val rec = FloatArray((SR * REC_S).toInt())
        if (noise > 0f) {
            val rnd = Random(7)
            for (i in rec.indices) rec[i] = (rnd.nextDouble() * 2 - 1).toFloat() * noise
        }
        if (!withChirps) return rec
        val up = Chirp.make(true, SR)
        val down = Chirp.make(false, SR)
        for (k in 0 until CalibrationSource.COUNT) {
            val eS = ANCHOR_S + CalibrationSource.SERVER_FIRST_S + k
            val eP = ANCHOR_S + CalibrationSource.POINT_FIRST_S + k
            // Point приходит позже Server на lateMs; общая задержка выхода и входа одинакова и сокращается
            // дробный сдвиг Server (0,37 сэмпла, ≈0,008 мс — в допуске): каналы не совпадают по сетке отсчётов
            val tS = eS + OUT_DELAY_S + 0.37 / SR
            val tP = eP + lateMs / 1000.0 + OUT_DELAY_S
            place(rec, down, tS, 0.3f)
            place(rec, up, tP, 0.15f)
            for (e in pointEchoes) place(rec, up, tP + e.delayS, 0.15f * e.amp)
        }
        return rec
    }

    private companion object {
        const val SR = 48_000
        const val REC_S = 8.2
        const val ANCHOR_S = 0.7
        const val ANCHOR_NS = 700_000_000L
        const val OUT_DELAY_S = 0.032
    }
}
