package app.soundfield.engine

/** Pure connection-address ordering so multi-address route behavior can be tested on the JVM. */
data class PlannedConnection(val host: String, val networkId: String)

object TransportPlan {
    /** Merge addresses without losing discovery order or interface/network metadata. */
    fun mergeAddresses(vararg groups: List<PeerAddress>): List<PeerAddress> {
        val merged = LinkedHashMap<String, PeerAddress>()
        groups.flatMap { it }.forEach { next ->
            if (next.ip.isBlank()) return@forEach
            val current = merged[next.ip]
            merged[next.ip] = when {
                current == null -> next
                current.iface.isBlank() && next.iface.isNotBlank() -> next
                current.networkId.isBlank() && next.networkId.isNotBlank() -> next.copy(iface = current.iface.ifBlank { next.iface })
                else -> current
            }
        }
        return merged.values.toList()
    }

    /** Last working peer address first, then mDNS, exchanged addresses, and persisted history. */
    fun candidates(
        lastSuccessfulHost: String?,
        mdns: List<String>,
        exchanged: List<String>,
        history: List<String>,
        networkIds: List<String>,
    ): List<PlannedConnection> {
        val hosts = LinkedHashSet<String>()
        lastSuccessfulHost?.takeIf { it.isNotBlank() }?.let(hosts::add)
        (mdns + exchanged + history).filter { it.isNotBlank() }.forEach(hosts::add)
        val networks = networkIds.distinct().ifEmpty { listOf("process_default") }
        return hosts.flatMap { host -> networks.map { network -> PlannedConnection(host, network) } }
    }
}

/** Deterministic tie-breaker for crossed invite/resume attempts: the larger ID accepts inbound. */
object CrossedConnectionPolicy {
    fun acceptInbound(localId: String, peerId: String): Boolean = localId > peerId
}

object ReconnectBackoff {
    fun delayMs(attempt: Int, hasWifi: Boolean): Long {
        val exponential = 1_000L shl (attempt.coerceAtLeast(0) / 2).coerceAtMost(4)
        return exponential.coerceAtMost(if (hasWifi) 3_000L else 15_000L)
    }
}
