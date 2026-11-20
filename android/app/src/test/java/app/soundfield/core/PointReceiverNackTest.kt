package app.soundfield.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Политика перезапроса потерянных UDP-пакетов на Point (PointReceiver.collectNacks). */
class PointReceiverNackTest {
    private val ms = 1_000_000L
    private val t0 = 10_000 * ms   // как System.nanoTime: далеко от нуля (lastNack = 0 у новых записей)

    private fun packet(seq: Int) = UdpMsg.Audio(1, 1, seq, seq * SF.PACKET_FRAMES.toLong(), 0L, 0L, 0,
        IntArray(SF.PACKET_FRAMES), SF.PACKET_FRAMES)

    @Test fun lostPacketsAreRequestedAfterDelayWithRetryIntervalAndTryLimit() {
        val rx = PointReceiver(ClockSync())
        rx.onAudio(packet(0), t0)
        rx.onAudio(packet(3), t0)                                   // 1 и 2 потеряны
        assertEquals(0, rx.collectNacks(t0 + 5 * ms).size)             // моложе 12 мс — рано
        assertArrayEquals(intArrayOf(1, 2), rx.collectNacks(t0 + 13 * ms))
        assertEquals(0, rx.collectNacks(t0 + 30 * ms).size)            // повтор не чаще 40 мс
        assertArrayEquals(intArrayOf(1, 2), rx.collectNacks(t0 + 60 * ms))
        rx.collectNacks(t0 + 110 * ms); rx.collectNacks(t0 + 160 * ms)      // 3-я и 4-я попытки
        assertEquals(0, rx.collectNacks(t0 + 210 * ms).size)           // попыток больше 4 нет
        rx.collectNacks(t0 + 400 * ms)                                 // старше 350 мс — окончательно потеряны
        assertEquals(2L, rx.lostFinal)
    }

    @Test fun retransmitClearsPendingAndRequestIsCappedAtWireLimit() {
        val rx = PointReceiver(ClockSync())
        rx.onAudio(packet(0), t0)
        rx.onAudio(packet(101), t0)                               // 100 потерянных
        val first = rx.collectNacks(t0 + 13 * ms)
        assertEquals(UdpMsg.MAX_NACK, first.size)
        assertEquals(1, first.first())                            // старейшие первыми
        rx.onAudio(UdpMsg.Audio(1, 1, 1, SF.PACKET_FRAMES.toLong(), 0L, 0L, UdpMsg.FLAG_RETRANSMIT,
            IntArray(SF.PACKET_FRAMES), SF.PACKET_FRAMES), t0 + 14 * ms)
        assertTrue(rx.collectNacks(t0 + 60 * ms).none { it == 1 })
    }
}
