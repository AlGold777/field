package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketizingTest {
    private class Fake(
        override val totalFrames: Long,
        override val readyFrames: Long,
        override val firstReadyFrame: Long = 0,
        private val values: Map<Long, Float> = emptyMap(),
    ) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "t"
        override val artist = "a"
        override fun sample(frame: Long, ch: Int) = values[frame] ?: 0f
    }

    /** Отправитель двигает курсор на count: суммарно должны уйти все готовые кадры, без дыр. */
    private fun sendAll(source: Fake, from: Long): List<Pair<Long, Int>> {
        val sent = mutableListOf<Pair<Long, Int>>()
        var cursor = from
        while (cursor < source.readyFrames) {
            val count = Packetizing.frameCount(source, cursor)
            if (count <= 0) break
            sent += cursor to count
            cursor += count
        }
        return sent
    }

    @Test fun shortReadyTailDoesNotSkipFrames() {
        val source = Fake(totalFrames = 1_000_000, readyFrames = 99_840 + 161)
        val sent = sendAll(source, 99_840L - SF.PACKET_FRAMES * 3)
        val last = sent.last()
        assertEquals(161, last.second)
        for (i in 1 until sent.size) assertEquals(sent[i - 1].first + sent[i - 1].second, sent[i].first)
        assertEquals(source.readyFrames, last.first + last.second)
    }

    @Test fun fullPacketsAreFullSize() {
        val source = Fake(totalFrames = 1_000_000, readyFrames = 1_000_000)
        assertEquals(SF.PACKET_FRAMES, Packetizing.frameCount(source, 12_345))
    }

    @Test fun endOfTrackAndNotReadyGiveShortOrEmptyPackets() {
        assertEquals(100, Packetizing.frameCount(Fake(1_000, 1_000), 900))
        assertEquals(0, Packetizing.frameCount(Fake(1_000, 500), 700))
        assertEquals(0, Packetizing.frameCount(Fake(1_000, 1_000), 1_000))
    }

    @Test fun availableReadPreservesDigitalZeroAndStopsAtUnavailableSample() {
        val source = Fake(
            totalFrames = 12,
            readyFrames = 12,
            values = mapOf(4L to 0f, 5L to 0.25f, 6L to Float.NaN),
        )
        val buffer = FloatArray(6) { -1f }

        val read = Packetizing.readAvailable(source, start = 4, channel = 0, buffer = buffer, count = 6)

        assertEquals(2, read)
        assertEquals(0f, buffer[0], 0f)
        assertEquals(0.25f, buffer[1], 0f)
        assertEquals(-1f, buffer[2], 0f)
    }

    @Test fun availableReadHonorsReadyWindowAndArrayBounds() {
        val source = Fake(totalFrames = 20, readyFrames = 8, firstReadyFrame = 3)
        val buffer = FloatArray(4)

        assertEquals(0, Packetizing.readAvailable(source, 2, 0, buffer, 4))
        assertEquals(4, Packetizing.readAvailable(source, 3, 0, buffer, 4))
        assertEquals(1, Packetizing.readAvailable(source, 7, 0, buffer, 4))
        assertEquals(0, Packetizing.readAvailable(source, 8, 0, buffer, 4))
        assertEquals(0, Packetizing.readAvailable(source, 3, 0, buffer, 0))
    }

    @Test fun packetCountDoesNotUnderflowBeforeReadyWindowOrOverflowAtLongBoundary() {
        val source = Fake(totalFrames = Long.MAX_VALUE, readyFrames = Long.MAX_VALUE, firstReadyFrame = Long.MAX_VALUE - 2)

        assertEquals(0, Packetizing.frameCount(source, Long.MAX_VALUE - 3))
        assertEquals(2, Packetizing.frameCount(source, Long.MAX_VALUE - 2))
        assertEquals(0, Packetizing.frameCount(source, Long.MAX_VALUE))
        assertEquals(2, Packetizing.readAvailable(source, Long.MAX_VALUE - 2, 0, FloatArray(4), 4))
    }
}
