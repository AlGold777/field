package app.soundfield.core

import app.soundfield.audio.AudioFileDecoder
import app.soundfield.dsp.PeakLimiter
import app.soundfield.dsp.SfDsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Тракт A1: кэш 24 бит с TPDF-дизерингом, сегменты, ресемплер ядра, лимитеры (нужна хост-сборка libsfdsp). */
class A1PathTest {
    private fun native() = assumeTrue("libsfdsp unavailable", SfDsp.load())

    @Test fun nativeDitherMatchesTheKotlinFallbackBitForBit() {
        native()
        val r = Random(5)
        val x = FloatArray(20_000) { (r.nextDouble() * 2 - 1).toFloat() * 0.9f }
        val q = IntArray(x.size)
        Pcm24.quantize(x, x.size, 77L, q)
        val g = Pcm24.KotlinDither(77L)
        var diff = 0
        for (i in x.indices) if (g.quantize(x[i]) != q[i]) diff++
        assertEquals("несовпадений: $diff", 0, diff)
        for (i in x.indices) assertTrue(abs(q[i] - x[i].toDouble() * Pcm24.SCALE) <= 1.5 + 1e-6)
    }

    @Test fun quantizeIsDeterministicPerSeedAndRangeSafe() {
        val x = floatArrayOf(2f, -2f, Float.NaN, 0.5f, 1f, -1f)
        val a = IntArray(6); val b = IntArray(6); val c = IntArray(6)
        Pcm24.quantize(x, 6, 3L, a); Pcm24.quantize(x, 6, 3L, b); Pcm24.quantize(x, 6, 4L, c)
        assertTrue(a.contentEquals(b))
        for (v in a) assertTrue(v in Pcm24.MIN..Pcm24.MAX)
        assertEquals(Pcm24.MAX, a[0]); assertEquals(Pcm24.MIN, a[1]); assertEquals(0, a[2])
    }

