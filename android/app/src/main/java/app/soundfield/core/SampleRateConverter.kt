package app.soundfield.core

import app.soundfield.dsp.SfDsp

/**
 * Потоковый стерео-ресемплер при импорте (A1.2): ядро sfdsp — полифазный sinc, окно Кайзера, подавление зеркал ≥ 120 dB,
 * неравномерность ≤ ±0,01 dB до 20 кГц, накопление в double. Без библиотеки ядра (32-битное устройство) — прежний
 * [StreamResampler] как запасной путь. Один экземпляр — один поток.
 */
class SampleRateConverter(private val inRate: Int, private val outRate: Int) : AutoCloseable {
    private var handle = if (inRate != outRate && SfDsp.load()) SfDsp.nativeSrcCreate(2, inRate, outRate) else 0L
    private val fallback: StreamResampler?
    private var fallbackOut = FloatArray(0)
    private var fallbackCount = 0
    val isNative: Boolean get() = handle != 0L
    val passthrough: Boolean = inRate == outRate

    init {
        fallback = if (!passthrough && handle == 0L) StreamResampler(inRate, outRate) { l, r ->
            if (fallbackCount + 2 > fallbackOut.size) fallbackOut = fallbackOut.copyOf(maxOf(8192, fallbackOut.size * 2))
            fallbackOut[fallbackCount++] = l; fallbackOut[fallbackCount++] = r
        } else null
    }

    /** Верхняя граница числа выходных кадров на [framesIn] входных (и на [finish]). */
    fun maxOutputFrames(framesIn: Int): Int =
        if (passthrough) framesIn else if (handle != 0L) SfDsp.nativeSrcMaxOutput(handle, framesIn.toLong()).toInt()
        else (framesIn.toLong() * outRate / inRate + 64).toInt()

    /** Интерлив [framesIn] кадров → [out] (ёмкость ≥ [maxOutputFrames]); возвращает число выходных кадров. */
    fun process(input: FloatArray, framesIn: Int, out: FloatArray): Int {
        if (passthrough) { System.arraycopy(input, 0, out, 0, framesIn * 2); return framesIn }
        if (handle != 0L) return SfDsp.nativeSrcProcess(handle, input, 0, framesIn, out, 0, out.size / 2).toInt().also { check(it >= 0) { "SRC" } }
        val fb = fallback!!
        fallbackCount = 0
        for (i in 0 until framesIn) fb.push(input[i * 2], input[i * 2 + 1])
        return takeFallback(out)
    }

    /** Конец входа: дописывает хвост (за концом — тишина). */
    fun finish(out: FloatArray): Int {
        if (passthrough) return 0
        if (handle != 0L) return SfDsp.nativeSrcFlush(handle, out, 0, out.size / 2).toInt().also { check(it >= 0) { "SRC" } }
        fallbackCount = 0
        fallback!!.finish()
        return takeFallback(out)
    }

    private fun takeFallback(out: FloatArray): Int {
        val n = fallbackCount / 2
        check(n * 2 <= out.size) { "SRC buffer" }
        System.arraycopy(fallbackOut, 0, out, 0, n * 2)
        fallbackCount = 0
        return n
    }

    override fun close() { if (handle != 0L) { SfDsp.nativeSrcDestroy(handle); handle = 0L } }

    companion object {
        /** Целиком: стерео интерлив [inRate] → стерео интерлив [outRate]. Для встроенного WAV, тестов и лаборатории. */
        fun convertAll(input: FloatArray, framesIn: Int, inRate: Int, outRate: Int): FloatArray {
            if (inRate == outRate) return input.copyOf(framesIn * 2)
            SampleRateConverter(inRate, outRate).use { c ->
                val chunk = 4096
                val block = FloatArray(chunk * 2)
                val tmp = FloatArray((maxOf(c.maxOutputFrames(chunk), c.maxOutputFrames(0)) + 16) * 2)
                var out = FloatArray(((framesIn.toLong() * outRate / inRate + 4096) * 2).toInt())
                var n = 0
                fun append(m: Int) {
                    if ((n + m) * 2 > out.size) out = out.copyOf(maxOf(out.size * 2, (n + m) * 2))
                    System.arraycopy(tmp, 0, out, n * 2, m * 2); n += m
                }
                var p = 0
                while (p < framesIn) {
                    val k = minOf(chunk, framesIn - p)
                    System.arraycopy(input, p * 2, block, 0, k * 2)
                    append(c.process(block, k, tmp))
                    p += k
                }
                append(c.finish(tmp))
                return out.copyOf(n * 2)
            }
        }
    }
}
