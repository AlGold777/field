package app.soundfield.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.soundfield.dsp.SpatialProfiles

class SpatialSettingsTest {
    @Test fun loudnessPreferenceReachesDspConfig() {
        val settings = SpatialSettings(enabled = true, level = SpatialLevel.SOFT,
            profile = "pair_wide", preserveLoudness = false)
        assertFalse(settings.dspConfig(SpatialProfiles.fallback("pair_wide")).preserveLoudness)
    }

    @Test fun loudnessPreferenceDefaultsToOn() = assertTrue(SpatialSettings().preserveLoudness)
}
