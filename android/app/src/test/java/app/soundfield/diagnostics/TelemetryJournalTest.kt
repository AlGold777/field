package app.soundfield.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TelemetryJournalTest {
    @Test fun historySurvivesReopeningAndKeepsUtf8() {
        val directory = Files.createTempDirectory("sf-telemetry").toFile()
        try {
            TelemetryJournal(directory).append("Сеть потеряна")
            val reopened = TelemetryJournal(directory)
            reopened.append("Соединение восстановлено")
            assertEquals("Сеть потеряна\nСоединение восстановлено\n", reopened.read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rotationBoundsHistoryWithoutSplittingOrReorderingEvents() {
        val directory = Files.createTempDirectory("sf-telemetry").toFile()
        try {
            val journal = TelemetryJournal(directory, maxBytes = 30)
            repeat(20) { journal.append("event-%02d".format(it)) }
            val lines = journal.read().trim().lines()
            assertEquals((12..19).map { "event-%02d".format(it) }, lines)
            assertEquals(3, directory.listFiles()!!.size)
            assertTrue(directory.listFiles()!!.all { it.length() <= 30 })
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidStorageFailsExplicitly() {
        val file = Files.createTempFile("sf-telemetry", ".tmp").toFile()
        try {
            try { TelemetryJournal(file).append("event"); fail("Must report storage failure") }
            catch (_: IllegalStateException) { }
        } finally { file.delete() }
    }

    @Test fun errorTextHidesCredentialsAndCannotInjectLogLines() {
        val cleaned = TelemetryText.clean("bad JSON {\"key\":\"pair-secret\"}, token=abc password='secret words'\nforged event\r")
        assertFalse(cleaned.contains("pair-secret"))
        assertFalse(cleaned.contains("abc"))
        assertFalse(cleaned.contains("secret words"))
        assertFalse(cleaned.contains('\n'))
        assertFalse(cleaned.contains('\r'))
        assertTrue(cleaned.contains("<redacted>"))
        assertEquals(7_000, TelemetryText.clean("x".repeat(10_000)).length)
    }
}
