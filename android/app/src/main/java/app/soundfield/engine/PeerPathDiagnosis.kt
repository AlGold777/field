package app.soundfield.engine

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException

/** Pure classification and state for diagnosing a discovered peer whose TCP paths time out. */
internal class PeerPathDiagnosis {
    enum class Reason { TIMEOUT, REFUSED, NO_ROUTE, CANCELLED, OTHER }
    enum class RoundVerdict { ALL_TIMEOUT, ANY_REFUSED, ALL_NO_ROUTE, MIXED }
    enum class Verdict { LAN_PEER_BLOCKED, PEER_NOT_LISTENING, NO_ROUTE, NOT_DISCOVERED, NONE }
    enum class Confidence { HIGH, MEDIUM }

    data class Candidate(val networkId: String, val networkLabel: String, val localIpv4: List<String>,
                         val remoteHost: String, val remotePort: Int, val reason: Reason)
    data class Round(val host: String, val port: Int, val atMs: Long, val candidates: List<Candidate>) {
        val verdict: RoundVerdict get() = classifyRound(candidates.map { it.reason })
        val networks: Set<String> get() = candidates.filter { it.reason != Reason.CANCELLED }.map { it.networkId }.toSet()
    }
    data class Result(val verdict: Verdict, val confidence: Confidence? = null, val rounds: Int = 0,
                      val spanMs: Long = 0, val resolvedPort: Int = 0, val triedPort: Int = 0,
                      val candidates: List<Candidate> = emptyList(), val changed: Boolean = true,
                      val lastResolveAgeMs: Long? = null)

    private data class Resolution(val host: String, val port: Int, val atMs: Long)
    private val rounds = ArrayDeque<Round>()
    private var resolution: Resolution? = null
    private var verdict = Verdict.NONE

    @Synchronized fun onResolved(host: String, port: Int, nowMs: Long) {
        resolution = Resolution(host, port, nowMs)
    }

    @Synchronized fun onRound(
        round: Round,
        currentNetworks: Set<String>,
        gateway: Map<String, String>,
        peerPresent: Boolean = true,
        presentPort: Int? = round.port,
    ): Result {
        if (!peerPresent) {
            rounds.clear()
            val changed = verdict != Verdict.NOT_DISCOVERED
            verdict = Verdict.NOT_DISCOVERED
            return Result(verdict, changed = changed, triedPort = round.port,
                candidates = round.candidates, lastResolveAgeMs = resolution?.let { round.atMs - it.atMs })
        }
        if (round.verdict == RoundVerdict.ANY_REFUSED) rounds.clear()
        rounds.addLast(round)
        while (rounds.size > 12) rounds.removeFirst()
        val lastResolution = resolution
        val currentPeerMatchesAttempt = presentPort != null && presentPort == round.port
        val timeoutRounds = rounds.takeLastWhile { it.verdict == RoundVerdict.ALL_TIMEOUT }
        val span = if (timeoutRounds.size >= 2) timeoutRounds.last().atMs - timeoutRounds.first().atMs else 0
        val allPathsTried = currentNetworks.isNotEmpty() && currentNetworks.all { n ->
            timeoutRounds.any { it.candidates.any { c -> c.networkId == n && c.reason == Reason.TIMEOUT } }
        }
        val gatewayState = currentNetworks.mapNotNull(gateway::get)
        val gatewayOk = gatewayState.any { it == "ok" }
        val gatewayUnknown = gatewayState.isEmpty() || gatewayState.any { it == "unknown" }
        val next = when {
            verdict == Verdict.LAN_PEER_BLOCKED && round.verdict != RoundVerdict.ANY_REFUSED -> Verdict.LAN_PEER_BLOCKED
            currentPeerMatchesAttempt && timeoutRounds.size >= 3 && span >= 5_000 && allPathsTried && (gatewayOk || gatewayUnknown) -> Verdict.LAN_PEER_BLOCKED
            round.verdict == RoundVerdict.ANY_REFUSED && currentPeerMatchesAttempt -> Verdict.PEER_NOT_LISTENING
            !currentPeerMatchesAttempt -> Verdict.NONE
            rounds.takeLastWhile { it.verdict == RoundVerdict.ALL_NO_ROUTE }.size >= 3 -> Verdict.NO_ROUTE
            else -> Verdict.NONE
        }
        val changed = next != verdict
        verdict = next
        return Result(next, if (gatewayUnknown) Confidence.MEDIUM else Confidence.HIGH,
            timeoutRounds.size, span, lastResolution?.port ?: 0, round.port, round.candidates, changed,
            lastResolution?.let { round.atMs - it.atMs })
    }

    @Synchronized fun onConnected() { reset() }
    @Synchronized fun onNetworkChanged() { reset() }
    @Synchronized fun onLost() {
        rounds.clear()
        resolution = null
        verdict = Verdict.NOT_DISCOVERED
    }
    @Synchronized fun currentVerdict(): Verdict = verdict
    @Synchronized fun latestCandidates(): List<Candidate> = rounds.lastOrNull()?.candidates.orEmpty()
    @Synchronized private fun reset() { rounds.clear(); resolution = null; verdict = Verdict.NONE }

    companion object {
        fun classifyRound(reasons: List<Reason>): RoundVerdict {
            val active = reasons.filter { it != Reason.CANCELLED }
            if (active.isEmpty()) return RoundVerdict.MIXED
            return when {
                active.any { it == Reason.REFUSED } -> RoundVerdict.ANY_REFUSED
                active.all { it == Reason.TIMEOUT } -> RoundVerdict.ALL_TIMEOUT
                active.all { it == Reason.NO_ROUTE } -> RoundVerdict.ALL_NO_ROUTE
                else -> RoundVerdict.MIXED
            }
        }

        fun classify(error: Throwable?): Reason {
            if (error == null) return Reason.OTHER
            val chain = generateSequence(error) { it.cause }.toList()
            val errno = chain.firstOrNull { it.javaClass.name == "android.system.ErrnoException" }
                ?.let { runCatching { it.javaClass.getField("errno").getInt(it) }.getOrNull() }
            when (errno) { 110 -> return Reason.TIMEOUT; 111 -> return Reason.REFUSED; 101, 113 -> return Reason.NO_ROUTE }
            if (chain.any { it is SocketTimeoutException }) return Reason.TIMEOUT
            if (chain.any { it is NoRouteToHostException }) return Reason.NO_ROUTE
            if (chain.any { it is ConnectException } && chain.any { it.message.orEmpty().contains("ECONNREFUSED", true) }) return Reason.REFUSED
            if (chain.any { it.message.orEmpty().contains("EHOSTUNREACH", true) || it.message.orEmpty().contains("ENETUNREACH", true) }) return Reason.NO_ROUTE
            if (chain.any { it.message.orEmpty().contains("ETIMEDOUT", true) || it.message.orEmpty().contains("after ") && it.message.orEmpty().contains("ms") }) return Reason.TIMEOUT
            if (chain.any { it is ConnectException } && chain.any { it.message.orEmpty().contains("refused", true) }) return Reason.REFUSED
            if (chain.any { it is SocketException && it.message.orEmpty().contains("interrupt", true) }) return Reason.CANCELLED
            return Reason.OTHER
        }
    }
}
