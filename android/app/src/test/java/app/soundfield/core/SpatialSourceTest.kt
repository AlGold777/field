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

    @Test fun widthDoesNotSeparateACommonMonoSignalAndPreservesTheSum() {
        val frames = SF.SAMPLE_RATE.toLong()
        val mono = MonoSource(frames)
        SpatialSource(mono, spatialConfig = SpatialDspConfig(
            enabled = true, gMidDb = 5.0, gHighDb = 3.0, gAmbientDb = 3.0,
        )).use { spatial ->
            spatial.requestThrough(frames)
            await("width processing") { spatial.complete || spatial.processingError != null }
            spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
            var maxChannelDifference = 0f
            var maxSumDifference = 0f
            for (frame in 0 until frames step 73) {
                val left = spatial.sample(frame, Ch.L)
                val right = spatial.sample(frame, Ch.R)
                maxChannelDifference = maxOf(maxChannelDifference, kotlin.math.abs(left - right))
                maxSumDifference = maxOf(maxSumDifference, kotlin.math.abs(left + right - 2f * mono.sample(frame, Ch.L)))
            }
            assertTrue("common center must remain centered", maxChannelDifference < 2e-6f)
            assertTrue("width layer must preserve M", maxSumDifference < 2e-6f)
        }
    }

    @Test fun earlyReflectionsStayInSideAndPreserveTheMonoSum() {
        val input = AntiPhaseSource(2L * SF.SAMPLE_RATE)
        val taps = listOf(app.soundfield.dsp.SpatialReflectionTap(4.0, 7.0, -17.0))
        val baseConfig = SpatialDspConfig(enabled = true, preserveLoudness = false)
        SpatialSource(input, spatialConfig = baseConfig).use { reference ->
            SpatialSource(input, spatialConfig = baseConfig.copy(reflectionsEnabled = true, reflectionTaps = taps)).use { spatial ->
                reference.requestThrough(input.totalFrames); spatial.requestThrough(input.totalFrames)
                await("reference render") { reference.complete || reference.processingError != null }
                await("reflection render") { spatial.complete || spatial.processingError != null }
                reference.processingError?.let { throw AssertionError("reference worker failed", it) }
                spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
                var addedSideEnergy = 0.0; var sumError = 0.0
                for (frame in SF.SAMPLE_RATE.toLong() until input.totalFrames step 29) {
                    val outL = spatial.sample(frame, 0); val outR = spatial.sample(frame, 1)
                    val refL = reference.sample(frame, 0); val refR = reference.sample(frame, 1)
                    val sideDelta = ((outL - outR) - (refL - refR)) * 0.5
                    addedSideEnergy += sideDelta * sideDelta
                    sumError = maxOf(sumError, kotlin.math.abs(outL + outR - refL - refR).toDouble())
                }
                assertTrue("reflections should add delayed Side energy", addedSideEnergy > 0.01)
                assertTrue("Side reflections must preserve the mono sum", sumError < 2e-6)
            }
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

    private class AntiPhaseSource(override val totalFrames: Long) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "anti-phase tone"
        override val artist = "test"
        override fun sample(frame: Long, ch: Int): Float {
            if (frame !in 0 until totalFrames) return 0f
            val value = sin(2 * PI * 1_000.0 * frame / SF.SAMPLE_RATE) * 0.3
            return (if (ch == 0) value else -value).toFloat()
        }
    }
}
