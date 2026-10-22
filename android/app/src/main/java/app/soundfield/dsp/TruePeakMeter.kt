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

    override fun close() { if (handle != 0L) { SfDsp.nativeMeterDestroy(handle); handle = 0L } }

    companion object {
        /** Запас 0,05 dB под потолок предлимитера −1,5 dBTP. */
        const val STATIC_TARGET_DBTP = -1.55

        /** Статическое усиление трека, dB: min(0, −1,55 − tp); NaN (анализ не завершён/неизвестен) → 0. */
        fun staticGainDb(truePeakDbtp: Double): Double =
            if (truePeakDbtp.isNaN()) 0.0 else minOf(0.0, STATIC_TARGET_DBTP - truePeakDbtp)
    }
}
