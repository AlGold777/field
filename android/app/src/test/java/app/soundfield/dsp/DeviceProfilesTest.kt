package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceProfilesTest {
    private val generic = """{"synthetic":false,"preamp_db":0.0,"f_low_hz":250.0,
        "sections":[{"type":"highpass","f0_hz":250.0,"q":0.5412}],"allpass_vs_partner":{}}"""

    private fun doc(models: String = "[]") =
        """{"profiles_version":"0.1.0","schema":1,"classes":{"generic_phone_speaker":$generic},"models":$models}"""

    private fun model(
        section: String = """{"type":"peak","f0_hz":2500.0,"q":1.2,"gain_db":-4.0}""",
        key: String = "pixel_8",
        match: String = """"Google/Pixel 8/shiba"""",
        synthetic: Boolean = false,
        preamp: String = "-2.0",
        fLow: String = "150.0",
        allpass: String = """{"other":[{"f0_hz":1800.0,"q":0.6}]}""",
    ) = """{"key":"$key","match":[$match],"synthetic":$synthetic,"preamp_db":$preamp,
        "f_low_hz":$fLow,"sections":[$section],"allpass_vs_partner":$allpass}"""

    /** Ищет profiles/devices.json, поднимаясь от рабочего каталога модуля (android/app). */
    private fun devicesJson(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "profiles/devices.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("profiles/devices.json не найден")
    }

    @Test fun parsesRealDevicesJson() {
        val table = DeviceProfiles.parse(devicesJson().readText())
        assertNotNull(table)
        table!!
        assertEquals("0.1.0", table.profilesVersion)
        assertEquals(1, table.schema)
        assertEquals(1, table.generic.sections.size)
        assertEquals("highpass", table.generic.sections[0].type)
        assertEquals(250.0, table.generic.sections[0].f0Hz, 0.0)
        val a = table.models.single { it.key == "synthetic_test_a" }
        assertTrue(table.models.any { it.key == "synthetic_test_b" && it.synthetic })
        assertEquals(3, a.sections.size)
        assertEquals(1800.0, DeviceProfiles.allpassFor(a, "synthetic_test_b")[0].f0Hz, 0.0)
        assertTrue(DeviceProfiles.allpassFor(a, "missing").isEmpty())
    }

    @Test fun lookupFindsModelIgnoringCase() {
        val table = DeviceProfiles.parse(doc("[${model()}]"))!!
        assertEquals("pixel_8", table.lookup("google", "PIXEL 8", "Shiba").key)
        assertEquals("pixel_8", table.lookup("Google", "Pixel 8", "shiba").key)
    }

    @Test fun unknownModelFallsBackToGeneric() {
        val table = DeviceProfiles.parse(doc("[${model()}]"))!!
        assertEquals(DeviceProfiles.GENERIC_KEY, table.lookup("Acme", "Phone X", "x").key)
    }

    @Test fun syntheticIgnoredUnlessRequested() {
        val table = DeviceProfiles.parse(doc("[${model(key = "synth", match = """"TEST/SYNTH_A/synth_a"""", synthetic = true)}]"))!!
        assertEquals(DeviceProfiles.GENERIC_KEY, table.lookup("TEST", "SYNTH_A", "synth_a").key)
        assertEquals("synth", table.lookup("test", "synth_a", "SYNTH_A", includeSynthetic = true).key)
    }

    @Test fun allpassForUnknownPartnerIsEmpty() {
        val table = DeviceProfiles.parse(doc("[${model()}]"))!!
        val profile = table.lookup("Google", "Pixel 8", "shiba")
        assertEquals(1, DeviceProfiles.allpassFor(profile, "other").size)
        assertTrue(DeviceProfiles.allpassFor(profile, "nobody").isEmpty())
    }

    @Test fun validDocumentParses() {
        assertNotNull(DeviceProfiles.parse(doc("[${model()}]")))
    }

    @Test fun brokenJsonReturnsNull() {
        assertNull(DeviceProfiles.parse(""))
        assertNull(DeviceProfiles.parse("{"))
        assertNull(DeviceProfiles.parse(doc("[${model()}]").dropLast(1)))
        assertNull(DeviceProfiles.parse(doc("[${model()}]") + " x"))
        assertNull(DeviceProfiles.parse("""{"a":1,"a":2}"""))
    }

    @Test fun outOfRangeValuesReturnNull() {
        assertNull(DeviceProfiles.parse(doc("[${model(preamp = "1.0")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(fLow = "700.0")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(section = """{"type":"peak","f0_hz":30000.0,"q":1.0,"gain_db":0.0}""")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(section = """{"type":"peak","f0_hz":2500.0,"q":7.0,"gain_db":0.0}""")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(section = """{"type":"peak","f0_hz":2500.0,"q":1.0,"gain_db":7.0}""")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(section = """{"type":"peak","f0_hz":2500.0,"q":1.0}""")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(section = """{"type":"lowpass","f0_hz":2500.0,"q":1.0,"gain_db":0.0}""")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(section = """{"type":"highshelf","f0_hz":9000.0,"q":0.7,"gain_db":4.0}""")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(allpass = """{"p":[{"f0_hz":1800.0,"q":9.0}]}""")}]")))
        assertNull(DeviceProfiles.parse(doc("[${model(allpass = """{"p":[{"f0_hz":1,"q":0.6}]}""")}]")))
    }

    @Test fun wrongSchemaOrVersionReturnsNull() {
        assertNull(DeviceProfiles.parse(doc().replace("\"schema\":1", "\"schema\":2")))
        assertNull(DeviceProfiles.parse(doc().replace("0.1.0", "0.1")))
    }

    @Test fun missingGenericReturnsNull() {
        assertNull(DeviceProfiles.parse("""{"profiles_version":"0.1.0","schema":1,"classes":{},"models":[]}"""))
    }
}
