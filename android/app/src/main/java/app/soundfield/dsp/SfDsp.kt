package app.soundfield.dsp

/**
 * Тонкая обёртка над общим C++-ядром (libsfdsp, см. dsp/include/sfdsp.h). Все вызовы — один JNI-вызов на блок.
 * Загрузка библиотеки не обязательна для работы приложения: пока блоки ядра не подключены к звуку (этап A0),
 * сбой загрузки не должен ронять приложение.
 */
object SfDsp {
    private var loaded: Boolean? = null

    /** Загружает libsfdsp из APK. На JVM-тестах — [loadFrom]. */
    @Synchronized fun load(): Boolean {
        loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("sfdsp"); nativeInit() }.isSuccess ||
            // JVM-тесты и лабораторные утилиты: хост-сборка по системному свойству (задаёт Gradle)
            System.getProperty("sfdsp.host.lib")?.let { path -> runCatching { System.load(path); nativeInit() }.isSuccess } == true
        loaded = ok
        return ok
    }

    /** Хост-сборка для JUnit (абсолютный путь к libsfdsp.dylib). */
    @Synchronized fun loadFrom(path: String): Boolean {
        loaded?.let { return it }
        return runCatching { System.load(path); nativeInit() }.isSuccess.also { loaded = it }
    }

    val isLoaded: Boolean get() = loaded == true
    const val EXPECTED_ABI = 1
    const val SINC_TAPS = 32
    const val SINC_PHASES = 256
    const val SINC_GROUP_DELAY_FRAMES = 16

    fun version(): String = nativeVersion()
    fun abiVersion(): Int = nativeAbiVersion()
    fun sincCoefficient(phase: Int, tap: Int): Float = nativeCoefficient(phase, tap)

    /** Интерполяция одного отсчёта из буфера, src[0] — кадр [base]; NaN, если нужен отсутствующий кадр. */
    fun interpolate(src: FloatArray, base: Long, length: Int, position: Double): Float =
        nativeInterpolate(src, base, length, position)

    @JvmStatic private external fun nativeAbiVersion(): Int
    @JvmStatic private external fun nativeVersion(): String
    @JvmStatic private external fun nativeInit()
    @JvmStatic private external fun nativeCoefficient(phase: Int, tap: Int): Float
    @JvmStatic private external fun nativeInterpolate(src: FloatArray, base: Long, len: Int, position: Double): Float
    @JvmStatic external fun nativePlayoutCreate(sampleRate: Int, hardResyncFrames: Int): Long
    @JvmStatic external fun nativePlayoutDestroy(handle: Long)
    @JvmStatic external fun nativePlayoutReset(handle: Long)
    @JvmStatic external fun nativePlayoutPlan(handle: Long, ideal: Double, n: Int)
    @JvmStatic external fun nativePlayoutPosition(handle: Long, i: Int): Double
    @JvmStatic external fun nativePlayoutSyncErrorUs(handle: Long): Double
    @JvmStatic external fun nativePlayoutHardJumps(handle: Long): Int
    @JvmStatic external fun nativePlayoutSlewEvents(handle: Long): Long
    @JvmStatic external fun nativePlayoutRender(
        handle: Long, src: FloatArray, base: Long, len: Int, ideal: Double, n: Int, out: FloatArray,
    )

    // ---- A1: PCM 24 бит, SRC, лимитер ----
    @JvmStatic external fun nativeDitherCreate(seed: Long): Long
    @JvmStatic external fun nativeDitherDestroy(handle: Long)
    @JvmStatic external fun nativePcm24Encode(
        dither: Long, input: FloatArray, inOff: Int, inStride: Int, n: Int,
        out: ByteArray, outOff: Int, outStride: Int, bigEndian: Boolean,
    )
    @JvmStatic external fun nativePcm24Decode(
        input: ByteArray, inOff: Int, inStride: Int, n: Int, bigEndian: Boolean,
        out: FloatArray, outOff: Int, outStride: Int,
    )
    @JvmStatic external fun nativeSrcCreate(channels: Int, inRate: Int, outRate: Int): Long
    @JvmStatic external fun nativeSrcDestroy(handle: Long)
    @JvmStatic external fun nativeSrcReset(handle: Long)
    @JvmStatic external fun nativeSrcTaps(handle: Long): Int
    @JvmStatic external fun nativeSrcMaxOutput(handle: Long, nIn: Long): Long
    @JvmStatic external fun nativeSrcProcess(
        handle: Long, input: FloatArray, inOffFrames: Int, nIn: Int, out: FloatArray, outOffFrames: Int, outCapFrames: Int,
    ): Long
    @JvmStatic external fun nativeSrcFlush(handle: Long, out: FloatArray, outOffFrames: Int, outCapFrames: Int): Long
    @JvmStatic external fun nativeLimiterCreate(sampleRate: Int, channels: Int, ceilingDbtp: Double): Long
    @JvmStatic external fun nativeLimiterDestroy(handle: Long)
    @JvmStatic external fun nativeLimiterReset(handle: Long)
    @JvmStatic external fun nativeLimiterLatency(handle: Long): Int
    @JvmStatic external fun nativeLimiterProcess(handle: Long, io: FloatArray, offFloats: Int, frames: Int)
    @JvmStatic external fun nativeLimiterTakeStats(handle: Long, stats: DoubleArray)
    @JvmStatic external fun nativeMeterCreate(channels: Int): Long
    @JvmStatic external fun nativeMeterDestroy(handle: Long)
    @JvmStatic external fun nativeMeterProcess(handle: Long, interleavedStereo: FloatArray, offFrames: Int, frames: Int)
    @JvmStatic external fun nativeMeterTruePeak(handle: Long): Double
    @JvmStatic external fun nativeMeterLoudness(handle: Long): Double

    // ---- A2: коррекция динамика ----
    @JvmStatic external fun nativeSpeakerCreate(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun nativeSpeakerDestroy(handle: Long)
    @JvmStatic external fun nativeSpeakerReset(handle: Long)
    /** Публикует цепочку (0 — успех, -1 — отклонено; цепочка тогда не меняется). Массивы одной длины. */
    @JvmStatic external fun nativeSpeakerSetChain(
        handle: Long, types: IntArray, f0: DoubleArray, q: DoubleArray, gainDb: DoubleArray, preampDb: Double,
    ): Int
    /** На месте, интерлив; [offFloats] — смещение в float'ах. */
    @JvmStatic external fun nativeSpeakerProcess(handle: Long, io: FloatArray, offFloats: Int, frames: Int)
}

