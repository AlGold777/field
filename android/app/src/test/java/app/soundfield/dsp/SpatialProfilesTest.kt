package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialProfilesTest {
    @Test fun parsesSpanAndXtcBeta() {
        val profiles = SpatialProfiles.parse("""
            {"version":3,"profiles":{"pair_wide":{"span":1.0,"xtcBeta":null},
            "solo_stereo_speaker":{"span":1.5,"xtcBeta":0.06}}}
        """.trimIndent())
        assertEquals(1.0, profiles.getValue("pair_wide").span, 0.0)
        assertNull(profiles.getValue("pair_wide").xtcBeta)
        assertEquals(1.5, profiles.getValue("solo_stereo_speaker").span, 0.0)
        assertEquals(0.06, profiles.getValue("solo_stereo_speaker").xtcBeta!!, 0.0)
    }

    @Test fun fallbackSpansGrowForNearerAndSoloPlacement() {
        assertEquals(3, SpatialProfiles.VERSION)
        assertTrue(SpatialProfiles.fallback("pair_near").span > SpatialProfiles.fallback("pair_wide").span)
        assertTrue(SpatialProfiles.fallback("solo_stereo_speaker").span > SpatialProfiles.fallback("pair_near").span)
        assertEquals(1.0, SpatialProfiles.fallback("off").span, 0.0)
    }

    @Test fun headphonesProfileHasNoBeyondBaseExtensionAndNoXtc() {
        val profile = SpatialProfiles.fallback("solo_headphones")
        assertEquals("solo_headphones", profile.id)
        assertEquals(0.0, profile.span, 0.0)
        assertNull(profile.xtcBeta)
    }

    @Test fun bundledProfilesMatchFallback() {
        val file = listOf(java.io.File("src/main/assets/spatial_profiles.json"), java.io.File("app/src/main/assets/spatial_profiles.json"))
            .first { it.isFile }
        val parsed = SpatialProfiles.parse(file.readText())
        for (id in listOf("pair_wide", "pair_near", "solo_stereo_speaker", "solo_headphones", "off"))
            assertEquals(id, SpatialProfiles.fallback(id), parsed.getValue(id))
    }
}
