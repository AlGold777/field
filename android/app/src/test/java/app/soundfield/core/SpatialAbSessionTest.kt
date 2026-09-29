package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAbSessionTest {
    @Test fun createsSixRandomizedComparisonsAndTwoAEqualsAControls() {
        val session = SpatialAbSession.create(SpatialAbComparison.OFF_SOFT, SpatialSettings(),
            anchorMs = 12_000, resumeAfter = true, fixedCompensationDb = 1.0, seed = 44)
        assertEquals(8, session.trials.size)
        assertEquals(2, session.trials.count { it.control })
        assertEquals(6, session.trials.count { !it.control })
        session.trials.filter { it.control }.forEach { assertEquals(it.variantA, it.variantB) }
        session.trials.filter { !it.control }.forEach { assertNotEquals(it.variantA, it.variantB) }
    }

    @Test fun correctDetectionProbabilityUsesAnExactOneSidedBinomialTail() {
        var session = SpatialAbSession.create(SpatialAbComparison.SOFT_STRONG, SpatialSettings(),
            anchorMs = 0, resumeAfter = false, fixedCompensationDb = 0.0, seed = 10)
        repeat(SpatialAbSession.TRIAL_COUNT) {
            val control = session.trials[session.currentTrial].control
            session = session.answerCurrent(SpatialAbAnswer(!control, "a", "same", "b"))
        }
        assertTrue(session.complete)
        assertEquals(8, session.correctDetections)
        assertEquals(1.0 / 256.0, session.pValue, 0.0)
    }
}
