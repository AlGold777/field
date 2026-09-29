package app.soundfield.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Parameters resolved from the selected placement profile before entering the audio worker. */
data class SpatialDspConfig(
    val enabled: Boolean = false,
    val gMidDb: Double = 0.0,
    val gHighDb: Double = 0.0,
    val gAmbientDb: Double = 0.0,
    val strength: Double = 1.0,
    val depthDb: Double = 0.0,
    val reflectionsEnabled: Boolean = false,
    val reflectionTaps: List<SpatialReflectionTap> = emptyList(),
    val preserveLoudness: Boolean = true,
)

data class SpatialReflectionTap(val leftMs: Double, val rightMs: Double, val gainDb: Double)

/** Streaming width processor. Input spectra are retained for attack lookahead, output stays frame aligned. */
class SpatialProcessor(
    private val config: SpatialDspConfig,
    private val sampleRate: Int = SAMPLE_RATE,
    private val fftSize: Int = FFT_SIZE,
    private val hopSize: Int = HOP_SIZE,
) {
    @Volatile var sideMidDb: Double = 0.0
        private set
    @Volatile var correlation: Double = 1.0
        private set
    val lookaheadHops: Int = ATTACK_LOOKAHEAD_HOPS
    private val bins = fftSize / 2 + 1
    private val ringSize = lookaheadHops + 1
    private val ringLReal = DoubleArray(ringSize * fftSize)
    private val ringLImag = DoubleArray(ringSize * fftSize)
    private val ringRReal = DoubleArray(ringSize * fftSize)
    private val ringRImag = DoubleArray(ringSize * fftSize)
    private val ringMask = DoubleArray(ringSize * bins)
    private val ringMidPower = DoubleArray(ringSize * bins)
    private val ringSidePower = DoubleArray(ringSize * bins)
    private val ringIldGate = DoubleArray(ringSize * bins)
    private val attacks = ByteArray(ringSize * BAND_COUNT)
    private val smoothL = DoubleArray(bins)
    private val smoothR = DoubleArray(bins)
    private val smoothCrossR = DoubleArray(bins)
    private val smoothCrossI = DoubleArray(bins)
    private val smoothedGain = DoubleArray(bins) { 1.0 }
    private val binLower = IntArray(bins)
    private val binUpper = IntArray(bins)
    private val binBand = IntArray(bins)
    private val energyHistory = DoubleArray(ENERGY_WINDOW_FRAMES * BAND_COUNT)
    private val fluxHistory = DoubleArray(FLUX_WINDOW_FRAMES * BAND_COUNT)
    private val fluxScratch = DoubleArray(FLUX_WINDOW_FRAMES)
    private val deviationScratch = DoubleArray(FLUX_WINDOW_FRAMES)
    private val bandEnergy = DoubleArray(BAND_COUNT)
    private val bandFluxMagnitude = DoubleArray(BAND_COUNT)
    private val previousBandMagnitude = DoubleArray(BAND_COUNT)
    private val releaseState = DoubleArray(BAND_COUNT) { 1.0 }
    private val energyCursor = IntArray(BAND_COUNT)
    private val fluxCursor = IntArray(BAND_COUNT)
    private val energyCount = IntArray(BAND_COUNT)
    private val fluxCount = IntArray(BAND_COUNT)
    private val attackSuppressed = BooleanArray(BAND_COUNT)
    private var frameIndex = 0L
    private var firstFrame = true
    private val powerSmoothing = exp(-(hopSize.toDouble() / sampleRate) / 0.080)
    private val widthSmoothing = exp(-(hopSize.toDouble() / sampleRate) / 0.020)
    private val attackRelease = 1.0 - exp(-(hopSize.toDouble() / sampleRate) / 0.150)
    private val absolutePowerFloor = 1e-9 // -90 dBFS power
    private val midGain = 10.0.pow(config.gMidDb * config.strength / 20.0)
    private val highGain = 10.0.pow(config.gHighDb * config.strength / 20.0)
    private val ambientGain = 10.0.pow(config.gAmbientDb * config.strength / 20.0)
    private val depthGain = 10.0.pow(config.depthDb * config.strength / 20.0)
    private val depthHighGain = 10.0.pow((config.depthDb - 2.0) * config.strength / 20.0)

    init {
        require(sampleRate > 0 && fftSize >= 2 && fftSize.countOneBits() == 1 && hopSize in 1..fftSize)
        for (bin in 0 until bins) {
            val hz = bin.toDouble() * sampleRate / fftSize
            if (hz <= 0.0) {
                binLower[bin] = bin; binUpper[bin] = bin + 1; binBand[bin] = 0
            } else {
                val halfOctave = 2.0.pow(1.0 / 12.0)
                binLower[bin] = max(1, kotlin.math.ceil((hz / halfOctave) * fftSize / sampleRate).toInt())
                    .coerceAtMost(bins - 1)
                binUpper[bin] = kotlin.math.floor((hz * halfOctave) * fftSize / sampleRate).toInt()
                    .plus(1).coerceAtMost(bins)
                binBand[bin] = (3.0 * log2(hz / BAND_START_HZ) + 0.5).toInt().coerceIn(0, BAND_COUNT - 1)
            }
        }
    }

    /** Copies a transformed frame into the lookahead ring and, once ready, emits its delayed spectrum. */
    fun process(realL: DoubleArray, imagL: DoubleArray, realR: DoubleArray, imagR: DoubleArray): Boolean {
        require(realL.size == fftSize && imagL.size == fftSize && realR.size == fftSize && imagR.size == fftSize)
        val inputSlot = (frameIndex % ringSize).toInt()
        val spectrumBase = inputSlot * fftSize
        val binBase = inputSlot * bins
        for (bin in 0 until fftSize) {
            ringLReal[spectrumBase + bin] = realL[bin]; ringLImag[spectrumBase + bin] = imagL[bin]
            ringRReal[spectrumBase + bin] = realR[bin]; ringRImag[spectrumBase + bin] = imagR[bin]
        }
        analyze(inputSlot, spectrumBase, binBase)
        frameIndex++
        if (frameIndex <= lookaheadHops) return false

        val outputSlot = ((frameIndex - 1L - lookaheadHops) % ringSize).toInt()
        val outputSpectrumBase = outputSlot * fftSize
        val outputBinBase = outputSlot * bins
        if (config.enabled) applyWidth(outputSpectrumBase, outputBinBase, realL, imagL, realR, imagR)
        else {
            for (bin in 0 until fftSize) {
                realL[bin] = ringLReal[outputSpectrumBase + bin]; imagL[bin] = ringLImag[outputSpectrumBase + bin]
                realR[bin] = ringRReal[outputSpectrumBase + bin]; imagR[bin] = ringRImag[outputSpectrumBase + bin]
            }
        }
        return true
    }

    fun reset() {
        ringLReal.fill(0.0); ringLImag.fill(0.0); ringRReal.fill(0.0); ringRImag.fill(0.0)
        ringMask.fill(0.0); ringMidPower.fill(0.0); ringSidePower.fill(0.0); ringIldGate.fill(0.0)
        attacks.fill(0); smoothL.fill(0.0); smoothR.fill(0.0); smoothCrossR.fill(0.0); smoothCrossI.fill(0.0)
        smoothedGain.fill(1.0); energyHistory.fill(0.0); fluxHistory.fill(0.0)
        bandEnergy.fill(0.0); bandFluxMagnitude.fill(0.0); previousBandMagnitude.fill(0.0); releaseState.fill(1.0)
        energyCursor.fill(0); fluxCursor.fill(0); energyCount.fill(0); fluxCount.fill(0)
        frameIndex = 0; firstFrame = true
        sideMidDb = 0.0; correlation = 1.0
    }

    private fun analyze(slot: Int, spectrumBase: Int, binBase: Int) {
        bandEnergy.fill(0.0); bandFluxMagnitude.fill(0.0)
        var totalLeft = 0.0; var totalRight = 0.0; var totalCross = 0.0
        var totalMid = 0.0; var totalSide = 0.0
        for (bin in 0 until bins) {
            val lr = ringLReal[spectrumBase + bin]; val li = ringLImag[spectrumBase + bin]
            val rr = ringRReal[spectrumBase + bin]; val ri = ringRImag[spectrumBase + bin]
            val pL = (lr * lr + li * li) * POWER_SCALE
            val pR = (rr * rr + ri * ri) * POWER_SCALE
            val crossR = (lr * rr + li * ri) * POWER_SCALE
            val crossI = (li * rr - lr * ri) * POWER_SCALE
            totalLeft += pL; totalRight += pR; totalCross += crossR
            if (firstFrame) {
                smoothL[bin] = pL; smoothR[bin] = pR
                smoothCrossR[bin] = crossR; smoothCrossI[bin] = crossI
            } else {
                smoothL[bin] = powerSmoothing * smoothL[bin] + (1.0 - powerSmoothing) * pL
                smoothR[bin] = powerSmoothing * smoothR[bin] + (1.0 - powerSmoothing) * pR
                smoothCrossR[bin] = powerSmoothing * smoothCrossR[bin] + (1.0 - powerSmoothing) * crossR
                smoothCrossI[bin] = powerSmoothing * smoothCrossI[bin] + (1.0 - powerSmoothing) * crossI
            }
            val midR = (lr + rr) * 0.5; val midI = (li + ri) * 0.5
            val sideR = (lr - rr) * 0.5; val sideI = (li - ri) * 0.5
            ringMidPower[binBase + bin] = (midR * midR + midI * midI) * POWER_SCALE
            ringSidePower[binBase + bin] = (sideR * sideR + sideI * sideI) * POWER_SCALE
            totalMid += ringMidPower[binBase + bin]
            totalSide += ringSidePower[binBase + bin]
            val band = binBand[bin]
            bandEnergy[band] += (pL + pR) * 0.5
            bandFluxMagnitude[band] += sqrt((pL + pR) * 0.5)
        }
        firstFrame = false
        correlation = (totalCross / sqrt(max(1e-30, totalLeft * totalRight))).coerceIn(-1.0, 1.0)
        sideMidDb = 10.0 * log10(max(1e-30, totalSide) / max(1e-30, totalMid))

        for (band in 0 until BAND_COUNT) {
            val e = bandEnergy[band]
            val energyPosition = energyCursor[band]
            energyHistory[energyPosition * BAND_COUNT + band] = e
            energyCursor[band] = (energyPosition + 1) % ENERGY_WINDOW_FRAMES
            energyCount[band] = min(ENERGY_WINDOW_FRAMES, energyCount[band] + 1)

            val magnitude = bandFluxMagnitude[band] / max(1, bandBinCount(band))
            val previous = previousBandMagnitude[band]
            val flux = if (previous <= 1e-12) 0.0 else max(0.0, magnitude - previous) / previous
            previousBandMagnitude[band] = magnitude
            val fluxPosition = fluxCursor[band]
            fluxHistory[fluxPosition * BAND_COUNT + band] = flux
            fluxCursor[band] = (fluxPosition + 1) % FLUX_WINDOW_FRAMES
            fluxCount[band] = min(FLUX_WINDOW_FRAMES, fluxCount[band] + 1)
            attacks[slot * BAND_COUNT + band] = if (isAttack(band, fluxCount[band], flux)) 1 else 0
        }

        for (bin in 0 until bins) {
            val lo = binLower[bin]; val hi = binUpper[bin]
            var pL = 0.0; var pR = 0.0; var crossR = 0.0; var crossI = 0.0
            for (k in lo until hi) {
                pL += smoothL[k]; pR += smoothR[k]
                crossR += smoothCrossR[k]; crossI += smoothCrossI[k]
            }
            val count = max(1, hi - lo).toDouble()
            pL /= count; pR /= count; crossR /= count; crossI /= count
            val coherence = (sqrt(crossR * crossR + crossI * crossI) /
                sqrt(max(1e-30, pL * pR))).coerceIn(0.0, 1.0)
            val ild = 10.0 * log10(max(1e-30, pL) / max(1e-30, pR))
            val ildGate = ((12.0 - abs(ild)) / 6.0).coerceIn(0.0, 1.0)
            var mask = ((1.0 - coherence - 0.3) / 0.7).coerceIn(0.0, 1.0) * ildGate
            val hz = bin.toDouble() * sampleRate / fftSize
            val band = binBand[bin]
            val currentBinPower = (ringLReal[spectrumBase + bin] * ringLReal[spectrumBase + bin] +
                ringLImag[spectrumBase + bin] * ringLImag[spectrumBase + bin] + ringRReal[spectrumBase + bin] * ringRReal[spectrumBase + bin] +
                ringRImag[spectrumBase + bin] * ringRImag[spectrumBase + bin]) * (0.5 * POWER_SCALE)
            val peak = runningPeak(band)
            if (hz < 300.0 || bandEnergy[band] / max(1, bandBinCount(band)) < absolutePowerFloor ||
                currentBinPower < peak * 1e-6) mask = 0.0
            ringMask[binBase + bin] = mask
            ringIldGate[binBase + bin] = ildGate
        }
    }

    private fun applyWidth(spectrumBase: Int, binBase: Int,
        realL: DoubleArray, imagL: DoubleArray, realR: DoubleArray, imagR: DoubleArray) {
        var totalLeft = 0.0; var totalRight = 0.0; var totalCross = 0.0
        var totalMid = 0.0; var totalSide = 0.0
        attackSuppressed.fill(false)
        for (ahead in 0..lookaheadHops) {
            val aheadSlot = ((frameIndex - 1L - ahead).coerceAtLeast(0) % ringSize).toInt()
            for (band in 0 until BAND_COUNT) if (attacks[aheadSlot * BAND_COUNT + band].toInt() != 0) attackSuppressed[band] = true
        }
        for (band in 0 until BAND_COUNT) {
            releaseState[band] = if (attackSuppressed[band]) 0.0
                else releaseState[band] + (1.0 - releaseState[band]) * attackRelease
        }
        for (bin in 0 until bins) {
            val band = binBand[bin]
            val index = binBase + bin
            var mask = ringMask[index] * releaseState[band]
            val hz = bin.toDouble() * sampleRate / fftSize
            val baseline = widthGain(hz)
            var gain = baseline * (1.0 + (ambientGain - 1.0) * mask)
            gain = 1.0 + (gain - 1.0) * ringIldGate[index]
            val mid = ringMidPower[index]
            val side = ringSidePower[index]
            val phaseLimit = if (side <= 1e-20) Double.POSITIVE_INFINITY else sqrt(mid / side)
            gain = max(1.0, min(gain, phaseLimit))
            val previous = smoothedGain[bin]
            gain = widthSmoothing * previous + (1.0 - widthSmoothing) * gain
            smoothedGain[bin] = gain

            val specIndex = spectrumBase + bin
            val lr = ringLReal[specIndex]; val li = ringLImag[specIndex]
            val rr = ringRReal[specIndex]; val ri = ringRImag[specIndex]
            val midR = (lr + rr) * 0.5; val midI = (li + ri) * 0.5
            val sideR = (lr - rr) * 0.5 * gain; val sideI = (li - ri) * 0.5 * gain
            val depthT = (ln(max(1.0, hz) / 8_000.0) / ln(2.0)).coerceIn(0.0, 1.0)
            val highBlend = smoothstepCos(depthT)
            val targetDepthGain = depthGain + (depthHighGain - depthGain) * highBlend
            val octaveThird = 2.0.pow(1.0 / 6.0)
            val depthLo = max(1, kotlin.math.ceil((hz / octaveThird) * fftSize / sampleRate).toInt()).coerceAtMost(bins - 1)
            val depthHi = kotlin.math.floor((hz * octaveThird) * fftSize / sampleRate).toInt().plus(1).coerceAtMost(bins)
            var depthMask = 0.0
            for (k in depthLo until depthHi) depthMask += ringMask[binBase + k] * releaseState[binBand[k]]
            depthMask /= max(1, depthHi - depthLo)
            val depth = 1.0 - (1.0 - targetDepthGain) * depthMask
            val effectiveMidR = midR * depth; val effectiveMidI = midI * depth
            val lReal = effectiveMidR + sideR; val lImag = effectiveMidI + sideI
            val rReal = effectiveMidR - sideR; val rImag = effectiveMidI - sideI
            totalLeft += lReal * lReal + lImag * lImag
            totalRight += rReal * rReal + rImag * rImag
            totalCross += lReal * rReal + lImag * rImag
            totalMid += effectiveMidR * effectiveMidR + effectiveMidI * effectiveMidI
            totalSide += sideR * sideR + sideI * sideI
            realL[bin] = lReal; imagL[bin] = lImag; realR[bin] = rReal; imagR[bin] = rImag
            val mirror = fftSize - bin
            if (mirror != bin && mirror < fftSize) {
                realL[mirror] = lReal; imagL[mirror] = -lImag
                realR[mirror] = rReal; imagR[mirror] = -rImag
            }
        }
        correlation = (totalCross / sqrt(max(1e-30, totalLeft * totalRight))).coerceIn(-1.0, 1.0)
        sideMidDb = 10.0 * log10(max(1e-30, totalSide) / max(1e-30, totalMid))
    }

    private fun widthGain(hz: Double): Double {
        if (hz < 1.0) return 1.0
        val transition = 2.0.pow(1.0 / 6.0)
        val lowStart = 300.0 / transition
        val lowEnd = 300.0 * transition
        val midEnd = 5_000.0 / transition
        val highStart = 5_000.0 * transition
        if (hz < lowStart) return 1.0
        if (hz < lowEnd) return 1.0 + (midGain - 1.0) * smoothstepCos(ln(hz / lowStart) / ln(lowEnd / lowStart))
        if (hz <= midEnd) return midGain
        if (hz < highStart) {
            val t = ln(hz / midEnd) / ln(highStart / midEnd)
            return midGain + (highGain - midGain) * smoothstepCos(t)
        }
        return highGain
    }

    private fun smoothstepCos(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return 0.5 - 0.5 * cos(PI * x)
    }

    private fun runningPeak(band: Int): Double {
        var peak = 0.0
        for (i in 0 until energyCount[band]) peak = max(peak, energyHistory[i * BAND_COUNT + band])
        return peak
    }

    private fun isAttack(band: Int, count: Int, currentFlux: Double): Boolean {
        if (count < 8) return false
        for (i in 0 until count) fluxScratch[i] = fluxHistory[i * BAND_COUNT + band]
        fluxScratch.sort(0, count)
        val median = median(fluxScratch, count)
        for (i in 0 until count) deviationScratch[i] = abs(fluxHistory[i * BAND_COUNT + band] - median)
        deviationScratch.sort(0, count)
        return currentFlux > median + 4.0 * median(deviationScratch, count)
    }

    private fun median(sorted: DoubleArray, count: Int): Double =
        if (count and 1 == 1) sorted[count / 2] else (sorted[count / 2 - 1] + sorted[count / 2]) * 0.5

    private fun bandBinCount(band: Int): Int {
        val low = BAND_START_HZ * 2.0.pow((band - 0.5) / 3.0)
        val high = BAND_START_HZ * 2.0.pow((band + 0.5) / 3.0)
        val first = max(1, (low * fftSize / sampleRate).toInt())
        val last = min(bins, kotlin.math.ceil(high * fftSize / sampleRate).toInt())
        return max(1, last - first)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FFT_SIZE = 2_048
        const val HOP_SIZE = 512
        const val ATTACK_LOOKAHEAD_HOPS = 5
        const val BAND_COUNT = 32
        private const val BAND_START_HZ = 20.0
        private const val ENERGY_WINDOW_FRAMES = 94
        private const val FLUX_WINDOW_FRAMES = 188
        private const val POWER_SCALE = 1.0 / (FFT_SIZE.toDouble() * FFT_SIZE)
    }
}
