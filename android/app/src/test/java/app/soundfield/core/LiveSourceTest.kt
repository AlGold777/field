package app.soundfield.core

import app.soundfield.dsp.WindowedSinc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class LiveSourceTest {
    private fun ramp(start: Int, frames: Int) = FloatArray(frames * 2) { i -> (start + i / 2) * (if (i % 2 == 0) 1f else -1f) }

    @Test fun framesAreAddressedAbsolutelyAndSilentOutsideTheReadyRange() {
        val live = LiveSource("t", "a", capacitySeconds = 2)
        live.append(ramp(0, 1_000), 1_000)
        assertEquals(1_000L, live.readyFrames)
        assertEquals(10f, live.sample(10, Ch.L), 0f)
        assertEquals(-10f, live.sample(10, Ch.R), 0f)
        assertEquals(0f, live.sample(1_000, Ch.L), 0f)   // ещё не принято
        assertEquals(0f, live.sample(-1, Ch.L), 0f)
        assertTrue(!live.complete && live.totalFrames > live.readyFrames)
    }

    @Test fun ringKeepsTheMostRecentAudioAfterWrap() {
        val live = LiveSource("t", "a", capacitySeconds = 2)
        val capacity = 2 * SF.SAMPLE_RATE
        var written = 0
        while (written < capacity * 3 / 2) { live.append(ramp(written, 4_800), 4_800); written += 4_800 }
        val head = live.readyFrames
        assertEquals(written.toLong(), head)
        assertEquals((head - 1).toFloat(), live.sample(head - 1, Ch.L), 0f)
        assertEquals((head - capacity).toFloat(), live.sample(head - capacity, Ch.L), 0f)
        assertEquals(0f, live.sample(head - capacity - 1, Ch.L), 0f)   // перезаписано
        live.appendSilence(100)
        assertEquals(0f, live.sample(head + 50, Ch.R), 0f)
        assertEquals(head + 100, live.readyFrames)
    }

    @Test fun servoIsBitExactWhenTheMarginIsOnTarget() {
        val live = LiveSource("t", "a", capacitySeconds = 4)
        val servo = LiveClockServo(live, targetMarginFrames = 48_000)
        val n = 48_000
        val x = FloatArray(n * 2) { i -> sin(i * 0.37).toFloat() * 0.5f }
        var offset = 0
        while (offset < n) {
            val frames = minOf(480, n - offset)
            servo.write(x.copyOfRange(offset * 2, (offset + frames) * 2), frames, marginFrames = 48_000)
            offset += frames
        }
        assertEquals(0.0, servo.ratioPpm, 0.0)
        val produced = live.readyFrames.toInt()
        // Выходной кадр f — это входной f + HALF − 1; интерполятору нужны ещё HALF будущих отводов.
        assertEquals(n - 2 * WindowedSinc.HALF + 1, produced)
        for (f in 0 until produced) {
            assertEquals(x[(f + WindowedSinc.HALF - 1) * 2], live.sample(f.toLong(), Ch.L), 0f)
            assertEquals(x[(f + WindowedSinc.HALF - 1) * 2 + 1], live.sample(f.toLong(), Ch.R), 0f)
        }
    }

    /** Источник идёт на 200 ppm быстрее шкалы воспроизведения: без привязки запас за 15 минут вырос бы на 180 мс. */
    @Test fun servoHoldsTheMarginAgainstClockDrift() {
        val live = LiveSource("t", "a", capacitySeconds = 4)
        val target = 48_000
        val servo = LiveClockServo(live, target)
        val driftPpm = 200.0
        val block = FloatArray(480 * 2)
        var position = 0.0
        var started = false
        var worstMs = 0.0
        val steps = 15 * 60 * 100
        for (step in 0 until steps) {
            val margin = if (started) (live.readyFrames - position).toLong() else null
            servo.write(block, 480, margin)
            if (!started && live.readyFrames >= target) { started = true; position = (live.readyFrames - target).toDouble() }
            else if (started) position += 480.0 / (1.0 + driftPpm * 1e-6)
            if (started) worstMs = maxOf(worstMs, abs(live.readyFrames - position - target) * 1000.0 / 48_000)
        }
        val finalMs = (live.readyFrames - position - target) * 1000.0 / 48_000
        println("MEASURED servo: drift $driftPpm ppm, ratio ${"%.1f".format(servo.ratioPpm)} ppm, margin error final ${"%.1f".format(finalMs)} ms, worst ${"%.1f".format(worstMs)} ms")
        assertEquals(driftPpm, servo.ratioPpm, 20.0)
        assertTrue("final $finalMs ms", abs(finalMs) < 50.0)
        assertTrue("worst $worstMs ms", worstMs < 60.0)
    }

    @Test fun servoKeepsATenKilohertzToneLevelAtFullCorrection() {
        val live = LiveSource("t", "a", capacitySeconds = 4)
        val servo = LiveClockServo(live, 48_000)
        val n = 96_000
        val x = FloatArray(n * 2) { i -> (0.5 * sin(2 * PI * 10_000.0 * (i / 2) / 48_000)).toFloat() }
        var offset = 0
        // Запас на 1 с больше цели: отношение упирается в предел +1000 ppm.
        while (offset < n) { servo.write(x.copyOfRange(offset * 2, (offset + 480) * 2), 480, 96_000); offset += 480 }
        assertEquals(LiveClockServo.MAX_PPM, servo.ratioPpm, 0.0)
        var sum = 0.0
        val from = 4_800L; val to = live.readyFrames - 4_800
        for (f in from until to) { val v = live.sample(f, Ch.L).toDouble(); sum += v * v }
        val db = 20 * log10(sqrt(sum / (to - from)) / (0.5 / sqrt(2.0)))
        println("MEASURED servo 10 kHz level at +1000 ppm: ${"%.4f".format(db)} dB")
        assertTrue("level $db dB", abs(db) < 0.02)
    }
}
