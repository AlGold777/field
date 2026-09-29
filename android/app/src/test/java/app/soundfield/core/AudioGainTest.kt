package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

class AudioGainTest {
    private fun db(slider: Float) = 20.0 * log10(AudioGain.taper(AudioGain.sliderToMaster(slider)).toDouble())

    @Test fun sliderEndpointsAreMuteAndFullScale() {
        assertEquals(0f, AudioGain.taper(AudioGain.sliderToMaster(0f)), 0f)
        assertEquals(1f, AudioGain.taper(AudioGain.sliderToMaster(1f)), 1e-6f)
    }

    @Test fun sliderIsMonotonicAndSmoothInDecibels() {
        var previous = Double.NEGATIVE_INFINITY
        var u = 0.03f
        while (u <= 1f) {
            val level = db(u)
            assertTrue("gain must grow with the slider at $u", level > previous)
            previous = level
            u += 0.01f
        }
    }

    @Test fun lowThirdOfTheSliderStaysAudible() {
        // Раньше 30 % ползунка давали −35 дБ и ниже, а первые 10 % — глубже −45 дБ.
        assertTrue("10 % must be above −32 dB, got ${db(0.10f)}", db(0.10f) > -32.0)
        assertTrue("30 % must be above −16 dB, got ${db(0.30f)}", db(0.30f) > -16.0)
        assertTrue("50 % must be above −10 dB, got ${db(0.50f)}", db(0.50f) > -10.0)
    }

    /** Воспринимаемая громкость по Стивенсу растёт как амплитуда^0,6: равные шаги ползунка должны давать равные шаги. */
    private fun loudness(slider: Float) = Math.pow(AudioGain.taper(AudioGain.sliderToMaster(slider)).toDouble(), 0.6)

    @Test fun equalSliderStepsGiveComparablePerceivedLoudnessSteps() {
        val steps = (1..9).map { i -> loudness(0.1f * (i + 1)) - loudness(0.1f * i) }
        val ratio = steps.max() / steps.min()
        assertTrue("perceived steps must be even (max/min=$ratio): $steps", ratio < 2.0)
    }

    @Test fun masterRoundTripsThroughTheSlider() {
        for (u in listOf(0.03f, 0.1f, 0.25f, 0.5f, 0.8f, 1f)) {
            assertEquals(u, AudioGain.masterToSlider(AudioGain.sliderToMaster(u)), 0.003f)
        }
        for (m in listOf(0.2f, 0.5f, 0.8f, 0.96f, 1f)) {
            assertEquals(m, AudioGain.sliderToMaster(AudioGain.masterToSlider(m)), 0.003f)
        }
    }

    @Test fun storedMasterKeepsItsGainLaw() {
        assertEquals(0.5f, AudioGain.masterToSlider(0.5f).let { AudioGain.sliderToMaster(it) }, 0.003f)
        assertTrue(abs(20.0 * log10(AudioGain.taper(0.5f).toDouble()) + 25.0) < 0.01)
    }
}
