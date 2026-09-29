package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class SpatialLoudnessCompensatorTest {
    @Test fun attenuatedProcessedBranchIsSlowlyMatchedWithinThreeDb() {
        val compensator = SpatialLoudnessCompensator()
        val sampleRate = 48_000
        val frames = sampleRate * 48
        for (frame in 0 until frames) {
            val input = (0.25 * sin(2.0 * PI * 1_000.0 * frame / sampleRate)).toFloat()
            compensator.observe(input, input, input * 0.5f, input * 0.5f)
        }
        assertTrue("compensation should lift the quieter branch", compensator.gain() > 1.3)
        assertEquals(3.0, compensator.compensationDb, 0.5)
    }

    @Test fun disabledCompensationLeavesGainAtUnity() {
        val compensator = SpatialLoudnessCompensator(enabled = false)
        repeat(48_000) { compensator.observe(0.4f, 0.4f, 0.2f, 0.2f) }
        assertEquals(1.0, compensator.gain(), 0.0)
        assertEquals(0.0, compensator.compensationDb, 0.0)
    }

    @Test fun fixedCompensationDoesNotDriftDuringABPlayback() {
        val compensator = SpatialLoudnessCompensator(fixedCompensationDb = 1.5)
        repeat(48_000 * 12) { compensator.observe(0.4f, 0.4f, 0.1f, 0.1f) }
        assertEquals(1.5, compensator.compensationDb, 1e-12)
        assertEquals(10.0.pow(1.5 / 20.0), compensator.gain(), 1e-12)
        compensator.reset()
        assertEquals(1.5, compensator.compensationDb, 1e-12)
    }
}
