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

    // ---- автомат живого потока

    private val second = SF.SAMPLE_RATE.toLong()

    @Test fun captureGapIsPaddedWithSilenceInsteadOfStopping() {
        assertEquals(LivePolicy.Action.NONE, LivePolicy.whileRunning(capture = true, marginFrames = second))
        assertEquals(LivePolicy.Action.NONE, LivePolicy.whileRunning(capture = true, marginFrames = LivePolicy.MIN_MARGIN_FRAMES))
        assertEquals(LivePolicy.Action.PAD, LivePolicy.whileRunning(capture = true, marginFrames = LivePolicy.MIN_MARGIN_FRAMES - 1))
        assertEquals(LivePolicy.Action.PAD, LivePolicy.whileRunning(capture = true, marginFrames = -second))
        // Тишина доводит запас до цели, но не больше 2 с за такт.
        assertEquals((second * 6 / 10).toInt(), LivePolicy.padFrames(second * 4 / 10))
        assertEquals((second * 2).toInt(), LivePolicy.padFrames(-second))
        assertEquals(0, LivePolicy.padFrames(second * 2))
    }

    @Test fun captureIsReanchoredWhenThePositionOrTheMarginRunsAway() {
        assertEquals(LivePolicy.Action.REANCHOR, LivePolicy.whileRunning(capture = true, marginFrames = -second - 1))
        assertEquals(LivePolicy.Action.NONE, LivePolicy.whileRunning(capture = true, marginFrames = LivePolicy.MAX_MARGIN_FRAMES))
        assertEquals(LivePolicy.Action.REANCHOR, LivePolicy.whileRunning(capture = true, marginFrames = LivePolicy.MAX_MARGIN_FRAMES + 1))
    }

    @Test fun bufferedSourceStopsWhenStarvedAndIsNeverPaddedOrReanchored() {
        assertEquals(LivePolicy.Action.NONE, LivePolicy.whileRunning(capture = false, marginFrames = LivePolicy.MIN_MARGIN_FRAMES))
        assertEquals(LivePolicy.Action.STARVED, LivePolicy.whileRunning(capture = false, marginFrames = LivePolicy.MIN_MARGIN_FRAMES - 1))
        assertEquals(LivePolicy.Action.STARVED, LivePolicy.whileRunning(capture = false, marginFrames = -10 * second))
        assertEquals(LivePolicy.Action.NONE, LivePolicy.whileRunning(capture = false, marginFrames = 10 * second))
    }

    @Test fun captureStartsOnceTheMarginWillReachTheTargetAtTheStartMoment() {
        val delay = second * 3 / 10
        assertEquals(-1L, LivePolicy.captureStartFrame(head = second * 7 / 10 - 1, startDelayFrames = delay, segmentStart = 0))
        assertEquals(0L, LivePolicy.captureStartFrame(head = second * 7 / 10, startDelayFrames = delay, segmentStart = 0))
        // К моменту старта голова уйдёт на delay: запас = (head + delay) − frame = цель.
        val head = 5 * second
        val frame = LivePolicy.captureStartFrame(head, delay, segmentStart = 0)
        assertEquals(LivePolicy.TARGET_MARGIN_FRAMES, head + delay - frame)
        assertEquals(-1L, LivePolicy.captureStartFrame(head, delay, segmentStart = head))
    }

    @Test fun bufferedSourceResumesFromThePauseUnlessItIsStale() {
        // Пауза: продолжаем с того же кадра, не раньше начала текущего участка.
        assertEquals(1_000L, LivePolicy.resumeFrame(head = 3 * second, anchorFrame = 1_000, segmentStart = 0))
        assertEquals(2_000L, LivePolicy.resumeFrame(head = 3 * second, anchorFrame = 1_000, segmentStart = 2_000))
        // Место паузы отстало от головы больше, чем приёмник держит впереди: с головы.
        assertEquals(10 * second, LivePolicy.resumeFrame(head = 10 * second, anchorFrame = 1_000, segmentStart = 0))
        assertTrue(LivePolicy.STALE_FRAMES > app.soundfield.spotify.SpotifyReceiver.MAX_AHEAD_FRAMES + second)
        assertTrue(!LivePolicy.readyToStart(head = second, frame = second / 5))
        assertTrue(LivePolicy.readyToStart(head = second, frame = second / 10))
    }

    @Test fun spotifyBackpressureLeavesRoomAboveTheStartMargin() {
        // Приёмник держит впереди позиции больше, чем нужно для старта, иначе воспроизведение не начнётся.
        assertTrue(app.soundfield.spotify.SpotifyReceiver.MAX_AHEAD_FRAMES >= LivePolicy.START_MARGIN_FRAMES + second / 2)
    }
}
