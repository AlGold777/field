package app.soundfield.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Linked stereo true-peak guard with a fixed 5 ms lookahead and an 80 ms release. */
class SpatialPeakLimiter(
    private val sampleRate: Int = 48_000,
    val lookaheadFrames: Int = sampleRate / 200,
    private val threshold: Double = 10.0.pow(-0.3 / 20.0),
) {
    private val length = lookaheadFrames + 1
    private val left = FloatArray(length)
    private val right = FloatArray(length)
    private val maximaFrames = LongArray(length + 1)
    private val maximaValues = DoubleArray(length + 1)
    private var cursor = 0
    private var pushed = 0
    private var maxHead = 0
    private var maxCount = 0
    private var gain = 1.0
    private val release = 1.0 - exp(-1.0 / (0.080 * sampleRate))
    var outputFrame: Long = -1L
        private set
    var outputLeft: Float = 0f
        private set
    var outputRight: Float = 0f
        private set
    @Volatile var maxReductionDb: Double = 0.0
        private set

    /** Returns true when an aligned frame is ready in [outputLeft]/[outputRight]. */
    fun process(inputFrame: Long, inputLeft: Float, inputRight: Float): Boolean {
        left[cursor] = inputLeft; right[cursor] = inputRight
        val currentPeak = max(abs(inputLeft.toDouble()), abs(inputRight.toDouble()))
        while (maxCount > 0) {
            val last = (maxHead + maxCount - 1) % maximaFrames.size
            if (maximaValues[last] > currentPeak) break
            maxCount--
        }
        val tail = (maxHead + maxCount) % maximaFrames.size
        maximaFrames[tail] = inputFrame; maximaValues[tail] = currentPeak; maxCount++
        val oldestFrame = inputFrame - lookaheadFrames
        while (maxCount > 0 && maximaFrames[maxHead] < oldestFrame) {
            maxHead = (maxHead + 1) % maximaFrames.size; maxCount--
        }
        val peak = if (maxCount > 0) maximaValues[maxHead] else 0.0
        val wanted = if (peak > threshold) threshold / peak else 1.0
        gain = if (wanted < gain) wanted else gain + (1.0 - gain) * release
        val read = (cursor + 1) % length
        outputFrame = inputFrame - lookaheadFrames
        val valid = pushed >= lookaheadFrames
        val delayedPeak = max(abs(left[read].toDouble()), abs(right[read].toDouble()))
        if (delayedPeak * gain > threshold) gain = threshold / delayedPeak
        val appliedGain = gain
        val reductionDb = -20.0 * ln(appliedGain) / ln(10.0)
        if (reductionDb > maxReductionDb) maxReductionDb = reductionDb
        outputLeft = (left[read] * appliedGain).toFloat()
        outputRight = (right[read] * appliedGain).toFloat()
        cursor = read
        pushed++
        return valid
    }

    fun reset() {
        left.fill(0f); right.fill(0f)
        cursor = 0; pushed = 0; maxHead = 0; maxCount = 0; gain = 1.0; outputFrame = -1L
        outputLeft = 0f; outputRight = 0f; maxReductionDb = 0.0
    }
}
