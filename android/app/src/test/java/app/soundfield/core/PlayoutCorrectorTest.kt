package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayoutCorrectorTest {
    @Test fun positionsRemainFractionalAndDoNotSkipWholeSamples() {
        val corrector = PlayoutCorrector()
        corrector.plan(10_000.25, 480)
        assertEquals(10_000.25, corrector.position(0), 1e-12)
        corrector.plan(10_485.05, 480)
        assertTrue(corrector.position(1) > corrector.position(0) + 1.0)
        assertTrue(corrector.position(1) < corrector.position(0) + 1.001)
    }

    @Test fun fractionalRateCorrectionConvergesUnderOneHundredPpmClockDrift() {
        val corrector = PlayoutCorrector()
        val block = 480
        var ideal = 100_000.0
        repeat(12_000) {
            corrector.plan(ideal, block)
            ideal += block * 1.0001
        }
        corrector.plan(ideal, block)
        assertTrue("remaining error ${corrector.syncErrorUs} us", kotlin.math.abs(corrector.syncErrorUs) <= 2.0)
        assertEquals(0, corrector.hardJumps)
        assertTrue(corrector.slewEvents > 0)
    }

    @Test fun oneHundredMicrosecondPhaseStepMostlySettlesWithinTenSeconds() {
        val corrector = PlayoutCorrector()
        val block = 480
        corrector.plan(1_000.0, block)
        var ideal = 1_000.0 + block + 4.8
        repeat(1_000) {
            corrector.plan(ideal, block)
            ideal += block
        }
        corrector.plan(ideal, block)
        assertTrue("remaining error ${corrector.syncErrorUs} us", kotlin.math.abs(corrector.syncErrorUs) <= 20.0)
    }
}

class ClockModelSmootherTest {
    @Test fun modelOffsetStepIsAppliedAtTwoMicrosecondsPerSecond() {
        val smoother = ClockModelSmoother()
        val now = 10_000_000_000L
        smoother.reset(ClockSync.Model(now, 0.0, 0.0, true), now)
        val shifted = ClockSync.Model(now, 100_000.0, 0.0, true)
        assertEquals(100.0, smoother.deltaTo(shifted, now) / 1_000.0, 1e-9)
        smoother.update(shifted, now, immediate = false)
        assertEquals(now, smoother.toServer(now))
        assertEquals(now + 1_000_002_000L, smoother.toServer(now + 1_000_000_000L))
        assertEquals(now + 50_000_100_000L, smoother.toServer(now + 50_000_000_000L))
    }

    @Test fun immediateRouteChangeUsesTheNewClockModelImmediately() {
        val smoother = ClockModelSmoother()
        val now = 20_000_000_000L
        smoother.reset(ClockSync.Model(now, 0.0, 0.0, true), now)
        smoother.update(ClockSync.Model(now, 3_000_000.0, 0.0, true), now, immediate = true)
        assertEquals(now + 3_000_000L, smoother.toServer(now))
    }
}
