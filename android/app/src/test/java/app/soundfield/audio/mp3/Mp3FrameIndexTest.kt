package app.soundfield.audio.mp3

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3FrameIndexTest {
    private val v1Rates = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val v2Rates = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** Кадр Layer III: MPEG-1 44.1 кГц либо MPEG-2 22.05 кГц; полезная нагрузка — байты [fill] (без 0xFF). */
    private fun frame(kbps: Int, mpeg2: Boolean = false, pad: Int = 0, mono: Boolean = false, fill: Int = 0x11): ByteArray {
        val idx = (if (mpeg2) v2Rates else v1Rates).indexOf(kbps) + 1
        require(idx > 0)
        val len = (if (mpeg2) 72 else 144) * kbps * 1000 / (if (mpeg2) 22050 else 44100) + pad
        val b = ByteArray(len) { fill.toByte() }
        b[0] = 0xFF.toByte()
        b[1] = (if (mpeg2) 0xF3 else 0xFB).toByte()
        b[2] = ((idx shl 4) or (pad shl 1)).toByte()
        b[3] = (if (mono) 0xC0 else 0x00).toByte()
        return b
    }

    private fun cat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); parts.forEach { o.write(it) }; return o.toByteArray()
    }

    private fun id3(payload: Int, footer: Boolean = false): ByteArray {
        val h = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, (if (footer) 0x10 else 0).toByte(),
            ((payload shr 21) and 0x7F).toByte(), ((payload shr 14) and 0x7F).toByte(), ((payload shr 7) and 0x7F).toByte(), (payload and 0x7F).toByte())
        return cat(h, ByteArray(payload), if (footer) ByteArray(10) else ByteArray(0))
    }

    /** Кадр Info с LAME-тегом (MPEG-1 стерео, 128 кбит/с). */
    private fun infoFrame(delay: Int, padding: Int, tag: String = "Info"): ByteArray {
        val f = frame(128)
        val t = 4 + 32
        tag.toByteArray().copyInto(f, t)
        f[t + 7] = 0x0F // frames+bytes+toc+quality
        val lame = t + 8 + 4 + 4 + 100 + 4
        "LAME3.99r".toByteArray().copyInto(f, lame)
        f[lame + 21] = (delay shr 4).toByte()
        f[lame + 22] = (((delay and 0xF) shl 4) or (padding shr 8)).toByte()
        f[lame + 23] = (padding and 0xFF).toByte()
        return f
    }

    private fun build(data: ByteArray) = Mp3FrameIndex.build(ByteArraySource(data))

    @Test fun cbr() {
        val data = cat(*Array(100) { frame(128, pad = it % 2) })
        val ix = build(data)!!
        assertEquals(100, ix.frameCount)
        assertEquals(44100, ix.sampleRate); assertEquals(2, ix.channels); assertEquals(1152, ix.samplesPerFrame)
        assertEquals(0, ix.encoderDelay)
        assertEquals(100L * 1152, ix.totalSamples)
        assertEquals(0L, ix.frameOffset(0)); assertEquals(417, ix.frameSize(0)); assertEquals(418, ix.frameSize(1))
        assertEquals(417L, ix.frameOffset(1))
        assertEquals(data.size.toLong(), ix.frameOffset(99) + ix.frameSize(99))
    }

    @Test fun mono() {
        val ix = build(cat(*Array(10) { frame(64, mono = true) }))!!
        assertEquals(1, ix.channels)
    }

    @Test fun vbrDifferentBitrates() {
        val rates = intArrayOf(32, 128, 320, 96, 256, 48, 160, 64)
        val frames = rates.map { frame(it) }
        val ix = build(cat(*frames.toTypedArray()))!!
        assertEquals(rates.size, ix.frameCount)
        var off = 0L
        for (i in rates.indices) {
            assertEquals(off, ix.frameOffset(i)); assertEquals(frames[i].size, ix.frameSize(i)); off += frames[i].size
        }
    }

    @Test fun id3v2AtStartAndTagsAtEnd() {
        val v1 = ByteArray(128).also { "TAG".toByteArray().copyInto(it) }
        val ape = "APETAGEX".toByteArray() + ByteArray(40)
        for (head in listOf(id3(500), id3(300, footer = true), cat(id3(100), id3(50)))) {
            for (tail in listOf(v1, ape, cat(ape, v1))) {
                val ix = build(cat(head, *Array(20) { frame(128) }, tail))!!
                assertEquals(20, ix.frameCount)
                assertEquals(head.size.toLong(), ix.frameOffset(0))
            }
        }
    }

    @Test fun infoFrameWithLameTag() {
        val ix = build(cat(id3(64), infoFrame(576, 1000), *Array(50) { frame(128) }))!!
        assertEquals(50, ix.frameCount) // Info-кадр не аудио
        assertEquals(576, ix.encoderDelay); assertEquals(1000, ix.encoderPadding)
        assertEquals(50L * 1152 - 576 - 1000, ix.totalSamples)
        assertEquals(64L + 10 + 417, ix.frameOffset(0))
    }

    @Test fun xingTag() {
        val ix = build(cat(infoFrame(1105, 2000, "Xing"), *Array(5) { frame(128) }))!!
        assertEquals(5, ix.frameCount); assertEquals(1105, ix.encoderDelay); assertEquals(2000, ix.encoderPadding)
    }

    @Test fun garbageBetweenFrames() {
        val junk = ByteArray(1500) { (it % 7 + 1).toByte() }
        // ложная «синхронизация» в мусоре: FF Fx с невалидным битрейтом
        junk[100] = 0xFF.toByte(); junk[101] = 0xFB.toByte(); junk[102] = 0xF0.toByte()
        val data = cat(*Array(10) { frame(128) }, junk, *Array(10) { frame(128) }, byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0), ByteArray(30), *Array(5) { frame(128) })
        val ix = build(data)!!
        assertEquals(25, ix.frameCount)
        assertEquals(10 * 417L + 1500, ix.frameOffset(10))
    }

    @Test fun falseSyncWithValidHeaderRejected() {
        // валидный заголовок в мусоре, но за ним нет согласованного заголовка
        val fake = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)
        val data = cat(fake, ByteArray(700) { 0x22 }, *Array(10) { frame(128) })
        val ix = build(data)!!
        assertEquals(10, ix.frameCount)
        assertEquals(704L, ix.frameOffset(0))
    }

    @Test fun truncatedLastFrame() {
        val full = cat(*Array(10) { frame(128) })
        val ix = build(cat(full, frame(128).copyOf(200)))!!
        assertEquals(10, ix.frameCount)
    }

    @Test fun mpeg2() {
        val ix = build(cat(*Array(20) { frame(64, mpeg2 = true) }))!!
        assertEquals(576, ix.samplesPerFrame); assertEquals(22050, ix.sampleRate)
        assertEquals(20L * 576, ix.totalSamples)
        assertEquals(208, ix.frameSize(0))
    }

    @Test fun notMp3() {
        assertNull(build(ByteArray(0)))
        assertNull(build(ByteArray(10000) { (it * 31 + 7).toByte().let { b -> if (b == 0xFF.toByte()) 0 else b } }))
        assertNull(build("RIFF....WAVEfmt ".toByteArray()))
        assertNull(build(id3(100)))
        assertNull(build(frame(128).copyOf(100))) // один обрезанный кадр
    }

    @Test fun cancelled() {
        val data = cat(*Array(5000) { frame(128) })
        assertNull(Mp3FrameIndex.build(ByteArraySource(data)) { true })
    }

    @Test fun frameForSampleAndTotals() {
        val ix = build(cat(infoFrame(576, 1000), *Array(10) { frame(128) }))!!
        assertEquals(0, ix.frameForSample(0))
        assertEquals(0, ix.frameForSample(575))   // 575+576=1151
        assertEquals(1, ix.frameForSample(576))   // 1152
        assertEquals(1, ix.frameForSample(1727))
        assertEquals(2, ix.frameForSample(1728))
        assertEquals(9, ix.frameForSample(1_000_000))
        assertEquals(0, ix.frameForSample(-5))
        assertEquals(1, ix.frameForSample(0, extraDelay = 1152 - 576))
    }

    @Test fun totalSamplesNeverNegative() {
        val ix = build(cat(infoFrame(4000, 4000), frame(128), frame(128)))!! // delay+padding > длины → игнорируются
        assertEquals(0, ix.encoderDelay)
        assertEquals(2304L, ix.totalSamples)
    }

    @Test fun serializationRoundTrip() {
        val junk = ByteArray(333) { 3 }
        val data = cat(id3(70), infoFrame(576, 1000), *Array(200) { frame(arrayOf(64, 128, 192)[it % 3], pad = it % 2) }, junk, *Array(50) { frame(96) })
        val a = build(data)!!
        val bos = ByteArrayOutputStream(); a.write(bos)
        val b = Mp3FrameIndex.read(ByteArrayInputStream(bos.toByteArray()))!!
        assertEquals(a.frameCount, b.frameCount)
        assertEquals(a.sampleRate, b.sampleRate); assertEquals(a.channels, b.channels); assertEquals(a.samplesPerFrame, b.samplesPerFrame)
        assertEquals(a.encoderDelay, b.encoderDelay); assertEquals(a.encoderPadding, b.encoderPadding)
        assertEquals(a.totalSamples, b.totalSamples); assertEquals(a.sourceSize, b.sourceSize)
        for (i in 0 until a.frameCount) { assertEquals(a.frameOffset(i), b.frameOffset(i)); assertEquals(a.frameSize(i), b.frameSize(i)) }
        // компактность: ~3 байта на кадр
        assertTrue(bos.size() < 60 + a.frameCount * 4)
        // повреждение / чужая версия
        assertNull(Mp3FrameIndex.read(ByteArrayInputStream(bos.toByteArray().copyOf(100))))
        assertNull(Mp3FrameIndex.read(ByteArrayInputStream(ByteArray(64))))
        val wrongVer = bos.toByteArray().also { it[7] = 9 }
        assertNull(Mp3FrameIndex.read(ByteArrayInputStream(wrongVer)))
    }

    @Test fun readerCopiesFrameBytes() {
        val frames = Array(8) { frame(128, fill = 0x20 + it) }
        val data = cat(id3(30), *frames)
        val ix = build(data)!!
        val r = Mp3FrameReader(ByteArraySource(data), ix)
        for (i in 0 until 8) {
            val bb = ByteBuffer.allocate(4096)
            assertEquals(frames[i].size, r.read(i, bb))
            assertArrayEquals(frames[i], bb.array().copyOf(bb.position()))
        }
        assertEquals(-1, r.read(8, ByteBuffer.allocate(4096)))
        assertEquals(-1, r.read(0, ByteBuffer.allocate(10)))
        assertEquals(0L, r.presentationTimeUs(0))
        assertEquals(1152L * 1_000_000 / 44100, r.presentationTimeUs(1))
        assertTrue(PREROLL_FRAMES in 2..4)
    }

    @Test fun channelSourceMatchesArraySource() {
        val data = cat(id3(200), infoFrame(576, 1000), *Array(300) { frame(arrayOf(64, 128, 320)[it % 3], pad = it % 2) }, ByteArray(128).also { "TAG".toByteArray().copyInto(it) })
        val f = File.createTempFile("mp3idx", ".mp3")
        try {
            f.writeBytes(data)
            java.io.RandomAccessFile(f, "r").use { raf ->
                val src = ChannelByteSource(raf.channel, 4096)
                val a = build(data)!!
                val b = Mp3FrameIndex.build(src)!!
                assertEquals(a.frameCount, b.frameCount)
                for (i in 0 until a.frameCount) assertEquals(a.frameOffset(i), b.frameOffset(i))
                // чтение кадра: и через окно, и напрямую (кадр крупнее окна)
                val r = Mp3FrameReader(src, b)
                for (i in intArrayOf(0, 7, 299)) {
                    val bb = ByteBuffer.allocate(4096)
                    r.read(i, bb)
                    assertArrayEquals(data.copyOfRange(b.frameOffset(i).toInt(), b.frameOffset(i).toInt() + b.frameSize(i)), bb.array().copyOf(bb.position()))
                }
                val big = ByteArray(10000)
                assertEquals(10000, src.read(0, big, 0, 10000))
                assertArrayEquals(data.copyOf(10000), big)
                assertEquals(-1, src.read(data.size.toLong(), big, 0, 10))
            }
        } finally { f.delete() }
    }

    @Test fun largeFileBuildsQuickly() {
        // ~1.5 ч CBR 128: 225 тыс. кадров
        val one = frame(128)
        val data = ByteArray(one.size * 225_000)
        for (i in 0 until 225_000) one.copyInto(data, i * one.size)
        val t0 = System.nanoTime()
        val ix = build(data)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(225_000, ix.frameCount)
        assertTrue("слишком долго: $ms мс", ms < 5000)
    }

    @Test fun notNull() { assertNotNull(build(cat(frame(128), frame(128)))) }
}
