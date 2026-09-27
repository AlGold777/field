package app.soundfield.diagnostics

import org.junit.Assert.*
import org.junit.Test

class TelemetryCoreTest {
    @Test fun jsonSerializerProducesOneEscapedJsonLine() {
        val line = TelemetryJson.encode(mapOf(
            "v" to 1,
            "cat" to "incident",
            "ev" to "INCIDENT_START",
            "data" to mapOf("message" to "line 1\nline 2", "ok" to true),
        ))
        assertTrue(line.startsWith("{"))
        assertTrue(line.endsWith("}"))
        assertFalse(line.contains('\n'))
        assertTrue(line.contains("\\n"))
        assertTrue(line.contains("\"ev\":\"INCIDENT_START\""))
    }

    @Test fun ruleEngineHonorsThresholdAggregatesOngoingAndEnds() {
        val rule = IncidentRule("OUTPUT_SILENT", "error", 2_000)
        val engine = IncidentRuleEngine(listOf(rule))
        val violation = mapOf("OUTPUT_SILENT" to mapOf("rmsDbfs" to -90.0))
        assertTrue(engine.check(0, violation).isEmpty())
        assertTrue(engine.check(1_000, violation).isEmpty())
        assertEquals("INCIDENT_START", engine.check(2_000, violation).single().kind)
        assertTrue(engine.check(11_999, violation).isEmpty())
        assertEquals("INCIDENT_ONGOING", engine.check(12_000, violation).single().kind)
        val ended = engine.check(13_500, emptyMap()).single()
        assertEquals("INCIDENT_END", ended.kind)
        assertEquals(13_500, ended.durationMs)
        assertTrue(engine.active(14_000).isEmpty())
    }

    @Test fun snapshotRingKeepsLatestWindowAndDrains() {
        val ring = SnapshotRing<Int>(3)
        ring.add(1); ring.add(2); ring.add(3); ring.add(4)
        assertEquals(listOf(2, 3, 4), ring.snapshot())
        assertEquals(listOf(2, 3, 4), ring.drain())
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test fun fileNamesAreHashedAndExtensionIsKept() {
        val cleaned = TelemetryText.clean("Не удалось открыть «Моя любимая песня.flac»")
        assertFalse(cleaned.contains("Моя любимая песня"))
        assertTrue(cleaned.contains(".flac"))
        assertTrue(cleaned.contains("<file:"))
    }

    @Test fun summaryListsIncidentsMarksErrorsClockAndLostEvents() {
        val server = TelemetryJson.encode(mapOf(
            "ts" to "2026-09-27T10:00:00Z", "mono" to 1000, "dev" to "server-id", "role" to "server",
            "app" to mapOf("version" to "1.4.2"), "sev" to "error", "ev" to "INCIDENT_START",
            "data" to mapOf("rule" to "POINT_NO_PACKETS", "incidentId" to "incident-1"),
        )) + "\n" + TelemetryJson.encode(mapOf(
            "ts" to "2026-09-27T10:00:04Z", "sev" to "info", "ev" to "INCIDENT_END",
            "data" to mapOf("rule" to "POINT_NO_PACKETS", "incidentId" to "incident-1", "durMs" to 4_000),
        ))
        val point = TelemetryJson.encode(mapOf(
            "ts" to "2026-09-27T10:01:00Z", "sev" to "info", "ev" to "USER_MARK",
            "data" to mapOf("markId" to "mark-1"),
        )) + "\n" + TelemetryJson.encode(mapOf(
            "ts" to "2026-09-27T10:02:00Z", "sev" to "error", "ev" to "AUDIO_DECODE_FAILED",
        ))
        val summary = TelemetrySummary.create(server, point, "iOS peer does not support export", 1.25, 2, 1)
        assertTrue(summary.contains("POINT_NO_PACKETS"))
        assertTrue(summary.contains("4.0 s; ended"))
        assertTrue(summary.contains("1.4.2"))
        assertTrue(summary.contains("mark-1"))
        assertTrue(summary.contains("AUDIO_DECODE_FAILED: 1"))
        assertTrue(summary.contains("1.250 ms"))
        assertTrue(summary.contains("dropped queue events=2"))
        assertTrue(summary.contains("Other phone journal missing"))
    }

    @Test fun signalMeterComputesOneSecondRmsAndPeak() {
        val meter = AtomicSignalMeter()
        meter.add(floatArrayOf(0.5f, -0.5f, 1f, -1f), 0, 4)
        val window = meter.take()
        assertEquals(4, window.frames)
        assertEquals(kotlin.math.sqrt(0.625), window.rms, 1e-4)
        assertEquals(1.0, window.peak, 1e-4)
        assertEquals(0L, meter.take().frames)
    }
}
