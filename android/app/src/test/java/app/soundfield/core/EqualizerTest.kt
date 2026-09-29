package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class EqualizerTest {
    @Test fun cascadePassesThroughTheTenBandTargets() {
        val random = Random(0x5F1E)
        val cases = buildList {
            repeat(1_000) { add(List(10) { (random.nextInt(-24, 25) / 2f) }) }
            add(List(10) { 12f })
            add(List(10) { if (it % 2 == 0) 12f else -12f })
            add(List(10) { if (it == 5) 12f else 0f })
        }
        cases.forEach { target ->
            val result = EqualizerMath.responseAtCenters(EqualizerMath.filterGains(target))
            val error = result.indices.maxOf { abs(result[it] - target[it]) }
            assertTrue("error=$error dB for $target", error <= 0.5)
        }
    }

    @Test fun flatEnabledCurveIsBitExactBeforeTheFixedLimiterDelay() {
        val input = FloatArray(4096) { i -> (sin(i * 0.091) * 0.7).toFloat() }
        val output = input.copyOf()
        val processor = EqualizerProcessor()
        processor.setConfig(EqualizerMath.config(EqSettings(enabled = true)))
        processor.processTone(output, output.size)
        processor.processLimiter(output, output.size)
        for (i in output.indices) {
            val expected = if (i < EqualizerMath.LOOKAHEAD_FRAMES) 0f else input[i - EqualizerMath.LOOKAHEAD_FRAMES]
            assertEquals("sample $i", expected, output[i], 0f)
        }
    }

    @Test fun magnetUsesGaussianOctaveDistanceAndOffMovesOneBand() {
        val base = List(10) { 0f }
        val moved = EqMagnetMath.move(base, 5, 8f, EqMagnet.SOFT)
        val adjacentWeight = kotlin.math.exp(-kotlin.math.ln(2.0) * (1.0 / 0.8).let { it * it }).toFloat()
        assertEquals(8f, moved[5], 0.001f)
        assertEquals(8f * adjacentWeight, moved[4], 0.02f)
        assertEquals(0f, moved[2], 0f) // w < 0.05 at three octaves
        val off = EqMagnetMath.move(base, 5, 8f, EqMagnet.OFF)
        assertEquals(8f, off[5], 0.001f)
        assertEquals(0f, off.indices.filter { it != 5 }.maxOf { abs(off[it]) }, 0f)
    }

    @Test fun filterUpdatesAreSmoothedAndRemainDeterministic() {
        val source = FloatArray(24_000) { i -> (0.2 * sin(2.0 * PI * 1_000 * i / 48_000)).toFloat() }
        val a = source.copyOf(); val b = source.copyOf()
        val one = EqualizerProcessor(); val two = EqualizerProcessor()
        val neutral = EqualizerMath.config(EqSettings())
        val boosted = EqualizerMath.config(EqSettings(enabled = true, gainsDb = List(10) { if (it == 5) 12f else 0f }))
        one.setConfig(neutral); two.setConfig(neutral)
        one.processTone(a, 240); two.processTone(b, 240)
        one.setConfig(boosted); two.setConfig(boosted)
        var maxStep = 0f
        var previous = a[239]
        var offset = 240
        while (offset < a.size) {
            val count = minOf(240, a.size - offset)
            val blockA = source.copyOfRange(offset, offset + count)
            val blockB = blockA.copyOf()
            one.processTone(blockA, count); two.processTone(blockB, count)
            for (i in 0 until count) {
                val x = blockA[i]
                maxStep = max(maxStep, abs(x - previous))
                assertEquals(x, blockB[i], 0f)
                a[offset + i] = x; b[offset + i] = blockB[i]
                previous = x
            }
            offset += count
        }
        val plainStep = (0.2 * 2.0 * PI * 1_000 / 48_000).toFloat()
        assertTrue("max sample jump $maxStep, base $plainStep", maxStep <= plainStep * 1.05f)
    }

    @Test fun limiterAddsTheSameFortyEightSampleDelay() {
        val processor = EqualizerProcessor()
        processor.setConfig(EqConfig.neutral())
        val impulse = FloatArray(512).also { it[0] = 0.5f }
        processor.process(impulse, impulse.size)
        assertEquals(0.5f, impulse[EqualizerMath.LOOKAHEAD_FRAMES], 0f)
        assertEquals(0f, impulse[EqualizerMath.LOOKAHEAD_FRAMES - 1], 0f)
    }

    @Test fun pairedProcessorsProduceBitIdenticalOutput() {
        val settings = EqSettings(enabled = true, gainsDb = listOf(4f, -3f, 2.5f, -1f, 5f, 8f, -4.5f, 3f, -2f, 1f))
        val configA = EqualizerMath.config(settings); val configB = EqualizerMath.config(settings)
        val source = FloatArray(8192) { i -> (0.4 * sin(2.0 * PI * (200 + i % 4_000) * i / 48_000)).toFloat() }
        val a = source.copyOf(); val b = source.copyOf()
        val p1 = EqualizerProcessor(); val p2 = EqualizerProcessor()
        p1.setConfig(configA); p2.setConfig(configB)
        p1.process(a, a.size); p2.process(b, b.size)
        assertTrue(a.contentEquals(b))
    }

    @Test fun allBandsPlusTwelveUseAutomaticHeadroom() {
        val settings = EqSettings(enabled = true, gainsDb = List(10) { 12f }, preserveLoudness = false)
        val config = EqualizerMath.config(settings)
        assertTrue("preamp=${config.preampDb}", config.preampDb <= -11.0)
        val samples = pinkMultisine(48_000, peakLevel = 1.0)
        val processor = EqualizerProcessor(); processor.setConfig(config); processor.process(samples, samples.size)
        assertTrue("output peak=${samples.maxOf { abs(it) }}", samples.maxOf { abs(it) } <= 1f)
        assertTrue("limiter reduction=${processor.maxLimiterReductionDb()}", processor.maxLimiterReductionDb() <= 1.0)
    }

    @Test fun loudnessPreservationNeverConsumesTheTruePeakHeadroom() {
        val cases = listOf(
            List(10) { -6f },
            listOf(-12f, -9f, -6f, -3f, 0f, 3f, 6f, 9f, 12f, 6f),
            listOf(-12f, -12f, -8f, -4f, 0f, 4f, 8f, 12f, 8f, 4f),
        )
        cases.forEach { gains ->
            val config = EqualizerMath.config(EqSettings(enabled = true, gainsDb = gains, preserveLoudness = true))
            val effectivePeak = (config.curveDb.maxOrNull() ?: 0.0) + config.outputGainDb
            assertTrue("effective peak=$effectivePeak dB", effectivePeak <= 1e-9)
        }
        val cuts = EqualizerMath.config(EqSettings(enabled = true, gainsDb = List(10) { -6f }, preserveLoudness = true))
        assertTrue("cut-only profile should recover some loudness", cuts.outputGainDb > 0.0)
    }

    @Test fun equalLoudnessBypassCompensationMatchesTheConfiguredWeightedMean() {
        val settings = EqSettings(enabled = true, gainsDb = listOf(4f, 3f, 1f, -2f, 0f, 3f, 4f, -1f, -4f, -2f))
        val config = EqualizerMath.config(settings)
        val meanDb = EqualizerMath.weightedMeanDb(config.curveDb)
        assertEquals(meanDb + config.outputGainDb, config.bypassCompDb, 1e-9)

        val input = pinkMultisine(96_000)
        val processed = input.copyOf(); val bypassed = input.copyOf()
        val dryProcessor = EqualizerProcessor(); val bypassProcessor = EqualizerProcessor()
        dryProcessor.setConfig(config)
        bypassProcessor.setConfig(EqualizerMath.config(settings.copy(bypass = true)))
        dryProcessor.process(processed, processed.size); bypassProcessor.process(bypassed, bypassed.size)
        fun rms(x: FloatArray): Double {
            var energy = 0.0
            for (i in 10_000 until x.size) energy += x[i] * x[i]
            return kotlin.math.sqrt(energy / (x.size - 10_000))
        }
        val differenceLu = 20.0 * log10(rms(kWeight(processed)) / rms(kWeight(bypassed)))
        assertTrue("loudness difference=$differenceLu LU, raw=${config.preampDb}, gain=${config.outputGainDb}, mean=$meanDb, bypass=${config.bypassCompDb}", abs(differenceLu) <= 0.5)
    }

    private fun pinkMultisine(frameCount: Int, peakLevel: Double = 0.2): FloatArray {
        val count = 120
        val random = Random(801)
        val frequencies = DoubleArray(count) { 20.0 * (20_000.0 / 20.0).pow(it.toDouble() / (count - 1)) }
        // One equal-energy tone per log-frequency bin gives pink (equal power per octave) noise.
        val amp = DoubleArray(count) { 1.0 }
        val sinPhase = DoubleArray(count) { sin(random.nextDouble() * 2.0 * PI) }
        val cosPhase = DoubleArray(count) { kotlin.math.sqrt(1.0 - sinPhase[it] * sinPhase[it]) * if (random.nextBoolean()) 1.0 else -1.0 }
        val sinStep = DoubleArray(count) { sin(2.0 * PI * frequencies[it] / 48_000.0) }
        val cosStep = DoubleArray(count) { kotlin.math.cos(2.0 * PI * frequencies[it] / 48_000.0) }
        val raw = FloatArray(frameCount)
        var peak = 0.0
        for (frame in 0 until frameCount) {
            var sample = 0.0
            for (band in 0 until count) {
                sample += amp[band] * sinPhase[band]
                val next = sinPhase[band] * cosStep[band] + cosPhase[band] * sinStep[band]
                cosPhase[band] = cosPhase[band] * cosStep[band] - sinPhase[band] * sinStep[band]
                sinPhase[band] = next
            }
            raw[frame] = sample.toFloat(); peak = max(peak, abs(sample))
        }
        val scale = (peakLevel / peak).toFloat()
        for (i in raw.indices) raw[i] *= scale
        return raw
    }

    private fun kWeight(input: FloatArray): FloatArray {
        val b = arrayOf(doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285), doubleArrayOf(1.0, -2.0, 1.0))
        val a = arrayOf(doubleArrayOf(1.0, -1.69065929318241, 0.73248077421585), doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621))
        val state = Array(2) { DoubleArray(4) }
        val output = FloatArray(input.size)
        for (i in input.indices) {
            var x = input[i].toDouble()
            for (stage in 0..1) {
                val s = state[stage]
                val y = b[stage][0] * x + b[stage][1] * s[0] + b[stage][2] * s[1] - a[stage][1] * s[2] - a[stage][2] * s[3]
                s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y
                x = y
            }
            output[i] = x.toFloat()
        }
        return output
    }
}
