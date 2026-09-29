package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class SpatialPeakLimiterTest {
    @Test fun linkedLimiterHasFixedLookaheadAndKeepsStereoRatio() {
        val limiter = SpatialPeakLimiter(sampleRate = 1_000, lookaheadFrames = 5)
        var impulseOut = -1
        for (frame in 0L..20L) {
            val left = if (frame == 7L) 2f else 0f
            val right = if (frame == 7L) 1f else 0f
            if (limiter.process(frame, left, right) && limiter.outputLeft != 0f) {
                impulseOut = limiter.outputFrame.toInt()
                assertEquals(limiter.outputLeft * 0.5f, limiter.outputRight, 1e-6f)
                assertTrue(kotlin.math.abs(limiter.outputLeft) <= 10.0.pow(-0.3 / 20.0))
            }
        }
        assertEquals(7, impulseOut)
        assertTrue(limiter.maxReductionDb > 5.0)
    }

    @Test fun belowFullScaleIsBitTransparent() {
        val limiter = SpatialPeakLimiter(sampleRate = 48_000, lookaheadFrames = 240)
        val input = FloatArray(600) { if (it % 2 == 0) 0.4f else -0.75f }
        val output = FloatArray(input.size)
        var count = 0
        for (frame in input.indices) {
            if (limiter.process(frame.toLong(), input[frame], input[frame])) {
                output[limiter.outputFrame.toInt()] = limiter.outputLeft
                count++
            }
        }
        assertEquals(input.size - 240, count)
        for (frame in 0 until count) assertEquals(input[frame], output[frame], 0f)
    }
}
