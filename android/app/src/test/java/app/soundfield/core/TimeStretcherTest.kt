package app.soundfield.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class TimeStretcherTest {
    private val sr = 48_000

    private fun sine(frames: Int, hz: Double = 440.0, amp: Double = 0.5, phaseR: Double = 0.0): FloatArray =
        FloatArray(frames * 2) { i ->
            val f = i / 2
            val ph = if (i % 2 == 1) phaseR else 0.0
            (amp * sin(2 * PI * hz * f / sr + ph)).toFloat()
        }

    private fun stretch(input: FloatArray, speed: Double, piece: Int = 5_000): FloatArray {
        val ts = TimeStretcher(speed)
        val frames = input.size / 2
        val parts = ArrayList<FloatArray>()
        var pos = 0
        while (pos < frames) {
            val c = minOf(piece, frames - pos)
            parts.add(ts.process(input.copyOfRange(pos * 2, (pos + c) * 2), c))
            pos += c
        }
        parts.add(ts.flush())
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun rmsDb(x: FloatArray, from: Int, to: Int, ch: Int): Double {
        var s = 0.0
        for (f in from until to) { val v = x[f * 2 + ch].toDouble(); s += v * v }
        return 10 * log10(s / (to - from))
    }

    /** Частота по положительным переходам через ноль с линейной интерполяцией. */
    private fun freq(x: FloatArray, from: Int, to: Int, ch: Int): Double {
        var first = -1.0; var last = -1.0; var count = 0
        for (f in from + 1 until to) {
            val a = x[(f - 1) * 2 + ch]; val b = x[f * 2 + ch]
            if (a < 0 && b >= 0) {
                val pos = f - 1 + (-a / (b - a)).toDouble()
                if (first < 0) first = pos
                last = pos; count++
            }
        }
        return (count - 1) * sr / (last - first)
    }

    @Test fun sineKeepsDurationPitchLevelAndHasNoClicks() {
        val frames = 5 * sr
        val input = sine(frames)
        for (speed in doubleArrayOf(0.5, 0.8, 1.25, 1.5, 2.0)) {
            val out = stretch(input, speed)
            val outFrames = out.size / 2
            assertEquals("длительность $speed", frames / speed, outFrames.toDouble(), frames / speed * 0.01)
            val from = sr / 2; val to = outFrames - sr / 2
            assertEquals("частота $speed", 440.0, freq(out, from, to, 0), 4.4)
            assertEquals("уровень $speed", rmsDb(input, sr, 4 * sr, 0), rmsDb(out, from, to, 0), 1.0)
            var maxDiff = 0f
            for (f in 1 until outFrames) maxDiff = maxOf(maxDiff, abs(out[f * 2] - out[(f - 1) * 2]))
            val ideal = (2 * PI * 440 / sr * 0.5).toFloat()
            assertTrue("щелчок при $speed: $maxDiff > ${ideal * 1.25f}", maxDiff <= ideal * 1.25f)
        }
    }

    @Test fun identicalChannelsStayIdentical() {
        val mono = sine(sr, 300.0, 0.4)
        val out = stretch(mono, 1.5)
        for (f in 0 until out.size / 2) assertEquals(out[f * 2], out[f * 2 + 1], 0f)
        // речеподобный шум с огибающей
        val rnd = java.util.Random(1)
        val noise = FloatArray(sr * 2) { 0f }
        for (f in 0 until sr) { val v = (rnd.nextGaussian() * 0.1).toFloat(); noise[f * 2] = v; noise[f * 2 + 1] = v }
        val o2 = stretch(noise, 0.7)
        for (f in 0 until o2.size / 2) assertEquals(o2[f * 2], o2[f * 2 + 1], 0f)
    }

    @Test fun interChannelPhaseDelayIsPreserved() {
        val phase = 0.9
        val input = sine(4 * sr, 440.0, 0.5, phase)
        for (speed in doubleArrayOf(0.8, 1.5)) {
            val out = stretch(input, speed)
            val from = sr / 2; val to = out.size / 2 - sr / 2
            // задержка R относительно L: разность моментов положительных переходов
            var sum = 0.0; var n = 0
            var lastL = -1.0
            for (f in from + 1 until to) {
                val a = out[(f - 1) * 2]; val b = out[f * 2]
                if (a < 0 && b >= 0) lastL = f - 1 + (-a / (b - a)).toDouble()
                val ra = out[(f - 1) * 2 + 1]; val rb = out[f * 2 + 1]
                if (ra < 0 && rb >= 0 && lastL >= 0) {
                    val pr = f - 1 + (-ra / (rb - ra)).toDouble()
                    var d = pr - lastL
                    if (d > sr / 440.0 / 2) d -= sr / 440.0
                    sum += d; n++
                }
            }
            val expected = -phase / (2 * PI * 440) * sr   // сдвиг фазы +phase опережает: отрицательная задержка
            assertEquals("межканальная задержка $speed", expected, sum / n, 0.5)
        }
    }

    @Test fun unitSpeedIsBitExact() {
        val rnd = java.util.Random(7)
        val input = FloatArray(10_000) { rnd.nextFloat() * 2 - 1 }
        val ts = TimeStretcher(1.0)
        val a = ts.process(input, 3_000)
        val b = ts.process(input.copyOfRange(6_000, 10_000), 2_000)
        assertArrayEquals(input.copyOfRange(0, 6_000), a, 0f)
        assertArrayEquals(input.copyOfRange(6_000, 10_000), b, 0f)
        assertEquals(0, ts.flush().size)
        assertEquals(0, ts.latencyFrames)
    }

    @Test fun chunkingDoesNotChangeOutput() {
        val input = sine(sr, 523.0)
        assertArrayEquals(stretch(input, 1.3, 777), stretch(input, 1.3, 20_000), 0f)
    }

    @Test fun runsMuchFasterThanRealtime() {
        val rnd = java.util.Random(3)
        val frames = 60 * sr
        val input = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val v = (0.3 * sin(2 * PI * 150 * f / sr) + 0.1 * sin(2 * PI * 1130 * f / sr) + rnd.nextGaussian() * 0.01).toFloat()
            input[f * 2] = v; input[f * 2 + 1] = v * 0.9f
        }
        stretch(input.copyOf(10 * sr * 2), 1.5)   // прогрев JIT
        for (speed in doubleArrayOf(0.8, 1.5, 2.0)) {
            val t0 = System.nanoTime()
            stretch(input, speed)
            val sec = (System.nanoTime() - t0) / 1e9
            val factor = 60.0 / sec
            println("TimeStretcher speed=$speed: 60 s за ${"%.2f".format(sec)} с, множитель ${"%.1f".format(factor)}x")
            assertTrue("медленно: ${factor}x", factor > 10.0)
        }
    }
}
