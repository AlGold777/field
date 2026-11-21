package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test

/**
 * Золотые векторы протокола v3. Читаются с диска из tools/protocol_vectors.json (тот же файл — источник для
 * Swift-проверки tools/check_protocol_vectors.swift); путь передаёт build.gradle.kts через свойство sf.protocol.vectors.
 */
class WireVectorsTest {
    /** Один вектор: плоский JSON-объект, значения — строки или целые числа. */
    private class Vec(private val f: Map<String, String>) {
        fun s(k: String): String = f[k] ?: error("в векторе нет поля $k")
        fun i(k: String): Int = s(k).toInt()
        fun l(k: String): Long = s(k).toLong()
        fun ints(k: String): IntArray = s(k).let { if (it.isEmpty()) IntArray(0) else it.split(",").map(String::toInt).toIntArray() }
    }

    /** Минимальный разбор: файл — массив плоских объектов по одному на строку (org.json в unit-тестах недоступен). */
    private fun loadVectors(): Map<String, Vec> {
        val path = System.getProperty("sf.protocol.vectors") ?: error("не задано sf.protocol.vectors")
        val field = Regex(""""(\w+)":(?:"([^"]*)"|(-?\d+))""")
        val out = LinkedHashMap<String, Vec>()
        for (line in java.io.File(path).readLines()) {
            if (!line.trimStart().startsWith("{")) continue
            val f = field.findAll(line).associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3]) }
            out[f.getValue("name")] = Vec(f)
        }
        return out
    }

    private val vectors = loadVectors()

    private fun audioOf(name: String, flags: Int, smp: IntArray): ByteArray = vec(name).let {
        Wire.audio(it.i("session"), it.i("epoch"), it.i("seq"), it.l("startFrame"), it.l("playoutNs"), it.l("sendNs"), flags, smp, smp.size, key)
    }
    private fun nackOf(name: String): ByteArray = vec(name).let { Wire.nack(it.i("session"), it.i("epoch"), it.ints("seqs"), key) }
    private fun vec(name: String): Vec = vectors[name] ?: error("нет вектора $name")

    private fun bytesOf(hex: String): ByteArray = PairCrypto.unhex(hex)!!
    private fun hexOf(b: ByteArray): String = PairCrypto.hex(b)

    /** Пересчитывает тег над телом пакета (тело — всё до последних TAG байт). */
    private fun retag(body: ByteArray, key: ByteArray): ByteArray =
        body + PairCrypto.hmac(key, body, 0, body.size).copyOf(UdpMsg.TAG)

    private val key = bytesOf(vec("ping").s("key"))
    private val pingHex = vec("ping").s("hex")
    private val pongHex = vec("pong").s("hex")
    private val audioFlag0Hex = vec("audio_flag0").s("hex")
    private val audioFlag1Hex = vec("audio_flag1").s("hex")
    private val nackN0Hex = vec("nack_n0").s("hex")
    private val nackN1Hex = vec("nack_n1").s("hex")
    private val nackN64Hex = vec("nack_n64").s("hex")
    private val audioFull384Hex = vec("audio_full_384").s("hex")
    private val samples = vec("audio_flag0").ints("samples")
    private val samples384 = vec("audio_full_384").ints("samples")

    @Test fun pingEncodesGoldenBytesAndDecodesFields() {
        assertEquals(pingHex, hexOf(vec("ping").let { Wire.ping(it.i("session"), it.l("t1"), it.i("pingSeq"), key) }))
        val m = Wire.decode(bytesOf(pingHex), bytesOf(pingHex).size, key) as UdpMsg.Ping
        vec("ping").let { assertEquals(it.i("session"), m.session); assertEquals(it.l("t1"), m.t1); assertEquals(it.i("pingSeq"), m.pingSeq) }
    }

    @Test fun pongEncodesGoldenBytesAndDecodesFields() {
        assertEquals(pongHex, hexOf(vec("pong").let { Wire.pong(it.i("session"), it.l("t1"), it.l("t2"), it.l("t3"), it.i("pingSeq"), key) }))
        val m = Wire.decode(bytesOf(pongHex), bytesOf(pongHex).size, key) as UdpMsg.Pong
        vec("pong").let { assertEquals(it.l("t1"), m.t1); assertEquals(it.l("t2"), m.t2); assertEquals(it.l("t3"), m.t3); assertEquals(it.i("pingSeq"), m.pingSeq) }
    }

    @Test fun audioWithoutRetransmitFlagMatchesGoldenBytes() {
        val b = audioOf("audio_flag0", 0, samples)
        assertEquals(audioFlag0Hex, hexOf(b))
        val m = Wire.decode(bytesOf(audioFlag0Hex), bytesOf(audioFlag0Hex).size, key) as UdpMsg.Audio
        assertFalse(m.isRetransmit)
        vec("audio_flag0").let { assertEquals(it.i("session"), m.session); assertEquals(it.i("epoch"), m.epoch); assertEquals(it.i("seq"), m.seq) }
        vec("audio_flag0").let { assertEquals(it.l("startFrame"), m.startFrame); assertEquals(it.l("playoutNs"), m.playoutNs); assertEquals(it.l("sendNs"), m.sendNs) }
        assertEquals(samples.toList(), m.samples.take(m.count))
    }

    @Test fun audioWithRetransmitFlagMatchesGoldenBytes() {
        val b = audioOf("audio_flag1", UdpMsg.FLAG_RETRANSMIT, samples)
        assertEquals(audioFlag1Hex, hexOf(b))
        val m = Wire.decode(bytesOf(audioFlag1Hex), bytesOf(audioFlag1Hex).size, key) as UdpMsg.Audio
        assertTrue(m.isRetransmit)
    }

    @Test fun audioFullPacketMatchesGoldenBytesAndRoundTrips() {
        val b = audioOf("audio_full_384", 0, samples384)
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
        assertEquals(nackN0Hex, hexOf(nackOf("nack_n0")))
        val m = Wire.decode(bytesOf(nackN0Hex), bytesOf(nackN0Hex).size, key) as UdpMsg.Nack
        assertEquals(vec("nack_n0").i("epoch"), m.epoch); assertEquals(0, m.seqs.size)
    }

    @Test fun nackWithOneSeqMatchesGoldenBytes() {
        assertEquals(nackN1Hex, hexOf(nackOf("nack_n1")))
        val m = Wire.decode(bytesOf(nackN1Hex), bytesOf(nackN1Hex).size, key) as UdpMsg.Nack
        assertEquals(vec("nack_n1").ints("seqs").toList(), m.seqs.toList())
    }

    @Test fun nackWithSixtyFourSeqsMatchesGoldenBytes() {
        val seqs = vec("nack_n64").ints("seqs")
        assertEquals(64, seqs.size)
        assertEquals(nackN64Hex, hexOf(nackOf("nack_n64")))
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
        val v = vec("x25519_rfc7748")
        val aPriv = bytesOf(v.s("privA")); val aPub = bytesOf(v.s("pubA"))
        val bPriv = bytesOf(v.s("privB")); val bPub = bytesOf(v.s("pubB"))
        val shared = v.s("shared")
        assertEquals(hexOf(aPub), hexOf(X25519.publicKey(aPriv)))
        assertEquals(hexOf(bPub), hexOf(X25519.publicKey(bPriv)))
        assertEquals(shared, hexOf(X25519.scalarMult(aPriv, bPub)))
        assertEquals(shared, hexOf(X25519.scalarMult(bPriv, aPub)))
    }

    @Test fun kdfAndProofsMatchGoldenBytes() {
        val v = vec("kdf_pair")
        val aPub = bytesOf(v.s("pubA")); val bPub = bytesOf(v.s("pubB"))
        val pairKey = PairCrypto.mac(bytesOf(v.s("shared")), "sf-pair-v2".toByteArray(), aPub, bPub)
        assertEquals(v.s("pairKey"), hexOf(pairKey))
        assertEquals(v.s("udpKey"), hexOf(PairCrypto.udpKey(pairKey, bytesOf(v.s("nonceA")), bytesOf(v.s("nonceB")))))
        assertEquals(v.s("acceptProof"), hexOf(PairCrypto.proof(pairKey, "sf-accept", v.s("idA").toByteArray(), v.s("idB").toByteArray(), aPub, bPub)))
    }
}
