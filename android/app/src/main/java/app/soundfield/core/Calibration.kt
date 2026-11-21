package app.soundfield.core

import app.soundfield.dsp.Radix2Fft
import kotlin.math.abs

/**
 * Акустическая калибровка. Server записывает микрофоном, как оба телефона проигрывают
 * CalibrationSource. Для каждого k сравниваются моменты прихода чирпа Point (вверх) и чирпа Server (вниз):
 *
 *   late_k = (arrivalPoint_k − arrivalServer_k) − (expectedPoint_k − expectedServer_k)
 *
 * Абсолютное время начала записи и входная задержка микрофона при вычитании сокращаются —
 * остаётся только разница фактических выходных задержек (плюс остаточная ошибка синхронизации часов).
 * Итог — медиана по парам; новый сдвиг Point: trim_new = trim_old − late.
 */
object CalibrationAnalyzer {

    class Result(
        val ok: Boolean,
        val lateMs: Double,        // на сколько Point звучит позже Server (после текущего trim)
        val spreadMs: Double,      // разброс (MAD) — качество измерения
        val pairs: Int,
        val snrDb: Double,
        val message: String,
    )

    /**
     * @param rec запись микрофона (моно, float)
     * @param recSr частота записи
     * @param recStartServerNs момент первого сэмпла записи в часах Server (приблизительно, ±100 мс — достаточно)
     * @param anchorNs момент кадра 0 калибровочного сигнала (часы Server)
     * @param trimNs текущий сдвиг Point, уже применённый при воспроизведении
     */
    fun analyze(rec: FloatArray, recSr: Int, recStartServerNs: Long, anchorNs: Long, trimNs: Long): Result {
        if (rec.size < recSr) return Result(false, 0.0, 0.0, 0, 0.0, "Запись слишком короткая")
        val up = Chirp.make(true, recSr)
        val down = Chirp.make(false, recSr)
        val (corrUp, corrDown) = xcorrAbsBoth(rec, up, down)

        fun expectedSample(serverNs: Long) = (serverNs - recStartServerNs).toDouble() * recSr / 1e9

        val lates = ArrayList<Double>()
        var snrSum = 0.0; var snrN = 0
        for (k in 0 until CalibrationSource.COUNT) {
            val eP = anchorNs + trimNs + ((CalibrationSource.POINT_FIRST_S + k) * 1e9).toLong()
            val eS = anchorNs + ((CalibrationSource.SERVER_FIRST_S + k) * 1e9).toLong()
            val pP = findPeak(corrUp, expectedSample(eP), recSr) ?: continue
            val pS = findPeak(corrDown, expectedSample(eS), recSr) ?: continue
            val late = (pP.pos - pS.pos) / recSr - (eP - eS) / 1e9
            lates.add(late * 1000.0)
            snrSum += (pP.snrDb + pS.snrDb) / 2; snrN++
        }
        if (lates.size < 3) {
            return Result(false, 0.0, 0.0, lates.size, if (snrN > 0) snrSum / snrN else 0.0,
                "Сигнал не распознан (${lates.size}/${CalibrationSource.COUNT}). Положите телефоны рядом, " +
                    "увеличьте громкость и повторите в тишине.")
        }
        val med = median(lates)
        val mad = median(lates.map { abs(it - med) })
        val snr = snrSum / snrN
        val ok = mad < 3.0
        return Result(ok, med, mad, lates.size, snr,
            if (ok) "Измерено по ${lates.size} парам сигналов"
            else "Большой разброс измерений (±%.1f мс) — повторите в более тихом месте".format(mad))
    }

    class Peak(val pos: Double, val snrDb: Double)

    /** Ищет пик корреляции в окне [e − 0.3 с, e + 0.45 с]; подтверждает превышение над медианой окна. */
    private fun findPeak(c: FloatArray, expected: Double, sr: Int): Peak? {
        val from = maxOf(1, (expected - 0.30 * sr).toInt())
        val to = minOf(c.size - 2, (expected + 0.45 * sr).toInt())
        if (to - from < sr / 20) return null
        var argmax = from; var maxV = 0f
        for (i in from..to) if (c[i] > maxV) { maxV = c[i]; argmax = i }
        // первый индекс, где огибающая достигает 50% максимума окна; дальше — главный лепесток прямого пути
        // (до 1 мс от начала). Лепестки несущей чирпа (±5 сэмплов, 60–90% пика) и отражения позже 1 мс не выбираются.
        var i0 = argmax
        for (i in from..to) if (c[i] >= 0.5f * maxV) { i0 = i; break }
        val iEnd = minOf(to, i0 + (0.001 * sr + 0.5).toInt())
        var best = i0
        for (i in i0..iEnd) if (c[i] > c[best]) best = i
        val window = FloatArray(to - from + 1) { c[from + it] }
        window.sort()
        val noise = window[window.size / 2].toDouble().coerceAtLeast(1e-9)
        val ratio = c[best] / noise
        if (ratio < 8.0) return null
        // параболическая интерполяция
        val y0 = c[best - 1].toDouble(); val y1 = c[best].toDouble(); val y2 = c[best + 1].toDouble()
        val den = y0 - 2 * y1 + y2
        val delta = if (abs(den) > 1e-12) 0.5 * (y0 - y2) / den else 0.0
        return Peak(best + delta.coerceIn(-0.5, 0.5), 20 * kotlin.math.log10(ratio))
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** |взаимная корреляция| с двумя шаблонами: c[i] = Σ tpl[j]·x[i+j]. БПФ записи считается один раз. */
    fun xcorrAbsBoth(x: FloatArray, tplA: FloatArray, tplB: FloatArray): Pair<FloatArray, FloatArray> {
        var n = 2 // Radix2Fft требует размер >= 2
        while (n < x.size + maxOf(tplA.size, tplB.size)) n = n shl 1
        val xr0 = DoubleArray(n); val xi0 = DoubleArray(n)
        for (i in x.indices) xr0[i] = x[i].toDouble()
        val fft = Radix2Fft(n)
        fft.transform(xr0, xi0, false)
        return Pair(corrWith(fft, xr0, xi0, tplA, x.size), corrWith(fft, xr0, xi0, tplB, x.size))
    }

    private fun corrWith(fft: Radix2Fft, xr0: DoubleArray, xi0: DoubleArray, tpl: FloatArray, len: Int): FloatArray {
        val n = xr0.size
        val xr = xr0.copyOf(); val xi = xi0.copyOf()
        val tr = DoubleArray(n); val ti = DoubleArray(n)
        for (i in tpl.indices) tr[i] = tpl[i].toDouble()
        fft.transform(tr, ti, false)
        for (i in 0 until n) { // X · conj(T)
            val re = xr[i] * tr[i] + xi[i] * ti[i]
            val im = xi[i] * tr[i] - xr[i] * ti[i]
            xr[i] = re; xi[i] = im
        }
        fft.transform(xr, xi, true)
        return FloatArray(len) { abs(xr[it]).toFloat() }
    }
}
