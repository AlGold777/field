package app.soundfield.dsp

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Four real FIR paths and the model diagnostics used to produce them. */
class XtcFilterSet internal constructor(
    internal val ll: DoubleArray,
    internal val lr: DoubleArray,
    internal val rl: DoubleArray,
    internal val rr: DoubleArray,
    val spacingMeters: Double,
    val beta: Double,
    designMs: Double,
    val predictedMedianDb: Double,
    val predictedWorstDb: Double,
    val delayFrames: Int = XtcFilterDesigner.DELAY_FRAMES,
) {
    @Volatile var designMs: Double = designMs
        internal set
    internal val spectra: Array<DoubleArray> = Array(8) { DoubleArray(XtcFilterDesigner.FFT_SIZE) }

    init {
        val fft = Radix2Fft(XtcFilterDesigner.FFT_SIZE)
        val filters = arrayOf(ll, lr, rl, rr)
        for (filterIndex in filters.indices) {
            val real = spectra[filterIndex * 2]
            val imag = spectra[filterIndex * 2 + 1]
            for (i in filters[filterIndex].indices) real[i] = filters[filterIndex][i]
            fft.transform(real, imag)
        }
    }
}

/** Calculates the regularized 2×2 inverse transfer over the hand-plus-phone listening region. */
object XtcFilterDesigner {
    const val FFT_SIZE = 4_096
    const val TAPS = 1_024
    const val DELAY_FRAMES = 480
    private const val SAMPLE_RATE = 48_000.0
    private const val HEAD_RADIUS_M = 0.0875
    private const val SPEED_OF_SOUND = 343.0
    private const val BLOCK_FRAMES = 1_024
    private val designExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sf-xtc-design").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
    }
    private val cache = ConcurrentHashMap<String, CompletableFuture<XtcFilterSet>>()

    /** Same model/level request shares one background design. */
    fun request(modelKey: String, spacingMeters: Double, beta: Double): CompletableFuture<XtcFilterSet> {
        val spacing = spacingMeters.coerceIn(0.08, 0.25)
        val regularization = beta.coerceIn(1e-4, 10.0)
        val key = "$modelKey:${"%.5f".format(java.util.Locale.US, spacing)}:${"%.6f".format(java.util.Locale.US, regularization)}"
        return cache.computeIfAbsent(key) {
            CompletableFuture.supplyAsync({ design(spacing, regularization) }, designExecutor)
        }
    }

    fun design(spacingMeters: Double, beta: Double): XtcFilterSet {
        val started = System.nanoTime()
        val spacing = spacingMeters.coerceIn(0.08, 0.25)
        val regularization = beta.coerceIn(1e-4, 10.0)
        val fft = Radix2Fft(FFT_SIZE)
        val response = Array(4) { Array(FFT_SIZE / 2 + 1) { Complex() } }
        val frequencyStep = SAMPLE_RATE / FFT_SIZE
        val points = buildList {
            for (distanceStep in 0..4) for (lateralCm in intArrayOf(-5, 0, 5))
                add(ListenerPoint(0.30 + distanceStep * 0.10, lateralCm / 100.0))
        }

        for (bin in 0..FFT_SIZE / 2) {
            val hz = bin * frequencyStep
            val band = bandWeight(hz)
            if (band <= 0.0) {
                response[0][bin] = Complex(1.0, 0.0)
                response[3][bin] = Complex(1.0, 0.0)
                continue
            }
            val normal = ComplexMatrix2()
            val rightHandSide = Array(2) { Array(2) { Complex() } }
            val pointWeight = 1.0 / points.size
            for (point in points) {
                val h = transfer(point, spacing, hz)
                val directL = h[0]
                val directR = h[3]
                // Each target keeps its same-side direct path and suppresses the opposite-side path.
                val target = arrayOf(arrayOf(directL, Complex()), arrayOf(Complex(), directR))
                val h0 = h[0]; val h1 = h[1]; val h2 = h[2]; val h3 = h[3]
                normal.a00 += (h0.conjugate() * h0 + h2.conjugate() * h2) * pointWeight
                normal.a11 += (h1.conjugate() * h1 + h3.conjugate() * h3) * pointWeight
                normal.a01 += (h0.conjugate() * h1 + h2.conjugate() * h3) * pointWeight
                for (column in 0..1) {
                    rightHandSide[0][column] += (h0.conjugate() * target[0][column] + h2.conjugate() * target[1][column]) * pointWeight
                    rightHandSide[1][column] += (h1.conjugate() * target[0][column] + h3.conjugate() * target[1][column]) * pointWeight
                }
            }
            val solved = normal.solve(rightHandSide, regularization)
            response[0][bin] = solved[0][0]
            response[1][bin] = solved[0][1]
            response[2][bin] = solved[1][0]
            response[3][bin] = solved[1][1]

            // Keep the regularized inverse inside the requested band and return smoothly to unity outside it.
            val unity = Complex(1.0, 0.0)
            response[0][bin] = response[0][bin] * band + unity * (1.0 - band)
            response[3][bin] = response[3][bin] * band + unity * (1.0 - band)
            response[1][bin] = response[1][bin] * band
            response[2][bin] = response[2][bin] * band
            normal.clear()
            for (row in 0..1) for (column in 0..1) rightHandSide[row][column] = Complex()
        }

        // Report on a separate held-out grid: 0.3–0.7 m by 2.5 cm and ±8 cm by 1 cm.
        val validationPoints = buildList {
            for (distanceStep in 0..16) for (lateralStep in 0..16)
                add(ListenerPoint(0.30 + distanceStep * 0.025, -0.08 + lateralStep * 0.01))
        }
        val validationFrequencies = doubleArrayOf(500.0, 630.0, 800.0, 1_000.0, 1_250.0, 1_600.0,
            2_000.0, 2_500.0, 3_150.0, 4_000.0, 5_000.0, 6_000.0)
        val crossRatios = ArrayList<Double>(validationPoints.size * validationFrequencies.size)
        for (hz in validationFrequencies) {
            val bin = (hz / frequencyStep).toInt().coerceIn(0, FFT_SIZE / 2)
            val c0 = response[0][bin]; val c1 = response[1][bin]
            val c2 = response[2][bin]; val c3 = response[3][bin]
            for (point in validationPoints) {
                val h = transfer(point, spacing, hz)
                val outLL = h[0] * c0 + h[1] * c2
                val outLR = h[0] * c1 + h[1] * c3
                val outRL = h[2] * c0 + h[3] * c2
                val outRR = h[2] * c1 + h[3] * c3
                val direct = max((outLL.magnitude() + outRR.magnitude()) * 0.5, 1e-12)
                val cross = (outLR.magnitude() + outRL.magnitude()) * 0.5
                crossRatios += 20.0 * kotlin.math.log10(max(cross / direct, 1e-12))
            }
        }

        // Enforce Hermitian symmetry before IFFT; then place the 10 ms causal delay at tap 480.
        val tapsByPath = Array(4) { path ->
            val real = DoubleArray(FFT_SIZE)
            val imag = DoubleArray(FFT_SIZE)
            for (bin in 0..FFT_SIZE / 2) {
                real[bin] = response[path][bin].re
                imag[bin] = response[path][bin].im
            }
            for (bin in 1 until FFT_SIZE / 2) {
                real[FFT_SIZE - bin] = real[bin]
                imag[FFT_SIZE - bin] = -imag[bin]
            }
            fft.transform(real, imag, inverse = true)
            val taps = DoubleArray(TAPS)
            for (tap in 0 until TAPS) {
                val sourceIndex = (tap - DELAY_FRAMES + FFT_SIZE) % FFT_SIZE
                taps[tap] = real[sourceIndex] * tukey(tap, TAPS)
            }
            taps
        }
        val sorted = crossRatios.sorted()
        val median = sorted.getOrNull(sorted.size / 2) ?: 0.0
        val worst = sorted.maxOrNull() ?: 0.0
        val result = XtcFilterSet(tapsByPath[0], tapsByPath[1], tapsByPath[2], tapsByPath[3], spacing,
            regularization, 0.0, median, worst)
        result.designMs = (System.nanoTime() - started) / 1e6
        return result
    }

    private fun transfer(point: ListenerPoint, spacing: Double, hz: Double): Array<Complex> {
        val speakerX = doubleArrayOf(-spacing / 2.0, spacing / 2.0)
        val k = 2.0 * PI * hz / SPEED_OF_SOUND
        val h = Array(4) { Complex() }
        for (speaker in 0..1) {
            val sourceDx = speakerX[speaker] - point.lateral
            val range = sqrt(sourceDx * sourceDx + point.distance * point.distance).coerceAtLeast(HEAD_RADIUS_M * 1.01)
            val freeField = Complex(cos(-k * range), sin(-k * range)) * (1.0 / range)
            for (ear in 0..1) {
            val outward = if (ear == 0) -1.0 else 1.0
                val cosTheta = (sourceDx / range * outward).coerceIn(-1.0, 1.0)
                val sphere = rigidSphereResponse(range, cosTheta, hz)
                h[ear * 2 + speaker] = freeField * sphere
            }
        }
        return h
    }

    /** Finite-range rigid-sphere solution evaluated with the Q-polynomial recurrence. */
    private fun rigidSphereResponse(range: Double, cosTheta: Double, hz: Double): Complex {
        val mu = 2.0 * PI * hz * HEAD_RADIUS_M / SPEED_OF_SOUND
        if (mu < 1e-8) return Complex(1.0)
        val rho = range / HEAD_RADIUS_M
        val zr = Complex(1.0) / Complex(0.0, mu * rho)
        val za = Complex(1.0) / Complex(0.0, mu)
        var qr2 = zr
        var qr1 = zr * Complex(1.0 - zr.re, -zr.im)
        var qa2 = za
        var qa1 = za * Complex(1.0 - za.re, -za.im)
        var p2 = 1.0
        var p1 = cosTheta
        var sum = zr / (za * (za - Complex(1.0)))
        var term = (zr * Complex(1.0 - zr.re, -zr.im) * (3.0 * cosTheta)) /
            (za * (za * za * 2.0 - za * 2.0 + Complex(1.0)))
        sum += term
        var stableTerms = 0
        for (order in 2..80) {
            val qr = zr * qr1 * (-(2.0 * order - 1.0)) + qr2
            val qa = za * qa1 * (-(2.0 * order - 1.0)) + qa2
            val p = ((2.0 * order - 1.0) * cosTheta * p1 - (order - 1.0) * p2) / order
            term = (qr * ((2.0 * order + 1.0) * p)) /
                (za * qa * (order + 1.0) - qa1)
            sum += term
            val relative = term.magnitude() / sum.magnitude().coerceAtLeast(1e-30)
            stableTerms = if (relative < 1e-12) stableTerms + 1 else 0
            if (stableTerms >= 2) break
            qr2 = qr1; qr1 = qr; qa2 = qa1; qa1 = qa; p2 = p1; p1 = p
        }
        val radialPhase = Complex(cos(mu), -sin(mu))
        return (radialPhase * sum * rho) / Complex(0.0, mu)
    }

    private fun bandWeight(hz: Double): Double {
        if (hz <= 0.0) return 0.0
        val edge = 2.0.pow(1.0 / 6.0)
        val lowStart = 500.0 / edge
        val lowEnd = 500.0 * edge
        val highStart = 6_000.0 / edge
        val highEnd = 6_000.0 * edge
        return when {
            hz <= lowStart || hz >= highEnd -> 0.0
            hz < lowEnd -> {
                val t = ln(hz / lowStart) / ln(lowEnd / lowStart)
                0.5 - 0.5 * cos(PI * t)
            }
            hz <= highStart -> 1.0
            else -> {
                val t = ln(hz / highStart) / ln(highEnd / highStart)
                0.5 + 0.5 * cos(PI * t)
            }
        }
    }

    private fun tukey(index: Int, length: Int): Double {
        val alpha = 0.5
        val x = index.toDouble() / (length - 1)
        return when {
            x < alpha / 2.0 -> 0.5 * (1.0 + cos(PI * (2.0 * x / alpha - 1.0)))
            x > 1.0 - alpha / 2.0 -> 0.5 * (1.0 + cos(PI * (2.0 * x / alpha - 2.0 / alpha + 1.0)))
            else -> 1.0
        }
    }

    private data class ListenerPoint(val distance: Double, val lateral: Double)

    private data class Complex(var re: Double = 0.0, var im: Double = 0.0) {
        operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
        operator fun minus(other: Complex) = Complex(re - other.re, im - other.im)
        operator fun times(other: Complex) = Complex(re * other.re - im * other.im, re * other.im + im * other.re)
        operator fun times(scale: Double) = Complex(re * scale, im * scale)
        operator fun div(other: Complex): Complex {
            val denominator = other.re * other.re + other.im * other.im
            return if (denominator <= 1e-30) Complex() else Complex(
                (re * other.re + im * other.im) / denominator,
                (im * other.re - re * other.im) / denominator,
            )
        }
        fun conjugate() = Complex(re, -im)
        fun magnitude() = sqrt(re * re + im * im)
    }

    private class ComplexMatrix2 {
        var a00 = Complex()
        var a01 = Complex()
        var a11 = Complex()
        fun clear() { a00 = Complex(); a01 = Complex(); a11 = Complex() }
        fun solve(rhs: Array<Array<Complex>>, beta: Double): Array<Array<Complex>> {
            // Tikhonov regularization is centered on unity so the high-regularization limit is transparent.
            val d00 = a00 + Complex(beta)
            val d11 = a11 + Complex(beta)
            val d01 = a01
            val determinant = d00 * d11 - d01.conjugate() * d01
            val safe = if (determinant.magnitude() < 1e-24) Complex(1e-24) else determinant
            val result = Array(2) { Array(2) { Complex() } }
            for (column in 0..1) {
                val b0 = rhs[0][column] + if (column == 0) Complex(beta) else Complex()
                val b1 = rhs[1][column] + if (column == 1) Complex(beta) else Complex()
                result[0][column] = (d11 * b0 - d01 * b1) / safe
                result[1][column] = (d00 * b1 - d01.conjugate() * b0) / safe
            }
            return result
        }
    }
}

