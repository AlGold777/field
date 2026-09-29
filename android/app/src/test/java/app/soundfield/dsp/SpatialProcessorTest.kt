package app.soundfield.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sin

class SpatialProcessorTest {
    @Test fun depthScalesOnlyDiffuseMidAndLeavesSideUntouched() {
        val processor = SpatialProcessor(SpatialDspConfig(enabled = true, depthDb = -6.0))
        val random = Random(712)
        val size = SpatialProcessor.FFT_SIZE
        val bins = size / 2 + 1
        val realL = DoubleArray(size); val imagL = DoubleArray(size)
        val realR = DoubleArray(size); val imagR = DoubleArray(size)
        val inputMid = DoubleArray(64); val inputSide = DoubleArray(64)
        var beforeMid = 0.0; var beforeSide = 0.0
        var afterMid = 0.0; var afterSide = 0.0
        for (frame in 0 until 64) {
            realL.fill(0.0); imagL.fill(0.0); realR.fill(0.0); imagR.fill(0.0)
            for (bin in 1 until bins - 1) {
                val phaseL = random.nextDouble(0.0, 2.0 * PI)
                val phaseR = random.nextDouble(0.0, 2.0 * PI)
                realL[bin] = cos(phaseL); imagL[bin] = sin(phaseL)
                realR[bin] = cos(phaseR); imagR[bin] = sin(phaseR)
                val mirror = size - bin
                realL[mirror] = realL[bin]; imagL[mirror] = -imagL[bin]
                realR[mirror] = realR[bin]; imagR[mirror] = -imagR[bin]
            }
            var mid = 0.0; var side = 0.0
            for (bin in 14 until bins - 1) {
                val mr = (realL[bin] + realR[bin]) * 0.5
                val mi = (imagL[bin] + imagR[bin]) * 0.5
                val sr = (realL[bin] - realR[bin]) * 0.5
                val si = (imagL[bin] - imagR[bin]) * 0.5
                mid += mr * mr + mi * mi
                side += sr * sr + si * si
            }
            inputMid[frame] = mid; inputSide[frame] = side
            val hasOutput = processor.process(realL, imagL, realR, imagR)
            if (hasOutput && frame >= 30) {
                val inputFrame = frame - processor.lookaheadHops
                var outputMid = 0.0; var outputSide = 0.0
                for (bin in 14 until bins - 1) {
                    val mr = (realL[bin] + realR[bin]) * 0.5
                    val mi = (imagL[bin] + imagR[bin]) * 0.5
                    val sr = (realL[bin] - realR[bin]) * 0.5
                    val si = (imagL[bin] - imagR[bin]) * 0.5
                    outputMid += mr * mr + mi * mi
                    outputSide += sr * sr + si * si
                }
                beforeMid += inputMid[inputFrame]; beforeSide += inputSide[inputFrame]
                afterMid += outputMid; afterSide += outputSide
            }
        }
        val midDb = 10.0 * log10(afterMid / beforeMid)
        val sideDb = 10.0 * log10(afterSide / beforeSide)
        assertTrue("diffuse Mid should be attenuated, got $midDb dB", midDb < -4.0)
        assertTrue("depth must not change Side, got $sideDb dB", kotlin.math.abs(sideDb) < 0.1)
    }

    @Test fun bypassEmitsTheExactSpectrumAfterFixedLookahead() {
        val processor = SpatialProcessor(SpatialDspConfig())
        val random = Random(802)
        val inputL = Array(10) { DoubleArray(SpatialProcessor.FFT_SIZE) { random.nextDouble(-1.0, 1.0) } }
        val inputR = Array(10) { DoubleArray(SpatialProcessor.FFT_SIZE) { random.nextDouble(-1.0, 1.0) } }
        val realL = DoubleArray(SpatialProcessor.FFT_SIZE)
        val imagL = DoubleArray(SpatialProcessor.FFT_SIZE)
        val realR = DoubleArray(SpatialProcessor.FFT_SIZE)
        val imagR = DoubleArray(SpatialProcessor.FFT_SIZE)

        for (frame in inputL.indices) {
            inputL[frame].copyInto(realL); imagL.fill(0.0)
            inputR[frame].copyInto(realR); imagR.fill(0.0)
            val hasOutput = processor.process(realL, imagL, realR, imagR)
            if (frame < processor.lookaheadHops) assertTrue(!hasOutput)
            else {
                val expected = frame - processor.lookaheadHops
                assertTrue(hasOutput)
                assertTrue(inputL[expected].contentEquals(realL))
                assertTrue(inputR[expected].contentEquals(realR))
            }
        }
    }

    @Test fun diffuseBandReceivesTheConfiguredSideGain() {
        val input = object : app.soundfield.core.AudioSource {
            override val mode = app.soundfield.core.SourceMode.MUSIC
            override val title = "synthetic diffuse tone"
            override val artist = "test"
            override val totalFrames = 4L * SpatialProcessor.SAMPLE_RATE
            override fun sample(frame: Long, ch: Int): Float {
                val phase = if (ch == 0) 0.0 else PI / 6.0
                return (sin(2 * PI * 1_000.0 * frame / SpatialProcessor.SAMPLE_RATE + phase) * 0.2).toFloat()
            }
        }
        val spatial = app.soundfield.core.SpatialSource(input, spatialConfig = SpatialDspConfig(
            enabled = true, gMidDb = 3.0, gHighDb = 3.0, gAmbientDb = 3.0,
        ))
        try {
            spatial.requestThrough(input.totalFrames)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!spatial.complete && spatial.processingError == null && System.nanoTime() < deadline) Thread.sleep(2)
            spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
            var originalSide = 0.0; var processedSide = 0.0; var count = 0
            for (frame in 2L * SpatialProcessor.SAMPLE_RATE until input.totalFrames step 17) {
                val side = (input.sample(frame, 0) - input.sample(frame, 1)) * 0.5
                val changed = (spatial.sample(frame, 0) - spatial.sample(frame, 1)) * 0.5
                originalSide += side * side; processedSide += changed * changed; count++
            }
            val gainDb = 10.0 * kotlin.math.log10(processedSide / originalSide)
            assertTrue("expected audible Side growth, got $gainDb dB (n=$count)", gainDb in 1.0..4.5)
        } finally { spatial.close() }
    }
}
