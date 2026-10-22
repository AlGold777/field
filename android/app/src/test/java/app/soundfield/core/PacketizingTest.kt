package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketizingTest {
    private class Fake(override val totalFrames: Long, override val readyFrames: Long) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "t"
        override val artist = "a"
        override fun sample(frame: Long, ch: Int) = 0f
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
}
