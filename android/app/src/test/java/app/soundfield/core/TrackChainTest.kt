package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class TrackChainTest {
    private val seg = SegmentedPcmSource.SEGMENT_FRAMES

    /** Трек из [segments] сегментов; каждый кадр — константа [level] (24 бит). */
    private fun track(segments: Int, lastFrames: Int, level: Float): SegmentedPcmSource {
        val total = (segments - 1L) * seg + lastFrames
        val t = SegmentedPcmSource("t", "a", total)
        for (i in 0 until segments) {
            val n = if (i == segments - 1) lastFrames else seg
            val x = FloatArray(n * 2) { level }
            val bytes = ByteArray(n * 6)
            Pcm24StereoEncoder(seed = 1).use { it.encode(x, n, bytes) }
            t.publish(i.toLong(), ByteBuffer.wrap(bytes), n)
        }
        t.markEnd(total)
        return t
    }

    @Test fun nextTrackStartsOnTheFrameWhereThePreviousEnds() {
        val a = track(2, 1000, 0.25f)
        val b = track(1, 500, -0.5f)
        val chain = TrackChain(a)
        assertTrue(chain.complete)
        val entry = chain.append(b)
        assertEquals(a.totalFrames, entry.offset)
        assertEquals(a.totalFrames + b.totalFrames, chain.totalFrames)
        // Последний кадр первого трека и первый кадр второго — соседние кадры шкалы.
        assertEquals(0.25f, chain.sample(a.totalFrames - 1, 0), 1e-4f)
        assertEquals(-0.5f, chain.sample(a.totalFrames, 0), 1e-4f)
        // Готовность идёт через границу: первый готов до конца, второй с начала.
        chain.focus(10)
        assertEquals(chain.totalFrames, chain.readyFrames)
        assertTrue(chain.complete)
        assertEquals(a.totalFrames, chain.nextTrackStartAfter(0))
        assertTrue(chain.trackKeyAt(0) != chain.trackKeyAt(a.totalFrames))
    }

    @Test fun playedTracksAreDroppedAndChangedQueueDetachesTheNext() {
        val a = track(1, 1000, 0.1f)
        val b = track(1, 1000, 0.2f)
        val chain = TrackChain(a)
        val first = chain.first
        chain.append(b)
        assertEquals(listOf(b), chain.truncateAfter(first))
        assertEquals(1, chain.entryCount)
        chain.append(b)
        assertEquals(listOf(a), chain.dropEndedBefore(1500))
        assertEquals(1, chain.entryCount)
        assertEquals(0.2f, chain.sample(1200, 1), 1e-4f)
        assertEquals(0f, chain.sample(10, 0), 0f)   // сыгранный трек отпущен
        assertFalse(chain.entriesSnapshot().any { it.track === a })
    }
}
