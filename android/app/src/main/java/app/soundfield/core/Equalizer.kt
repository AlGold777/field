package app.soundfield.core

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class EqMagnet(val sigma: Double) {
    OFF(0.0), SOFT(0.8), STRONG(1.4);

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name.equals(value, true) } ?: SOFT
    }
}

data class EqSettings(
    val enabled: Boolean = false,
    val gainsDb: List<Float> = List(EqualizerMath.BAND_COUNT) { 0f },
    val magnet: EqMagnet = EqMagnet.SOFT,
    val preset: String = "Ровно",
    val preserveLoudness: Boolean = true,
    val bypass: Boolean = false,
    val analyzerEnabled: Boolean = true,
) {
    fun sanitized() = copy(
        gainsDb = List(EqualizerMath.BAND_COUNT) { i ->
            (gainsDb.getOrNull(i) ?: 0f).coerceIn(-12f, 12f)
                .let { (it * 2f).roundToInt() / 2f }
        },
    )
}

data class EqPreset(val name: String, val gainsDb: List<Float>)

object EqPresets {
    // Один источник значений пресетов: держать здесь, не дублировать числа в UI и движке.
    val builtIn = listOf(
        EqPreset("Ровно", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        EqPreset("Больше баса", listOf(4f, 3.5f, 2.5f, 1.5f, 0f, 0f, 0f, 0.5f, 1f, 1f)),
        EqPreset("Вокал", listOf(-2f, -1.5f, 0f, 1f, 2.5f, 3f, 2f, 0.5f, 0f, -0.5f)),
        EqPreset("Яркость", listOf(-1.5f, -1f, 0f, 0.5f, 0f, 0.5f, 2f, 3f, 3.5f, 2.5f)),
        EqPreset("Мягко", listOf(1f, 1f, 0.5f, 0f, -0.5f, -1f, -1f, -0.5f, 0.5f, 1f)),
        EqPreset("Динамик телефона", listOf(-12f, -9f, -5f, -2f, 0f, 1.5f, 2f, 1.5f, 0.5f, 0f)),
    )
}

/**
 * Accurate-cascade graphic equalizer. Filter gains are solved against the cascade response,
 * so the plotted response passes through the requested band gains.
 */
object EqualizerMath {
    const val BAND_COUNT = 10
    const val LOOKAHEAD_FRAMES = 48 // fixed 1 ms on every render path
    val frequencies = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
    private const val SAMPLE_RATE = 48_000.0
    private const val Q = 1.4142135623730951
    private val ln2 = ln(2.0)

    fun filterGains(targetDb: List<Float>): DoubleArray {
        val target = DoubleArray(BAND_COUNT) { targetDb.getOrNull(it)?.toDouble()?.coerceIn(-12.0, 12.0) ?: 0.0 }
        val gains = target.copyOf()
        // Newton correction of the cascade response. The 10x10 system is tiny and runs on the
        // engine thread when a control snapshot changes, never on the audio callback.
        repeat(7) {
            val response = responseAtCenters(gains)
            val error = DoubleArray(BAND_COUNT) { target[it] - response[it] }
            if (error.maxOf { abs(it) } < 0.015) return gains
            val jacobian = Array(BAND_COUNT) { DoubleArray(BAND_COUNT) }
            val delta = 0.05
            for (j in 0 until BAND_COUNT) {
                gains[j] += delta
                val plus = responseAtCenters(gains)
                gains[j] -= 2.0 * delta
                val minus = responseAtCenters(gains)
                gains[j] += delta
                for (i in 0 until BAND_COUNT) jacobian[i][j] = (plus[i] - minus[i]) / (2.0 * delta)
            }
            val correction = solve(jacobian, error) ?: return gains
            for (i in gains.indices) gains[i] = (gains[i] + correction[i]).coerceIn(-36.0, 36.0)
        }
        return gains
    }

    fun responseDb(settings: EqSettings, frequencyHz: Double): Double {
        if (!settings.enabled) return 0.0
        val filterGains = filterGains(settings.gainsDb)
        return cascadeResponse(frequencyHz, filterGains)
    }

    fun curve(settings: EqSettings, pointCount: Int = 256): DoubleArray {
        val n = pointCount.coerceAtLeast(2)
        if (!settings.enabled) return DoubleArray(n)
        val gains = filterGains(settings.gainsDb)
        return DoubleArray(n) { index ->
            val f = 20.0 * (20_000.0 / 20.0).pow(index.toDouble() / (n - 1))
            cascadeResponse(f, gains)
        }
    }

