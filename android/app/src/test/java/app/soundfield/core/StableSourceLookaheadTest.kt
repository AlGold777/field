package app.soundfield.core

import app.soundfield.dsp.WindowedSinc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableSourceLookaheadTest {
    @Test fun ordinaryAudioSourcesStartAtFrameZeroByDefault() {
        val source = ConstantSource(totalFrames = SF.SAMPLE_RATE.toLong())
        assertEquals(0L, source.firstReadyFrame)
    }

    @Test fun preLimitedSourcePublishesAStableRetainedWindow() {
        val input = RampSource(8L * SF.SAMPLE_RATE)
        PreLimitedSource(input, cacheSeconds = 2).use { source ->
            val target = 3L * SF.SAMPLE_RATE
            source.requestThrough(target)
            await("prefetch beyond the two-second ring") {
                source.readyFrames >= target && source.firstReadyFrame > 0
            }

            val first = source.firstReadyFrame
            assertTrue("retained window must start before its exclusive ready head", first < source.readyFrames)
            val missesBeforeProbe = source.misses
            assertTrue("prepared read below retention must be unavailable", source.readPrepared(first - 1, Ch.L).isNaN())
            assertEquals("prepared reads must not move the retained window", first, source.firstReadyFrame)
            assertEquals("prepared reads must not schedule a restart", missesBeforeProbe, source.misses)

            val boundarySample = source.readPrepared(first, Ch.L)
            assertTrue("first retained frame must be readable", boundarySample.isFinite())
            assertEquals("repeated reads of retained PCM must be bit stable", boundarySample, source.readPrepared(first, Ch.L), 0f)
        }
    }

    @Test fun rendererLookaheadKeepsPreLimitedPlaybackInsideItsRing() {
        val input = ConstantSource(totalFrames = 8L * SF.SAMPLE_RATE)
        PreLimitedSource(input, cacheSeconds = 2).use { source ->
            source.requestThrough(2L * SF.SAMPLE_RATE)
            await("initial two-second prelimit cache") { source.readyFrames >= 2L * SF.SAMPLE_RATE }
            val start = SF.SAMPLE_RATE * 7L / 4L
            val state = ServerPlayState(Timeline(1, true, start, 0), source, Ch.L, 1f)
            val renderer = ServerRenderer { state }
            val block = SF.SAMPLE_RATE / 200 // 5 ms
            val output = FloatArray(block)

            renderer.render(output, block, 0L)
            await("renderer-driven prefetch past 2.2 seconds") { source.readyFrames >= SF.SAMPLE_RATE * 22L / 10L }
            var peak = output.maxOf { kotlin.math.abs(it) }

            repeat(79) { callback ->
                renderer.render(output, block, (callback + 1L) * 5_000_000L)
                peak = maxOf(peak, output.maxOf { kotlin.math.abs(it) })
            }

            assertTrue("rendered audio should contain signal", peak > 0.05f)
            assertEquals("playback must not miss samples while the ring advances", 0L, renderer.sourceMissingFrames.get())
        }
    }

    @Test fun serverRendererRequestsOneAdvancingHalfSecondWindowPerBlock() {
        val source = RecordingSource(total = 20L * SF.SAMPLE_RATE)
        val state = ServerPlayState(Timeline(1, true, 0, 0), source, Ch.L, 1f)
        val renderer = ServerRenderer { state }
        val block = SF.SAMPLE_RATE / 100

        renderer.render(FloatArray(block), block, 2_000_000_000L)
        assertEquals("one prefetch request per render block", 1, source.requests.size)
        assertLookaheadCoversLastSincTap(renderer, source.requests.single(), block)

        renderer.render(FloatArray(block), block, 2_010_000_000L)
        assertEquals("one additional request for the next block", 2, source.requests.size)
        assertTrue("prefetch head should advance with playback", source.requests[1] > source.requests[0])
        assertLookaheadCoversLastSincTap(renderer, source.requests[1], block)
    }

    @Test fun serverRendererClampsPrefetchAtScheduledStopAfterSincTaps() {
        val stop = 2L * SF.SAMPLE_RATE + SF.SAMPLE_RATE / 5
        val source = RecordingSource(total = 20L * SF.SAMPLE_RATE)
        val state = ServerPlayState(Timeline(1, true, 0, 0, stop), source, Ch.L, 1f)
        val renderer = ServerRenderer { state }
        val block = SF.SAMPLE_RATE / 100

        renderer.render(FloatArray(block), block, 2_000_000_000L)

        assertEquals(1, source.requests.size)
        assertTrue("prefetch may include sinc taps around the stop", source.requests.single() <= stop + WindowedSinc.HALF + 1)
        assertTrue("prefetch should reach the scheduled stop", source.requests.single() >= stop)
    }

    private fun assertLookaheadCoversLastSincTap(renderer: ServerRenderer, requested: Long, block: Int) {
        val lastPosition = renderer.corrector.position(block - 1)
        val lastTapExclusive = kotlin.math.floor(lastPosition).toLong() + WindowedSinc.HALF + 1
        assertTrue("request must cover the last sinc tap plus 500 ms", requested - lastTapExclusive >= LOOKAHEAD_FRAMES)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue("Timed out waiting for $label", condition())
    }

    private open class ConstantSource(override val totalFrames: Long) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "synthetic"
        override val artist = "test"
        override fun sample(frame: Long, ch: Int) = 0.125f
    }

    private class RampSource(total: Long) : ConstantSource(total) {
        override fun sample(frame: Long, ch: Int): Float = ((frame % 997L) - 498L).toFloat() / 2_000f
    }

    private class RecordingSource(total: Long) : ConstantSource(total), PrefetchableAudioSource {
        val requests = mutableListOf<Long>()
        override fun requestThrough(frameExclusive: Long) { requests += frameExclusive }
    }

    companion object {
        private const val LOOKAHEAD_FRAMES = SF.SAMPLE_RATE / 2L
    }
}
