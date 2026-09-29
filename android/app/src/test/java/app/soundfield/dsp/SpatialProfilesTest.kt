package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialProfilesTest {
    @Test fun parsesProfileFieldsBeforeNestedReflectionData() {
        val profiles = SpatialProfiles.parse("""
            {"version":1,"profiles":{"pair_wide":{"gMidDb":3.0,"gHighDb":2.0,
            "gAmbientDb":2.0,"depthDb":-2.0,"reflectionsEnabled":true,"reflectionTaps":[
            {"leftMs":7.2,"rightMs":10.5,"gainDb":-17.0},
            {"leftMs":13.4,"rightMs":18.1,"gainDb":-16.0},
            {"leftMs":21.0,"rightMs":15.7,"gainDb":-18.0}]},
            "pair_near":{"gMidDb":5.0,"gHighDb":3.0,"gAmbientDb":3.0,"depthDb":-1.5}}}
        """.trimIndent())
        assertEquals(3.0, profiles.getValue("pair_wide").gMidDb, 0.0)
        assertEquals(-2.0, profiles.getValue("pair_wide").depthDb, 0.0)
        assertEquals(5.0, profiles.getValue("pair_near").gMidDb, 0.0)
        assertTrue(profiles.getValue("pair_wide").reflectionsEnabled)
        assertEquals(3, profiles.getValue("pair_wide").reflectionTaps.size)
        assertEquals(7.2, profiles.getValue("pair_wide").reflectionTaps.first().leftMs, 0.0)
        assertTrue(SpatialProfiles.fallback("pair_near").gMidDb > SpatialProfiles.fallback("pair_wide").gMidDb)
    }
}
