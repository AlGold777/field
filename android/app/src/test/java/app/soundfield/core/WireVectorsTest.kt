package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Золотые векторы протокола v2. Те же байты лежат в tools/protocol_vectors.json (источник для Swift-проверки
 * tools/check_protocol_vectors.swift). При изменении формата — обновить оба файла.
 */
class WireVectorsTest {
    private fun bytesOf(hex: String): ByteArray = PairCrypto.unhex(hex)!!
    private fun hexOf(b: ByteArray): String = PairCrypto.hex(b)

    private val key = bytesOf("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val samples = shortArrayOf(0, 1, -1, 32767, -32768)

    @Test fun pingEncodesGoldenBytesAndDecodesFields() {
        val hex = "53460202010203040a0b0c0d0e0f10110000004d6099f86436a776c0"
        assertEquals(hex, hexOf(Wire.ping(16909060, 723685415333072913L, 77, key)))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size, key) as UdpMsg.Ping
        assertEquals(16909060, m.session); assertEquals(723685415333072913L, m.t1); assertEquals(77, m.pingSeq)
    }

    @Test fun pongEncodesGoldenBytesAndDecodesFields() {
        val hex = "5346020301020304000000003b9aca01000000007735940200000000b2d05e0300000005194ac197e88528c5"
        assertEquals(hex, hexOf(Wire.pong(16909060, 1000000001L, 2000000002L, 3000000003L, 5, key)))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size, key) as UdpMsg.Pong
        assertEquals(1000000001L, m.t1); assertEquals(2000000002L, m.t2); assertEquals(3000000003L, m.t3); assertEquals(5, m.pingSeq)
    }

    @Test fun audioWithoutRetransmitFlagMatchesGoldenBytes() {
        val hex = "534602011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a0005000000000001ffff7fff8000ec213a3091744534"
        val b = Wire.audio(305441741, 7, 99, 1234567890123L, -5L, 42L, 0, samples, samples.size, key)
        assertEquals(hex, hexOf(b))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size, key) as UdpMsg.Audio
        assertFalse(m.isRetransmit)
        assertEquals(305441741, m.session); assertEquals(7, m.epoch); assertEquals(99, m.seq)
        assertEquals(1234567890123L, m.startFrame); assertEquals(-5L, m.playoutNs); assertEquals(42L, m.sendNs)
        assertEquals(samples.toList(), m.samples.take(m.count))
    }

    @Test fun audioWithRetransmitFlagMatchesGoldenBytes() {
        val hex = "534602011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a0005010000000001ffff7fff800066757264d3866535"
        val b = Wire.audio(305441741, 7, 99, 1234567890123L, -5L, 42L, UdpMsg.FLAG_RETRANSMIT, samples, samples.size, key)
        assertEquals(hex, hexOf(b))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size, key) as UdpMsg.Audio
        assertTrue(m.isRetransmit)
    }

    @Test fun nackWithoutSeqsMatchesGoldenBytes() {
        val hex = "53460204010203040000000300007033ddcb8a00733e"
        assertEquals(hex, hexOf(Wire.nack(16909060, 3, IntArray(0), key)))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size, key) as UdpMsg.Nack
        assertEquals(3, m.epoch); assertEquals(0, m.seqs.size)
    }

    @Test fun nackWithOneSeqMatchesGoldenBytes() {
        val hex = "53460204010203040000000300010000000ac18492445cbaf7f4"
        assertEquals(hex, hexOf(Wire.nack(16909060, 3, intArrayOf(10), key)))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size, key) as UdpMsg.Nack
        assertEquals(listOf(10), m.seqs.toList())
    }

    @Test fun nackWithSixtyFourSeqsMatchesGoldenBytes() {
        val hex = "5346020401020304000000030040000003e8000003e9000003ea000003eb000003ec000003ed000003ee000003ef000003f0000003f1000003f2000003f3000003f4000003f5000003f6000003f7000003f8000003f9000003fa000003fb000003fc000003fd000003fe000003ff000004000000040100000402000004030000040400000405000004060000040700000408000004090000040a0000040b0000040c0000040d0000040e0000040f000004100000041100000412000004130000041400000415000004160000041700000418000004190000041a0000041b0000041c0000041d0000041e0000041f0000042000000421000004220000042300000424000004250000042600000427aafdb1952a998216"
        val seqs = IntArray(64) { 1000 + it }
        assertEquals(hex, hexOf(Wire.nack(16909060, 3, seqs, key)))
        val m = Wire.decode(bytesOf(hex), bytesOf(hex).size, key) as UdpMsg.Nack
        assertEquals(seqs.toList(), m.seqs.toList())
    }

    @Test fun nackIsTruncatedToMax() {
        assertEquals(UdpMsg.HEADER + 6 + UdpMsg.MAX_NACK * 4 + UdpMsg.TAG, Wire.nack(1, 1, IntArray(65) { it }, key).size)
    }

    @Test fun truncatedForeignAndForgedPacketsAreRejected() {
        val audio = bytesOf("534602011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a0005000000000001ffff7fff8000ec213a3091744534")
        assertNull(Wire.decode(audio, audio.size - 2, key))
        assertNull(Wire.decode(audio, 7, key))
        val wrongMagic = audio.copyOf(); wrongMagic[0] = 'X'.code.toByte()
        assertNull(Wire.decode(wrongMagic, wrongMagic.size, key))
        val wrongVersion = audio.copyOf(); wrongVersion[2] = 1
        assertNull(Wire.decode(wrongVersion, wrongVersion.size, key))
        // подделка: изменён epoch (AUDIO с большим epoch глушил Point) — тег не сходится
        val forged = audio.copyOf(); forged[8 + 3] = 0x7f
        assertNull(Wire.decode(forged, forged.size, key))
        // чужой ключ
        assertNull(Wire.decode(audio, audio.size, ByteArray(32) { 1 }))
        // без тега (формат v1 той же длины минус тег)
        assertNull(Wire.decode(audio, audio.size - UdpMsg.TAG, key))
    }

    @Test fun x25519MatchesRfc7748() {
        val aPriv = bytesOf("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"); val aPub = bytesOf("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bPriv = bytesOf("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"); val bPub = bytesOf("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(hexOf(aPub), hexOf(X25519.publicKey(aPriv)))
        assertEquals(hexOf(bPub), hexOf(X25519.publicKey(bPriv)))
        assertEquals(shared, hexOf(X25519.scalarMult(aPriv, bPub)))
        assertEquals(shared, hexOf(X25519.scalarMult(bPriv, aPub)))
    }

    @Test fun kdfAndProofsMatchGoldenBytes() {
        val aPub = bytesOf("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"); val bPub = bytesOf("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val pairKey = PairCrypto.mac(bytesOf("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"), "sf-pair-v2".toByteArray(), aPub, bPub)
        assertEquals("d565847bb50c71b3cabc66ae3d9fa01934b50b85c1b581f556d2d4632c5eab77", hexOf(pairKey))
        assertEquals("8fa0a6c0afab5d1711c8d8afb5975887ee4edc20669ed3e5a9a7d64e3ed522f7", hexOf(PairCrypto.udpKey(pairKey, ByteArray(16), ByteArray(16) { -1 })))
        assertEquals("ac92fedf2582578ac05122a63ac13b38116c2f9711d531682a7df7dc636cebff", hexOf(PairCrypto.proof(pairKey, "sf-accept", "id-A".toByteArray(), "id-B".toByteArray(), aPub, bPub)))
    }
}
