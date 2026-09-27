package app.soundfield.diagnostics

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/** Small JSON encoder kept independent of Android so the journal format can be tested on the JVM. */
internal object TelemetryJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> if (value.isFinite()) value.toString() else "null"
        is Double -> if (value.isFinite()) value.toString() else "null"
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
            quote(it.key.toString()) + ":" + encode(it.value)
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        else -> quote(value.toString())
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

internal object TelemetryIdentifiers {
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
}

internal data class IncidentRule(val name: String, val severity: String, val thresholdMs: Long)

internal data class IncidentUpdate(
    val kind: String,
    val rule: IncidentRule,
    val sinceMs: Long,
    val atMs: Long,
    val durationMs: Long,
    val values: Map<String, Any?>,
)

internal data class ActiveIncident(
    val rule: IncidentRule,
    val sinceMs: Long,
    val durationMs: Long,
    val values: Map<String, Any?>,
)

/** A heartbeat-driven rules engine. Calling it once per second never creates duplicate starts. */
internal class IncidentRuleEngine(private val rules: List<IncidentRule>) {
    private data class State(
        val sinceMs: Long,
        var started: Boolean,
        var lastOngoingMs: Long,
        var values: Map<String, Any?>,
    )

    private val states = LinkedHashMap<String, State>()

    fun check(nowMs: Long, violations: Map<String, Map<String, Any?>>): List<IncidentUpdate> {
        val updates = mutableListOf<IncidentUpdate>()
        rules.forEach { rule ->
            val current = violations[rule.name]
            val state = states[rule.name]
            if (current == null) {
                if (state?.started == true) {
                    updates += IncidentUpdate("INCIDENT_END", rule, state.sinceMs, nowMs,
                        (nowMs - state.sinceMs).coerceAtLeast(0), state.values)
                }
                states.remove(rule.name)
                return@forEach
            }

            if (state == null) {
                states[rule.name] = State(nowMs, false, nowMs, current.toMap())
                return@forEach
            }
            state.values = current.toMap()
            val duration = (nowMs - state.sinceMs).coerceAtLeast(0)
            if (!state.started && duration >= rule.thresholdMs) {
                state.started = true
                state.lastOngoingMs = nowMs
                updates += IncidentUpdate("INCIDENT_START", rule, state.sinceMs, nowMs, duration, state.values)
            } else if (state.started && nowMs - state.lastOngoingMs >= 10_000) {
                state.lastOngoingMs = nowMs
                updates += IncidentUpdate("INCIDENT_ONGOING", rule, state.sinceMs, nowMs, duration, state.values)
            }
        }
        return updates
    }

    fun active(nowMs: Long): List<ActiveIncident> = rules.mapNotNull { rule ->
        states[rule.name]?.takeIf { it.started }?.let {
            ActiveIncident(rule, it.sinceMs, (nowMs - it.sinceMs).coerceAtLeast(0), it.values)
        }
    }
}

internal object AndroidTelemetryRules {
    val all = listOf(
        IncidentRule("POINT_NO_PACKETS", "error", 2_000),
        IncidentRule("POINT_SILENT_OUTPUT", "error", 2_000),
        IncidentRule("SERVER_SILENT_OUTPUT", "error", 2_000),
        IncidentRule("OUTPUT_STALLED", "error", 2_000),
        IncidentRule("OUTPUT_MUTED", "warn", 1_000),
        IncidentRule("SYNC_ERROR", "warn", 2_000),
        IncidentRule("DECODE_LEAD_LOW", "warn", 1_000),
        IncidentRule("DECODE_STUCK", "error", 10_000),
        IncidentRule("TRANSFER_STUCK", "error", 10_000),
        IncidentRule("STATE_MISMATCH", "warn", 3_000),
        IncidentRule("LOOP_LATENCY", "warn", 5_000),
        IncidentRule("LINK_SILENT", "error", 0),
        IncidentRule("PEER_UNREACHABLE", "error", 0),
    )
}

/** Fixed-size FIFO used by the heartbeat; snapshots and draining happen off the audio path. */
internal class SnapshotRing<T>(private val capacity: Int) {
    private val values = ArrayDeque<T>(capacity)

    @Synchronized fun add(value: T) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(value)
    }

    @Synchronized fun snapshot(): List<T> = values.toList()

    @Synchronized fun drain(): List<T> = values.toList().also { values.clear() }
}

