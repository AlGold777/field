package app.soundfield.core

/** Разбиение готовой части источника на UDP-пакеты. Курсор отправки двигается на фактическое число кадров. */
object Packetizing {
    /** Кадров в пакете, начинающемся с [start]: не больше [maxFrames] и только из готовой части источника. */
    fun frameCount(source: AudioSource, start: Long, maxFrames: Int = SF.PACKET_FRAMES): Int {
        val end = minOf(source.totalFrames, source.readyFrames)
        if (maxFrames <= 0 || start < 0 || start < source.firstReadyFrame || start >= end) return 0
        return minOf(maxFrames.toLong(), end - start).toInt()
    }

    /** Copies only the finite, contiguous prefix. Digital silence is a valid sample. */
    fun readAvailable(source: AudioSource, start: Long, channel: Int, buffer: FloatArray, count: Int): Int {
        val available = frameCount(source, start, minOf(buffer.size, count))
        for (i in 0 until available) {
            if (start + i < source.firstReadyFrame) return i
            val sample = source.readPrepared(start + i, channel)
            if (!sample.isFinite()) return i
            buffer[i] = sample
        }
        return available
    }
}