    fun config(settingsRaw: EqSettings): EqConfig {
        val settings = settingsRaw.sanitized()
        val filterGains = filterGains(settings.gainsDb)
        val curve = DoubleArray(512) { i ->
            val f = 20.0 * (20_000.0 / 20.0).pow(i.toDouble() / 511.0)
            cascadeResponse(f, filterGains)
        }
        val peak = curve.maxOrNull() ?: 0.0
        val preamp = if (settings.enabled) -max(0.0, peak) else 0.0
        val loudness = weightedMeanDb(curve)
        // Recover cuts only while the true cascade peak still has headroom. Boosted curves already
        // spend that headroom in preamp, so loudness preservation must not push them back above 0 dBFS.
        val availableHeadroom = max(0.0, -peak)
        val outputGain = if (settings.enabled && settings.preserveLoudness) {
            preamp + min(max(0.0, -loudness), availableHeadroom)
        } else preamp
        val bypassComp = if (settings.enabled) outputGain + loudness else 0.0
        return EqConfig(settings, filterGains, preamp, outputGain, bypassComp, curve)
    }

    fun responseAtCenters(filterGains: DoubleArray): DoubleArray = DoubleArray(BAND_COUNT) { i ->
        cascadeResponse(frequencies[i], filterGains)
    }

    private fun cascadeResponse(frequency: Double, gains: DoubleArray): Double {
        var result = 0.0
        for (i in 0 until BAND_COUNT) result += peakingResponseDb(frequency, frequencies[i], gains[i])
        return result
    }

    private fun peakingResponseDb(f: Double, center: Double, gainDb: Double): Double {
        if (abs(gainDb) < 1e-12) return 0.0
        val w0 = 2.0 * PI * center / SAMPLE_RATE
        val w = (2.0 * PI * f / SAMPLE_RATE).coerceIn(0.0, PI)
        val a = 10.0.pow(gainDb / 40.0)
        val alpha = sin(w0) / (2.0 * Q)
        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cos(w0)
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cos(w0)
        val a2 = 1.0 - alpha / a
        val c = cos(w); val s = sin(w)
        val nr = b0 + b1 * c + b2 * cos(2.0 * w)
        val ni = -b1 * s - b2 * sin(2.0 * w)
        val dr = a0 + a1 * c + a2 * cos(2.0 * w)
        val di = -a1 * s - a2 * sin(2.0 * w)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20.0 * log10(mag.coerceAtLeast(1e-15))
    }

    fun weightedMeanDb(curve: DoubleArray): Double {
        // BS.1770 K-weighting transfer, evaluated over a pink spectrum (uniform in log frequency).
        val shelfB = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285)
        val shelfA = doubleArrayOf(1.0, -1.69065929318241, 0.73248077421585)
        val highB = doubleArrayOf(1.0, -2.0, 1.0)
        val highA = doubleArrayOf(1.0, -1.99004745483398, 0.99007225036621)
        var weightedEnergy = 0.0
        var totalWeight = 0.0
        for (i in curve.indices) {
            val f = 20.0 * (20_000.0 / 20.0).pow(i.toDouble() / (curve.lastIndex))
            val weight = kWeightPower(f, shelfB, shelfA) * kWeightPower(f, highB, highA)
            weightedEnergy += weight * 10.0.pow(curve[i] / 10.0)
            totalWeight += weight
        }
        return 10.0 * log10((weightedEnergy / totalWeight).coerceAtLeast(1e-15))
    }

    private fun kWeightPower(f: Double, b: DoubleArray, a: DoubleArray): Double {
        val w = 2.0 * PI * f / SAMPLE_RATE
        val c = cos(w); val s = sin(w)
        val nr = b[0] + b[1] * c + b[2] * cos(2.0 * w)
        val ni = -b[1] * s - b[2] * sin(2.0 * w)
        val dr = a[0] + a[1] * c + a[2] * cos(2.0 * w)
        val di = -a[1] * s - a[2] * sin(2.0 * w)
        return ((nr * nr + ni * ni) / (dr * dr + di * di)).coerceAtLeast(1e-12)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1).also { row ->
            a[i].copyInto(row); row[n] = b[i]
        } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-10) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val d = m[col][col]
            for (j in col until n + 1) m[col][j] /= d
            for (r in 0 until n) if (r != col) {
                val factor = m[r][col]
                for (j in col until n + 1) m[r][j] -= factor * m[col][j]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }

    internal fun writeCoefficients(centerHz: Double, gainDb: Double, out: DoubleArray, offset: Int) {
        val w0 = 2.0 * PI * centerHz / SAMPLE_RATE
        val a = 10.0.pow(gainDb / 40.0)
        val alpha = sin(w0) / (2.0 * Q)
        val a0 = 1.0 + alpha / a
        out[offset] = (1.0 + alpha * a) / a0
        out[offset + 1] = (-2.0 * cos(w0)) / a0
        out[offset + 2] = (1.0 - alpha * a) / a0
        out[offset + 3] = (-2.0 * cos(w0)) / a0
        out[offset + 4] = (1.0 - alpha / a) / a0
    }
}