/** One-pass, fixed-memory interval histogram; safe to update from tickLoop and drain in heartbeat. */
internal class AtomicLatencyWindow(private val maxBucketMs: Int = 1_000) {
    private val buckets = AtomicLongArray(maxBucketMs + 1)
    private val count = AtomicLong()

    fun record(nanos: Long) {
        if (nanos < 0) return
        val ms = ((nanos + 999_999L) / 1_000_000L).toInt().coerceAtMost(maxBucketMs)
        buckets.incrementAndGet(ms)
        count.incrementAndGet()
    }

    fun takeP95Ms(): Double {
        val n = count.getAndSet(0)
        if (n == 0L) return 0.0
        val target = kotlin.math.ceil(n * 0.95).toLong()
        var seen = 0L
        for (i in 0..maxBucketMs) {
            seen += buckets.getAndSet(i, 0)
            if (seen >= target) return i.toDouble()
        }
        return maxBucketMs.toDouble()
    }
}

internal data class SignalWindow(val frames: Long, val rms: Double, val peak: Double) {
    val rmsDbfs: Double get() = dbfs(rms)
    val peakDbfs: Double get() = dbfs(peak)

    companion object {
        fun dbfs(value: Double): Double = 20.0 * log10(value.coerceAtLeast(1e-6))
    }
}

/** Audio/render threads only perform arithmetic and atomic additions; heartbeat consumes a one-second window. */
internal class AtomicSignalMeter {
    private val sumSquaresMicros = AtomicLong()
    private val frames = AtomicLong()
    private val peakBits = AtomicInteger(java.lang.Float.floatToRawIntBits(0f))

    fun add(samples: FloatArray, offset: Int, count: Int) {
        if (count <= 0) return
        var sumSquares = 0.0
        var peak = 0f
        for (i in offset until offset + count) {
            val value = samples[i]
            sumSquares += value * value
            peak = maxOf(peak, kotlin.math.abs(value))
        }
        sumSquaresMicros.addAndGet((sumSquares * 1_000_000.0).toLong())
        frames.addAndGet(count.toLong())
        val old = peakBits.get()
        val oldValue = java.lang.Float.intBitsToFloat(old)
        if (peak > oldValue) peakBits.compareAndSet(old, java.lang.Float.floatToRawIntBits(peak))
    }

    fun addPcm16(samples: ShortArray, count: Int) {
        if (count <= 0) return
        var sumSquares = 0.0
        var peak = 0f
        for (i in 0 until count) {
            val value = samples[i] / 32768.0f
            sumSquares += value * value
            peak = maxOf(peak, kotlin.math.abs(value))
        }
        sumSquaresMicros.addAndGet((sumSquares * 1_000_000.0).toLong())
        frames.addAndGet(count.toLong())
        val old = peakBits.get()
        val oldValue = java.lang.Float.intBitsToFloat(old)
        if (peak > oldValue) peakBits.compareAndSet(old, java.lang.Float.floatToRawIntBits(peak))
    }

    fun take(): SignalWindow {
        val n = frames.getAndSet(0)
        val sum = sumSquaresMicros.getAndSet(0).coerceAtLeast(0)
        val peak = java.lang.Float.intBitsToFloat(peakBits.getAndSet(java.lang.Float.floatToRawIntBits(0f))).toDouble()
        return SignalWindow(n, if (n == 0L) 0.0 else sqrt(sum / 1_000_000.0 / n), peak)
    }
}

internal object TelemetrySummary {
    private val eventPattern = Regex("\\\"ev\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val severityPattern = Regex("\\\"sev\\\":\\\"(error|warn)\\\"")
    private val timestampPattern = Regex("\\\"ts\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val rulePattern = Regex("\\\"rule\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val markPattern = Regex("\\\"markId\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val incidentIdPattern = Regex("\\\"incidentId\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val durationPattern = Regex("\\\"durMs\\\":(-?\\d+)")
    private val rolePattern = Regex("\\\"role\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val devicePattern = Regex("\\\"dev\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val versionPattern = Regex("\\\"version\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val modelPattern = Regex("\\\"model\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    private val androidPattern = Regex("\\\"android\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")

    private fun event(line: String): String? = eventPattern.find(line)?.groupValues?.get(1)
    private fun incidentId(line: String): String? = incidentIdPattern.find(line)?.groupValues?.get(1)
    private fun duration(line: String): Long? = durationPattern.find(line)?.groupValues?.get(1)?.toLongOrNull()

