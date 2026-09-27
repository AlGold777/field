package app.soundfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class TcpAudioTest {
    @Test fun wireRoundTripsHelloAndPcmFrames() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        TcpAudioWire.writeHello(out, "point-id", 42)
        TcpAudioWire.writeChunk(out, TcpAudioChunk(7, "track-id", 48_000L, shortArrayOf(-32768, 0, 32767)))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals("point-id" to 42, TcpAudioWire.readHello(input))
        val chunk = TcpAudioWire.readChunk(input)
        assertEquals(7, chunk.epoch)
        assertEquals("track-id", chunk.trackId)
        assertEquals(48_000L, chunk.startFrame)
        assertTrue(chunk.samples.contentEquals(shortArrayOf(-32768, 0, 32767)))
    }

    @Test fun twoEpochBanksKeepCurrentAudioWhileNextEpochPrebuffers() {
        val buffer = TcpAudioBuffer(capacityPow2 = 5)
        buffer.activate(1)
        buffer.write(TcpAudioChunk(1, "old", 100, shortArrayOf(1000, 1001, 1002)))
        buffer.prepare(2)
        buffer.write(TcpAudioChunk(2, "new", 100, shortArrayOf(2000, 2001, 2002)))
        assertEquals(1000 / 32768f, buffer.read(1, 100), 0.00001f)
        assertEquals(2000 / 32768f, buffer.read(2, 100), 0.00001f)
        assertEquals(3, buffer.contiguousFrames(2, 100, 10))
        buffer.activate(2)
        assertEquals(2000 / 32768f, buffer.read(2, 100), 0.00001f)
        assertTrue(buffer.read(1, 100).isNaN())
    }

    @Test fun ringReportsMissingFramesAndOverwritesOldAbsoluteFrames() {
        val buffer = TcpAudioBuffer(capacityPow2 = 4)
        buffer.activate(3)
        buffer.write(TcpAudioChunk(3, "track", 0, ShortArray(8) { it.toShort() }))
        assertEquals(8, buffer.contiguousFrames(3, 0, 20))
        assertEquals(8 / 48_000.0, buffer.bufferedSeconds(3, 0), 1e-9)
        buffer.write(TcpAudioChunk(3, "track", 16, ShortArray(8) { (it + 16).toShort() }))
        assertFalse(buffer.read(3, 0).isFinite())
        assertEquals(8, buffer.contiguousFrames(3, 16, 20))
    }

    @Test fun receiverBufferHoldsAtLeastFortySeconds() {
        assertTrue(TcpAudioBuffer().capacitySeconds >= 40.0)
    }

    @Test fun startPolicyRequiresHalfSecondOfContiguousAudio() {
        assertFalse(TcpAudioStartPolicy.ready(23_999, 24_000))
        assertTrue(TcpAudioStartPolicy.ready(24_000, 24_000))
    }
}
