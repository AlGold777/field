package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Золотые векторы протокола v3. Те же байты лежат в tools/protocol_vectors.json (источник для Swift-проверки
 * tools/check_protocol_vectors.swift). При изменении формата — обновить оба файла.
 */
class WireVectorsTest {
    private fun bytesOf(hex: String): ByteArray = PairCrypto.unhex(hex)!!
    private fun hexOf(b: ByteArray): String = PairCrypto.hex(b)

    /** Пересчитывает тег над телом пакета (тело — всё до последних TAG байт). */
    private fun retag(body: ByteArray, key: ByteArray): ByteArray =
        body + PairCrypto.hmac(key, body, 0, body.size).copyOf(UdpMsg.TAG)

    private val key = bytesOf("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val samples = intArrayOf(0, 1, -1, 8388607, -8388608, 1193046, -1193046)
    private val samples384 = IntArray(384) { ((it * 43691 + 7) % 16777216) - 8388608 }

    private val pingHex = "53460302010203040a0b0c0d0e0f10110000004d59b2d54ac7c03f38"
    private val pongHex = "5346030301020304000000003b9aca01000000007735940200000000b2d05e03000000051b01298eebab1b49"
    private val audioFlag0Hex = "534603011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a00070000000000000001ffffff7fffff800000123456edcbaac8f9910320b7f044"
    private val audioFlag1Hex = "534603011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a00070100000000000001ffffff7fffff800000123456edcbaa8cb7d35d6717fe2c"
    private val nackN0Hex = "5346030401020304000000030000c95cbe34173856c9"
    private val nackN1Hex = "53460304010203040000000300010000000aa8d22e767678352b"
    private val nackN64Hex = "5346030401020304000000030040000003e8000003e9000003ea000003eb000003ec000003ed000003ee000003ef000003f0000003f1000003f2000003f3000003f4000003f5000003f6000003f7000003f8000003f9000003fa000003fb000003fc000003fd000003fe000003ff000004000000040100000402000004030000040400000405000004060000040700000408000004090000040a0000040b0000040c0000040d0000040e0000040f000004100000041100000412000004130000041400000415000004160000041700000418000004190000041a0000041b0000041c0000041d0000041e0000041f0000042000000421000004220000042300000424000004250000042600000427dffe8a9083dc7c07"
    private val audioFull384Hex = "534603011234abcd00000007000000630000011f71fb04cbfffffffffffffffb000000000000002a0180000080000780aab281555d82000882aab383555e84000984aab485555f86000a86aab587556088000b88aab68955618a000c8aaab78b55628c000d8caab88d55638e000e8eaab98f556490000f90aaba91556592001092aabb93556694001194aabc95556796001296aabd97556898001398aabe9955699a00149aaabf9b556a9c00159caac09d556b9e00169eaac19f556ca00017a0aac2a1556da20018a2aac3a3556ea40019a4aac4a5556fa6001aa6aac5a75570a8001ba8aac6a95571aa001caaaac7ab5572ac001dacaac8ad5573ae001eaeaac9af5574b0001fb0aacab15575b20020b2aacbb35576b40021b4aaccb55577b60022b6aacdb75578b80023b8aaceb95579ba0024baaacfbb557abc0025bcaad0bd557bbe0026beaad1bf557cc00027c0aad2c1557dc20028c2aad3c3557ec40029c4aad4c5557fc6002ac6aad5c75580c8002bc8aad6c95581ca002ccaaad7cb5582cc002dccaad8cd5583ce002eceaad9cf5584d0002fd0aadad15585d20030d2aadbd35586d40031d4aadcd55587d60032d6aaddd75588d80033d8aaded95589da0034daaadfdb558adc0035dcaae0dd558bde0036deaae1df558ce00037e0aae2e1558de20038e2aae3e3558ee40039e4aae4e5558fe6003ae6aae5e75590e8003be8aae6e95591ea003ceaaae7eb5592ec003decaae8ed5593ee003eeeaae9ef5594f0003ff0aaeaf15595f20040f2aaebf35596f40041f4aaecf55597f60042f6aaedf75598f80043f8aaeef95599fa0044faaaeffb559afc0045fcaaf0fd559bfe0046feaaf1ff559c00004700aaf201559d02004802aaf303559e04004904aaf405559f06004a06aaf50755a008004b08aaf60955a10a004c0aaaf70b55a20c004d0caaf80d55a30e004e0eaaf90f55a410004f10aafa1155a512005012aafb1355a614005114aafc1555a716005216aafd1755a818005318aafe1955a91a00541aaaff1b55aa1c00551cab001d55ab1e00561eab011f55ac20005720ab022155ad22005822ab032355ae24005924ab042555af26005a26ab052755b028005b28ab062955b12a005c2aab072b55b22c005d2cab082d55b32e005e2eab092f55b430005f30ab0a3155b532006032ab0b3355b634006134ab0c3555b736006236ab0d3755b838006338ab0e3955b93a00643aab0f3b55ba3c00653cab103d55bb3e00663eab113f55bc40006740ab124155bd42006842ab134355be44006944ab144555bf46006a46ab154755c048006b48ab164955c14a006c4aab174b55c24c006d4cab184d55c34e006e4eab194f55c450006f50ab1a5155c552007052ab1b5355c654007154ab1c5555c756007256ab1d5755c858007358ab1e5955c95a00745aab1f5b55ca5c00755cab205d55cb5e00765eab215f55cc60007760ab226155cd62007862ab236355ce64007964ab246555cf66007a66ab256755d068007b68ab266955d16a007c6aab276b55d26c007d6cab286d55d36e007e6eab296f55d470007f70ab2a7155d572008072ab2b7355d674008174ab2c7555d776008276ab2d7755d878008378ab2e7955d97a00847aab2f7b55da7c00857cab307d55db7e00867eab317f55dc71099fa48a7def5e"

    @Test fun pingEncodesGoldenBytesAndDecodesFields() {
        assertEquals(pingHex, hexOf(Wire.ping(16909060, 723685415333072913L, 77, key)))
        val m = Wire.decode(bytesOf(pingHex), bytesOf(pingHex).size, key) as UdpMsg.Ping
        assertEquals(16909060, m.session); assertEquals(723685415333072913L, m.t1); assertEquals(77, m.pingSeq)
    }

    @Test fun pongEncodesGoldenBytesAndDecodesFields() {
        assertEquals(pongHex, hexOf(Wire.pong(16909060, 1000000001L, 2000000002L, 3000000003L, 5, key)))
        val m = Wire.decode(bytesOf(pongHex), bytesOf(pongHex).size, key) as UdpMsg.Pong
        assertEquals(1000000001L, m.t1); assertEquals(2000000002L, m.t2); assertEquals(3000000003L, m.t3); assertEquals(5, m.pingSeq)
    }

    @Test fun audioWithoutRetransmitFlagMatchesGoldenBytes() {
        val b = Wire.audio(305441741, 7, 99, 1234567890123L, -5L, 42L, 0, samples, samples.size, key)
        assertEquals(audioFlag0Hex, hexOf(b))
        val m = Wire.decode(bytesOf(audioFlag0Hex), bytesOf(audioFlag0Hex).size, key) as UdpMsg.Audio
        assertFalse(m.isRetransmit)
        assertEquals(305441741, m.session); assertEquals(7, m.epoch); assertEquals(99, m.seq)
        assertEquals(1234567890123L, m.startFrame); assertEquals(-5L, m.playoutNs); assertEquals(42L, m.sendNs)
        assertEquals(samples.toList(), m.samples.take(m.count))
    }

    @Test fun audioWithRetransmitFlagMatchesGoldenBytes() {
        val b = Wire.audio(305441741, 7, 99, 1234567890123L, -5L, 42L, UdpMsg.FLAG_RETRANSMIT, samples, samples.size, key)
        assertEquals(audioFlag1Hex, hexOf(b))
        val m = Wire.decode(bytesOf(audioFlag1Hex), bytesOf(audioFlag1Hex).size, key) as UdpMsg.Audio
        assertTrue(m.isRetransmit)
    }

    @Test fun audioFullPacketMatchesGoldenBytesAndRoundTrips() {
        val b = Wire.audio(305441741, 7, 99, 1234567890123L, -5L, 42L, 0, samples384, 384, key)
        assertEquals(audioFull384Hex, hexOf(b))
        val m = Wire.decode(bytesOf(audioFull384Hex), bytesOf(audioFull384Hex).size, key) as UdpMsg.Audio
        assertEquals(384, m.count)
        assertEquals(samples384.toList(), m.samples.take(m.count))
    }

    @Test fun maximalAudioPacketFitsIpv6MtuWithoutFragmentation() {
        val full = bytesOf(audioFull384Hex)
        assertEquals(1204, full.size)
        assertEquals(UdpMsg.AUDIO_HEADER + UdpMsg.MAX_AUDIO_FRAMES * UdpMsg.SAMPLE_BYTES + UdpMsg.TAG, full.size)
        assertTrue(full.size <= 1232)
    }

    @Test fun nackWithoutSeqsMatchesGoldenBytes() {
        assertEquals(nackN0Hex, hexOf(Wire.nack(16909060, 3, IntArray(0), key)))
        val m = Wire.decode(bytesOf(nackN0Hex), bytesOf(nackN0Hex).size, key) as UdpMsg.Nack
        assertEquals(3, m.epoch); assertEquals(0, m.seqs.size)
    }

    @Test fun nackWithOneSeqMatchesGoldenBytes() {
        assertEquals(nackN1Hex, hexOf(Wire.nack(16909060, 3, intArrayOf(10), key)))
        val m = Wire.decode(bytesOf(nackN1Hex), bytesOf(nackN1Hex).size, key) as UdpMsg.Nack
        assertEquals(listOf(10), m.seqs.toList())
    }

    @Test fun nackWithSixtyFourSeqsMatchesGoldenBytes() {
        val seqs = IntArray(64) { 1000 + it }
        assertEquals(nackN64Hex, hexOf(Wire.nack(16909060, 3, seqs, key)))
        val m = Wire.decode(bytesOf(nackN64Hex), bytesOf(nackN64Hex).size, key) as UdpMsg.Nack
        assertEquals(seqs.toList(), m.seqs.toList())
    }

    @Test fun nackIsTruncatedToMax() {
        assertEquals(UdpMsg.HEADER + 6 + UdpMsg.MAX_NACK * 4 + UdpMsg.TAG, Wire.nack(1, 1, IntArray(65) { it }, key).size)
    }

    @Test fun truncatedForeignAndForgedPacketsAreRejected() {
        val audio = bytesOf(audioFlag0Hex)
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
        // без тега (формат той же длины минус тег)
        assertNull(Wire.decode(audio, audio.size - UdpMsg.TAG, key))
    }

    @Test fun versionTwoPacketIsRejectedEvenWithValidTag() {
        // тег пересчитан правильно, отвергает только байт версии: пакеты версии 2 не принимаются
        val body = bytesOf(pingHex).copyOf(bytesOf(pingHex).size - UdpMsg.TAG)
        body[2] = 2
        val v2 = retag(body, key)
        assertNull(Wire.decode(v2, v2.size, key))
        body[2] = 3
        val v3 = retag(body, key)
        assertNotNull(Wire.decode(v3, v3.size, key))
    }

    @Test fun audioWithCountAboveMaxIsRejectedEvenWithValidTag() {
        // count = 385 при корректном теге: декодер обязан отвергнуть по count, а не по тегу
        val full = bytesOf(audioFull384Hex)
        val body = full.copyOf(full.size - UdpMsg.TAG + 3) // +1 кадр (3 нулевых байта), чтобы длина не отвергла раньше
        body[40] = 0x01; body[41] = 0x81.toByte() // count = 385 (u16 big-endian на смещении AUDIO_HEADER − 4)
        val pkt = retag(body, key)
        assertNull(Wire.decode(pkt, pkt.size, key))
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
