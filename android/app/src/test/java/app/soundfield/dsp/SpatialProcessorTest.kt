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
}
