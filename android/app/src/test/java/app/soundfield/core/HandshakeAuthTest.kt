package app.soundfield.core

import org.junit.Assert.*
import org.junit.Test

class HandshakeAuthTest {
    private fun hex(b: ByteArray) = PairCrypto.hex(b)

    @Test fun inviteHandshakeAgreesAndRejectsWrongProof() {
        val a = PairCrypto.ephemeral(); val b = PairCrypto.ephemeral()
        val keyA = PairCrypto.pairKey(a.priv, b.pub, a.pub, b.pub)!!
        val keyB = PairCrypto.pairKey(b.priv, a.pub, a.pub, b.pub)!!
        assertArrayEquals(keyA, keyB)
        val proof = HandshakeAuth.acceptProof(keyB, "id-A", "id-B", a.pub, b.pub)
        assertTrue(HandshakeAuth.matches(hex(proof), HandshakeAuth.acceptProof(keyA, "id-A", "id-B", a.pub, b.pub)))
        val expected = HandshakeAuth.acceptProof(keyA, "id-A", "id-B", a.pub, b.pub)
        // чужой ключ (MITM подставил свой epk), другие id, мусор, пустая и усечённая строки
        val m = PairCrypto.ephemeral()
        val keyM = PairCrypto.pairKey(m.priv, a.pub, a.pub, m.pub)!!
        assertFalse(HandshakeAuth.matches(hex(HandshakeAuth.acceptProof(keyM, "id-A", "id-B", a.pub, b.pub)), expected))
        assertFalse(HandshakeAuth.matches(hex(HandshakeAuth.acceptProof(keyB, "id-A", "id-X", a.pub, b.pub)), expected))
        assertFalse(HandshakeAuth.matches("zz", expected))
        assertFalse(HandshakeAuth.matches("", expected))
        assertFalse(HandshakeAuth.matches(hex(proof).dropLast(2), expected))
    }

    @Test fun resumeProofsRejectWrongKeyNonceAndRoleSwap() {
        val key = PairCrypto.random(32); val other = PairCrypto.random(32)
        val cn = PairCrypto.random(16); val sn = PairCrypto.random(16)
        val server = HandshakeAuth.resumeServerProof(key, cn, sn, "cli", "srv")
        val client = HandshakeAuth.resumeClientProof(key, cn, sn, "cli", "srv")
        assertTrue(HandshakeAuth.matches(hex(client), HandshakeAuth.resumeClientProof(key, cn, sn, "cli", "srv")))
        assertFalse("неверный ключ пары", HandshakeAuth.matches(hex(HandshakeAuth.resumeClientProof(other, cn, sn, "cli", "srv")), client))
        assertFalse("другой nonce (повтор старого auth)", HandshakeAuth.matches(hex(client), HandshakeAuth.resumeClientProof(key, cn, PairCrypto.random(16), "cli", "srv")))
        assertFalse("отражение: proof сервера вместо proof клиента", HandshakeAuth.matches(hex(server), client))
        assertFalse(HandshakeAuth.matches("", client))
    }
}
