package app.soundfield.core

/** Timing for one AudioTrack lifetime. Only successfully written frames advance the clock. */
class AudioPlayoutClock(private val sampleRate: Int, private val capacityFrames: Int) {
    private var written = 0L
    private var stampFrame = 0L
    private var stampNs = 0L
    private var hasTimestamp = false
    var timestampOk = false; private set

    fun accepted(frames: Int) { require(frames > 0); written += frames }
    fun timestamp(frame: Long, ns: Long) {
        stampFrame = frame
        stampNs = ns
        hasTimestamp = frame > 0
    }
    fun invalidateTimestamp() { hasTimestamp = false }

    fun presentation(now: Long, playbackHead: Long): Long {
        val candidate = stampNs + (written - stampFrame) * 1_000_000_000L / sampleRate
        timestampOk = hasTimestamp && now - stampNs in 0L..500_000_000L &&
            candidate - now in 0L..1_000_000_000L
        if (timestampOk) return candidate
        // A stale hardware timestamp must never freeze the renderer in the past.
        // playbackHead is an unsigned 32-bit counter; subtraction also handles its wrap.
        val queued = ((written - playbackHead) and 0xFFFFFFFFL).coerceAtMost(capacityFrames.toLong())
        return now + queued * 1_000_000_000L / sampleRate + 20_000_000L
    }
}
