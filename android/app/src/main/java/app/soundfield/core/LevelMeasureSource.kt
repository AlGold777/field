package app.soundfield.core

/** Three reciprocal microphone sweeps. Only one phone plays during each sweep. */
class LevelMeasureSource(private val serverChannel: Int, private val pointChannel: Int) : AudioSource {
    override val mode = SourceMode.LEVEL_MEASURE
    override val title = "Эксперимент · измерение микрофонами"
    override val artist = "Поочередные измерительные свипы L/R"
    override val totalFrames = (SF.SAMPLE_RATE * CAPTURE_SECONDS).toLong()

    private val initialFrames = (SF.SAMPLE_RATE * INITIAL_SECONDS).toLong()
    private val cycleFrames = (SF.SAMPLE_RATE * CYCLE_SECONDS).toLong()
    private val activeFrames = (SF.SAMPLE_RATE * ACTIVE_SECONDS).toLong()
    private val gapFrames = (SF.SAMPLE_RATE * GAP_SECONDS).toLong()
    private val noise = PinkNoise.make(SF.SAMPLE_RATE * ACTIVE_SECONDS.toInt(), 500.0, 4_000.0, seed = 0x2468ACE)

    override fun sample(frame: Long, ch: Int): Float {
        if (frame < initialFrames || frame >= totalFrames) return 0f
        val relative = frame - initialFrames
        val pos = relative % cycleFrames
        if (pos < activeFrames && ch == serverChannel) {
            // Индекс от начала свипа, как у Point: смещение повтора (7 с против 3 с шума) рвало свип щелчком.
            return noise[(pos % noise.size).toInt()]
        }
        if (pos >= activeFrames + gapFrames && pos < activeFrames * 2 + gapFrames && ch == pointChannel) {
            return noise[((pos - activeFrames - gapFrames) % noise.size).toInt()]
        }
        return 0f
    }

    companion object {
        const val INITIAL_SECONDS = 0.75
        const val ACTIVE_SECONDS = 3.0
        const val GAP_SECONDS = 0.5
        const val CYCLE_SECONDS = 7.0
        const val REPEATS = 3
        const val CAPTURE_SECONDS = INITIAL_SECONDS + CYCLE_SECONDS * REPEATS
    }
}
