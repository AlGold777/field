package app.soundfield.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import java.util.Base64

/** JSON Lines diagnostics. Formatting, file access, export, and sharing stay off audio/network loops. */
class Telemetry private constructor(private val app: Context) {
    private val journal = TelemetryJournal(File(app.filesDir, "telemetry"))
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(512),
        { job -> Thread(job, "sf-telemetry") }, ThreadPoolExecutor.AbortPolicy())
    private val dropped = AtomicLong()
    private val writeErrors = AtomicLong()
    private data class Limit(var at: Long, var skipped: Long = 0)
    private val limits = HashMap<String, Limit>()

    @Volatile private var contextProvider: () -> TelemetryContext = { TelemetryContext() }
    @Volatile private var roleForExport: () -> String = { "server" }
    @Volatile private var crashSnapshotProvider: (() -> List<*>)? = null
    private val appMetadata: Map<String, Any?> by lazy {
        @Suppress("DEPRECATION")
        run {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            mapOf("name" to app.packageName, "version" to info.versionName, "build" to code)
        }
    }

    init { event("PROCESS_START", "") }

    fun configure(context: () -> TelemetryContext, localRole: () -> String) {
        contextProvider = context
        roleForExport = localRole
        record("DEVICE_INFO", mapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "android" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT))
    }

    fun setCrashSnapshotProvider(provider: () -> List<*>) { crashSnapshotProvider = provider }

    private fun line(
        event: String,
        category: String,
        severity: String,
        data: Map<String, Any?>,
        op: String?,
        error: Throwable?,
        explicitThread: String = Thread.currentThread().name,
    ): String {
        val nowNs = System.nanoTime()
        val ctx = try { contextProvider() } catch (_: Exception) { TelemetryContext() }
        val err = error?.let {
            mapOf(
                "class" to it.javaClass.name,
                "message" to TelemetryText.clean(it.message.orEmpty()),
                "stack" to it.stackTrace.take(12).map { frame -> TelemetryText.clean(frame.toString()) },
            )
        }
        val result = linkedMapOf<String, Any?>(
            "v" to 1,
            "ts" to Instant.now().toString(),
            "mono" to SystemClock.elapsedRealtime(),
            "srvNs" to ctx.serverNs?.invoke(nowNs),
            "dev" to ctx.deviceId,
            "role" to ctx.role,
            "app" to appMetadata,
            "pid" to Process.myPid(),
            "thread" to explicitThread,
            "sev" to severity,
            "cat" to category,
            "ev" to event,
        )
        ctx.pair?.let { result["pair"] = it }
        ctx.session?.let { result["session"] = it }
        ctx.epoch?.let { result["epoch"] = it }
        ctx.track?.let { result["track"] = it }
        op?.let { result["op"] = it }
        result["data"] = data.mapValues { (_, value) ->
            if (value is String) TelemetryText.clean(value, TelemetryJournal.MAX_LINE_BYTES) else value
        }
        if (err != null) result["err"] = err
        return TelemetryJson.encode(result)
    }

    private fun severity(event: String, error: Throwable?): String = when {
        error != null || event.contains("FAILED") || event.contains("ERROR") || event.contains("UNCAUGHT") -> "error"
        event.contains("REJECT") || event.contains("ABORT") || event.contains("CANCEL") -> "warn"
        event.startsWith("DEBUG_") -> "debug"
        else -> "info"
    }

    private fun classifyCategory(event: String): String = when {
        event.startsWith("INCIDENT_") || event == "BLACKBOX" -> "incident"
        event.startsWith("AUDIO_DECODE") -> "decode"
        event.startsWith("FILE_TRANSFER") -> "transfer"
        event.startsWith("UDP") || event.startsWith("TCP") || event.startsWith("WIFI") -> "udp"
        event.startsWith("USER_") || event.startsWith("CMD_") -> "user"
        event.startsWith("AUDIO") -> "audio"
        event.startsWith("PLAY") || event.startsWith("STATE") -> "play"
        event.startsWith("EXPORT") || event.startsWith("TELEMETRY") || event.startsWith("HEALTH") -> "telemetry"
        event.startsWith("PHASE") || event.startsWith("PAIR") || event.startsWith("RECONNECT") || event.startsWith("INVITE") -> "pair"
        event.contains("LINK") -> "link"
        event.contains("RESOURCE") || event.contains("HEAP") || event.contains("CPU") -> "res"
        else -> "app"
    }

    private fun parseDetail(detail: String): Map<String, Any?> {
        val cleaned = TelemetryText.clean(detail).trim()
        if (cleaned.isEmpty()) return emptyMap()
        val matches = Regex("([A-Za-z][A-Za-z0-9_.-]*)=([^\\s]+)").findAll(cleaned).toList()
        if (matches.isEmpty()) return mapOf("message" to cleaned)
        val values = linkedMapOf<String, Any?>()
        matches.forEach { match ->
            val raw = match.groupValues[2].trim('"', '\'')
            val value: Any? = when {
                raw.equals("true", true) -> true
                raw.equals("false", true) -> false
                raw.toLongOrNull() != null -> raw.toLong()
                raw.toDoubleOrNull() != null -> raw.toDouble()
                else -> raw
            }
            values[match.groupValues[1]] = value
        }
        return values
    }

    fun event(event: String, detail: String = "", error: Throwable? = null) =
        record(event, parseDetail(detail), error = error)

    fun record(
        event: String,
        data: Map<String, Any?> = emptyMap(),
        category: String = classifyCategory(event),
        op: String? = null,
        error: Throwable? = null,
        severityOverride: String? = null,
    ) {
        val entry = line(event, category, severityOverride ?: severity(event, error), data, op, error)
        enqueue(entry)
    }

    /** Compresses metric snapshots away from the engine, audio, and network threads. */
    fun recordCompressed(
        event: String,
        data: Map<String, Any?>,
        field: String,
        op: String? = null,
    ) {
        try {
            worker.execute {
                try {
                    val raw = TelemetryJson.encode(data[field]).toByteArray(Charsets.UTF_8)
                    val bytes = ByteArrayOutputStream()
                    GZIPOutputStream(bytes).use { it.write(raw) }
                    val compressed = data - field + mapOf(
                        "encoding" to "gzip+base64",
                        "payload" to Base64.getEncoder().encodeToString(bytes.toByteArray()),
                        "samples" to ((data[field] as? List<*>)?.size ?: 1),
                    )
                    journal.append(line(event, classifyCategory(event), severity(event, null), compressed, op, null))
                } catch (e: Exception) {
                    writeErrors.incrementAndGet()
                    Log.e("SoundField.Telemetry", "Compressed telemetry write failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun enqueue(entry: String) {
        try {
            worker.execute {
                try { journal.append(entry) }
                catch (e: Exception) { writeErrors.incrementAndGet(); Log.e("SoundField.Telemetry", "Journal write failed", e) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    /** Repetitive UDP/network faults keep a count, with one detailed entry per 5 seconds. */
    fun limited(event: String, detail: String = "", error: Throwable? = null, op: String? = null) {
        val now = SystemClock.elapsedRealtime()
        val skipped = synchronized(limits) {
            val previous = limits[event]
            if (previous != null && now - previous.at < 5_000) { previous.skipped++; return }
            val count = previous?.skipped ?: 0
            limits[event] = Limit(now)
            count
        }
        record(event, parseDetail(detail) + (if (skipped > 0) mapOf("suppressedSinceLast" to skipped) else emptyMap()), op = op, error = error)
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                journal.append(line("UNCAUGHT", "app", "error", emptyMap(), null, error, thread.name))
                val samples = crashSnapshotProvider?.invoke().orEmpty()
                if (samples.isNotEmpty()) {
                    val compressed = ByteArrayOutputStream().also { bytes ->
                        GZIPOutputStream(bytes).use { it.write(TelemetryJson.encode(samples).toByteArray(Charsets.UTF_8)) }
                    }.toByteArray()
                    val blackbox = mapOf("id" to "uncaught", "encoding" to "gzip+base64",
                        "payload" to Base64.getEncoder().encodeToString(compressed), "samples" to samples.size)
                    journal.append(line("BLACKBOX", "incident", "error", blackbox, "uncaught", null, thread.name))
                }
            } catch (_: Exception) { }
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    /** Executes the read after queued events so the exported file includes all preceding heartbeats. */
    fun readJournalSnapshot(complete: (Result<String>) -> Unit) {
        try {
            worker.execute { complete(runCatching { journal.read().let(::jsonLinesForExport) }) }
        } catch (e: java.util.concurrent.RejectedExecutionException) { complete(Result.failure(e)) }
    }

    fun writeTransferChunk(file: File, offset: Long, bytes: ByteArray, complete: (Result<Unit>) -> Unit) {
        try {
            worker.execute {
                complete(runCatching {
                    file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot create transfer directory" } }
                    java.io.RandomAccessFile(file, "rw").use { output ->
                        check(output.length() == offset) { "Telemetry transfer offset mismatch" }
                        output.seek(offset)
                        output.write(bytes)
                    }
                })
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) { complete(Result.failure(e)) }
    }

    fun readTransferFile(file: File, complete: (Result<String>) -> Unit) {
        try {
            worker.execute { complete(runCatching { file.readText(Charsets.UTF_8) }) }
        } catch (e: java.util.concurrent.RejectedExecutionException) { complete(Result.failure(e)) }
    }

    fun export(
        snapshot: String,
        localRole: String,
        remoteJournal: String?,
        remoteSnapshot: String?,
        remoteMissingReason: String?,
        clockOffsetMs: Double?,
        op: String?,
        complete: (Result<String>) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        record("EXPORT_START", op = op)
        try {
            worker.execute {
                val result = runCatching {
                    val local = jsonLinesForExport(journal.read())
                    val server = if (localRole == "server") local else remoteJournal.orEmpty()
                    val point = if (localRole == "point") local else remoteJournal.orEmpty()
                    val summary = TelemetrySummary.create(server, point, remoteMissingReason, clockOffsetMs,
                        dropped.get(), writeErrors.get())
                    val serverState = if (localRole == "server") snapshot else remoteSnapshot.orEmpty().ifBlank { "{}" }
                    val pointState = if (localRole == "point") snapshot else remoteSnapshot.orEmpty().ifBlank { "{}" }
                    val report = buildString {
                        appendLine("=== summary.txt ===")
                        append(summary)
                        appendLine("=== state-server.json ===")
                        appendLine(serverState)
                        appendLine("=== state-point.json ===")
                        appendLine(pointState)
                        appendLine("=== server.jsonl ===")
                        append(server)
                        appendLine("=== point.jsonl ===")
                        append(point)
                    }
                    val saved = TelemetryDownloads.save(app, report)
                    runCatching { TelemetryDownloads.share(app, saved) }
                    saved.displayPath
                }
                result.fold(
                    { path ->
                        record("EXPORT_SAVED", mapOf("path" to path), op = op)
                        record("EXPORT_END", mapOf("result" to "ok", "durMs" to SystemClock.elapsedRealtime() - startedAt), op = op)
                    },
                    { error ->
                        record("EXPORT_FAILED", op = op, error = error)
                        record("EXPORT_END", mapOf("result" to "error", "durMs" to SystemClock.elapsedRealtime() - startedAt), op = op, error = error)
                    },
                )
                complete(result)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            record("EXPORT_END", mapOf("result" to "error", "durMs" to SystemClock.elapsedRealtime() - startedAt), op = op, error = e)
            complete(Result.failure(e))
        }
    }

    private fun jsonLinesForExport(text: String): String = buildString {
        text.lineSequence().filter { it.isNotBlank() }.forEach { oldLine ->
            val line = oldLine.trimEnd()
            if (line.trimStart().startsWith("{")) appendLine(line)
            else appendLine(TelemetryJson.encode(mapOf(
                "v" to 1, "ts" to null, "mono" to null, "srvNs" to null, "dev" to "", "role" to "",
                "app" to emptyMap<String, Any?>(), "pid" to 0, "thread" to "legacy-export", "sev" to "info",
                "cat" to "telemetry", "ev" to "LEGACY_TEXT", "data" to mapOf("line" to TelemetryText.clean(line)),
            )))
        }
    }

    companion object {
        @Volatile private var instance: Telemetry? = null
        fun get(context: Context): Telemetry = instance ?: synchronized(this) {
            instance ?: Telemetry(context.applicationContext).also { instance = it }
        }
    }
}

data class TelemetryContext(
    val deviceId: String = "",
    val role: String = "",
    val pair: String? = null,
    val session: String? = null,
    val epoch: Int? = null,
    val track: String? = null,
    val serverNs: ((Long) -> Long?)? = null,
)
