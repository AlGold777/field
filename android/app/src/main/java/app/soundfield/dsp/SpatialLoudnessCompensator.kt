package app.soundfield.dsp

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Slow 3-second gated K-weighted level matching for the processed branch. */
class SpatialLoudnessCompensator(
    private val sampleRate: Int = 48_000,
    private val enabled: Boolean = true,
    fixedCompensationDb: Double? = null,
) {
    private val fixedGain = fixedCompensationDb?.coerceIn(-3.0, 3.0)?.let { 10.0.pow(it / 20.0) }
    private val shelf = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285,
        -1.69065929318241, 0.73248077421585)
    private val highPass = doubleArrayOf(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621)
    // Four streams: input L/R and processed L/R. Each has two biquad state groups.
    private val states = Array(4) { DoubleArray(8) }
    private val windowBlocks = (3.0 * sampleRate / BLOCK_FRAMES).toInt().coerceAtLeast(1)
    private val inputWindow = DoubleArray(windowBlocks)
    private val outputWindow = DoubleArray(windowBlocks)
    private var windowCount = 0
    private var windowCursor = 0
    private var blockFrames = 0
    private var blockInputPower = 0.0
    private var blockOutputPower = 0.0
    private var gain = fixedGain ?: 1.0
    private val smoothing = 1.0 - exp(-BLOCK_FRAMES / (10.0 * sampleRate))
    @Volatile var compensationDb: Double = fixedCompensationDb?.coerceIn(-3.0, 3.0) ?: 0.0
        private set

    /** Apply the current gain to output samples, then feed pre-gain levels to the estimator. */
    fun gain(): Double = if (enabled) gain else 1.0

    fun observe(inputLeft: Float, inputRight: Float, outputLeft: Float, outputRight: Float) {
        if (!enabled || fixedGain != null) return
        val inL = weighted(inputLeft.toDouble(), states[0])
        val inR = weighted(inputRight.toDouble(), states[1])
        val outL = weighted(outputLeft.toDouble(), states[2])
        val outR = weighted(outputRight.toDouble(), states[3])
        blockInputPower += (inL * inL + inR * inR) * 0.5
        blockOutputPower += (outL * outL + outR * outR) * 0.5
        blockFrames++
        if (blockFrames < BLOCK_FRAMES) return

        val inputPower = blockInputPower / blockFrames
        val outputPower = blockOutputPower / blockFrames
        inputWindow[windowCursor] = inputPower
        outputWindow[windowCursor] = outputPower
        windowCursor = (windowCursor + 1) % windowBlocks
        windowCount = minOf(windowBlocks, windowCount + 1)
        blockFrames = 0; blockInputPower = 0.0; blockOutputPower = 0.0
        updateGain()
    }

    private fun updateGain() {
        val absoluteGate = 1e-7 // -70 dBFS power
        var inputSum = 0.0; var inputN = 0
        var outputSum = 0.0; var outputN = 0
        for (i in 0 until windowCount) {
            val input = inputWindow[i]; val output = outputWindow[i]
            if (input >= absoluteGate) { inputSum += input; inputN++ }
            if (output >= absoluteGate) { outputSum += output; outputN++ }
        }
        if (inputN == 0 || outputN == 0) return
        val inputMean = inputSum / inputN
        val outputMean = outputSum / outputN
        val inputGate = max(absoluteGate, inputMean * 0.1) // BS.1770 relative gate: -10 LU
        val outputGate = max(absoluteGate, outputMean * 0.1)
        inputSum = 0.0; inputN = 0; outputSum = 0.0; outputN = 0
        for (i in 0 until windowCount) {
            if (inputWindow[i] >= inputGate) { inputSum += inputWindow[i]; inputN++ }
            if (outputWindow[i] >= outputGate) { outputSum += outputWindow[i]; outputN++ }
        }
        if (inputN == 0 || outputN == 0) return
        val target = sqrt((inputSum / inputN) / (outputSum / outputN))
            .coerceIn(10.0.pow(-3.0 / 20.0), 10.0.pow(3.0 / 20.0))
        gain += (target - gain) * smoothing
        compensationDb = 20.0 * kotlin.math.log10(gain)
    }

    private fun weighted(input: Double, state: DoubleArray): Double {
        val first = filter(input, state, 0, shelf)
        return filter(first, state, 4, highPass)
    }

    private fun filter(input: Double, state: DoubleArray, offset: Int, c: DoubleArray): Double {
        val output = c[0] * input + c[1] * state[offset] + c[2] * state[offset + 1] -
            c[3] * state[offset + 2] - c[4] * state[offset + 3]
        state[offset + 1] = state[offset]; state[offset] = input
        state[offset + 3] = state[offset + 2]; state[offset + 2] = output
        return output
    }

    fun reset() {
        states.forEach { it.fill(0.0) }
        inputWindow.fill(0.0); outputWindow.fill(0.0)
        windowCount = 0; windowCursor = 0; blockFrames = 0
        blockInputPower = 0.0; blockOutputPower = 0.0
        gain = fixedGain ?: 1.0
        compensationDb = if (fixedGain == null) 0.0 else 20.0 * kotlin.math.log10(fixedGain)
    }

    companion object { private const val BLOCK_FRAMES = 512 }
}
