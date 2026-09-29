package app.soundfield.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class SyncProbeResult(
    val valid: Boolean,
    val reason: String = "",
    val trials: Int = 0,
    /** Measured L-to-R median includes geometry and fixed output latency; it is not a sync error. */
    val medianMs: Double = 0.0,
    val sigmaUs: Double = 0.0,
    val p95Us: Double = 0.0,
    val maxUs: Double = 0.0,
    val ppm: Double = 0.0,
)

/** Recording-side chirp correlation. This is diagnostics-only and runs off the audio thread. */
object SyncProbeAnalyzer {
    private const val MIN_CORRELATION = 0.12
    private const val COARSE_STEP = 4

    fun analyze(
        samples: FloatArray,
        sampleRate: Int,
        recordingStartNs: Long,
        anchorServerNs: Long,
        trialLimit: Int = SyncProbeSource.TRIALS,
    ): SyncProbeResult {
        if (samples.any { abs(it) > 0.89125094f }) return SyncProbeResult(false, "recording_clipped")
        val template = SyncProbeSource.makeChirp()
        val expectedStart = (anchorServerNs - recordingStartNs).toDouble() * sampleRate / 1e9
        val searchRadius = (sampleRate * 0.040).toInt()
        val deltas = ArrayList<Double>(trialLimit)
        val localPositions = ArrayList<Double>(trialLimit)
        for (trial in 0 until trialLimit) {
            val base = expectedStart + trial.toDouble() * sampleRate
            val left = peak(samples, template, base.toInt(), searchRadius)
            val right = peak(samples, template, (base + SyncProbeSource.SECOND_CHIRP_OFFSET_FRAMES).toInt(), searchRadius)
            if (left == null || right == null || left.score < MIN_CORRELATION || right.score < MIN_CORRELATION) continue
            localPositions += left.position.toDouble()
            deltas += (right.position - left.position - SyncProbeSource.SECOND_CHIRP_OFFSET_FRAMES).toDouble()
        }
        if (deltas.size < 10) return SyncProbeResult(false, "insufficient_chirps", trials = deltas.size)
        val sorted = deltas.sorted()
        val median = percentile(sorted, 0.5)
        val deviations = deltas.map { abs(it - median) }.sorted()
        val mean = deltas.average()
        val sigma = sqrt(deltas.sumOf { (it - mean) * (it - mean) } / deltas.size)
        return SyncProbeResult(
            valid = true,
            trials = deltas.size,
            medianMs = median * 1_000.0 / sampleRate,
            sigmaUs = sigma * 1e6 / sampleRate,
            p95Us = percentile(deviations, 0.95) * 1e6 / sampleRate,
            maxUs = deviations.last() * 1e6 / sampleRate,
            ppm = driftPpm(localPositions, sampleRate),
        )
    }

    private data class Peak(val position: Double, val score: Double)

    private fun peak(signal: FloatArray, template: FloatArray, expected: Int, radius: Int): Peak? {
        val first = max(0, expected - radius)
        val last = minOf(signal.size - template.size, expected + radius)
        if (last <= first) return null
        var best = first
        var bestScore = -1.0
        for (start in first..last step COARSE_STEP) {
            var dot = 0.0
            for (i in template.indices) {
                dot += signal[start + i] * template[i]
            }
            val score = abs(dot) / TEMPLATE_ENERGY
            if (score > bestScore) { best = start; bestScore = score }
        }
        val refineFirst = max(first, best - COARSE_STEP)
        val refineLast = minOf(last, best + COARSE_STEP)
        for (start in refineFirst..refineLast) {
            val score = abs(correlation(signal, template, start)) / TEMPLATE_ENERGY
            if (score > bestScore) { best = start; bestScore = score }
        }
        val before = correlation(signal, template, best - 1)
        val center = correlation(signal, template, best)
        val after = correlation(signal, template, best + 1)
        val denominator = before - 2.0 * center + after
        val correction = if (abs(denominator) > 1e-12)
            (0.5 * (before - after) / denominator).coerceIn(-0.5, 0.5) else 0.0
        return Peak(best + correction, bestScore)
    }

    private fun correlation(signal: FloatArray, template: FloatArray, start: Int): Double {
        if (start < 0 || start + template.size > signal.size) return 0.0
        var dot = 0.0
        for (i in template.indices) dot += signal[start + i] * template[i]
        return dot
    }

    private val TEMPLATE_ENERGY = SyncProbeSource.makeChirp().sumOf { it.toDouble() * it }

    private fun percentile(sorted: List<Double>, fraction: Double): Double =
        sorted[((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)]

    private fun driftPpm(positions: List<Double>, sampleRate: Int): Double {
        if (positions.size < 3) return 0.0
        val meanX = (positions.size - 1) / 2.0
        val meanY = positions.average()
        var numerator = 0.0
        var denominator = 0.0
        for (i in positions.indices) {
            val x = i - meanX
            numerator += x * (positions[i] - meanY)
            denominator += x * x
        }
        val framesPerTrial = numerator / denominator
        return (framesPerTrial / sampleRate - 1.0) * 1e6
    }
}
