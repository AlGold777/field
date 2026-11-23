package app.soundfield.audio

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TrackAnalysisStoreTest {
    private fun dir() = Files.createTempDirectory("tas").toFile()
    private fun id(n: Int) = TrackIdentity("content://t/$n", 1000L + n, 5L)

    @Test fun keyIsStableAndDependsOnFields() {
        assertEquals(id(1).key, TrackIdentity("content://t/1", 1001L, 5L).key)
        assertEquals(32, id(1).key.length)
        assertNotEquals(id(1).key, id(1).copy(lastModified = 6L).key)
        assertNotEquals(id(1).key, id(1).copy(size = 1L).key)
    }

    @Test fun roundTrip() {
        val s = TrackAnalysisStore(dir())
        assertNull(s.get(id(1)))
        val r = TrackAnalysisResult(-14.25, -0.5, 123456789012L, writtenAtMs = 42L)
        s.put(id(1), r)
        assertEquals(r, s.get(id(1)))
        s.put(id(2), TrackAnalysisResult(Double.NaN, -200.0, -1L))
        assertTrue(s.get(id(2))!!.lufs.isNaN())
        assertEquals(-1L, s.get(id(2))!!.frames)
    }

    @Test fun otherAnalyzerVersionIsAbsent() {
        val s = TrackAnalysisStore(dir())
        s.put(id(1), TrackAnalysisResult(-10.0, -1.0, 10, analyzerVersion = ANALYZER_VERSION + 1))
        assertNull(s.get(id(1)))
    }

    @Test fun corruptFileIsNull() {
        val d = dir(); val s = TrackAnalysisStore(d)
        s.put(id(1), TrackAnalysisResult(-10.0, -1.0, 10))
        File(d, id(1).key + ".txt").writeText("lufs=abc\n\u0000garbage")
        assertNull(s.get(id(1)))
        File(d, id(1).key + ".txt").writeText("")
        assertNull(s.get(id(1)))
    }

    @Test fun evictsOldest() {
        val s = TrackAnalysisStore(dir(), maxEntries = 3)
        for (n in 1..5) s.put(id(n), TrackAnalysisResult(-10.0, -1.0, 10, writtenAtMs = 1_000_000L + n * 10_000L))
        assertEquals(3, s.count())
        assertNull(s.get(id(1))); assertNull(s.get(id(2)))
        assertNotNull(s.get(id(3))); assertNotNull(s.get(id(5)))
    }
}
