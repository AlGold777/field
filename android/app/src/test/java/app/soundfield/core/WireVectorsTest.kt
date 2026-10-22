package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Золотые векторы протокола. Те же байты лежат в tools/protocol_vectors.json (источник для Swift-проверки
 * tools/check_protocol_vectors.swift). При изменении формата — обновить оба файла.
 */
class WireVectorsTest {
    private fun bytesOf(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private val samples = shortArrayOf(0, 1, -1, 32767, -32768)

    @Test fun pingEncodesGoldenBytesAndDecodesFields() {
        val hex = "53460102010203040a0b0c0d0e0f10110000004d"
        assertEquals(hex, hexOf(Wire.ping(16909060, 723685415333072913L, 77)))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size) as UdpMsg.Ping
        assertEquals(16909060, m.session); assertEquals(723685415333072913L, m.t1); assertEquals(77, m.pingSeq)
    }

    @Test fun pongEncodesGoldenBytesAndDecodesFields() {
        val hex = "5346010301020304000000003b9aca01000000007735940200000000b2d05e0300000005"
        assertEquals(hex, hexOf(Wire.pong(16909060, 1000000001L, 2000000002L, 3000000003L, 5)))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size) as UdpMsg.Pong
        assertEquals(1000000001L, m.t1); assertEquals(2000000002L, m.t2); assertEquals(3000000003L, m.t3); assertEquals(5, m.pingSeq)
    }

    @Test fun audioWithoutRetransmitFlagMatchesGoldenBytes() {
        val hex = "534601011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a0005000000000001ffff7fff8000"
        val b = Wire.audio(305441741, 7, 99, 1234567890123L, -5L, 42L, 0, samples, samples.size)
        assertEquals(hex, hexOf(b))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size) as UdpMsg.Audio
        assertFalse(m.isRetransmit)
        assertEquals(305441741, m.session); assertEquals(7, m.epoch); assertEquals(99, m.seq)
        assertEquals(1234567890123L, m.startFrame); assertEquals(-5L, m.playoutNs); assertEquals(42L, m.sendNs)
        assertEquals(samples.toList(), m.samples.take(m.count))
    }

    @Test fun audioWithRetransmitFlagMatchesGoldenBytes() {
        val hex = "534601011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a0005010000000001ffff7fff8000"
        val b = Wire.audio(305441741, 7, 99, 1234567890123L, -5L, 42L, UdpMsg.FLAG_RETRANSMIT, samples, samples.size)
        assertEquals(hex, hexOf(b))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size) as UdpMsg.Audio
        assertTrue(m.isRetransmit)
    }

    @Test fun nackWithoutSeqsMatchesGoldenBytes() {
        val hex = "5346010401020304000000030000"
        assertEquals(hex, hexOf(Wire.nack(16909060, 3, IntArray(0))))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size) as UdpMsg.Nack
        assertEquals(3, m.epoch); assertEquals(0, m.seqs.size)
    }

    @Test fun nackWithOneSeqMatchesGoldenBytes() {
        val hex = "53460104010203040000000300010000000a"
        assertEquals(hex, hexOf(Wire.nack(16909060, 3, intArrayOf(10))))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size) as UdpMsg.Nack
        assertEquals(listOf(10), m.seqs.toList())
    }

    @Test fun nackWithSixtyFourSeqsMatchesGoldenBytes() {
        val hex = "5346010401020304000000030040000003e8000003e9000003ea000003eb000003ec000003ed000003ee000003ef000003f0000003f1000003f2000003f3000003f4000003f5000003f6000003f7000003f8000003f9000003fa000003fb000003fc000003fd000003fe000003ff000004000000040100000402000004030000040400000405000004060000040700000408000004090000040a0000040b0000040c0000040d0000040e0000040f000004100000041100000412000004130000041400000415000004160000041700000418000004190000041a0000041b0000041c0000041d0000041e0000041f0000042000000421000004220000042300000424000004250000042600000427"
        val seqs = IntArray(64) { 1000 + it }
        assertEquals(hex, hexOf(Wire.nack(16909060, 3, seqs)))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size) as UdpMsg.Nack
        assertEquals(seqs.toList(), m.seqs.toList())
    }

    @Test fun nackIsTruncatedToMaxAndOversizedCountIsRejected() {
        assertEquals(UdpMsg.HEADER + 6 + UdpMsg.MAX_NACK * 4, Wire.nack(1, 1, IntArray(65) { it }).size)
        val raw = bytesOf("5346010401020304000000030040000003e8000003e9000003ea000003eb000003ec000003ed000003ee000003ef000003f0000003f1000003f2000003f3000003f4000003f5000003f6000003f7000003f8000003f9000003fa000003fb000003fc000003fd000003fe000003ff000004000000040100000402000004030000040400000405000004060000040700000408000004090000040a0000040b0000040c0000040d0000040e0000040f000004100000041100000412000004130000041400000415000004160000041700000418000004190000041a0000041b0000041c0000041d0000041e0000041f0000042000000421000004220000042300000424000004250000042600000427")
        raw[UdpMsg.HEADER + 5] = 65.toByte() // n = 65 при 64 записях: отказ
        assertNull(Wire.decode(raw, raw.size))
    }

    @Test fun truncatedAndForeignPacketsAreRejected() {
        val audio = bytesOf("534601011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a0005000000000001ffff7fff8000")
        assertNull(Wire.decode(audio, audio.size - 2))
        assertNull(Wire.decode(audio, 7))
        val wrongMagic = audio.copyOf(); wrongMagic[0] = 'X'.code.toByte()
        assertNull(Wire.decode(wrongMagic, wrongMagic.size))
        val wrongVersion = audio.copyOf(); wrongVersion[2] = 9
        assertNull(Wire.decode(wrongVersion, wrongVersion.size))
    }
}
