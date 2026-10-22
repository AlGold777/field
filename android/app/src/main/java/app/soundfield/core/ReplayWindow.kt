package app.soundfield.core

/**
 * Защита UDP-контура от повтора подлинных пакетов (HMAC сам повтор не отсекает).
 * Оба класса сбрасываются при смене udpKey (новое управляющее соединение).
 */

/** Server: PING принимается, только если pingSeq строго больше последнего принятого. */
class PingReplayGate {
    private var last = 0
    private var any = false

    @Synchronized fun accept(pingSeq: Int): Boolean {
        if (any && pingSeq <= last) return false
        any = true; last = pingSeq
        return true
    }

    @Synchronized fun reset() { any = false; last = 0 }
}

/** Point: PONG принимается один раз и только для pingSeq, отправленного в этой сессии (окно последних [size]). */
class PongWindow(private val size: Int = 64) {
    private val outstanding = ArrayDeque<Int>()

    @Synchronized fun sent(pingSeq: Int) {
        outstanding.addLast(pingSeq)
        while (outstanding.size > size) outstanding.removeFirst()
    }

    @Synchronized fun accept(pingSeq: Int): Boolean = outstanding.remove(pingSeq)

    @Synchronized fun reset() = outstanding.clear()
}

/** Проверки доказательств рукопожатия (README, «Аутентификация»): одно место для Engine и тестов. */
object HandshakeAuth {
    fun acceptProof(pairKey: ByteArray, inviterId: String, acceptorId: String, inviterPub: ByteArray, acceptorPub: ByteArray) =
        PairCrypto.proof(pairKey, "sf-accept", inviterId.toByteArray(), acceptorId.toByteArray(), inviterPub, acceptorPub)

    fun resumeServerProof(key: ByteArray, cn: ByteArray, sn: ByteArray, initiatorId: String, responderId: String) =
        PairCrypto.proof(key, "sf-resume-s", cn, sn, initiatorId.toByteArray(), responderId.toByteArray())

    fun resumeClientProof(key: ByteArray, cn: ByteArray, sn: ByteArray, initiatorId: String, responderId: String) =
        PairCrypto.proof(key, "sf-resume-c", cn, sn, initiatorId.toByteArray(), responderId.toByteArray())

    /** Сравнение hex-строки с ожидаемым доказательством; null/мусор/неверная длина — отказ. */
    fun matches(proofHex: String, expected: ByteArray): Boolean {
        val p = PairCrypto.unhex(proofHex) ?: return false
        return PairCrypto.equal(p, expected)
    }
}
