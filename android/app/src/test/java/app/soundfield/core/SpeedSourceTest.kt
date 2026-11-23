package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpeedSourceTest {
    /** Тон 440 Гц с линейно растущей огибающей env(f) = f / total; готовность ограничивается [limit]. */
    private class EnvSource(val frames: Long, @Volatile var limit: Long = frames) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "t"
        override val artist = "a"
        override val totalFrames = frames
        override val readyFrames get() = limit
        override val complete get() = limit >= frames
        private fun v(f: Long) = (f.toDouble() / frames * sin(2 * PI * 440 * f / 48_000)).toFloat()
        override fun sample(frame: Long, ch: Int) = readPrepared(frame, ch)
        override fun readPrepared(frame: Long, ch: Int): Float =
            if (frame < 0 || frame >= frames) 0f else if (frame >= limit) Float.NaN else v(frame)
    }

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!cond()) {
            assertTrue("timeout: $what", System.nanoTime() < end)
            Thread.sleep(2)
        }
    }

    @Test fun mappingAndTotalFrames() {
        val inner = EnvSource(480_000)
        SpeedSource(inner, 2.0, 48_000).use { s ->
            assertEquals(48_000L + (480_000 - 48_000) / 2, s.totalFrames)
            assertEquals(48_000L, s.inputFrameOf(48_000))
            assertEquals(48_000L + 200, s.inputFrameOf(48_000 + 100))
            assertEquals(48_000L + 100, s.outputFrameOf(48_000 + 200))
            assertEquals(s.inputFrameOf(100_000), s.inputFrameOf(100_000))
            assertEquals(100_000L, s.outputFrameOf(s.inputFrameOf(100_000)))
        }
        SpeedSource(EnvSource(480_000), 0.5, 0).use { s ->
            assertEquals(960_000L, s.totalFrames)
            assertEquals(500L, s.inputFrameOf(1_000))
        }
    }

    @Test fun contentFollowsFrameCorrespondence() {
        val frames = 10L * 48_000
        for (speed in doubleArrayOf(0.8, 1.5)) {
            val inner = EnvSource(frames)
            SpeedSource(inner, speed, 48_000).use { s ->
                val o = 200_000L
                s.requestThrough(o + 3_000)
                await("ready") { s.readyFrames >= o + 3_000 }
                var e = 0.0
                for (f in o until o + 2_400) { val x = s.readPrepared(f, 0).toDouble(); e += x * x }
                val rms = sqrt(e / 2_400) * sqrt(2.0)   // амплитуда
                val expected = (s.inputFrameOf(o + 1_200)).toDouble() / frames
                assertEquals("огибающая при $speed", expected, rms, 0.02)
            }
        }
    }

    @Test fun nanBeforeReadyAndReadinessFollowsInner() {
        val inner = EnvSource(10L * 48_000, limit = 48_000)   // готова 1 секунда
        SpeedSource(inner, 2.0, 0).use { s ->
            assertTrue(s.readPrepared(200_000, 0).isNaN())
            s.requestThrough(24_000)
            await("first half second") { s.readyFrames >= 20_000 }
            assertTrue(s.readyFrames <= 24_000)   // 48000 входных кадров / 2
            assertTrue(!s.complete)
            assertTrue(s.readPrepared(100, 0).isFinite())
            assertTrue(s.readPrepared(30_000, 0).isNaN())
            inner.limit = inner.frames
            s.requestThrough(s.totalFrames)
            await("complete") { s.complete }
            assertEquals(s.totalFrames, s.readyFrames)
            assertTrue(s.readPrepared(s.totalFrames - 1, 0).isFinite())
        }
    }

    @Test fun restartsWhenReaderJumpsFarAhead() {
        val inner = EnvSource(120L * 48_000)
        SpeedSource(inner, 1.5, 0, cacheSeconds = 2).use { s ->
            s.requestThrough(48_000)
            await("start") { s.readyFrames >= 48_000 }
            val far = 40L * 48_000
            var v = Float.NaN
            await("restart at far frame") { v = s.sample(far, 0); v.isFinite() }
            assertTrue(s.misses > 0)
            // и назад
            await("restart back") { s.sample(1_000, 0).isFinite() }
        }
    }
}
