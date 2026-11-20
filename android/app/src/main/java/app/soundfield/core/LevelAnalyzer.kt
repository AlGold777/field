package app.soundfield.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Shared one-third-octave Welch analysis used by the microphone experiment. */
object LevelAnalyzer {
    val centersHz = doubleArrayOf(500.0, 630.0, 800.0, 1_000.0, 1_250.0, 1_600.0, 2_000.0, 2_500.0, 3_150.0, 4_000.0)
    private const val FFT_SIZE = 8_192
    private const val HOP = FFT_SIZE / 2

    data class Result(
        val levelsDb: List<Double?>,
        val snrDb: List<Double?>,
        val validBands: Int,
        val peakDbfs: Double,
    )

    /** start/duration are seconds from the recording start. */
    fun analyze(samples: FloatArray, sampleRate: Int, start: Double, duration: Double,
                backgroundStart: Double? = null, backgroundDuration: Double = 0.0): Result {
        require(sampleRate > 0)
        val signal = spectrum(samples, sampleRate, start, duration)
        val background = backgroundStart?.let { spectrum(samples, sampleRate, it, backgroundDuration, trimEdges = false) }
        val snr = centersHz.indices.map { i ->
            val s = signal.levels[i]
            val b = background?.levels?.get(i)
            if (s == null || (background != null && b == null)) null else if (b == null) 99.0 else s - b
        }
        val unclipped = signal.peakDbfs < -1.0
        val valid = snr.count { it != null && it >= 15.0 }
        return Result(signal.levels, snr, if (unclipped) valid else 0, signal.peakDbfs)
    }

    fun median(values: Iterable<Double>): Double? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Median band difference where both microphones meet the SNR and clipping gates. */
    fun differenceDb(a: Result, b: Result): Double? {
        if (a.validBands < 6 || b.validBands < 6) return null
        val shared = centersHz.indices.mapNotNull { i ->
        val la = a.levelsDb[i]; val lb = b.levelsDb[i]
        val sa = a.snrDb[i] ?: 0.0; val sb = b.snrDb[i] ?: 0.0
        if (la != null && lb != null && sa >= 15.0 && sb >= 15.0) la - lb else null
        }
        return if (shared.size >= 6) median(shared) else null
    }

    private data class Spectrum(val levels: List<Double?>, val peakDbfs: Double)

    private fun spectrum(samples: FloatArray, sampleRate: Int, start: Double, duration: Double, trimEdges: Boolean = true): Spectrum {
        if (samples.isEmpty() || duration <= 0) return Spectrum(List(centersHz.size) { null }, Double.POSITIVE_INFINITY)
        val from = (start * sampleRate).toInt().coerceIn(0, samples.size)
        val until = ((start + duration) * sampleRate).toInt().coerceIn(from, samples.size)
        val peak = (from until until).maxOfOrNull { abs(samples[it].toDouble()) } ?: 0.0
        val peakDb = if (peak <= 1e-12) Double.NEGATIVE_INFINITY else 20 * log10(peak)
        val edge = if (trimEdges) (0.3 * sampleRate).toInt() else 0
        val first = min(until, from + edge)
        val last = max(first, until - edge)
        if (last - first < FFT_SIZE) return Spectrum(List(centersHz.size) { null }, peakDb)
        val accum = DoubleArray(centersHz.size)
        var windows = 0
        var offset = first
        val windowSum = (0 until FFT_SIZE).sumOf { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        while (offset + FFT_SIZE <= last) {
            val re = DoubleArray(FFT_SIZE)
            val im = DoubleArray(FFT_SIZE)
            for (i in 0 until FFT_SIZE) {
                val w = 0.5 - 0.5 * cos(2 * PI * i / (FFT_SIZE - 1))
                re[i] = samples[offset + i].toDouble() * w
            }
            fft(re, im)
            for (band in centersHz.indices) {
                val low = centersHz[band] / THIRD_OCTAVE_HALF_STEP
                val high = centersHz[band] * THIRD_OCTAVE_HALF_STEP
                val loBin = max(1, kotlin.math.ceil(low * FFT_SIZE / sampleRate).toInt())
                val hiBin = min(FFT_SIZE / 2, (high * FFT_SIZE / sampleRate).toInt())
                var p = 0.0
                for (k in loBin..hiBin) p += re[k] * re[k] + im[k] * im[k]
                accum[band] += 2.0 * p / (windowSum * windowSum)
            }
            windows++
            offset += HOP
        }
        val levels = centersHz.indices.map { i ->
            if (windows == 0 || accum[i] <= 1e-24) null
            else 10 * log10(accum[i] / windows)
        }
        return Spectrum(levels, peakDb)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr0 = cos(angle); val wi0 = kotlin.math.sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val even = start + k; val odd = even + len / 2
                    val tr = wr * re[odd] - wi * im[odd]
                    val ti = wr * im[odd] + wi * re[odd]
                    re[odd] = re[even] - tr; im[odd] = im[even] - ti
                    re[even] += tr; im[even] += ti
                    val nextWr = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0; wr = nextWr
                }
                start += len
            }
            len = len shl 1
        }
    }

    /** Половина третьоктавной полосы: 2^(1/6). */
    private val THIRD_OCTAVE_HALF_STEP = kotlin.math.exp(ln(2.0) / 6.0)
}
