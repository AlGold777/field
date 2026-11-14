package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.soundfield.dsp.SpatialDspConfig
import kotlin.math.PI
import kotlin.math.sin

class SpatialSourceTest {
    @Test fun stftIdentityIsAlignedAndStableForArbitraryReadOrder() {
        val input = ProceduralSource(SF.SAMPLE_RATE * 4L)
        SpatialSource(input).use { spatial ->
            spatial.requestThrough(input.totalFrames)
            await("identity processing") { spatial.complete || spatial.processingError != null }
            spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
            assertTrue(spatial.complete)

            val indices = longArrayOf(0, 1, 511, 512, 17_003, 23_999, 48_000, 99_999, input.totalFrames - 1)
            val expected = indices.map { frame -> floatArrayOf(input.sample(frame, Ch.L), input.sample(frame, Ch.R)) }
            for (index in indices.indices.reversed()) {
                assertEquals("L frame ${indices[index]}", expected[index][0], spatial.sample(indices[index], Ch.L), 0f)
                assertEquals("R frame ${indices[index]}", expected[index][1], spatial.sample(indices[index], Ch.R), 0f)
            }
            // Every frame is available in cache and sample output is independent of consumer request order.
            for (frame in 0 until input.totalFrames step 97) {
                assertEquals(input.sample(frame, Ch.L), spatial.sample(frame, Ch.L), 0f)
                assertEquals(input.sample(frame, Ch.R), spatial.sample(frame, Ch.R), 0f)
            }
            assertEquals(0L, spatial.misses)
        }
    }

    @Test fun progressiveInputNeverPublishesFramesAheadOfDecoder() {
        val input = ProgressiveSource(SF.SAMPLE_RATE * 2L)
        SpatialSource(input).use { spatial ->
            Thread.sleep(25)
            assertEquals(0L, spatial.readyFrames)
            input.publish(SF.SAMPLE_RATE / 2L)
            await("first progressive block") { spatial.readyFrames > 0 }
            assertTrue(spatial.readyFrames <= input.readyFrames)
            input.finish()
            spatial.requestThrough(input.totalFrames)
            await("progressive tail") { spatial.complete || spatial.processingError != null }
            spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
            assertEquals(input.totalFrames, spatial.readyFrames)
        }
    }

    @Test fun requestOutsideSmallCacheWarmsFromSeekPosition() {
        val input = ProceduralSource(SF.SAMPLE_RATE * 5L)
        SpatialSource(input, cacheSeconds = 1).use { spatial ->
            spatial.requestThrough(SF.SAMPLE_RATE.toLong())
            await("initial cache") { spatial.readyFrames >= SF.SAMPLE_RATE }
            val seekFrame = SF.SAMPLE_RATE * 3L
            spatial.sample(seekFrame, Ch.L)
            spatial.requestThrough(seekFrame + SF.SAMPLE_RATE / 2)
            await("seek restart") {
                spatial.restarts > 0 && spatial.readyFrames >= seekFrame + SF.SAMPLE_RATE / 2
            }
            assertTrue(spatial.processingError == null)
            assertEquals(input.sample(seekFrame, Ch.L), spatial.sample(seekFrame, Ch.L), 2e-6f)
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue("Timed out waiting for $label", condition())
    }

    private open class ProceduralSource(override val totalFrames: Long) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "synthetic"
        override val artist = "test"
        override fun sample(frame: Long, ch: Int): Float {
            if (frame !in 0 until totalFrames) return 0f
            val t = frame.toDouble() / SF.SAMPLE_RATE
            val fundamental = sin(2 * PI * (431.0 + ch * 37.0) * t)
            val harmonic = 0.22 * sin(2 * PI * (3_117.0 + ch * 113.0) * t)
            val attack = if (frame % SF.SAMPLE_RATE < 96) (frame % SF.SAMPLE_RATE) / 96.0 else 1.0
            return ((fundamental + harmonic) * attack * 0.45).toFloat()
        }
    }

    private class ProgressiveSource(total: Long) : ProceduralSource(total) {
        @Volatile override var readyFrames: Long = 0
            private set
        @Volatile override var complete = false
            private set

        fun publish(frames: Long) { readyFrames = frames.coerceAtMost(totalFrames) }
        fun finish() { readyFrames = totalFrames; complete = true }

        override fun sample(frame: Long, ch: Int): Float =
            if (frame < readyFrames) super.sample(frame, ch) else Float.NaN
    }

    private class MonoSource(total: Long) : ProceduralSource(total) {
        override fun sample(frame: Long, ch: Int): Float = super.sample(frame, Ch.L)
    }
}