    @Test fun stereoEncoderWritesLittleEndian24BitWithinOneLsb() {
        val frames = 5_000
        val r = Random(9)
        val x = FloatArray(frames * 2) { (r.nextDouble() * 1.8 - 0.9).toFloat() }
        val bytes = ByteArray(frames * 6)
        Pcm24StereoEncoder(seed = 1).use { it.encode(x, frames, bytes) }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) for (c in 0..1) {
            val v = Pcm24.readLe(buf, i * 6 + c * 3)
            assertTrue(abs(v - x[i * 2 + c]) <= 1.5f / Pcm24.SCALE)
        }
        // независимые генераторы каналов: одинаковый вход слева и справа даёт разные отсчёты
        val same = FloatArray(2_000) { 1f / 3f }
        val sb = ByteArray(1_000 * 6)
        Pcm24StereoEncoder(seed = 2).use { it.encode(same, 1_000, sb) }
        val sbuf = ByteBuffer.wrap(sb).order(ByteOrder.LITTLE_ENDIAN)
        var different = 0
        for (i in 0 until 1_000) if (Pcm24.readLe(sbuf, i * 6) != Pcm24.readLe(sbuf, i * 6 + 3)) different++
        assertTrue("L/R одинаковы слишком часто: $different", different > 300)
    }

    @Test fun readLeHandlesTheLastSampleOfTheBuffer() {
        val b = ByteBuffer.wrap(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8388607f / Pcm24.SCALE, Pcm24.readLe(b, 0), 0f)
        val n = ByteBuffer.wrap(byteArrayOf(0, 0, 0x80.toByte())).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-1f, Pcm24.readLe(n, 0), 0f)
    }

    @Test fun valuesOnThe24BitGridAreStoredBitExact() {
        val r = Random(21)
        val frames = 10_000
        val x = FloatArray(frames * 2) { i -> val bits = if (i % 3 == 0) 16 else 24; (r.nextInt(1 shl bits) - (1 shl (bits - 1))).toFloat() / (1 shl (bits - 1)).toFloat() }
        val bytes = ByteArray(frames * 6)
        Pcm24StereoEncoder(seed = 8).use { it.encode(x, frames, bytes) }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) for (c in 0..1) assertEquals(x[i * 2 + c], Pcm24.readLe(buf, i * 6 + c * 3), 0f)
        val q = IntArray(x.size); Pcm24.quantize(x, x.size, 3L, q)
        for (i in x.indices) assertEquals(x[i], q[i] / Pcm24.SCALE, 0f)
    }

    @Test fun segmentedSourceReadsThe24BitCache() {
        val frames = 1_000
        val x = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * 440.0 * (i / 2) / SF.SAMPLE_RATE) * if (i % 2 == 0) 1 else -1).toFloat() }
        val bytes = ByteArray(frames * 6)
        Pcm24StereoEncoder(seed = 4).use { it.encode(x, frames, bytes) }
        val source = SegmentedPcmSource("t", "a", frames.toLong())
        source.publish(0, ByteBuffer.wrap(bytes), frames)
        source.markEnd(frames.toLong())
        assertTrue(source.complete)
        for (i in 0 until frames) for (c in 0..1) assertTrue(abs(source.sample(i.toLong(), c) - x[i * 2 + c]) <= 1.5f / Pcm24.SCALE)
        assertEquals(0f, source.sample(frames.toLong(), 0), 0f)
    }

    @Test fun segmentedSourceKeepsWindowAroundPlayhead() {
        val seg = SegmentedPcmSource.SEGMENT_FRAMES
        fun segment() = ByteBuffer.wrap(ByteArray(seg * 6))
        val source = SegmentedPcmSource("t", "a", 400L * seg)
        assertEquals(0L, source.nextWantedSegment())
        for (i in 0 until SegmentedPcmSource.AHEAD_SEGMENTS) source.publish(i, segment(), seg)
        assertNull(source.nextWantedSegment())                       // окно заполнено — декодер ждёт
        assertEquals(SegmentedPcmSource.AHEAD_SEGMENTS * seg, source.readyFrames)
        // Перемотка к сегменту 300: готового участка нет, декодер нужен там.
        source.focus(300L * seg + 100)
        assertEquals(300L * seg, source.readyFrames)
        assertEquals(300L, source.nextWantedSegment())
        source.publish(300, segment(), seg)
        source.publish(301, segment(), seg)
        assertEquals(302L * seg, source.readyFrames)
        assertEquals(302L, source.nextWantedSegment())
        // Старые сегменты вытеснены: в памяти только окно.
        assertFalse(source.hasSegment(0))
        assertTrue(source.residentSegments <= SegmentedPcmSource.AHEAD_SEGMENTS + SegmentedPcmSource.BEHIND_SEGMENTS + 1)
        // Конец файла: окно не выходит за последний сегмент, длинный трек не становится complete.
        source.markEnd(305L * seg + 10)
        assertTrue(source.endKnown)
        assertFalse(source.complete)
        assertEquals(306L, source.endSegmentExclusive)
        source.focus(304L * seg)
        assertEquals(304L, source.nextWantedSegment())
        // Всё от позиции до конца файла готово: обёртки дорабатывают хвост (complete), хотя начало вытеснено.
        for (i in 302L..304L) source.publish(i, segment(), seg)
        source.publish(305, ByteBuffer.wrap(ByteArray(10 * 6)), 10)
        assertTrue(source.complete)
        assertEquals(305L * seg + 10, source.readyFrames)
        assertNull(source.nextWantedSegment())
    }

    @Test fun sampleRateConverterKeepsToneLevelAndLength() {
        native()
        val n = 44_100
        val x = FloatArray(n * 2) { i -> (0.5 * sin(2 * PI * 1_000.0 * (i / 2) / 44_100.0)).toFloat() }
        val y = SampleRateConverter.convertAll(x, n, 44_100, 48_000)
        assertEquals(48_000, y.size / 2)
        var peak = 0f
        for (i in 6_000 until 42_000) peak = maxOf(peak, abs(y[i * 2]))
        assertEquals(0.5, peak.toDouble(), 0.002)
        for (i in 6_000 until 42_000 step 997) assertEquals(0.5 * sin(2 * PI * 1_000.0 * i / 48_000.0), y[i * 2].toDouble(), 1e-5)
    }

    @Test fun wavDecoderResamplesWith24BitAndFloatInputs() {
        native()
        fun wav(bits: Int, format: Int, rate: Int, frames: Int, sample: (Int) -> Float): ByteArray {
            val bps = bits / 8
            val data = ByteBuffer.allocate(frames * bps).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                val v = sample(i)
                when (bits) { 24 -> { val q = (v * 8388607f).toInt(); data.put(q.toByte()); data.put((q shr 8).toByte()); data.put((q shr 16).toByte()) }
                    32 -> data.putFloat(v); else -> data.putShort((v * 32767f).toInt().toShort()) }
            }
            val b = ByteBuffer.allocate(44 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(36 + data.capacity()); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
            b.putInt(16); b.putShort(format.toShort()); b.putShort(1); b.putInt(rate); b.putInt(rate * bps); b.putShort(bps.toShort()); b.putShort(bits.toShort())
            b.put("data".toByteArray()); b.putInt(data.capacity()); b.put(data.array())
            return b.array()
        }
        val tone: (Int) -> Float = { i -> (0.4 * sin(2 * PI * 500.0 * i / 44_100.0)).toFloat() }
        for ((bits, fmt) in listOf(16 to 1, 24 to 1, 32 to 3)) {
            val d = Wav.decode(wav(bits, fmt, 44_100, 22_050, tone))
            assertEquals(24_000, d.pcm.size / 2)
            assertEquals(SF.SAMPLE_RATE, d.sampleRate)
            var worst = 0.0
            for (i in 3_000 until 20_000 step 313) worst = maxOf(worst, abs(d.pcm[i * 2] - 0.4 * sin(2 * PI * 500.0 * i / 48_000.0)))
            assertTrue("bits=$bits worst=$worst", worst < 2e-4)
        }
    }

    // ---- лимитеры ----
    @Test fun deviceLimiterIsTransparentBelowTheCeilingAndHoldsItAbove() {
        native()
        val l = PeakLimiter(SF.SAMPLE_RATE, 1, -1.0)
        try {
            val quiet = FloatArray(10_000) { (0.3 * sin(0.05 * it)).toFloat() }
            val q = quiet.copyOf(); l.process(q, 0, q.size)
            val lat = l.latencyFrames
            for (i in lat until q.size) assertEquals(quiet[i - lat], q[i], 0f)
            assertEquals(0L, l.takeStats().activeBlocks)
            val loud = FloatArray(48_000) { (1.2 * sin(2 * PI * 1_000.0 * it / 48_000.0)).toFloat() }
            l.process(loud, 0, loud.size)
            var peak = 0f
            for (i in 10_000 until loud.size) peak = maxOf(peak, abs(loud[i]))
            assertTrue("peak=${20 * log10(peak.toDouble())} dBFS", peak <= 10.0.pow(-1.0 / 20) + 1e-6)
            assertTrue(l.takeStats().maxReductionDb > 1.0)
        } finally { l.close() }
    }

    private class Stereo(val l: FloatArray, val r: FloatArray) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "t"; override val artist = "a"
        override val totalFrames = l.size.toLong()
        override fun sample(frame: Long, ch: Int) = if (frame !in 0 until totalFrames) 0f else if (ch == 0) l[frame.toInt()] else r[frame.toInt()]
    }

    private fun drain(s: PreLimitedSource) {
        s.requestThrough(s.totalFrames)
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!s.complete && s.processingError == null && System.nanoTime() < deadline) Thread.sleep(2)
        assertNull(s.processingError)
        assertTrue("timeout", s.complete)
    }

    private class Metered(l: FloatArray, r: FloatArray, override val truePeakDbtp: Double) : AudioSource, TrackMeters {
        val inner = Stereo(l, r)
        override val mode = SourceMode.MUSIC
        override val title = "t"; override val artist = "a"
        override val totalFrames = inner.totalFrames
        override fun sample(frame: Long, ch: Int) = inner.sample(frame, ch)
    }

    @Test fun staticGainBringsAHotTrackBelowTheCeilingWithoutTheLimiterWorking() {
        native()
        val n = SF.SAMPLE_RATE * 2
        val l = FloatArray(n) { (1.0 * sin(2 * PI * 440.0 * it / 48_000.0)).toFloat() }   // 0 dBFS
        val src = Metered(l, l.copyOf(), 0.0)
        PreLimitedSource(src).use { s ->
            drain(s)
            assertEquals(-1.55, s.staticGainDb, 1e-9)
            var peak = 0.0
            for (i in 2_000 until n) peak = maxOf(peak, abs(s.sample(i.toLong(), 0).toDouble()))
            assertEquals(10.0.pow(-1.55 / 20), peak, 1e-4)
            assertEquals(0L, s.takeStats().activeBlocks)
        }
        assertEquals(0.0, app.soundfield.dsp.TruePeakMeter.staticGainDb(-3.0), 0.0)
        assertEquals(0.0, app.soundfield.dsp.TruePeakMeter.staticGainDb(Double.NaN), 0.0)
    }

    @Test fun preLimiterKeepsFrameNumbersAndIsTransparentWhenQuiet() {
        native()
        val n = SF.SAMPLE_RATE
        val l = FloatArray(n) { (0.3 * sin(0.02 * it)).toFloat() }
        val r = FloatArray(n) { (0.2 * sin(0.031 * it)).toFloat() }
        PreLimitedSource(Stereo(l, r)).use { s ->
            drain(s)
            for (i in 0 until n step 7) { assertEquals(l[i], s.sample(i.toLong(), 0), 0f); assertEquals(r[i], s.sample(i.toLong(), 1), 0f) }
        }
    }

    @Test fun preLimiterLinksChannelsAndHoldsTheMinus1p5Ceiling() {
        native()
        val n = SF.SAMPLE_RATE * 2
        val l = FloatArray(n) { (1.3 * sin(2 * PI * 440.0 * it / 48_000.0)).toFloat() }
        val r = FloatArray(n) { 0.5f * l[it] }
        PreLimitedSource(Stereo(l, r)).use { s ->
            drain(s)
            var peak = 0.0
            for (i in 0 until n) {
                val a = s.sample(i.toLong(), 0); val b = s.sample(i.toLong(), 1)
                peak = maxOf(peak, abs(a.toDouble()), abs(b.toDouble()))
                assertEquals(0.5f * a, b, 1e-6f)   // общее усиление: картина не смещается
            }
            assertTrue("peak=${20 * log10(peak)}", peak <= 10.0.pow(-1.5 / 20) + 1e-6)
        }
    }

    @Test fun preLimiterRestartsAfterASeekAndGivesTheSameValuesToEveryReader() {
        native()
        val n = SF.SAMPLE_RATE * 40
        val rnd = Random(3)
        val l = FloatArray(n) { (if (it % 9_973 == 0) 1.5 else 0.2 * (rnd.nextDouble() - 0.5)).toFloat() }
        val r = l.copyOf()
        PreLimitedSource(Stereo(l, r), cacheSeconds = 4).use { s ->
            s.requestThrough(SF.SAMPLE_RATE.toLong())
            val far = 30L * SF.SAMPLE_RATE
            var v = Float.NaN
            val deadline = System.nanoTime() + 20_000_000_000L
            while (v.isNaN() && System.nanoTime() < deadline) { v = s.sample(far, 0); Thread.sleep(2) }
            assertFalse("после перемотки кадр так и не стал доступен", v.isNaN())
            assertEquals(v, s.sample(far, 0), 0f)
            assertEquals(s.sample(far, 0), s.sample(far, 1), 0f)
            assertNotNull(s.takeStats())
        }
    }
}
