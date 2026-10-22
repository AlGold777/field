package app.soundfield.dsp

/**
 * Коррекция динамика (A2.4) поверх ядра sfdsp: каскад биквадов, смена профиля — кроссфейд 50 мс внутри ядра.
 * [process] — один аудиопоток, без аллокаций. [setProfile] — управляющий поток: ядро публикует цепочку лок-фри.
 * Если библиотека ядра недоступна, это прозрачный проход: вход не меняется, [setProfile] возвращает false.
 */
class SpeakerCorrector(sampleRate: Int, val channels: Int) : AutoCloseable {
    private var handle = if (SfDsp.load()) SfDsp.nativeSpeakerCreate(sampleRate, channels) else 0L
    val isNative: Boolean get() = handle != 0L

    /**
     * Секции профиля (в порядке профиля), затем allpass для пары [partnerKey] (тип 4, gain 0 dB).
     * profile == null — пустая цепочка (коррекция снята). Возвращает true, если ядро приняло цепочку.
     */
    fun setProfile(profile: Profile?, partnerKey: String?): Boolean {
        if (handle == 0L) return false
        val sections = profile?.sections.orEmpty()
        val allpass = if (profile != null && partnerKey != null) DeviceProfiles.allpassFor(profile, partnerKey) else emptyList()
        val n = sections.size + allpass.size
        val types = IntArray(n)
        val f0 = DoubleArray(n)
        val q = DoubleArray(n)
        val gainDb = DoubleArray(n)
        sections.forEachIndexed { i, s ->
            types[i] = typeCode(s.type)
            f0[i] = s.f0Hz
            q[i] = s.q
            gainDb[i] = s.gainDb ?: 0.0
        }
        allpass.forEachIndexed { j, a ->
            val i = sections.size + j
            types[i] = TYPE_ALLPASS
            f0[i] = a.f0Hz
            q[i] = a.q
        }
        return SfDsp.nativeSpeakerSetChain(handle, types, f0, q, gainDb, profile?.preampDb ?: 0.0) == 0
    }

    /** Сброс состояния фильтров (например, при остановке потока). */
    fun reset() { if (handle != 0L) SfDsp.nativeSpeakerReset(handle) }

    /** На месте, интерлив. [offsetFrames] и [frames] — в кадрах. */
    fun process(io: FloatArray, offsetFrames: Int, frames: Int) {
        if (handle != 0L) SfDsp.nativeSpeakerProcess(handle, io, offsetFrames * channels, frames)
    }

    override fun close() { if (handle != 0L) { SfDsp.nativeSpeakerDestroy(handle); handle = 0L } }

    private companion object {
        const val TYPE_HIGHPASS = 0
        const val TYPE_LOWSHELF = 1
        const val TYPE_HIGHSHELF = 2
        const val TYPE_PEAK = 3
        const val TYPE_ALLPASS = 4

        fun typeCode(type: String): Int = when (type) {
            "highpass" -> TYPE_HIGHPASS
            "lowshelf" -> TYPE_LOWSHELF
            "highshelf" -> TYPE_HIGHSHELF
            "peak" -> TYPE_PEAK
            else -> throw IllegalArgumentException("unknown section type $type")
        }
    }
}
