package app.soundfield.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

object TcpAudioStartPolicy {
    fun ready(contiguousFrames: Int, minimumFrames: Int): Boolean =
        minimumFrames > 0 && contiguousFrames >= minimumFrames
}

data class TcpAudioChunk(val epoch: Int, val trackId: String, val startFrame: Long, val samples: ShortArray)

/** Separate length-prefixed binary transport. The version-1 UDP packet format is untouched. */
object TcpAudioWire {
    const val HELLO_MAGIC = 0x53464131 // SFA1
    private const val MAX_CHUNK_BYTES = 10_500
    const val CHUNK_FRAMES = 4_800 // 100 ms of mono 48 kHz PCM16

    fun writeHello(out: DataOutputStream, peerId: String, sessionId: Int) {
        out.writeInt(HELLO_MAGIC)
        out.writeUTF(peerId.take(64))
        out.writeInt(sessionId)
        out.flush()
    }

    fun readHello(input: DataInputStream): Pair<String, Int> {
        require(input.readInt() == HELLO_MAGIC) { "Invalid TCP audio hello" }
        return input.readUTF().take(64) to input.readInt()
    }

    fun writeChunk(out: DataOutputStream, chunk: TcpAudioChunk) {
        require(chunk.samples.size in 1..CHUNK_FRAMES)
        val bytes = ByteArrayOutputStream(32 + chunk.samples.size * 2)
        DataOutputStream(bytes).use { frame ->
            frame.writeInt(chunk.epoch)
            frame.writeUTF(chunk.trackId.take(96))
            frame.writeLong(chunk.startFrame)
            frame.writeInt(chunk.samples.size)
            chunk.samples.forEach { frame.writeShort(it.toInt()) }
        }
        val payload = bytes.toByteArray()
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun readChunk(input: DataInputStream): TcpAudioChunk {
        val size = try { input.readInt() } catch (e: EOFException) { throw e }
        require(size in 18..MAX_CHUNK_BYTES) { "Invalid TCP audio frame size: $size" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        DataInputStream(ByteArrayInputStream(bytes)).use { frame ->
            val epoch = frame.readInt()
            val track = frame.readUTF().take(96)
            val start = frame.readLong()
            val count = frame.readInt()
            require(count in 1..CHUNK_FRAMES && frame.available() == count * 2) {
                "Invalid TCP audio frame count: $count"
            }
            val samples = ShortArray(count) { frame.readShort() }
            return TcpAudioChunk(epoch, track, start, samples)
        }
    }
}

/** Two absolute-frame rings let a seek/track change prebuffer without interrupting the active epoch. */
class TcpAudioBuffer(capacityPow2: Int = 21) {
    private val capacity = 1 shl capacityPow2
    private val mask = (capacity - 1).toLong()

    private class Slot(capacity: Int) {
        val samples = ShortArray(capacity)
        val tags = IntArray(capacity) { Int.MIN_VALUE }
        @Volatile var epoch = Int.MIN_VALUE
        @Volatile var maxFrame = -1L

        fun reset(newEpoch: Int) {
            java.util.Arrays.fill(tags, Int.MIN_VALUE)
            epoch = newEpoch
            maxFrame = -1L
        }

        fun write(startFrame: Long, data: ShortArray) {
            for (i in data.indices) {
                val frame = startFrame + i
                val index = (frame and (samples.size - 1).toLong()).toInt()
                samples[index] = data[i]
                tags[index] = frame.toInt()
            }
            val end = startFrame + data.size - 1
            if (end > maxFrame) maxFrame = end
        }

        fun read(frame: Long): Float {
            val publishedThrough = maxFrame
            if (frame > publishedThrough) return Float.NaN
            val index = (frame and (samples.size - 1).toLong()).toInt()
            return if (tags[index] == frame.toInt()) samples[index] / 32768f else Float.NaN
        }

        fun contiguousFrom(startFrame: Long, limit: Int): Int {
            val publishedThrough = maxFrame
            var count = 0
            while (count < limit) {
                val frame = startFrame + count
                if (frame > publishedThrough) break
                val index = (frame and (samples.size - 1).toLong()).toInt()
                if (tags[index] != frame.toInt()) break
                count++
            }
            return count
        }
    }

    private val lock = Any()
    @Volatile private var active = Slot(capacity)
    @Volatile private var staging = Slot(capacity)

    val capacityFrames: Int get() = capacity
    val capacitySeconds: Double get() = capacity.toDouble() / SF.SAMPLE_RATE
    val activeEpoch: Int get() = active.epoch

    fun clear() = synchronized(lock) {
        active.reset(Int.MIN_VALUE)
        staging.reset(Int.MIN_VALUE)
    }

    fun prepare(epoch: Int) = synchronized(lock) {
        if (active.epoch == epoch || staging.epoch == epoch) return@synchronized
        staging.reset(epoch)
    }

    fun activate(epoch: Int) = synchronized(lock) {
        if (active.epoch == epoch) return@synchronized
        if (staging.epoch == epoch) {
            val old = active
            active = staging
            staging = old
            staging.reset(Int.MIN_VALUE)
        } else {
            active.reset(epoch)
            staging.reset(Int.MIN_VALUE)
        }
    }

    fun write(chunk: TcpAudioChunk): Boolean {
        val slot = when {
            active.epoch == chunk.epoch -> active
            staging.epoch == chunk.epoch -> staging
            chunk.epoch > active.epoch -> synchronized(lock) {
                if (staging.epoch != chunk.epoch) staging.reset(chunk.epoch)
                staging
            }
            else -> return false
        }
        slot.write(chunk.startFrame, chunk.samples)
        return true
    }

    fun read(epoch: Int, frame: Long): Float = when {
        active.epoch == epoch -> active.read(frame)
        staging.epoch == epoch -> staging.read(frame)
        else -> Float.NaN
    }

    fun contiguousFrames(epoch: Int, startFrame: Long, limit: Int): Int = when {
        active.epoch == epoch -> active.contiguousFrom(startFrame, limit)
        staging.epoch == epoch -> staging.contiguousFrom(startFrame, limit)
        else -> 0
    }

    fun bufferedSeconds(epoch: Int, positionFrame: Long): Double {
        val slot = when {
            active.epoch == epoch -> active
            staging.epoch == epoch -> staging
            else -> return 0.0
        }
        return ((slot.maxFrame - positionFrame + 1).coerceAtLeast(0) / SF.SAMPLE_RATE.toDouble())
    }
}
