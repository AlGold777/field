package app.soundfield.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

class PeerPathDiagnosisTest {
    private fun candidate(network: String = "wifi", reason: PeerPathDiagnosis.Reason = PeerPathDiagnosis.Reason.TIMEOUT) =
        PeerPathDiagnosis.Candidate(network, network, listOf("192.168.1.2"), "192.168.1.9", 41871, reason)

    private fun round(d: PeerPathDiagnosis, at: Long, port: Int = 41871, reason: PeerPathDiagnosis.Reason = PeerPathDiagnosis.Reason.TIMEOUT,
                      peerPresent: Boolean = true, presentPort: Int? = 41871) =
        d.onRound(PeerPathDiagnosis.Round("192.168.1.9", port, at, listOf(candidate(reason = reason))),
            setOf("wifi"), mapOf("wifi" to "ok"), peerPresent, presentPort)

    private fun run(vararg times: Long, port: Int = 41871, network: String = "wifi", gateway: String = "ok"): PeerPathDiagnosis.Result {
        val d = PeerPathDiagnosis()
        d.onResolved("192.168.1.9", port, 0)
        var result = PeerPathDiagnosis.Result(PeerPathDiagnosis.Verdict.NONE)
        times.forEach { at -> result = d.onRound(PeerPathDiagnosis.Round("192.168.1.9", port, at, listOf(candidate(network))),
            setOf(network), mapOf(network to gateway), peerPresent = true, presentPort = port) }
        return result
    }

    @Test fun threeTimeoutRoundsAcrossFiveSecondsAndReachableGatewayDiagnoseIsolation() {
        val result = run(0, 3_000, 6_000)
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, result.verdict)
        assertEquals(PeerPathDiagnosis.Confidence.HIGH, result.confidence)
    }

    @Test fun roundsCloserThanFiveSecondsDoNotDiagnose() {
        assertFalse(run(0, 1_500, 3_000).verdict == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED)
    }

    @Test fun oldResolveStillDiagnosesWhenPeerRemainsPresentOnSamePort() {
        val d = PeerPathDiagnosis().apply { onResolved("192.168.1.9", 41871, 0) }
        var result = round(d, 40_000)
        result = round(d, 50_000)
        result = round(d, 60_000)
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, result.verdict)
        assertEquals(60_000L, result.lastResolveAgeMs)
    }

    @Test fun lanBlockedVerdictStaysAfterResolutionGetsOld() {
        val d = PeerPathDiagnosis().apply { onResolved("192.168.1.9", 41871, 0) }
        listOf(0L, 5_000L, 10_000L).forEach { round(d, it) }
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, d.currentVerdict())
        listOf(35_000L, 45_000L).forEach { round(d, it) }
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, d.currentVerdict())
    }

    @Test fun absentPeerClearsLanBlockedVerdict() {
        val d = PeerPathDiagnosis().apply { onResolved("h", 41871, 0) }
        listOf(0L, 3_000L, 6_000L).forEach { round(d, it) }
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, d.currentVerdict())
        assertEquals(PeerPathDiagnosis.Verdict.NOT_DISCOVERED,
            round(d, 9_000, peerPresent = false, presentPort = null).verdict)
    }

    @Test fun presentPeerWithDifferentPortDoesNotDiagnose() {
        val d = PeerPathDiagnosis().apply { onResolved("h", 41871, 0) }
        repeat(3) { index -> round(d, index * 3_000L, presentPort = 40000) }
        assertEquals(PeerPathDiagnosis.Verdict.NONE, d.currentVerdict())
    }

    @Test fun refusedCandidateClearsLanBlockedVerdictAndReportsNotListening() {
        val d = PeerPathDiagnosis().apply { onResolved("h", 41871, 0) }
        listOf(0L, 3_000L, 6_000L).forEach { round(d, it) }
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, d.currentVerdict())
        assertEquals(PeerPathDiagnosis.Verdict.PEER_NOT_LISTENING,
            round(d, 9_000, reason = PeerPathDiagnosis.Reason.REFUSED).verdict)
    }

    @Test fun everyCurrentNetworkMustTimeout() {
        val d = PeerPathDiagnosis().apply { onResolved("192.168.1.9", 41871, 0) }
        repeat(3) { d.onRound(PeerPathDiagnosis.Round("h", 41871, it * 3_000L, listOf(candidate("wifi1"))),
            setOf("wifi1", "wifi2"), mapOf("wifi1" to "ok", "wifi2" to "ok"), true, 41871) }
        assertEquals(PeerPathDiagnosis.Verdict.NONE, d.currentVerdict())

        val both = PeerPathDiagnosis().apply { onResolved("192.168.1.9", 41871, 0) }
        repeat(3) { both.onRound(PeerPathDiagnosis.Round("h", 41871, it * 3_000L,
            listOf(candidate("wifi1"), candidate("wifi2"))), setOf("wifi1", "wifi2"),
            mapOf("wifi1" to "ok", "wifi2" to "ok"), true, 41871) }
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, both.currentVerdict())
    }

    @Test fun successfulConnectionAndNetworkChangeClearVerdict() {
        val d = PeerPathDiagnosis().apply { onResolved("h", 41871, 0) }
        listOf(0L, 3_000L, 6_000L).forEach { round(d, it) }
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, d.currentVerdict())
        d.onConnected(); assertEquals(PeerPathDiagnosis.Verdict.NONE, d.currentVerdict())
        listOf(0L, 3_000L, 6_000L).forEach { round(d, it) }
        d.onNetworkChanged(); assertEquals(PeerPathDiagnosis.Verdict.NONE, d.currentVerdict())
    }

    @Test fun gatewayTimeoutBlocksDiagnosisButUnknownAllowsMediumConfidence() {
        assertFalse(run(0, 3_000, 6_000, gateway = "timeout").verdict == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED)
        val result = run(0, 3_000, 6_000, gateway = "unknown")
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, result.verdict)
        assertEquals(PeerPathDiagnosis.Confidence.MEDIUM, result.confidence)
    }

    @Test fun classifySocketFailureReasons() {
        assertEquals(PeerPathDiagnosis.Reason.TIMEOUT, PeerPathDiagnosis.classify(SocketTimeoutException("timeout")))
        assertEquals(PeerPathDiagnosis.Reason.REFUSED, PeerPathDiagnosis.classify(ConnectException("ECONNREFUSED")))
        assertEquals(PeerPathDiagnosis.Reason.NO_ROUTE, PeerPathDiagnosis.classify(NoRouteToHostException("host")))
        assertEquals(PeerPathDiagnosis.Reason.TIMEOUT, PeerPathDiagnosis.classify(java.io.IOException("failed to connect after 2500ms")))
        assertEquals(PeerPathDiagnosis.RoundVerdict.ALL_TIMEOUT,
            PeerPathDiagnosis.classifyRound(listOf(PeerPathDiagnosis.Reason.TIMEOUT, PeerPathDiagnosis.Reason.CANCELLED)))
    }
}