/** Uniform 1024-frame overlap-save blocks using the required 4096-point transforms. */
class XtcProcessor(private val filters: XtcFilterSet, private val sink: (Long, Float, Float) -> Unit) {
    private val fft = Radix2Fft(XtcFilterDesigner.FFT_SIZE)
    private val inputLeft = DoubleArray(BLOCK_FRAMES)
    private val inputRight = DoubleArray(BLOCK_FRAMES)
    private val historyLeft = DoubleArray(XtcFilterDesigner.TAPS - 1)
    private val historyRight = DoubleArray(XtcFilterDesigner.TAPS - 1)
    private val leftReal = DoubleArray(XtcFilterDesigner.FFT_SIZE)
    private val leftImag = DoubleArray(XtcFilterDesigner.FFT_SIZE)
    private val rightReal = DoubleArray(XtcFilterDesigner.FFT_SIZE)
    private val rightImag = DoubleArray(XtcFilterDesigner.FFT_SIZE)
    private val outLeftReal = DoubleArray(XtcFilterDesigner.FFT_SIZE)
    private val outLeftImag = DoubleArray(XtcFilterDesigner.FFT_SIZE)
    private val outRightReal = DoubleArray(XtcFilterDesigner.FFT_SIZE)
    private val outRightImag = DoubleArray(XtcFilterDesigner.FFT_SIZE)
    private var count = 0
    private var blockStart = 0L

