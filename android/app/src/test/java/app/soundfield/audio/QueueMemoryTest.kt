package app.soundfield.audio

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QueueMemoryTest {
    private lateinit var dir: File
    private var now = 1_000_000L
    private val clock = { now }

    @Before fun setUp() { dir = Files.createTempDirectory("qm").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun snap(pos: Long = 5000, index: Int = 1) = QueueSnapshot(
        listOf(SavedTrack("content://a/1", "Один", "Арт"), SavedTrack("content://a/2?x=1%20y", "Два\tс табом\nи строкой", ""),
            SavedTrack("file:///c.mp3", "100% тест", "X")), index, pos, true, 42L)

    @Test fun roundTrip() {
        val f = File(dir, "q.txt"); val s = QueueStateStore(f, clock = clock)
        val a = snap(); assertTrue(s.save(a))
        assertEquals(a, QueueStateStore(f).load())
    }

    @Test fun emptyQueueRoundTrip() {
        val f = File(dir, "q.txt"); val a = QueueSnapshot(emptyList(), -1, 0, false, 1)
        QueueStateStore(f).save(a); assertEquals(a, QueueStateStore(f).load())
    }

    @Test fun missingCorruptAndForeignAreNull() {
        val f = File(dir, "q.txt"); val s = QueueStateStore(f)
        assertNull(s.load())
        f.writeText("мусор"); assertNull(s.load())
        f.writeText("{\"tracks\":[]}"); assertNull(s.load())
        s.save(snap()); val ok = f.readText()
        f.writeText(ok.substringBeforeLast("end")); assertNull(s.load())            // обрезан
        f.writeText(ok.replace("count=3", "count=2")); assertNull(s.load())
        f.writeText(ok.replace("index=1", "index=9")); assertNull(s.load())
        f.writeText(ok.replace("pos=5000", "pos=abc")); assertNull(s.load())
    }

    @Test fun otherVersionIsNull() {
        val f = File(dir, "q.txt"); val s = QueueStateStore(f)
        s.save(snap()); f.writeText(f.readText().replace("v${QueueStateStore.FORMAT_VERSION}", "v99"))
        assertNull(s.load())
        s.save(snap()); assertNotNull(s.load())
        assertTrue(f.readLines().first().endsWith("v${QueueStateStore.FORMAT_VERSION}"))
    }

    @Test fun debounceWritesOnceAndFlushWritesNow() {
        val f = File(dir, "q.txt"); val s = QueueStateStore(f, 5000, clock)
        s.saveDebounced(snap(1000)); assertEquals(1000, s.load()!!.positionMs)       // первая — сразу
        now += 1000; s.saveDebounced(snap(2000))
        now += 1000; s.saveDebounced(snap(3000))
        assertEquals(1000, s.load()!!.positionMs)                                     // ещё не писали
        s.flush(); assertEquals(3000, s.load()!!.positionMs)
        s.flush(); assertEquals(3000, s.load()!!.positionMs)
        now += 6000; s.saveDebounced(snap(9000)); assertEquals(9000, s.load()!!.positionMs)
    }

    @Test fun saveCancelsPendingAndClearRemoves() {
        val f = File(dir, "q.txt"); val s = QueueStateStore(f, 5000, clock)
        s.saveDebounced(snap(1000)); s.saveDebounced(snap(2000)); s.save(snap(7000)); s.flush()
        assertEquals(7000, s.load()!!.positionMs)
        s.clear(); assertNull(s.load()); assertFalse(f.exists())
    }

    // ---- ResumePositions ----
    private val min = 60_000L
    private val long = 120 * min

    private fun rp(max: Int = 500, f: File = File(dir, "r.txt")) = ResumePositions(f, max, 5000, clock)

    @Test fun shortTrackNotRemembered() {
        val r = rp(); r.update("k", 60_000, 5 * min); assertNull(r.positionFor("k", 5 * min)); assertEquals(0, r.count())
    }

    @Test fun startIgnoredAndDoesNotEraseSaved() {
        val r = rp(); r.update("k", 5000, long); assertNull(r.positionFor("k", long))
        r.update("k", 30 * min, long); r.update("k", 0, long)
        assertEquals(30 * min - 3000, r.positionFor("k", long))
    }

    @Test fun rewindOnResume() {
        val r = rp(); r.update("k", 40 * min, long)
        assertEquals(40 * min - ResumePositions.REWIND_ON_RESUME_MS, r.positionFor("k", long))
        r.update("k", 12_000, long); assertEquals(9_000L, r.positionFor("k", long))
        r.update("k", 10_000, long); assertEquals(7_000L, r.positionFor("k", long))
    }

    @Test fun nearEndErases() {
        val r = rp(); r.update("k", 40 * min, long); r.update("k", long - 20_000, long)
        assertNull(r.positionFor("k", long)); assertEquals(0, r.count())
    }

    @Test fun nearEndByFraction() {
        val r = rp(); r.update("k", long - 70_000, long); assertNull(r.positionFor("k", long))   // 70 с < 1% от 2 ч (72 с)
        r.update("k", long - 80_000, long); assertNotNull(r.positionFor("k", long))
    }

    @Test fun clearRemoves() {
        val r = rp(); r.update("k", 40 * min, long); r.clear("k"); assertNull(r.positionFor("k", long))
    }

    @Test fun evictsOldest() {
        val r = rp(max = 3)
        for (i in 1..4) { now += 10; r.update("k$i", 30 * min, long) }
        assertEquals(3, r.count()); assertNull(r.positionFor("k1", long)); assertNotNull(r.positionFor("k2", long))
        now += 10; r.update("k2", 31 * min, long); now += 10; r.update("k5", 30 * min, long)   // k2 свежий, уходит k3
        assertNull(r.positionFor("k3", long)); assertNotNull(r.positionFor("k2", long))
    }

    @Test fun persistsBetweenInstancesAndDebounces() {
        val f = File(dir, "r.txt"); val r = rp(f = f)
        r.update("a", 30 * min, long)                       // первая запись — сразу
        now += 1000; r.update("a", 31 * min, long)           // отложена
        assertEquals(30 * min - 3000, rp(f = f).positionFor("a", long))
        r.flush(); assertEquals(31 * min - 3000, rp(f = f).positionFor("a", long))
    }

    @Test fun corruptFileIsEmpty() {
        val f = File(dir, "r.txt"); f.writeText("чушь\nещё"); val r = rp(f = f)
        assertEquals(0, r.count()); r.update("a", 30 * min, long); r.flush()
        assertNotNull(rp(f = f).positionFor("a", long))
    }
}
