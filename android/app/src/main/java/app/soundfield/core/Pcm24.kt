package app.soundfield.core

import app.soundfield.dsp.SfDsp

/**
 * PCM 24 бит со знаком (A1.1). Квантование float → 24 бит — всегда с TPDF-дизерингом ядра (xorshift128+, два равномерных
 * значения по ±0,5 LSB, независимый генератор на канал). Без библиотеки ядра — та же формула на Kotlin.
 */
object Pcm24 {
    const val SCALE = 8_388_608f
    const val MIN = -8_388_608
    const val MAX = 8_388_607
    const val BYTES = 3

    /** Читает знаковое 24-бит значение (little-endian) из ByteBuffer: кэш декодированного трека. */
    fun readLe(b: java.nio.ByteBuffer, offset: Int): Float {
        val v = if (offset + 4 <= b.limit()) (b.getInt(offset) shl 8) shr 8      // b в порядке LITTLE_ENDIAN
        else (b.get(offset).toInt() and 0xFF) or ((b.get(offset + 1).toInt() and 0xFF) shl 8) or (b.get(offset + 2).toInt() shl 16)
        return v / SCALE
    }

    /** Сетевое квантование: [count] значений float → знаковые 24 бит, TPDF, детерминированный seed (повторная отправка даёт тот же пакет). */
    fun quantize(input: FloatArray, count: Int, seed: Long, out: IntArray) {
        val bytes = scratch.get()!!.let { if (it.size >= count * BYTES) it else ByteArray(count * BYTES).also { n -> scratch.set(n) } }
        if (SfDsp.load()) {
            val d = SfDsp.nativeDitherCreate(seed)
            try { SfDsp.nativePcm24Encode(d, input, 0, 1, count, bytes, 0, BYTES, false) } finally { SfDsp.nativeDitherDestroy(d) }
            for (i in 0 until count) {
                out[i] = ((bytes[i * 3].toInt() and 0xFF) or ((bytes[i * 3 + 1].toInt() and 0xFF) shl 8) or (bytes[i * 3 + 2].toInt() shl 16))
            }
        } else {
            val g = KotlinDither(seed)
            for (i in 0 until count) out[i] = g.quantize(input[i])
        }
    }

    private val scratch = ThreadLocal.withInitial { ByteArray(0) }

    /** Запасной путь без библиотеки ядра (тот же алгоритм). */
    internal class KotlinDither(seed: Long) {
        private var s0 = seed * -7046029254386353131L + 1
        private var s1 = seed xor -3335678366873096957L
        init { repeat(8) { next() } }
        private fun next(): Long {
            var x = s0; val y = s1
            s0 = y
            x = x xor (x shl 23)
            s1 = x xor y xor (x ushr 17) xor (y ushr 26)
            return s1 + y
        }
        private fun uniform(): Double = (next() ushr 11).toDouble() * (1.0 / 9007199254740992.0)
        fun quantize(x: Float): Int {
            val v = x.toDouble() * 8388608.0 + (uniform() - 0.5) + (uniform() - 0.5)
            if (v.isNaN()) return 0
            return Math.floor(v + 0.5).coerceIn(MIN.toDouble(), MAX.toDouble()).toInt()
        }
    }
}

/**
 * Кодировщик интерлива стерео float → 24 бит little-endian (кэш на диске), независимый дизер на канал.
 * [encode] вызывать из одного потока; [close] освобождает генераторы ядра.
 */
class Pcm24StereoEncoder(seed: Long) : AutoCloseable {
    private val native = SfDsp.load()
    private val dl = if (native) SfDsp.nativeDitherCreate(seed * 2 + 1) else 0L
    private val dr = if (native) SfDsp.nativeDitherCreate(seed * 2 + 2) else 0L
    private val kl = if (native) null else Pcm24.KotlinDither(seed * 2 + 1)
    private val kr = if (native) null else Pcm24.KotlinDither(seed * 2 + 2)

    /** [frames] кадров интерлива из [input] (с нулевого элемента) → [frames]·6 байт в [out]. */
    fun encode(input: FloatArray, frames: Int, out: ByteArray) {
        if (native) {
            SfDsp.nativePcm24Encode(dl, input, 0, 2, frames, out, 0, 6, false)
            SfDsp.nativePcm24Encode(dr, input, 1, 2, frames, out, 3, 6, false)
        } else {
            for (i in 0 until frames) {
                put(out, i * 6, kl!!.quantize(input[i * 2])); put(out, i * 6 + 3, kr!!.quantize(input[i * 2 + 1]))
            }
        }
    }

    private fun put(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte() }

    override fun close() {
        if (dl != 0L) SfDsp.nativeDitherDestroy(dl)
        if (dr != 0L) SfDsp.nativeDitherDestroy(dr)
    }
}
