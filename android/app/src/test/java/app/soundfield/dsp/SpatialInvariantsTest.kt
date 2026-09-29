package app.soundfield.dsp

import app.soundfield.core.AudioSource
import app.soundfield.core.Ch
import app.soundfield.core.SF
import app.soundfield.core.SourceMode
import app.soundfield.core.SpatialSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Инварианты потокового процессора на реальном `SpatialSource` (те, что пропустили тесты прототипа):
 * центр и моно после тишины, предзвон, жёстко разведённые источники, выравнивание по времени.
 */
class SpatialInvariantsTest {
    private class Buffer(val left: FloatArray, val right: FloatArray) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "synthetic"
        override val artist = "test"
        override val totalFrames = left.size.toLong()
        override fun sample(frame: Long, ch: Int) = if (frame !in 0 until totalFrames) 0f
            else if (ch == Ch.L) left[frame.toInt()] else right[frame.toInt()]
    }

    private fun noise(random: Random, count: Int, amplitude: Float) = FloatArray(count) { (random.nextFloat() * 2f - 1f) * amplitude }

    private fun render(input: Buffer, config: SpatialDspConfig): Pair<FloatArray, FloatArray> {
        SpatialSource(input, spatialConfig = config).use { spatial ->
            spatial.requestThrough(input.totalFrames)
            val deadline = System.nanoTime() + 20_000_000_000L
            while (!spatial.complete && spatial.processingError == null && System.nanoTime() < deadline) Thread.sleep(2)
            spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
            assertTrue("render timed out", spatial.complete)
            val n = input.totalFrames.toInt()
            return FloatArray(n) { spatial.sample(it.toLong(), Ch.L) } to FloatArray(n) { spatial.sample(it.toLong(), Ch.R) }
        }
    }

    private val wide = SpatialDspConfig(enabled = true, gMidDb = 4.0, gHighDb = 3.0, gAmbientDb = 3.0,
        depthDb = -2.0, preserveLoudness = false)

    private fun db(v: Double) = 20.0 * log10(max(v, 1e-12))

    @Test fun centreAttackAfterSilenceStaysPerfectlyCentred() {
        val random = Random(11)
        val n = SF.SAMPLE_RATE * 6
        val l = FloatArray(n); val r = FloatArray(n)
        for (k in 0 until 4) {
            val start = SF.SAMPLE_RATE + k * SF.SAMPLE_RATE
            val burst = noise(random, 480, 0.3f)
            for (i in burst.indices) { l[start + i] = burst[i]; r[start + i] = burst[i] }
        }
        val (outL, outR) = render(Buffer(l, r), wide)
        var worst = 0.0
        for (i in 0 until n) worst = max(worst, abs((outL[i] - outR[i]).toDouble()))
        assertTrue("a centred signal must stay centred, max |L-R|=$worst", worst < 1e-6)
    }

    @Test fun monoSumIsUntouchedWhenDepthIsOff() {
        val random = Random(12)
        val n = SF.SAMPLE_RATE * 6
        val common = noise(random, n, 0.12f)
        val l = FloatArray(n) { common[it] + noise(random, 1, 0.08f)[0] }
        val r = FloatArray(n) { common[it] + noise(random, 1, 0.08f)[0] }
        val (outL, outR) = render(Buffer(l, r), wide.copy(depthDb = 0.0))
        var peak = 0.0; var worst = 0.0
        for (i in 0 until n) {
            peak = max(peak, abs((l[i] + r[i]).toDouble()))
            worst = max(worst, abs((outL[i] + outR[i] - l[i] - r[i]).toDouble()))
        }
        assertTrue("width layer must not touch L+R: ${db(worst / peak)} dB", db(worst / peak) < -90.0)
    }

    @Test fun preEchoBeforeStereoBurstStaysBelowTheRegressionThreshold() {
        val random = Random(13)
        val n = SF.SAMPLE_RATE * 5
        val l = FloatArray(n); val r = FloatArray(n)
        val starts = intArrayOf(SF.SAMPLE_RATE, SF.SAMPLE_RATE * 2 + 5_000, SF.SAMPLE_RATE * 3 + 12_345)
        for (start in starts) {
            val a = noise(random, 480, 0.3f); val b = noise(random, 480, 0.3f)
            for (i in a.indices) { l[start + i] = a[i]; r[start + i] = b[i] }
        }
        val (outL, outR) = render(Buffer(l, r), wide)
        for (start in starts) {
            var before = 0.0; var peak = 0.0
            for (i in start - 4_800 until start - 96) before = max(before, max(abs(outL[i].toDouble()), abs(outR[i].toDouble())))
            for (i in start until start + 2_400) peak = max(peak, max(abs(outL[i].toDouble()), abs(outR[i].toDouble())))
            val level = db(before / peak)
            // Достигнуто около −40 дБ (мгновенный ограничитель фазовости давал −33). Порог держит регрессию.
            assertTrue("pre-echo at $start is $level dB relative to the burst peak", level < -35.0)
        }
    }

    @Test fun hardPannedSourcesKeepTheirChannelAndDoNotLeak() {
        val random = Random(14)
        val n = SF.SAMPLE_RATE * 5
        val piano = noise(random, n, 0.2f)
        val cymbal = noise(random, n, 0.2f)
        val (outL, outR) = render(Buffer(piano, cymbal), wide)
        fun rms(x: FloatArray, from: Int) = kotlin.math.sqrt((from until x.size).sumOf { (x[it] * x[it]).toDouble() } / (x.size - from))
        val from = SF.SAMPLE_RATE
        assertEquals("own level L", 0.0, db(rms(outL, from) / rms(piano, from)), 0.8)
        assertEquals("own level R", 0.0, db(rms(outR, from) / rms(cymbal, from)), 0.8)
        fun projection(a: FloatArray, b: FloatArray): Double {
            var ab = 0.0; var bb = 0.0
            for (i in from until a.size) { ab += a[i].toDouble() * b[i]; bb += b[i].toDouble() * b[i] }
            return ab / bb
        }
        assertTrue("piano into R: ${db(abs(projection(outR, piano)))} dB", db(abs(projection(outR, piano))) < -28.0)
        assertTrue("cymbal into L: ${db(abs(projection(outL, cymbal)))} dB", db(abs(projection(outL, cymbal))) < -28.0)
    }

    @Test fun centredImpulseKeepsItsPosition() {
        val n = SF.SAMPLE_RATE * 3
        val l = FloatArray(n); val r = FloatArray(n)
        l[SF.SAMPLE_RATE] = 0.5f; r[SF.SAMPLE_RATE] = 0.5f
        val (outL, outR) = render(Buffer(l, r), wide)
        fun peakAt(x: FloatArray) = x.indices.maxByOrNull { abs(x[it]) }!!
        assertEquals(SF.SAMPLE_RATE, peakAt(outL))
        assertEquals(SF.SAMPLE_RATE, peakAt(outR))
    }

    @Test fun outputNeverExceedsTheLimiterCeiling() {
        val n = SF.SAMPLE_RATE * 4
        val tone = FloatArray(n) { (sin(2 * PI * 1_000.0 * it / SF.SAMPLE_RATE) * 0.98).toFloat() }
        val other = FloatArray(n) { (sin(2 * PI * 1_300.0 * it / SF.SAMPLE_RATE) * 0.98).toFloat() }
        val (outL, outR) = render(Buffer(tone, other), wide.copy(gMidDb = 8.0, gHighDb = 8.0))
        var peak = 0.0
        for (i in 0 until n) peak = max(peak, max(abs(outL[i].toDouble()), abs(outR[i].toDouble())))
        assertTrue("peak ${db(peak)} dBFS", db(peak) <= -0.25)
    }
}
