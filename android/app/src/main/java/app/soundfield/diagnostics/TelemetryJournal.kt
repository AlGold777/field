package app.soundfield.diagnostics

import java.io.File

/** Bounded on-disk history. Synchronized also for a final write from the crash handler. */
internal class TelemetryJournal(private val directory: File, private val maxBytes: Long = 512 * 1024) {
    private fun file(index: Int) = File(directory, "connection-$index.log")

    @Synchronized fun append(line: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create telemetry directory" }
        val bytes = (line.take(8_000) + "\n").toByteArray(Charsets.UTF_8)
        if (file(0).length() + bytes.size > maxBytes) {
            if (file(2).exists()) check(file(2).delete()) { "Cannot rotate telemetry" }
            for (i in 1 downTo 0) if (file(i).exists()) {
                check(file(i).renameTo(file(i + 1))) { "Cannot rotate telemetry" }
            }
        }
        file(0).appendBytes(bytes)
    }

    @Synchronized fun read(): String = (2 downTo 0).mapNotNull { i ->
        file(i).takeIf { it.exists() }?.readText(Charsets.UTF_8)
    }.joinToString("")
}

internal object TelemetryText {
    private val secrets = Regex("(?i)([\"']?(?:key|password|token)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)")
    fun clean(value: String): String = secrets.replace(value) { it.groupValues[1] + "<redacted>" }
        .replace("\r", "\\r").replace("\n", "\\n").take(7_000)
}
