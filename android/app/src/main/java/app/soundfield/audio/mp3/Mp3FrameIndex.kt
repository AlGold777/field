package app.soundfield.audio.mp3

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Индекс кадров MP3: смещение и размер каждого аудиокадра, параметры потока и gapless-данные LAME.
 * Строится одним проходом по заголовкам ([build]), читает только заголовки, а не весь файл.
 * Служебный кадр Xing/Info/VBRI в индекс не входит. Иммутабелен, потокобезопасен.
 */
class Mp3FrameIndex internal constructor(
    val sampleRate: Int,
    val channels: Int,
    val samplesPerFrame: Int,
    /** Задержка кодера из LAME-тега (0, если тега нет). Задержку декодера (~529) не включает. */
    val encoderDelay: Int,
    /** Добавочная тишина в конце из LAME-тега (0, если тега нет). */
    val encoderPadding: Int,
    private val offsets: LongArray,
    private val sizes: ShortArray,
    /** Размер файла на момент построения — для проверки актуальности сохранённого индекса. */
    val sourceSize: Long,
) {
    val frameCount: Int get() = offsets.size

    /** Длина выходного потока в сэмплах (на канал) без задержки и хвоста кодера. */
    val totalSamples: Long
        get() = (frameCount.toLong() * samplesPerFrame - encoderDelay - encoderPadding).coerceAtLeast(0)

    fun frameOffset(i: Int): Long = offsets[i]
    fun frameSize(i: Int): Int = sizes[i].toInt() and 0xFFFF

    /**
     * Номер кадра, содержащего выходной сэмпл [sample] (отсчёт с начала звука, после обрезки задержки).
     * [extraDelay] — дополнительная задержка декодера, если вызывающий её учитывает. Результат зажат в [0, frameCount-1].
     */
    fun frameForSample(sample: Long, extraDelay: Int = 0): Int {
        val pos = sample + encoderDelay + extraDelay
        if (pos <= 0) return 0
        return (pos / samplesPerFrame).coerceAtMost(frameCount - 1L).toInt()
    }

    /** Сохраняет индекс компактно: смещения — разрывами между кадрами (varint), размеры — по 2 байта. */
    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(FORMAT_VERSION)
        d.writeInt(sampleRate); d.writeInt(channels); d.writeInt(samplesPerFrame)
        d.writeInt(encoderDelay); d.writeInt(encoderPadding)
        d.writeLong(sourceSize)
        d.writeInt(frameCount)
        var prevEnd = 0L
        for (i in 0 until frameCount) {
            writeVarLong(d, offsets[i] - prevEnd)
            d.writeShort(sizes[i].toInt())
            prevEnd = offsets[i] + frameSize(i)
        }
        d.flush()
    }

    companion object {
        private const val MAGIC = 0x53464D49 // "SFMI"
        const val FORMAT_VERSION = 1

        /** Задержка декодера MP3 (сэмплов), типичная для декодеров на базе mpg123/libmad; в индекс не входит. */
        const val DECODER_DELAY_SAMPLES = 529

        /** Читает индекс; null — чужой формат, другая версия, повреждение. */
        fun read(input: InputStream): Mp3FrameIndex? = try {
            val d = DataInputStream(input)
            if (d.readInt() != MAGIC || d.readInt() != FORMAT_VERSION) null else {
                val sr = d.readInt(); val ch = d.readInt(); val spf = d.readInt()
                val delay = d.readInt(); val pad = d.readInt()
                val srcSize = d.readLong()
                val n = d.readInt()
                if (sr <= 0 || ch !in 1..2 || spf <= 0 || delay < 0 || pad < 0 || n <= 0 || n > 50_000_000) null else {
                    val offs = LongArray(n)
                    val sz = ShortArray(n)
                    var prevEnd = 0L
                    var ok = true
                    for (i in 0 until n) {
                        val off = prevEnd + readVarLong(d)
                        val s = d.readShort()
                        if (off < 0 || s.toInt() == 0) { ok = false; break }
                        offs[i] = off; sz[i] = s
                        prevEnd = off + (s.toInt() and 0xFFFF)
                    }
                    if (ok) Mp3FrameIndex(sr, ch, spf, delay, pad, offs, sz, srcSize) else null
                }
            }
        } catch (_: IOException) { null }

        private fun writeVarLong(d: DataOutputStream, value: Long) {
            var v = value
            while (v >= 0x80) { d.writeByte(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            d.writeByte(v.toInt())
        }

        private fun readVarLong(d: DataInputStream): Long {
            var result = 0L
            var shift = 0
            while (shift < 63) {
                val b = d.readUnsignedByte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
            throw IOException("bad varint")
        }

        /**
         * Строит индекс. null — не MP3 или нет валидных кадров (или [cancelled] вернул true).
         * Ложная синхронизация отсекается: кадр после мусора принимается, только если за ним
         * идёт заголовок с той же версией/слоем/частотой (или конец файла/тег).
         */
        fun build(src: ByteSource, cancelled: () -> Boolean = { false }): Mp3FrameIndex? =
            Builder(src, cancelled).run()
    }
}

private class Hdr(
    val mpeg: Int, val layer: Int, val sampleRate: Int, val channels: Int,
    val samplesPerFrame: Int, val length: Int,
) {
    fun sameStream(o: Hdr) = mpeg == o.mpeg && layer == o.layer && sampleRate == o.sampleRate
}

private class Builder(val src: ByteSource, val cancelled: () -> Boolean) {
    val size = src.size
    val tmp = ByteArray(8)
    var offs = LongArray(1024)
    var sizes = ShortArray(1024)
    var n = 0

    fun run(): Mp3FrameIndex? {
        var pos = skipId3(0)
        var ref: Hdr? = null
        var chain = false // предыдущий кадр принят и мы стоим точно за ним
        var delay = 0
        var padding = 0
        var iter = 0
        while (pos + 4 <= size) {
            if ((++iter and 0x3FF) == 0 && cancelled()) return null
            val h = headerAt(pos)
            if (h == null || (ref != null && !h.sameStream(ref)) || (!chain && !strongValid(pos, h, ref))) {
                pos++; chain = false; continue
            }
            if (pos + h.length > size) break // обрезанный последний кадр
            if (ref == null) {
                ref = h
                val x = parseXing(pos, h)
                if (x != null) { // служебный кадр: не аудио
                    delay = x.first; padding = x.second
                    pos += h.length; chain = true
                    continue
                }
            }
            if (n == offs.size) { offs = offs.copyOf(n * 2); sizes = sizes.copyOf(n * 2) }
            offs[n] = pos; sizes[n] = h.length.toShort(); n++
            pos += h.length
            chain = true
        }
        if (ref == null || n == 0) return null
        if (delay + padding > n.toLong() * ref.samplesPerFrame) { delay = 0; padding = 0 }
        return Mp3FrameIndex(ref.sampleRate, ref.channels, ref.samplesPerFrame, delay, padding,
            offs.copyOf(n), sizes.copyOf(n), size)
    }

    fun readAt(pos: Long, len: Int, dst: ByteArray = tmp): Int {
        var got = 0
        while (got < len) {
            val r = src.read(pos + got, dst, got, len - got)
            if (r <= 0) break
            got += r
        }
        return got
    }

    fun headerAt(pos: Long): Hdr? {
        if (readAt(pos, 4) < 4) return null
        return parseHeader(tmp[0].toInt() and 0xFF, tmp[1].toInt() and 0xFF, tmp[2].toInt() and 0xFF, tmp[3].toInt() and 0xFF)
    }

    /** Кадр в начале/после мусора: за ним конец файла, тег или заголовок того же потока. */
    fun strongValid(pos: Long, h: Hdr, ref: Hdr?): Boolean {
        val end = pos + h.length
        if (end > size) return false
        if (end == size) return true
        if (tagAt(end)) return true
        val h2 = headerAt(end) ?: return false
        return h2.sameStream(h) && (ref == null || h2.sameStream(ref))
    }

    fun tagAt(pos: Long): Boolean {
        val got = readAt(pos, 8)
        if (got >= 3 && tmp[0] == 'I'.code.toByte() && tmp[1] == 'D'.code.toByte() && tmp[2] == '3'.code.toByte()) return true
        if (got >= 3 && tmp[0] == 'T'.code.toByte() && tmp[1] == 'A'.code.toByte() && tmp[2] == 'G'.code.toByte()) return true
        return got == 8 && String(tmp, Charsets.ISO_8859_1) == "APETAGEX"
    }

    /** Пропуск ID3v2 в начале (в т.ч. нескольких подряд), с учётом footer. */
    fun skipId3(start: Long): Long {
        var pos = start
        val hb = ByteArray(10)
        while (readAt(pos, 10, hb) == 10 && hb[0] == 'I'.code.toByte() && hb[1] == 'D'.code.toByte() && hb[2] == '3'.code.toByte()) {
            val sz = ((hb[6].toInt() and 0x7F) shl 21) or ((hb[7].toInt() and 0x7F) shl 14) or
                ((hb[8].toInt() and 0x7F) shl 7) or (hb[9].toInt() and 0x7F)
            val footer = if ((hb[5].toInt() and 0x10) != 0) 10 else 0
            pos += 10L + sz + footer
        }
        return pos
    }

    /** Если кадр — Xing/Info/VBRI, возвращает (delay, padding) из LAME-тега (0,0 если его нет); иначе null. */
    fun parseXing(pos: Long, h: Hdr): Pair<Int, Int>? {
        if (h.layer != 3) return null
        val len = minOf(h.length, 512)
        val b = ByteArray(len)
        if (readAt(pos, len, b) < len) return null
        fun id(at: Int, s: String) = at + s.length <= len && String(b, at, s.length, Charsets.ISO_8859_1) == s
        if (id(36, "VBRI")) return 0 to 0
        val side = if (h.mpeg == 1) (if (h.channels == 1) 17 else 32) else (if (h.channels == 1) 9 else 17)
        for (crc in intArrayOf(0, 2)) {
            val t = 4 + side + crc
            if (!(id(t, "Xing") || id(t, "Info"))) continue
            if (t + 8 > len) return 0 to 0
            val flags = ((b[t + 4].toInt() and 0xFF) shl 24) or ((b[t + 5].toInt() and 0xFF) shl 16) or
                ((b[t + 6].toInt() and 0xFF) shl 8) or (b[t + 7].toInt() and 0xFF)
            var lame = t + 8
            if (flags and 1 != 0) lame += 4
            if (flags and 2 != 0) lame += 4
            if (flags and 4 != 0) lame += 100
            if (flags and 8 != 0) lame += 4
            if (lame + 24 <= len && (id(lame, "LAME") || id(lame, "Lavf") || id(lame, "Lavc"))) {
                val d0 = b[lame + 21].toInt() and 0xFF
                val d1 = b[lame + 22].toInt() and 0xFF
                val d2 = b[lame + 23].toInt() and 0xFF
                return ((d0 shl 4) or (d1 shr 4)) to (((d1 and 0x0F) shl 8) or d2)
            }
            return 0 to 0
        }
        return null
    }
}

private val BITRATES_V1 = arrayOf(
    intArrayOf(32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
    intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
    intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
)
private val BITRATES_V2 = arrayOf(
    intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
    intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
)
private val SAMPLE_RATES = arrayOf(
    intArrayOf(44100, 48000, 32000), // MPEG-1
    intArrayOf(22050, 24000, 16000), // MPEG-2
    intArrayOf(11025, 12000, 8000),  // MPEG-2.5
)

/** Разбор 4-байтового заголовка кадра; null — не заголовок (free-format не поддерживается). */
private fun parseHeader(b0: Int, b1: Int, b2: Int, b3: Int): Hdr? {
    if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
    val vb = (b1 shr 3) and 3
    if (vb == 1) return null
    val mpeg = when (vb) { 3 -> 1; 2 -> 2; else -> 25 }
    val lb = (b1 shr 1) and 3
    if (lb == 0) return null
    val layer = 4 - lb
    val bi = (b2 shr 4) and 15
    if (bi == 0 || bi == 15) return null
    val si = (b2 shr 2) and 3
    if (si == 3) return null
    val pad = (b2 shr 1) and 1
    val br = (if (mpeg == 1) BITRATES_V1 else BITRATES_V2)[layer - 1][bi - 1] * 1000
    val sr = SAMPLE_RATES[if (mpeg == 1) 0 else if (mpeg == 2) 1 else 2][si]
    val len = when (layer) {
        1 -> (12 * br / sr + pad) * 4
        2 -> 144 * br / sr + pad
        else -> (if (mpeg == 1) 144 else 72) * br / sr + pad
    }
    if (len <= 4) return null
    val spf = when (layer) { 1 -> 384; 2 -> 1152; else -> if (mpeg == 1) 1152 else 576 }
    return Hdr(mpeg, layer, sr, if ((b3 shr 6) == 3) 1 else 2, spf, len)
}