/** Immutable render-thread handoff. Calculated away from the audio callback. */
class EqConfig internal constructor(
    val settings: EqSettings,
    val filterGainsDb: DoubleArray,
    val preampDb: Double,
    val outputGainDb: Double,
    val bypassCompDb: Double,
    val curveDb: DoubleArray,
) {
    companion object { fun neutral() = EqualizerMath.config(EqSettings()) }
}

/** Per-output DSP state. There is one instance per local renderer/channel. */
class EqualizerProcessor {
    @Volatile private var pending = EqConfig.neutral()
    private var applied: EqConfig? = null
    private val gains = DoubleArray(EqualizerMath.BAND_COUNT)
    private val target = DoubleArray(EqualizerMath.BAND_COUNT)
    private val coeffs = DoubleArray(EqualizerMath.BAND_COUNT * 5)
    private val delay = DoubleArray(EqualizerMath.LOOKAHEAD_FRAMES + 1)
    private val states = Array(EqualizerMath.BAND_COUNT) { DoubleArray(4) } // x1,x2,y1,y2
    private var preamp = 0.0
    private var targetPreamp = 0.0
    private var bypassMix = 0.0
    private var targetBypassMix = 0.0
    private var bypassComp = 0.0
    private var targetBypassComp = 0.0
    private var smoothRemaining = 0
    private var delayIndex = 0
    private var limiterGain = 1.0
    private val release = exp(-1.0 / (48_000.0 * 0.050))
    private val limiterActivations = AtomicLong()
    private val maxReductionMilliDb = AtomicLong()
    private var limiterWasActive = false

    fun setConfig(config: EqConfig) { pending = config }
    fun limiterCount(): Long = limiterActivations.get()
    fun maxLimiterReductionDb(): Double = maxReductionMilliDb.get() / 1_000.0
    fun takeLimiterStats(): Pair<Long, Double> = limiterActivations.getAndSet(0) to maxReductionMilliDb.getAndSet(0) / 1_000.0

    fun process(samples: FloatArray, n: Int) {
        processTone(samples, n)
        processLimiter(samples, n)
    }

    fun processTone(samples: FloatArray, n: Int) {
        val next = pending
        if (next !== applied) {
            applied = next
            val active = next.settings.enabled && !next.settings.bypass
            for (i in target.indices) target[i] = if (active) next.filterGainsDb[i] else 0.0
            targetPreamp = if (active) next.outputGainDb else 0.0
            targetBypassMix = if (next.settings.enabled && next.settings.bypass) 1.0 else 0.0
            targetBypassComp = if (next.settings.enabled) next.bypassCompDb else 0.0
            smoothRemaining = 1_440
        }

        var frame = 0
        while (frame < n) {
            val step = min(32, n - frame)
            if (smoothRemaining > 0) {
                val fraction = min(1.0, step.toDouble() / smoothRemaining)
                for (i in gains.indices) gains[i] += (target[i] - gains[i]) * fraction
                preamp += (targetPreamp - preamp) * fraction
                bypassMix += (targetBypassMix - bypassMix) * fraction
                bypassComp += (targetBypassComp - bypassComp) * fraction
                smoothRemaining -= step
                for (i in gains.indices) EqualizerMath.writeCoefficients(EqualizerMath.frequencies[i], gains[i], coeffs, i * 5)
            }
            val wetGain = 10.0.pow(preamp / 20.0)
            val dryGain = 10.0.pow(bypassComp / 20.0)
            for (k in frame until frame + step) {
                val dry = samples[k].toDouble()
                var wet = dry
                for (band in 0 until EqualizerMath.BAND_COUNT) {
                    val c = band * 5; val s = states[band]
                    val y = coeffs[c] * wet + coeffs[c + 1] * s[0] + coeffs[c + 2] * s[1] - coeffs[c + 3] * s[2] - coeffs[c + 4] * s[3]
                    s[1] = s[0]; s[0] = wet; s[3] = s[2]; s[2] = y
                    wet = y
                }
                wet *= wetGain
                val mixed = wet * (1.0 - bypassMix) + dry * dryGain * bypassMix
                samples[k] = mixed.toFloat()
            }
            frame += step
        }
    }

