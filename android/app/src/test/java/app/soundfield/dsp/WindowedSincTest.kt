package app.soundfield.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class WindowedSincTest {
    @Test fun integerPositionsReproduceTheSourceSample() {
        for (frame in -100L..100L) {
            val expected = (frame / 100.0).toFloat()
            val actual = WindowedSinc.interpolate(frame.toDouble()) { (it / 100.0).toFloat() }
            assertEquals(expected, actual, 1e-6f)
        }
    }

    @Test fun fractionalPositionsAccuratelyInterpolateAudioBandSines() {
        for (frequency in listOf(1_000.0, 10_000.0, 18_000.0)) {
            val position = 123.375
            val expected = sin(2.0 * PI * frequency * position / 48_000.0).toFloat()
            val actual = WindowedSinc.interpolate(position) {
                sin(2.0 * PI * frequency * it / 48_000.0).toFloat()
            }
            assertTrue("$frequency Hz error=${kotlin.math.abs(actual - expected)}", kotlin.math.abs(actual - expected) < 0.001f)
        }
    }
}
