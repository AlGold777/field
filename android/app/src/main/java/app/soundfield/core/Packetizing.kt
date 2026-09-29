package app.soundfield.core

/** Разбиение готовой части источника на UDP-пакеты. Курсор отправки двигается на фактическое число кадров. */
object Packetizing {
    /** Кадров в пакете, начинающемся с [start]: не больше [maxFrames] и только из готовой части источника. */
    fun frameCount(source: AudioSource, start: Long, maxFrames: Int = SF.PACKET_FRAMES): Int =
        minOf(maxFrames.toLong(), source.totalFrames - start, source.readyFrames - start).toInt().coerceAtLeast(0)
}