    fun processLimiter(samples: FloatArray, n: Int) {
        for (k in 0 until n) {
            delay[delayIndex] = samples[k].toDouble()
            var peak = 0.0
            for (look in delay.indices) peak = max(peak, abs(delay[look]))
            val delayedIndex = (delayIndex + 1) % delay.size
            val delayed = delay[delayedIndex]
            delayIndex = delayedIndex
            val wantedGain = if (peak > 1.0) 1.0 / peak else 1.0
            limiterGain = if (wantedGain < limiterGain) wantedGain else 1.0 - (1.0 - limiterGain) * release
            val reduction = if (limiterGain < 1.0) -20.0 * log10(limiterGain) else 0.0
            if (reduction > 0.01) {
                if (!limiterWasActive) limiterActivations.incrementAndGet()
                limiterWasActive = true
                maxReductionMilliDb.accumulateAndGet((reduction * 1_000.0).toLong()) { old, next -> max(old, next) }
            } else limiterWasActive = false
            samples[k] = (delayed * limiterGain).coerceIn(-1.0, 1.0).toFloat()
        }
    }
}

/** 1 ms lookahead is baked into EqualizerProcessor and remains present when EQ is bypassed. */
class EqSpectrumCapture(private val capacity: Int = 8192) {
    private val samples = FloatArray(capacity)
    @Volatile private var writeIndex = 0L

    fun add(values: FloatArray, n: Int) {
        var p = writeIndex
        for (i in 0 until n) { samples[(p % capacity).toInt()] = values[i]; p++ }
        writeIndex = p
    }

    fun copyLatest(target: FloatArray): Boolean {
        if (target.size > capacity) return false
        val end = writeIndex
        if (end < target.size) return false
        val start = end - target.size
        for (i in target.indices) target[i] = samples[((start + i) % capacity).toInt()]
        return true
    }

    fun spectrumDb(samples: FloatArray, bands: Int = 60): FloatArray {
        val n = samples.size
        require(n > 0 && (n and (n - 1)) == 0)
        val real = DoubleArray(n); val imag = DoubleArray(n)
        for (i in 0 until n) {
            val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            real[i] = samples[i] * hann
        }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = real[i]; real[i] = real[j]; real[j] = tr }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr0 = cos(angle); val wi0 = sin(angle)
            for (start in 0 until n step len) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val even = start + k; val odd = even + len / 2
                    val tr = wr * real[odd] - wi * imag[odd]
                    val ti = wr * imag[odd] + wi * real[odd]
                    real[odd] = real[even] - tr; imag[odd] = imag[even] - ti
                    real[even] += tr; imag[even] += ti
                    val nextWr = wr * wr0 - wi * wi0; wi = wr * wi0 + wi * wr0; wr = nextWr
                }
            }
            len *= 2
        }
        val result = FloatArray(bands)
        for (band in 0 until bands) {
            val low = (20.0 * (20_000.0 / 20.0).pow(band.toDouble() / bands) * n / 48_000.0).toInt().coerceAtLeast(1)
            val high = (20.0 * (20_000.0 / 20.0).pow((band + 1.0) / bands) * n / 48_000.0).toInt().coerceAtLeast(low + 1)
            var energy = 0.0; var count = 0
            for (bin in low until min(high, n / 2)) { energy += real[bin] * real[bin] + imag[bin] * imag[bin]; count++ }
            result[band] = (10.0 * log10((energy / max(1, count) / (n * n)).coerceAtLeast(1e-12))).toFloat()
        }
        return result
    }
}

/** The same Gaussian magnetic movement is used for slider and graph gestures. */
object EqMagnetMath {
    private val ln2 = ln(2.0)
    fun move(start: List<Float>, index: Int, deltaDb: Float, magnet: EqMagnet, phoneSpeaker: Boolean = false): List<Float> {
        require(index in 0 until EqualizerMath.BAND_COUNT)
        val source = start.map { it.coerceIn(-12f, 12f) }
        val result = source.toMutableList()
        val requested = (source[index] + deltaDb).coerceIn(-12f, 12f)
        val constrained = if (phoneSpeaker && index < 2) requested.coerceAtMost(3f) else requested
        val actualDelta = constrained - source[index]
        for (j in result.indices) {
            if (j == index) { result[j] = constrained; continue }
            val distance = abs(log2(EqualizerMath.frequencies[j] / EqualizerMath.frequencies[index]))
            val weight = if (magnet == EqMagnet.OFF) 0.0 else exp(-ln2 * (distance / magnet.sigma).pow(2.0))
            if (weight < 0.05) continue
            var candidate = (source[j] + actualDelta * weight.toFloat()).coerceIn(-12f, 12f)
            // A neighbor may follow, but may not pass the dragged band away from zero.
            if (actualDelta > 0f && candidate > constrained) candidate = constrained
            if (actualDelta < 0f && candidate < constrained) candidate = constrained
            if (phoneSpeaker && j < 2) candidate = candidate.coerceAtMost(3f)
            result[j] = candidate
        }
        return result
    }

    fun log2(value: Double) = ln(value) / ln2
}

/** Linear time-domain interpolation helper for UI slider updates. */
fun List<Float>.withBand(index: Int, value: Float) = toMutableList().also { it[index] = value.coerceIn(-12f, 12f) }
