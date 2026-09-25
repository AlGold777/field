package app.soundfield.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Бинарный UDP-протокол медиаконтура (big-endian). Описание — README, раздел «Протокол».
 *
 * Заголовок (8 байт): 'S' 'F' | version u8 | type u8 | sessionId u32
 *  AUDIO (1): epoch i32 | seq i32 | startFrame i64 | playoutNs i64 | sendNs i64 | count u16 | flags u8 | 0 u8 | count × i16
 *  PING  (2): t1 i64 | pingSeq i32
 *  PONG  (3): t1 i64 | t2 i64 | t3 i64 | pingSeq i32
 *  NACK  (4): epoch i32 | n u16 | n × seq i32
 *
 * playoutNs — момент (по монотонным часам Server), когда кадр startFrame должен прозвучать.
 * sendNs — момент отправки (часы Server), нужен Point для оценки jitter.
 */
sealed class UdpMsg {
    abstract val session: Int

    class Audio(
        override val session: Int, val epoch: Int, val seq: Int, val startFrame: Long,
        val playoutNs: Long, val sendNs: Long, val flags: Int, val samples: ShortArray, val count: Int,
    ) : UdpMsg() { val isRetransmit get() = flags and FLAG_RETRANSMIT != 0 }

    class Ping(override val session: Int, val t1: Long, val pingSeq: Int) : UdpMsg()
    class Pong(override val session: Int, val t1: Long, val t2: Long, val t3: Long, val pingSeq: Int) : UdpMsg()
    class Nack(override val session: Int, val epoch: Int, val seqs: IntArray) : UdpMsg()

    companion object {
        const val FLAG_RETRANSMIT = 1
        const val T_AUDIO = 1; const val T_PING = 2; const val T_PONG = 3; const val T_NACK = 4
        const val HEADER = 8
        const val AUDIO_HEADER = HEADER + 36
        const val MAX_NACK = 64
    }
}

object Wire {
    private fun header(b: ByteBuffer, type: Int, session: Int) {
        b.put('S'.code.toByte()); b.put('F'.code.toByte()); b.put(SF.PROTOCOL_VERSION.toByte())
        b.put(type.toByte()); b.putInt(session)
    }

    fun audio(session: Int, epoch: Int, seq: Int, startFrame: Long, playoutNs: Long, sendNs: Long,
              flags: Int, samples: ShortArray, count: Int): ByteArray {
        val b = ByteBuffer.allocate(UdpMsg.AUDIO_HEADER + count * 2).order(ByteOrder.BIG_ENDIAN)
        header(b, UdpMsg.T_AUDIO, session)
        b.putInt(epoch); b.putInt(seq); b.putLong(startFrame); b.putLong(playoutNs); b.putLong(sendNs)
        b.putShort(count.toShort()); b.put(flags.toByte()); b.put(0)
        for (i in 0 until count) b.putShort(samples[i])
        return b.array()
    }

    fun ping(session: Int, t1: Long, pingSeq: Int): ByteArray {
        val b = ByteBuffer.allocate(UdpMsg.HEADER + 12)
        header(b, UdpMsg.T_PING, session); b.putLong(t1); b.putInt(pingSeq)
        return b.array()
    }

    fun pong(session: Int, t1: Long, t2: Long, t3: Long, pingSeq: Int): ByteArray {
        val b = ByteBuffer.allocate(UdpMsg.HEADER + 28)
        header(b, UdpMsg.T_PONG, session); b.putLong(t1); b.putLong(t2); b.putLong(t3); b.putInt(pingSeq)
        return b.array()
    }

    fun nack(session: Int, epoch: Int, seqs: IntArray): ByteArray {
        val n = minOf(seqs.size, UdpMsg.MAX_NACK)
        val b = ByteBuffer.allocate(UdpMsg.HEADER + 6 + n * 4)
        header(b, UdpMsg.T_NACK, session); b.putInt(epoch); b.putShort(n.toShort())
        for (i in 0 until n) b.putInt(seqs[i])
        return b.array()
    }

    /** Возвращает null для чужих/битых пакетов. */
    fun decode(data: ByteArray, length: Int): UdpMsg? {
        if (length < UdpMsg.HEADER) return null
        val b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (b.get() != 'S'.code.toByte() || b.get() != 'F'.code.toByte()) return null
        if (b.get().toInt() != SF.PROTOCOL_VERSION) return null
        val type = b.get().toInt()
        val session = b.getInt()
        return try {
            when (type) {
                UdpMsg.T_AUDIO -> {
                    if (length < UdpMsg.AUDIO_HEADER) return null
                    val epoch = b.getInt(); val seq = b.getInt(); val start = b.getLong()
                    val playout = b.getLong(); val send = b.getLong()
                    val count = b.getShort().toInt() and 0xFFFF
                    val flags = b.get().toInt() and 0xFF; b.get()
                    if (length < UdpMsg.AUDIO_HEADER + count * 2) return null
                    val s = ShortArray(count) { b.getShort() }
                    UdpMsg.Audio(session, epoch, seq, start, playout, send, flags, s, count)
                }
                UdpMsg.T_PING -> UdpMsg.Ping(session, b.getLong(), b.getInt())
                UdpMsg.T_PONG -> UdpMsg.Pong(session, b.getLong(), b.getLong(), b.getLong(), b.getInt())
                UdpMsg.T_NACK -> {
                    val epoch = b.getInt(); val n = b.getShort().toInt() and 0xFFFF
                    if (n > UdpMsg.MAX_NACK || length < UdpMsg.HEADER + 6 + n * 4) return null
                    UdpMsg.Nack(session, epoch, IntArray(n) { b.getInt() })
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }
}