/** Дробный корректор воспроизведения в ядре (порт PlayoutCorrector). Не потокобезопасен; [close] обязателен. */
class NativePlayout(sampleRate: Int, hardResyncFrames: Int) : AutoCloseable {
    private var handle = SfDsp.nativePlayoutCreate(sampleRate, hardResyncFrames)

    fun reset() = SfDsp.nativePlayoutReset(handle)
    fun plan(ideal: Double, n: Int) = SfDsp.nativePlayoutPlan(handle, ideal, n)
    fun position(i: Int): Double = SfDsp.nativePlayoutPosition(handle, i)
    val syncErrorUs: Double get() = SfDsp.nativePlayoutSyncErrorUs(handle)
    val hardJumps: Int get() = SfDsp.nativePlayoutHardJumps(handle)
    val slewEvents: Long get() = SfDsp.nativePlayoutSlewEvents(handle)

    /** plan + чтение [n] кадров из [src] (src[0] — кадр [base]) в [out]; NaN — кадр недоступен. */
    fun render(src: FloatArray, base: Long, length: Int, ideal: Double, n: Int, out: FloatArray) =
        SfDsp.nativePlayoutRender(handle, src, base, length, ideal, n, out)

    override fun close() {
        if (handle != 0L) { SfDsp.nativePlayoutDestroy(handle); handle = 0 }
    }
}
