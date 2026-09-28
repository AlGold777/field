package app.soundfield.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

class PeerPathDiagnosisTest {
    private fun candidate(network: String = "wifi", reason: PeerPathDiagnosis.Reason = PeerPathDiagnosis.Reason.TIMEOUT) =
        PeerPathDiagnosis.Candidate(network, network, listOf("192.168.1.2"), "192.168.1.9", 41871, reason)

    private fun run(vararg times: Long, port: Int = 41871, network: String = "wifi", gateway: String = "ok"): PeerPathDiagnosis.Result {
        val d = PeerPathDiagnosis()
        d.onResolved("192.168.1.9", port, 0)
        var result = PeerPathDiagnosis.Result(PeerPathDiagnosis.Verdict.NONE)
        times.forEach { at -> result = d.onRound(PeerPathDiagnosis.Round("192.168.1.9", port, at, listOf(candidate(network))),
            setOf(network), mapOf(network to gateway)) }
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

    @Test fun staleResolutionOrDifferentPortDoesNotDiagnose() {
        val stale = PeerPathDiagnosis().apply { onResolved("192.168.1.9", 41871, 0) }
        repeat(3) { stale.onRound(PeerPathDiagnosis.Round("192.168.1.9", 41871, 35_000L + it * 3_000, listOf(candidate())), setOf("wifi"), mapOf("wifi" to "ok")) }
        assertFalse(stale.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED)
        val changedPort = PeerPathDiagnosis().apply { onResolved("192.168.1.9", 41871, 0) }
        repeat(3) { changedPort.onRound(PeerPathDiagnosis.Round("192.168.1.9", 40000, it * 3_000L, listOf(candidate())),
            setOf("wifi"), mapOf("wifi" to "ok")) }
        assertFalse(changedPort.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED)
    }

    @Test fun refusedCandidateResetsTimeoutRunAndReportsNotListening() {
        val d = PeerPathDiagnosis().apply { onResolved("192.168.1.9", 41871, 0) }
        d.onRound(PeerPathDiagnosis.Round("h", 41871, 0, listOf(candidate())), setOf("wifi"), mapOf("wifi" to "ok"))
        val result = d.onRound(PeerPathDiagnosis.Round("h", 41871, 3_000, listOf(candidate(reason = PeerPathDiagnosis.Reason.REFUSED))), setOf("wifi"), mapOf("wifi" to "ok"))
        assertEquals(PeerPathDiagnosis.Verdict.PEER_NOT_LISTENING, result.verdict)
    }

    @Test fun everyCurrentNetworkMustTimeout() {
        val d = PeerPathDiagnosis().apply { onResolved("192.168.1.9", 41871, 0) }
        repeat(3) { d.onRound(PeerPathDiagnosis.Round("h", 41871, it * 3_000L, listOf(candidate("wifi1"))),
            setOf("wifi1", "wifi2"), mapOf("wifi1" to "ok", "wifi2" to "ok")) }
        assertEquals(PeerPathDiagnosis.Verdict.NONE, d.currentVerdict())
    }

    @Test fun successfulConnectionAndNetworkChangeClearVerdict() {
        val d = PeerPathDiagnosis().apply { onResolved("h", 41871, 0) }
        repeat(3) { d.onRound(PeerPathDiagnosis.Round("h", 41871, it * 3_000L, listOf(candidate())), setOf("wifi"), mapOf("wifi" to "ok")) }
        assertEquals(PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED, d.currentVerdict())
        d.onConnected(); assertEquals(PeerPathDiagnosis.Verdict.NONE, d.currentVerdict())
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
