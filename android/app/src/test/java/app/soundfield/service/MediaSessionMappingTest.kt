package app.soundfield.service

import android.support.v4.media.session.PlaybackStateCompat as P
import org.junit.Assert.*
import org.junit.Test

class MediaSessionMappingTest {
    private val base = MediaInput(true, "T", "A", 200_000, playing = true, buffering = false, live = false, queueSize = 3)

    @Test fun playingHasSpeedOneAndSeek() {
        val s = MediaSessionMapping.playback(base, 5_000)
        assertEquals(P.STATE_PLAYING, s.state); assertEquals(1f, s.speed); assertEquals(5_000L, s.positionMs)
        assertTrue(s.actions and P.ACTION_SEEK_TO != 0L)
        assertTrue(s.actions and P.ACTION_SKIP_TO_NEXT != 0L)
    }

    @Test fun pausedHasZeroSpeed() {
        val s = MediaSessionMapping.playback(base.copy(playing = false), 5_000)
        assertEquals(P.STATE_PAUSED, s.state); assertEquals(0f, s.speed)
    }

    @Test fun bufferingWins() {
        assertEquals(P.STATE_BUFFERING, MediaSessionMapping.playback(base.copy(buffering = true), 0).state)
    }

    @Test fun liveHasNoSeekAndUnknownDuration() {
        val live = base.copy(live = true, durationMs = 0)
        assertEquals(0L, MediaSessionMapping.playback(live, 9_000).actions and P.ACTION_SEEK_TO)
        assertEquals(-1L, MediaSessionMapping.metadata(live).durationMs)
    }

    @Test fun noTrackIsNone() {
        assertEquals(P.STATE_NONE, MediaSessionMapping.playback(base.copy(hasTrack = false), 0).state)
    }

    @Test fun positionClampedToDuration() {
        assertEquals(200_000L, MediaSessionMapping.playback(base, 999_999).positionMs)
    }

    @Test fun driftDetection() {
        assertFalse(MediaSessionMapping.positionDrifted(true, 10_000, 0, 11_100, 1_000))
        assertTrue(MediaSessionMapping.positionDrifted(true, 10_000, 0, 40_000, 1_000))
        assertTrue(MediaSessionMapping.positionDrifted(false, 10_000, 0, 20_000, 1_000))
    }

    @Test fun blankTitleFallback() {
        assertEquals("SoundField", MediaSessionMapping.metadata(base.copy(title = "")).title)
    }
}
