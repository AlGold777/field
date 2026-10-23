package app.soundfield.dsp

/**
 * Анализатор трека при импорте (ядро sfdsp): максимальный true-peak 4× за один проход по стерео-интерливу.
 * Сюда же в A3.1 добавится интегральная громкость BS.1770 (один проход на оба значения).
 * Без библиотеки ядра — выборочный пик (нижняя оценка true-peak: статическое усиление тогда недозащищает, остаётся предлимитер).
 */
class TruePeakMeter : AutoCloseable {
    private var handle = if (SfDsp.load()) SfDsp.nativeMeterCreate(2) else 0L
    private var samplePeak = 0.0

    fun process(interleavedStereo: FloatArray, frames: Int) {
        if (handle != 0L) SfDsp.nativeMeterProcess(handle, interleavedStereo, 0, frames)
        else for (i in 0 until frames * 2) samplePeak = maxOf(samplePeak, Math.abs(interleavedStereo[i].toDouble()))
    }

    /** Максимум с начала потока, dBTP (−200 — тишина). */
    val truePeakDbtp: Double get() =
        if (handle != 0L) SfDsp.nativeMeterTruePeak(handle) else if (samplePeak < 1e-10) -200.0 else 20.0 * Math.log10(samplePeak)

    /** Интегральная громкость BS.1770-4 с начала потока, LUFS; NaN — нет данных (нет ядра или трек тише порога). */
    val loudnessLufs: Double get() =
        if (handle != 0L) SfDsp.nativeMeterLoudness(handle).takeIf { it > -100.0 } ?: Double.NaN else Double.NaN

    override fun close() { if (handle != 0L) { SfDsp.nativeMeterDestroy(handle); handle = 0L } }

    companion object {
        /** Запас 0,05 dB под потолок предлимитера −1,5 dBTP. */
        const val STATIC_TARGET_DBTP = -1.55

        /** Целевая громкость трека, LUFS (A3.1). */
        const val TARGET_LUFS = -16.0
        /** Превышение потолка предлимитера, которое ещё отдаётся ему, dB; сверх — усиление уменьшается. */
        const val LIMITER_EXCESS_DB = 3.0
        /** Верхняя граница усиления нормализации, dB (не раздувать шум почти беззвучных треков). */
        const val MAX_BOOST_DB = 12.0

        /** Статическое усиление трека, dB: min(0, −1,55 − tp); NaN (анализ не завершён/неизвестен) → 0. */
        fun staticGainDb(truePeakDbtp: Double): Double =
            if (truePeakDbtp.isNaN()) 0.0 else minOf(0.0, STATIC_TARGET_DBTP - truePeakDbtp)

        /**
         * Усиление нормализации (A3.1), dB; формула и векторы — tools/loudness_gain_vectors.json.
         * Громкость неизвестна → прежнее правило по true-peak; иначе TARGET − L, не выше запаса до потолка + 3 dB и не выше 12 dB.
         */
        fun normalizationGainDb(lufs: Double, truePeakDbtp: Double): Double {
            if (lufs.isNaN() || lufs <= -100.0) return staticGainDb(truePeakDbtp)
            var g = TARGET_LUFS - lufs
            g = if (truePeakDbtp.isNaN()) minOf(g, 0.0) else minOf(g, STATIC_TARGET_DBTP - truePeakDbtp + LIMITER_EXCESS_DB)
            return minOf(g, MAX_BOOST_DB)
        }
    }
}
