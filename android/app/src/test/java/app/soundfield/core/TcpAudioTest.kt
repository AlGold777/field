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
        TcpAudioWire.writeHello(out, "point-id", 42, ByteArray(32) { 3 })
        TcpAudioWire.writeChunk(out, TcpAudioChunk(7, "track-id", 48_000L, intArrayOf(-8388608, 0, 8388607)))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals("point-id" to 42, TcpAudioWire.readHello(input, ByteArray(32) { 3 }))
        val chunk = TcpAudioWire.readChunk(input)
        assertEquals(7, chunk.epoch)
        assertEquals("track-id", chunk.trackId)
        assertEquals(48_000L, chunk.startFrame)
        assertTrue(chunk.samples.contentEquals(intArrayOf(-8388608, 0, 8388607)))
    }

    @Test fun twoEpochBanksKeepCurrentAudioWhileNextEpochPrebuffers() {
        val buffer = TcpAudioBuffer(capacityPow2 = 5)
        buffer.activate(1)
        buffer.write(TcpAudioChunk(1, "old", 100, intArrayOf(1000, 1001, 1002)))
        buffer.prepare(2)
        buffer.write(TcpAudioChunk(2, "new", 100, intArrayOf(2000, 2001, 2002)))
        assertEquals(1000 / 8388608f, buffer.read(1, 100), 0.00001f)
        assertEquals(2000 / 8388608f, buffer.read(2, 100), 0.00001f)
        assertEquals(3, buffer.contiguousFrames(2, 100, 10))
        buffer.activate(2)
        assertEquals(2000 / 8388608f, buffer.read(2, 100), 0.00001f)
        assertTrue(buffer.read(1, 100).isNaN())
    }

    @Test fun ringReportsMissingFramesAndOverwritesOldAbsoluteFrames() {
        val buffer = TcpAudioBuffer(capacityPow2 = 4)
        buffer.activate(3)
        buffer.write(TcpAudioChunk(3, "track", 0, IntArray(8) { it }))
        assertEquals(8, buffer.contiguousFrames(3, 0, 20))
        assertEquals(8 / 48_000.0, buffer.bufferedSeconds(3, 0), 1e-9)
        buffer.write(TcpAudioChunk(3, "track", 16, IntArray(8) { it + 16 }))
        assertFalse(buffer.read(3, 0).isFinite())
        assertEquals(8, buffer.contiguousFrames(3, 16, 20))
    }

    @Test fun fullChunkOfPcm24FitsTheWireLimit() {
        val bytes = ByteArrayOutputStream()
        val samples = IntArray(TcpAudioWire.CHUNK_FRAMES) { (it * 1747 % 16_777_216) - 8_388_608 }
        TcpAudioWire.writeChunk(DataOutputStream(bytes), TcpAudioChunk(1, "x".repeat(96), 5L, samples))
        val chunk = TcpAudioWire.readChunk(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertTrue(chunk.samples.contentEquals(samples))
    }

    @Test fun receiverBufferHoldsAtLeastFortySeconds() {
        assertTrue(TcpAudioBuffer().capacitySeconds >= 40.0)
    }

    @Test fun sameEpochFramesCanBeReprocessedAndOverwritten() {
        val buffer = TcpAudioBuffer(capacityPow2 = 5)
        buffer.activate(9)
        buffer.write(TcpAudioChunk(9, "track", 480, intArrayOf(100, 200, 300, 400)))
        buffer.write(TcpAudioChunk(9, "track", 480, intArrayOf(-100, -200, -300, -400)))

        assertEquals(-100 / 8388608f, buffer.read(9, 480), 0.00001f)
        assertEquals(-200 / 8388608f, buffer.read(9, 481), 0.00001f)
        assertEquals(4, buffer.contiguousFrames(9, 480, 10))
    }

    @Test fun startPolicyRequiresHalfSecondOfContiguousAudio() {
        assertFalse(TcpAudioStartPolicy.ready(23_999, 24_000))
        assertTrue(TcpAudioStartPolicy.ready(24_000, 24_000))
    }
}
