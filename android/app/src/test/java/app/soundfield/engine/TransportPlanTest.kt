package app.soundfield.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportPlanTest {
    @Test fun mergeKeepsAllAddressesAndBestMetadata() {
        val merged = TransportPlan.mergeAddresses(
            listOf(PeerAddress("192.168.1.26", "", ""), PeerAddress("192.168.1.30", "wlan0", "n0")),
            listOf(PeerAddress("192.168.1.26", "wlan1", "n1"), PeerAddress("192.168.1.31", "wlan2", "n2")),
        )
        assertEquals(listOf("192.168.1.26", "192.168.1.30", "192.168.1.31"), merged.map { it.ip })
        assertEquals("wlan1", merged.first().iface)
        assertEquals("n1", merged.first().networkId)
    }

    @Test fun candidatesPreferLastWorkingThenMdnsAndTryEveryNetwork() {
        val result = TransportPlan.candidates(
            lastSuccessfulHost = "192.168.1.19",
            mdns = listOf("192.168.1.16", "192.168.1.19"),
            exchanged = listOf("192.168.1.26"),
            history = listOf("192.168.1.26", "192.168.1.30"),
            networkIds = listOf("wifi-0", "wifi-1"),
        )
        assertEquals(listOf(
            PlannedConnection("192.168.1.19", "wifi-0"), PlannedConnection("192.168.1.19", "wifi-1"),
            PlannedConnection("192.168.1.16", "wifi-0"), PlannedConnection("192.168.1.16", "wifi-1"),
            PlannedConnection("192.168.1.26", "wifi-0"), PlannedConnection("192.168.1.26", "wifi-1"),
            PlannedConnection("192.168.1.30", "wifi-0"), PlannedConnection("192.168.1.30", "wifi-1"),
        ), result)
    }

    @Test fun missingWifiStillHasProcessDefaultCandidate() {
        assertEquals(listOf(PlannedConnection("192.168.1.9", "process_default")),
            TransportPlan.candidates(null, listOf("192.168.1.9"), emptyList(), emptyList(), emptyList()))
    }
}
