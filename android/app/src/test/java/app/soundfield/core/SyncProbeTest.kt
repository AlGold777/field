package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProbeTest {
    @Test fun generatedRecordingRecoversInterPhoneOffsetAndDrift() {
        val rate = 48_000
        val startNs = 5_000_000_000L
        val anchorNs = startNs + 500_000_000L
        val chirp = SyncProbeSource.makeChirp()
        val recording = FloatArray(rate * 12 + 1_000)
        for (trial in 0 until 10) {
            val localStart = rate / 2 + trial * rate + 240
            val remoteStart = localStart + SyncProbeSource.SECOND_CHIRP_OFFSET_FRAMES + 12 + (trial % 3 - 1)
            chirp.indices.forEach { i ->
                recording[localStart + i] += chirp[i]
                recording[remoteStart + i] += chirp[i]
            }
        }
        val result = SyncProbeAnalyzer.analyze(recording, rate, startNs, anchorNs, 10)
        assertTrue(result.reason, result.valid)
        assertEquals(10, result.trials)
        assertEquals(0.25, result.medianMs, 0.03)
        assertTrue(result.p95Us < 50.0)
    }

    @Test fun clippingInvalidatesProbeRecording() {
        val recording = FloatArray(48_000) { if (it == 100) 0.95f else 0f }
        val result = SyncProbeAnalyzer.analyze(recording, 48_000, 1L, 1L, 1)
        assertFalse(result.valid)
        assertEquals("recording_clipped", result.reason)
    }

    @Test fun silentRecordingHasNoChirps() {
        val result = SyncProbeAnalyzer.analyze(FloatArray(48_000 * 12), 48_000, 0L, 500_000_000L, 10)
        assertFalse(result.valid)
        assertEquals("insufficient_chirps", result.reason)
        assertEquals(0, result.trials)
    }

    @Test fun zeroTrialsIsInvalid() {
        val result = SyncProbeAnalyzer.analyze(FloatArray(48_000), 48_000, 0L, 0L, 0)
        assertFalse(result.valid)
        assertEquals("insufficient_chirps", result.reason)
    }
}
