package app.soundfield.diagnostics

import java.io.File

/** Bounded on-disk history. Synchronized also for a final write from the crash handler. */
internal class TelemetryJournal(private val directory: File, private val maxBytes: Long = 2_621_440L) {
    private fun file(index: Int) = File(directory, "connection-$index.log")

    @Synchronized fun append(line: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create telemetry directory" }
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_LINE_BYTES) { "Telemetry event exceeds the line limit" }
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

    companion object { const val MAX_LINE_BYTES = 256 * 1024 }
}

internal object TelemetryText {
    private val secrets = Regex("(?i)([\"']?(?:key|password|token)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)")
    private const val AUDIO_EXT = "aac|aif|aiff|alac|amr|flac|m4a|m4b|mp3|oga|ogg|opus|wav|wave|weba|wma"
    private val audioUri = Regex("""(?i)(?:content|file)://[^\s,;\"'<>]+""")
    private val quotedAudioName = Regex("""(?i)([«\"])([^»\"\n]{1,240}\.(?:$AUDIO_EXT))([»\"])""")
    private val audioName = Regex("""(?i)(?<![\p{L}\p{N}_])[^\s,;<>\"'«»/\\:]+\.(?:$AUDIO_EXT)(?![\p{L}\p{N}_])""")
    private val audioErrorName = Regex("""(?i)(Не аудиофайл:\s*)(.{1,240}?\.(?:$AUDIO_EXT))(?=$|[ ,;])""")

    fun clean(value: String, maxLength: Int = 7_000): String {
        var result = secrets.replace(value) { it.groupValues[1] + "<redacted>" }
        result = audioUri.replace(result) { uri ->
            val cleanUri = uri.value.substringBefore('?').substringBefore('#')
            val extension = cleanUri.substringAfterLast('.', "").takeWhile(Char::isLetter).take(8).lowercase()
            "<file:" + TelemetryIdentifiers.hash(uri.value) + if (extension.isNotBlank()) ".$extension>" else ">"
        }
        result = quotedAudioName.replace(result) { match ->
            val name = match.groupValues[2]
            val extension = name.substringAfterLast('.', "").lowercase()
            match.groupValues[1] + "<file:" + TelemetryIdentifiers.hash(name) + ".$extension>" + match.groupValues[3]
        }
        result = audioErrorName.replace(result) { match ->
            val name = match.groupValues[2]
            val extension = name.substringAfterLast('.', "").lowercase()
            match.groupValues[1] + "<file:" + TelemetryIdentifiers.hash(name) + ".$extension>"
        }
        result = audioName.replace(result) { fileName ->
            val extension = fileName.value.substringAfterLast('.', "").lowercase()
            "<file:" + TelemetryIdentifiers.hash(fileName.value) + ".$extension>"
        }
        return result.replace("\r", "\\r").replace("\n", "\\n").take(maxLength)
    }
}
