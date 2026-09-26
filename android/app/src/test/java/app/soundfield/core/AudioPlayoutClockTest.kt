package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test

class AudioPlayoutClockTest {
    @Test fun partialWriteAdvancesOnlyAcceptedFrames() {
        val clock = AudioPlayoutClock(48_000, 2048)
        clock.accepted(120)
        assertEquals(1_022_500_000L, clock.presentation(1_000_000_000L, 0))
        clock.accepted(120)
        assertEquals(1_025_000_000L, clock.presentation(1_000_000_000L, 0))
    }

    @Test fun staleTimestampCannotKeepRenderingPastFrames() {
        val clock = AudioPlayoutClock(48_000, 2048)
        clock.accepted(480)
        clock.timestamp(240, 1_000_000_000L)
        assertEquals(1_005_000_000L, clock.presentation(1_000_000_000L, 240))
        assertTrue(clock.timestampOk)
        assertEquals(2_020_000_000L, clock.presentation(2_000_000_000L, 480))
        assertFalse(clock.timestampOk)
    }

    @Test fun unavailableTimestampFallsBackAndRecovers() {
        val clock = AudioPlayoutClock(48_000, 2048)
        clock.accepted(480)
        clock.timestamp(240, 1_000_000_000L)
        clock.invalidateTimestamp()
        assertEquals(1_025_000_000L, clock.presentation(1_000_000_000L, 240))
        assertFalse(clock.timestampOk)
        clock.timestamp(240, 1_000_000_000L)
        assertEquals(1_005_000_000L, clock.presentation(1_000_000_000L, 240))
        assertTrue(clock.timestampOk)
    }

    @Test fun underrunAndFutureTimestampUseCurrentTime() {
        val clock = AudioPlayoutClock(48_000, 2048)
        clock.accepted(480)
        clock.timestamp(480, 1_000_000_000L)
        assertEquals(1_120_000_000L, clock.presentation(1_100_000_000L, 480))
        assertFalse(clock.timestampOk)
        clock.timestamp(480, 3_000_000_000L)
        assertEquals(1_120_000_000L, clock.presentation(1_100_000_000L, 480))
        assertFalse(clock.timestampOk)
    }

    @Test fun playbackHeadWrapKeepsQueueLength() {
        val clock = AudioPlayoutClock(48_000, 2048)
        repeat(2) { clock.accepted(Int.MAX_VALUE) }
        clock.accepted(242) // 2^32 + 240 frames
        assertEquals(1_025_000_000L, clock.presentation(1_000_000_000L, 0))
    }

    @Test fun recreatedTrackStartsWithEmptyTiming() {
        val old = AudioPlayoutClock(48_000, 2048)
        old.accepted(48_000)
        old.timestamp(47_000, 1_000_000_000L)
        val fresh = AudioPlayoutClock(48_000, 2048)
        assertEquals(2_020_000_000L, fresh.presentation(2_000_000_000L, 0))
        assertFalse(fresh.timestampOk)
    }
}
