package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAvailabilityTest {
    private fun eval(solo: Boolean = false, music: Boolean = true, paired: Boolean = true, peer: Boolean = true,
        speaker: Boolean = true, stereo: Boolean = false) =
        SpatialAvailability.evaluate(solo, music, paired, peer, speaker, stereo)

    @Test fun pairWithCapablePeerOnSpeakerIsAvailable() = assertTrue(eval().available)

    @Test fun soloOnStereoSpeakersIsAvailableInAnyOrientation() = assertTrue(eval(solo = true, paired = false, stereo = true).available)

    @Test fun soloOnMonoPhoneIsHiddenWithoutHint() {
        val r = eval(solo = true, paired = false, stereo = false)
        assertFalse(r.available); assertEquals("", r.hint)
    }

    @Test fun soloOnHeadphonesIsHiddenWithoutHint() {
        val r = eval(solo = true, paired = false, speaker = false, stereo = true)
        assertFalse(r.available); assertEquals("", r.hint)
    }

    @Test fun pairWithOldPeerExplainsWhatToDo() {
        val r = eval(peer = false)
        assertFalse(r.available); assertEquals(SpatialAvailability.HINT_PEER_VERSION, r.hint)
    }

    @Test fun pairOnHeadphonesExplainsWhatToDo() {
        val r = eval(speaker = false)
        assertFalse(r.available); assertEquals(SpatialAvailability.HINT_SPEAKER, r.hint)
    }

    @Test fun serviceSignalsAndNoPairShowNothing() {
        assertEquals("", eval(music = false, peer = false).hint)
        assertEquals("", eval(paired = false).hint)
        assertFalse(eval(music = false).available)
    }
}