    private fun phoneDescription(label: String, journal: String): String {
        val records = journal.lineSequence().filter { it.isNotBlank() }.toList()
        if (records.isEmpty()) return "$label=missing"
        val deviceRecord = records.firstOrNull { event(it) == "DEVICE_INFO" }.orEmpty()
        // Engine starts with Role.SERVER before pairing is established. Use the latest identity
        // record so a phone that becomes Point is not mislabeled from its startup events.
        val identityRecord = records.lastOrNull {
            rolePattern.find(it)?.groupValues?.get(1)?.isNotBlank() == true &&
                devicePattern.find(it)?.groupValues?.get(1)?.isNotBlank() == true
        } ?: records.first()
        val fields = listOfNotNull(
            versionPattern.find(identityRecord)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { "v$it" }
                ?: versionPattern.find(records.first())?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { "v$it" },
            modelPattern.find(deviceRecord)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            androidPattern.find(deviceRecord)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { "Android $it" },
            devicePattern.find(identityRecord)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { "id $it" },
        )
        val actualRole = rolePattern.find(identityRecord)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: label.lowercase()
        return "$actualRole (${fields.joinToString(", ").ifEmpty { "device details unavailable" }})"
    }

    private fun incidentSummary(lines: List<String>): List<String> = lines
        .filter { event(it) == "INCIDENT_START" }
        .map { start ->
            val id = incidentId(start)
            val rule = rulePattern.find(start)?.groupValues?.get(1) ?: "UNKNOWN"
            val related = lines.filter { it !== start && id != null && incidentId(it) == id }
            val ended = related.lastOrNull { event(it) == "INCIDENT_END" }
            val latest = ended ?: related.lastOrNull { event(it) == "INCIDENT_ONGOING" }
            val durMs = latest?.let(::duration) ?: duration(start)
            val state = if (ended != null) "ended" else "ongoing"
            "- $rule: ${timestampPattern.find(start)?.groupValues?.get(1) ?: "unknown"}; " +
                "${durMs?.let { String.format(Locale.US, if (ended == null) "≥ %.1f s" else "%.1f s", it / 1000.0) } ?: "duration unavailable"}; $state"
        }

    fun create(
        server: String,
        point: String,
        peerMissingReason: String?,
        clockOffsetMs: Double?,
        dropped: Long,
        writeErrors: Long,
    ): String {
        val lines = (server.lineSequence() + point.lineSequence()).filter { it.isNotBlank() }.toList()
        val timestamps = lines.mapNotNull { timestampPattern.find(it)?.groupValues?.get(1) }
        val incidents = incidentSummary(lines)
        val marks = lines.filter { event(it) == "USER_MARK" }
            .map { "- ${markPattern.find(it)?.groupValues?.get(1) ?: "unknown"}: ${timestampPattern.find(it)?.groupValues?.get(1) ?: "unknown"}" }
        val errors = lines.mapNotNull { line ->
            if (severityPattern.find(line)?.groupValues?.get(1) != "error") null
            else event(line)
        }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10)
        val errorsText = errors.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- ${it.key}: ${it.value}" } ?: "- нет"
        val counters = listOf("AUDIO_DECODE_FAILED", "FILE_TRANSFER_COMPLETE", "AUDIO_SESSION_RENEW", "UDP_INVALID")
            .mapNotNull { name -> lines.count { event(it) == name }.takeIf { it > 0 }?.let { "$name=$it" } }
        return buildString {
            appendLine("SoundField telemetry summary")
            appendLine("Period: ${timestamps.minOrNull() ?: "unknown"} — ${timestamps.maxOrNull() ?: "unknown"}")
            appendLine("Phones: ${phoneDescription("Server", server)}; ${phoneDescription("Point", point)}")
            if (peerMissingReason != null) appendLine("Other phone journal missing: ${TelemetryText.clean(peerMissingReason)}")
            appendLine("Estimated Server clock offset at export: ${clockOffsetMs?.let { String.format(Locale.US, "%.3f ms", it) } ?: "not available"}")
            appendLine("Incidents:")
            appendLine(incidents.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "- нет")
            appendLine("User marks:")
            appendLine(marks.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "- нет")
            appendLine("Top errors:")
            appendLine(errorsText)
            appendLine("Key counters: ${counters.ifEmpty { listOf("no notable events") }.joinToString(", ")}")
            appendLine("Telemetry losses: dropped queue events=$dropped; disk write errors=$writeErrors")
        }
    }
}
