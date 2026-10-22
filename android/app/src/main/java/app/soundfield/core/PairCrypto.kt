package app.soundfield.core

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Криптография протокола v2 (README, «Протокол»). Только HMAC-SHA256 и X25519 (RFC 7748);
 * X25519 реализован здесь, т.к. KeyAgreement("X25519") в Android доступен с API 33, а minSdk = 26.
 */
object X25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)

    private fun decodeLe(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
    private fun encodeLe(v: BigInteger): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until 32) { val idx = be.size - 1 - i; if (idx >= 0) out[i] = be[idx] }
        return out
    }

    fun scalarMult(k: ByteArray, u: ByteArray): ByteArray {
        require(k.size == 32 && u.size == 32)
        val kc = k.copyOf()
        kc[0] = (kc[0].toInt() and 248).toByte()
        kc[31] = ((kc[31].toInt() and 127) or 64).toByte()
        val uc = u.copyOf(); uc[31] = (uc[31].toInt() and 127).toByte()
        val x1 = decodeLe(uc).mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO; var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = (kc[t ushr 3].toInt() ushr (t and 7)) and 1
            if (swap xor kt == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            val s = da.add(cb).mod(P); val dd = da.subtract(cb).mod(P)
            x3 = s.multiply(s).mod(P)
            z3 = x1.multiply(dd.multiply(dd).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        return encodeLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P))
    }

    private val BASE = ByteArray(32).also { it[0] = 9 }
    fun publicKey(priv: ByteArray): ByteArray = scalarMult(priv, BASE)
}

object PairCrypto {
    const val TAG_BYTES = 8
    private val rnd = SecureRandom()
    private val macs = ThreadLocal.withInitial { Mac.getInstance("HmacSHA256") }

    fun random(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    fun unhex(s: String): ByteArray? =
        if (s.length % 2 != 0 || !s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) null
        else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun hmac(key: ByteArray, data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray {
        val mac = macs.get()!!
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(data, off, len)
        return mac.doFinal()
    }

    /** MAC над частями, каждая с префиксом длины u16 BE: разные разбиения не дают одинаковый вход. */
    fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) { out.write(p.size ushr 8); out.write(p.size and 0xFF); out.write(p) }
        return hmac(key, out.toByteArray())
    }

    fun mac(key: ByteArray, vararg parts: String): ByteArray = mac(key, *parts.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray())

    fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    class Ephemeral(val priv: ByteArray, val pub: ByteArray)
    fun ephemeral(): Ephemeral { val p = random(32); return Ephemeral(p, X25519.publicKey(p)) }

    /** Ключ пары (32 байта) из X25519 с привязкой к обоим открытым ключам. */
    fun pairKey(priv: ByteArray, theirPub: ByteArray, inviterPub: ByteArray, acceptorPub: ByteArray): ByteArray? {
        val shared = X25519.scalarMult(priv, theirPub)
        if (shared.all { it == 0.toByte() }) return null
        return mac(shared, "sf-pair-v2".toByteArray(), inviterPub, acceptorPub)
    }

    /** Ключ UDP-контура: свой для каждого управляющего соединения (контекст — nonce/открытые ключи рукопожатия). */
    fun udpKey(pairKey: ByteArray, a: ByteArray, b: ByteArray): ByteArray = mac(pairKey, "sf-udp-v2".toByteArray(), a, b)

    fun proof(pairKey: ByteArray, label: String, vararg ctx: ByteArray): ByteArray = mac(pairKey, label.toByteArray(), *ctx)
}