    fun push(frame: Long, left: Float, right: Float) {
        if (count == 0) blockStart = frame
        inputLeft[count] = left.toDouble()
        inputRight[count] = right.toDouble()
        count++
        if (count == BLOCK_FRAMES) processBlock()
    }

    fun reset() {
        inputLeft.fill(0.0); inputRight.fill(0.0)
        historyLeft.fill(0.0); historyRight.fill(0.0)
        count = 0; blockStart = 0L
    }

    private fun processBlock() {
        leftReal.fill(0.0); leftImag.fill(0.0); rightReal.fill(0.0); rightImag.fill(0.0)
        val overlap = XtcFilterDesigner.TAPS - 1
        System.arraycopy(historyLeft, 0, leftReal, 0, overlap)
        System.arraycopy(historyRight, 0, rightReal, 0, overlap)
        System.arraycopy(inputLeft, 0, leftReal, overlap, BLOCK_FRAMES)
        System.arraycopy(inputRight, 0, rightReal, overlap, BLOCK_FRAMES)
        fft.transform(leftReal, leftImag)
        fft.transform(rightReal, rightImag)
        val llReal = filters.spectra[0]; val llImag = filters.spectra[1]
        val lrReal = filters.spectra[2]; val lrImag = filters.spectra[3]
        val rlReal = filters.spectra[4]; val rlImag = filters.spectra[5]
        val rrReal = filters.spectra[6]; val rrImag = filters.spectra[7]
        for (bin in 0 until XtcFilterDesigner.FFT_SIZE) {
            val lR = leftReal[bin]; val lI = leftImag[bin]
            val rR = rightReal[bin]; val rI = rightImag[bin]
            val llR = llReal[bin] * lR - llImag[bin] * lI
            val llI = llReal[bin] * lI + llImag[bin] * lR
            val lrR = lrReal[bin] * rR - lrImag[bin] * rI
            val lrI = lrReal[bin] * rI + lrImag[bin] * rR
            val rlR = rlReal[bin] * lR - rlImag[bin] * lI
            val rlI = rlReal[bin] * lI + rlImag[bin] * lR
            val rrR = rrReal[bin] * rR - rrImag[bin] * rI
            val rrI = rrReal[bin] * rI + rrImag[bin] * rR
            outLeftReal[bin] = llR + lrR; outLeftImag[bin] = llI + lrI
            outRightReal[bin] = rlR + rrR; outRightImag[bin] = rlI + rrI
        }
        fft.transform(outLeftReal, outLeftImag, inverse = true)
        fft.transform(outRightReal, outRightImag, inverse = true)
        val validStart = XtcFilterDesigner.TAPS - 1
        for (i in 0 until BLOCK_FRAMES) {
            val outputFrame = blockStart + i - filters.delayFrames
            sink(outputFrame, outLeftReal[validStart + i].toFloat(), outRightReal[validStart + i].toFloat())
        }
        System.arraycopy(inputLeft, BLOCK_FRAMES - overlap, historyLeft, 0, overlap)
        System.arraycopy(inputRight, BLOCK_FRAMES - overlap, historyRight, 0, overlap)
        count = 0
    }

    companion object { const val BLOCK_FRAMES = 1_024 }
}
