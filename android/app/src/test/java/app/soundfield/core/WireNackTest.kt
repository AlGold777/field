package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Граничные случаи NACK: усечение при кодировании (MAX_NACK = 64) и отказ декодера при n > MAX_NACK. */
class WireNackTest {
    private val key = ByteArray(32) { it.toByte() }

    /** NACK с произвольным заявленным n и данными n номеров, с верным тегом (Wire.nack усекает, поэтому собираем руками). */
    private fun rawNack(n: Int, seqCount: Int = n): ByteArray {
        val b = ByteBuffer.allocate(UdpMsg.HEADER + 6 + seqCount * 4)
        b.put('S'.code.toByte()); b.put('F'.code.toByte()); b.put(SF.PROTOCOL_VERSION.toByte()); b.put(UdpMsg.T_NACK.toByte())
        b.putInt(0x01020304); b.putInt(3); b.putShort(n.toShort())
        for (i in 0 until seqCount) b.putInt(500 + i)
        val body = b.array()
        return body + PairCrypto.hmac(key, body, 0, body.size).copyOf(UdpMsg.TAG)
    }

    private fun encodedCount(n: Int): Int {
        val pkt = Wire.nack(0x01020304, 3, IntArray(n) { 500 + it }, key)
        val m = Wire.decode(pkt, pkt.size, key) as UdpMsg.Nack
        assertEquals(UdpMsg.HEADER + 6 + m.seqs.size * 4 + UdpMsg.TAG, pkt.size)
        return m.seqs.size
    }

    @Test fun encodeZeroOneAndMaxKeepAllSeqs() {
        assertEquals(0, encodedCount(0))
        assertEquals(1, encodedCount(1))
        assertEquals(64, encodedCount(64))
    }

    @Test fun encodeSixtyFiveIsTruncatedToSixtyFour() {
        val seqs = IntArray(65) { 500 + it }
        val pkt = Wire.nack(0x01020304, 3, seqs, key)
        val m = Wire.decode(pkt, pkt.size, key) as UdpMsg.Nack
        assertEquals(64, m.seqs.size)
        assertEquals(seqs.take(64), m.seqs.toList())
    }

    @Test fun decodeAcceptsZeroOneAndSixtyFour() {
        for (n in intArrayOf(0, 1, 64)) {
            val pkt = rawNack(n)
            val m = Wire.decode(pkt, pkt.size, key) as UdpMsg.Nack
            assertEquals(n, m.seqs.size)
            assertEquals(3, m.epoch)
            assertEquals((0 until n).map { 500 + it }, m.seqs.toList())
        }
    }

    @Test fun decodeRejectsSixtyFiveEvenWithValidTagAndFullData() {
        val pkt = rawNack(65)
        assertNull(Wire.decode(pkt, pkt.size, key))
    }

    @Test fun decodeRejectsDeclaredCountLargerThanData() {
        val pkt = rawNack(n = 10, seqCount = 3)
        assertNull(Wire.decode(pkt, pkt.size, key))
    }
}
