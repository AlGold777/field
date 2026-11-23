package app.soundfield.audio.mp3

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Источник байт с произвольным доступом (файл, массив в тестах). Позиционные чтения, без общего курсора. */
interface ByteSource {
    val size: Long

    /** Читает до [length] байт с [position] в [dst]; возвращает число прочитанных или -1 за концом данных. */
    fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int
}

/** Источник поверх массива — для тестов. */
class ByteArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()

    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (position < 0 || position >= data.size) return -1
        val n = minOf(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), dst, offset, n)
        return n
    }
}

/**
 * Источник поверх [FileChannel] (например `FileInputStream(pfd.fileDescriptor).channel`).
 * Позиционные чтения (курсор канала не двигается). Окно-буфер: при прыжке вперёд на длину кадра
 * читается небольшое окно (проход по заголовкам не читает весь файл), при последовательном
 * чтении окно растёт до [bufferSize]. Большие чтения идут напрямую. Потокобезопасен (синхронизирован).
 * Канал закрывает владелец.
 */
class ChannelByteSource(private val channel: FileChannel, private val bufferSize: Int = 64 * 1024) : ByteSource {
    override val size: Long = channel.size()

    private val buf = ByteArray(bufferSize)
    private var winStart = 0L
    private var winEnd = 0L

    @Synchronized
    override fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (position < 0 || position >= size) return -1
        val n = minOf(length.toLong(), size - position).toInt()
        var done = 0
        while (done < n) {
            val p = position + done
            if (p in winStart until winEnd) {
                val c = minOf((winEnd - p).toInt(), n - done)
                System.arraycopy(buf, (p - winStart).toInt(), dst, offset + done, c)
                done += c
            } else if (n - done >= bufferSize) {
                val r = channel.read(ByteBuffer.wrap(dst, offset + done, n - done), p)
                if (r <= 0) break
                done += r
            } else if (!fill(p)) break
        }
        return if (done == 0) -1 else done
    }

    /** Заполняет окно с [p]: после последовательного чтения — целиком, после прыжка — малым окном. */
    private fun fill(p: Long): Boolean {
        val want = if (p == winEnd && winEnd > winStart) bufferSize else minOf(bufferSize, SMALL_WINDOW)
        val bb = ByteBuffer.wrap(buf, 0, want)
        var got = 0
        while (got < want) {
            val r = channel.read(bb, p + got)
            if (r <= 0) break
            got += r
        }
        if (got <= 0) { winStart = 0; winEnd = 0; return false }
        winStart = p
        winEnd = p + got
        return true
    }

    private companion object { const val SMALL_WINDOW = 1024 }
}
