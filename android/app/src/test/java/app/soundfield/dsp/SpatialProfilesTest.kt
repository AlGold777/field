package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialProfilesTest {
    @Test fun parsesProfileFieldsBeforeNestedReflectionData() {
        val profiles = SpatialProfiles.parse("""
            {"version":1,"profiles":{"pair_wide":{"gMidDb":3.0,"gHighDb":2.0,
            "gAmbientDb":2.0,"depthDb":-2.0,"reflectionTaps":[{"gainDb":-17.0}]},
            "pair_near":{"gMidDb":5.0,"gHighDb":3.0,"gAmbientDb":3.0,"depthDb":-1.5}}}
        """.trimIndent())
        assertEquals(3.0, profiles.getValue("pair_wide").gMidDb, 0.0)
        assertEquals(-2.0, profiles.getValue("pair_wide").depthDb, 0.0)
        assertEquals(5.0, profiles.getValue("pair_near").gMidDb, 0.0)
        assertTrue(SpatialProfiles.fallback("pair_near").gMidDb > SpatialProfiles.fallback("pair_wide").gMidDb)
    }
}
