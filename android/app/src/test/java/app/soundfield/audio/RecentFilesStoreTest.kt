package app.soundfield.audio

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecentFilesStoreTest {
    private lateinit var dir: File
    private var now = 1_000L

    @Before fun setUp() { dir = Files.createTempDirectory("rf").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun store(limit: Int = 40) = RecentFilesStore(File(dir, "r.txt"), limit) { now }
    private fun f(n: Int, title: String = "T$n") = RecentFile("content://a/$n", title, "A", 0)

    @Test fun newestFirstAndSurvivesRestart() {
        val s = store()
        s.touch(f(1)); now += 10; s.touch(f(2)); now += 10; s.touch(f(3))
        assertEquals(listOf("content://a/3", "content://a/2", "content://a/1"), s.load().map { it.uri })
        assertEquals(s.load(), store().load())
    }

    @Test fun reopenMovesToFrontWithoutDuplicate() {
        val s = store()
        s.touch(f(1)); now += 10; s.touch(f(2)); now += 10; s.touch(f(1))
        assertEquals(listOf("content://a/1", "content://a/2"), s.load().map { it.uri })
    }

    @Test fun batchKeepsListOrder() {
        val s = store()
        s.touch(listOf(f(1), f(2), f(3)))
        assertEquals(listOf(1, 2, 3).map { "content://a/$it" }, s.load().map { it.uri })
    }

    @Test fun limitDropsOldest() {
        val s = store(limit = 2)
        for (i in 1..4) { s.touch(f(i)); now += 10 }
        assertEquals(listOf("content://a/4", "content://a/3"), s.load().map { it.uri })
    }

    @Test fun blankTitleKeepsKnownOne() {
        val s = store()
        s.touch(f(1, "Песня")); now += 10; s.touch(f(1, ""))
        assertEquals("Песня", s.load().single().title)
    }

    @Test fun removeAndSpecialCharacters() {
        val s = store()
        s.touch(RecentFile("content://a/1?x=1%20y", "Два\tс табом\nи 100%", "", 0)); now += 10; s.touch(f(2))
        assertEquals("Два\tс табом\nи 100%", store().load().last().title)
        s.remove(listOf("content://a/2"))
        assertEquals(1, store().load().size)
    }

    @Test fun corruptOrTruncatedIsEmpty() {
        val file = File(dir, "r.txt")
        file.writeText("мусор"); assertTrue(store().load().isEmpty())
        val s = store(); s.touch(f(1)); val ok = file.readText()
        file.writeText(ok.substringBeforeLast("end")); assertTrue(store().load().isEmpty())
    }
}
