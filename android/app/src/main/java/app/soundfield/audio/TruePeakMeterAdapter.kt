package app.soundfield.audio

import app.soundfield.dsp.TruePeakMeter

/** [TrackAnalyzer.Meter] поверх измерителя ядра: true-peak 4× и громкость BS.1770-4 за один проход. */
class TruePeakMeterAdapter : Meter {
    private val meter = TruePeakMeter()
    override fun process(block: FloatArray, frames: Int) = meter.process(block, frames)
    override val truePeakDbtp: Double get() = meter.truePeakDbtp
    override val loudnessLufs: Double get() = meter.loudnessLufs
    override fun close() = meter.close()
}
