package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test

class ReplayWindowTest {
    @Test fun replayedPingIsRejectedAndNewerSeqAccepted() {
        val gate = PingReplayGate()
        assertTrue(gate.accept(5))
        assertFalse("повтор того же PING", gate.accept(5))
        assertFalse("старый seq", gate.accept(4))
        assertTrue(gate.accept(6))
    }

    @Test fun gateAcceptsFirstPacketWhateverItsSeqAndResets() {
        val gate = PingReplayGate()
        assertTrue(gate.accept(-3))
        assertFalse(gate.accept(-3))
        gate.reset()
        assertTrue("после смены ключа счётчик начинается заново", gate.accept(0))
    }

    /** Эмуляция Server: адрес получателя меняется только принятым PING. */
    @Test fun replayFromOtherAddressDoesNotRedirectAudio() {
        val gate = PingReplayGate()
        var pointAddr = ""
        fun onPing(seq: Int, from: String): Boolean { if (!gate.accept(seq)) return false; pointAddr = from; return true }
        assertTrue(onPing(10, "point:1000"))
        assertFalse(onPing(10, "attacker:2000"))
        assertEquals("point:1000", pointAddr)
        assertTrue(onPing(11, "point:1001"))
        assertEquals("point:1001", pointAddr)
    }

    @Test fun pongAcceptedOnceAndOnlyIfSent() {
        val w = PongWindow()
        assertFalse("не отправлялся", w.accept(1))
        w.sent(1); w.sent(2)
        assertTrue(w.accept(1))
        assertFalse("повтор", w.accept(1))
        assertTrue(w.accept(2))
        w.sent(3); w.reset()
        assertFalse(w.accept(3))
    }

    @Test fun pongWindowForgetsOldestBeyondSize() {
        val w = PongWindow(size = 4)
        for (i in 0 until 6) w.sent(i)
        assertFalse(w.accept(0)); assertFalse(w.accept(1))
        assertTrue(w.accept(5))
    }
}
