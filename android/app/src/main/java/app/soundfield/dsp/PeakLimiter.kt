package app.soundfield.dsp

/** Статистика лимитера с момента прошлого [PeakLimiter.takeStats]. */
data class LimiterStats(val blocks: Long, val activeBlocks: Long, val maxReductionDb: Double) {
    /** Доля блоков с ослаблением > 0,5 dB (метрика A1.4). */
    val activeShare: Double get() = if (blocks > 0) activeBlocks.toDouble() / blocks else 0.0
}

/**
 * Сцепленный true-peak лимитер ядра (sfdsp, A1.4): упреждение 1,5 мс, детектор 4×. Один экземпляр — один аудиопоток.
 * Вызов [process] — без аллокаций. Если библиотека ядра недоступна (32-битное устройство), остаётся только жёсткое
 * ограничение ±1 без задержки: звук не искажается сверх клипа, [latencyFrames] = 0.
 */
class PeakLimiter(sampleRate: Int, val channels: Int, ceilingDbtp: Double) : AutoCloseable {
    private var handle = if (SfDsp.load()) SfDsp.nativeLimiterCreate(sampleRate, channels, ceilingDbtp) else 0L
    private val statsBuffer = DoubleArray(3)
    val isNative: Boolean get() = handle != 0L
    val latencyFrames: Int = if (handle != 0L) SfDsp.nativeLimiterLatency(handle) else 0

    fun reset() { if (handle != 0L) SfDsp.nativeLimiterReset(handle) }

    /** На месте, интерлив (для channels = 1 — моно). [offsetFrames] и [frames] — в кадрах. */
    fun process(io: FloatArray, offsetFrames: Int, frames: Int) {
        if (handle != 0L) SfDsp.nativeLimiterProcess(handle, io, offsetFrames * channels, frames)
        else for (i in offsetFrames * channels until (offsetFrames + frames) * channels) io[i] = io[i].coerceIn(-1f, 1f)
    }

    fun takeStats(): LimiterStats {
        if (handle == 0L) return LimiterStats(0, 0, 0.0)
        SfDsp.nativeLimiterTakeStats(handle, statsBuffer)
        return LimiterStats(statsBuffer[0].toLong(), statsBuffer[1].toLong(), statsBuffer[2])
    }

    override fun close() { if (handle != 0L) { SfDsp.nativeLimiterDestroy(handle); handle = 0L } }
}
