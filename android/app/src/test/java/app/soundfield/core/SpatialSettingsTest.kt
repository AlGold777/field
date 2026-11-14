package app.soundfield.core

import org.junit.Assert.assertEquals
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

    @Test fun zeroWidthAndDepthSanitiseToDisabled() {
        val settings = SpatialSettings().withScene(0.0, 0.0).sanitized()
        assertFalse(settings.enabled)
        assertFalse(settings.dspConfig(SpatialProfiles.fallback("pair_wide")).enabled)
    }

    @Test fun sceneAndProfileReachDspConfig() {
        val config = SpatialSettings().withScene(0.7, 0.3).dspConfig(SpatialProfiles.fallback("pair_near"))
        assertTrue(config.enabled)
        assertEquals(0.7, config.width, 0.0)
        assertEquals(0.3, config.depth, 0.0)
        assertEquals(1.25, config.span, 0.0)
    }
}
