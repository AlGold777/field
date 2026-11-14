package app.soundfield.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Параметры сцены, разрешённые из настроек и профиля до входа в аудио-поток. */
data class SpatialDspConfig(
    val enabled: Boolean = false,
    /** Положение ползунка «Ширина», 0…1. */
    val width: Double = 0.0,
    /** Положение ползунка «Глубина», 0…1. */
    val depth: Double = 0.0,
    /** Масштаб геометрии колонок из профиля: больше — дальше за пределы базы. */
    val span: Double = 1.0,
    val preserveLoudness: Boolean = true,
    val fixedLoudnessCompensationDb: Double? = null,
)

/**
 * STFT-стадия сцены. Бин делится на первичный источник (главная ось ковариации L/R) и остаток (ambient).
 * Ширина: первичный источник переносится по панораме дальше (за колонки — через противофазу
 * противоположного канала), Side остатка усиливается. Глубина: остаток усиливается и вместе с первичным
 * уходит в посыл на комнату ([SceneRoom]). Ковариация двусторонняя: прошлое (80 мс) плюс опережающие кадры.
 */
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
    // Мгновенная ковариация кадра по бинам: |L|², |R|², L·conj(R).
    private val ringA = DoubleArray(ringSize * bins)
    private val ringB = DoubleArray(ringSize * bins)
    private val ringCr = DoubleArray(ringSize * bins)
    private val ringCi = DoubleArray(ringSize * bins)
    private val attacks = ByteArray(ringSize * BAND_COUNT)
    private val pastA = DoubleArray(bins)
    private val pastB = DoubleArray(bins)
    private val pastCr = DoubleArray(bins)
    private val pastCi = DoubleArray(bins)
    private var pastReady = false
    private val preA = DoubleArray(bins + 1)
    private val preB = DoubleArray(bins + 1)
    private val preCr = DoubleArray(bins + 1)
    private val preCi = DoubleArray(bins + 1)
    private val smoothLo = IntArray(bins)
    private val smoothHi = IntArray(bins)
    private val binBand = IntArray(bins)
    // Частотные кривые, посчитанные один раз.
    private val sigma = DoubleArray(bins)
    private val omega = DoubleArray(bins)
    private val ambientLiftMid = DoubleArray(bins)
    private val ambientLiftSide = DoubleArray(bins)
    private val sendWeight = DoubleArray(bins)
    private val fluxHistory = DoubleArray(FLUX_WINDOW_FRAMES * BAND_COUNT)
    // Те же значения потока по возрастанию (по полосам): медиана и MAD без сортировки на каждом кадре.
    private val fluxSorted = DoubleArray(FLUX_WINDOW_FRAMES * BAND_COUNT)
    private val bandEnergy = DoubleArray(BAND_COUNT)
    private val bandFluxMagnitude = DoubleArray(BAND_COUNT)
    private val previousBandMagnitude = DoubleArray(BAND_COUNT)
    private val releaseState = DoubleArray(BAND_COUNT) { 1.0 }
    private val fluxCursor = IntArray(BAND_COUNT)
    private val fluxCount = IntArray(BAND_COUNT)
    private val attackSuppressed = BooleanArray(BAND_COUNT)
    private var frameIndex = 0L
    private val powerSmoothing = exp(-(hopSize.toDouble() / sampleRate) / 0.080)
    private val attackRelease = 1.0 - exp(-(hopSize.toDouble() / sampleRate) / 0.150)
    private val width = config.width.coerceIn(0.0, 1.0)
    private val depth = config.depth.coerceIn(0.0, 1.0)

    init {
        require(sampleRate > 0 && fftSize >= 2 && fftSize.countOneBits() == 1 && hopSize in 1..fftSize)
        val halfOctave = 2.0.pow(1.0 / 12.0)
        val omegaScale = width * config.span * OMEGA_MAX
        for (bin in 0 until bins) {
            val hz = bin.toDouble() * sampleRate / fftSize
            if (hz <= 0.0) {
                smoothLo[bin] = 0; smoothHi[bin] = 1; binBand[bin] = 0
            } else {
                val lo = max(1, kotlin.math.ceil((hz / halfOctave) * fftSize / sampleRate).toInt())
                val hi = kotlin.math.floor((hz * halfOctave) * fftSize / sampleRate).toInt() + 1
                // Не уже ±1 бина: на НЧ 1/6 октавы меньше ширины бина.
                smoothLo[bin] = min(lo, bin - 1).coerceAtLeast(0)
                smoothHi[bin] = max(hi, bin + 2).coerceAtMost(bins)
                binBand[bin] = (3.0 * log2(hz / BAND_START_HZ) + 0.5).toInt().coerceIn(0, BAND_COUNT - 1)
            }
            val f = raisedCosineLog(hz, 120.0, 250.0)
            sigma[bin] = 1.0 + width * (SIGMA_MAX - 1.0) * f
            val shape = f * (1.0 - (1.0 - OMEGA_HF / OMEGA_MAX) * raisedCosineLog(hz, 1_200.0, 5_000.0))
            omega[bin] = min(OMEGA_CAP, omegaScale * shape)
            // Глубина поднимает весь остаток; ширина — только его Side (иначе диффузное поле не становится шире).
            ambientLiftMid[bin] = 10.0.pow(AMBIENT_DEPTH_DB * depth * f / 20.0) - 1.0
            ambientLiftSide[bin] = 10.0.pow((AMBIENT_WIDTH_DB * width + AMBIENT_DEPTH_DB * depth) * f / 20.0) - 1.0
            sendWeight[bin] = raisedCosineLog(hz, 150.0, 350.0) * (1.0 - 0.5 * raisedCosineLog(hz, 6_000.0, 12_000.0))
        }
    }

    /** Основной выход без посыла в комнату. */
    fun process(realL: DoubleArray, imagL: DoubleArray, realR: DoubleArray, imagR: DoubleArray): Boolean =
        process(realL, imagL, realR, imagR, null, null, null, null)

    /**
     * Копирует кадр в кольцо опережения и, когда оно заполнено, отдаёт спектр отстающего кадра.
     * Если переданы массивы посыла и глубина > 0, в них пишется спектр посыла в комнату (иначе работа над ним пропускается).
     */
    fun process(
        realL: DoubleArray, imagL: DoubleArray, realR: DoubleArray, imagR: DoubleArray,
        sendRealL: DoubleArray?, sendImagL: DoubleArray?, sendRealR: DoubleArray?, sendImagR: DoubleArray?,
    ): Boolean {
        require(realL.size == fftSize && imagL.size == fftSize && realR.size == fftSize && imagR.size == fftSize)
        val inputSlot = (frameIndex % ringSize).toInt()
        val spectrumBase = inputSlot * fftSize
        for (bin in 0 until fftSize) {
            ringLReal[spectrumBase + bin] = realL[bin]; ringLImag[spectrumBase + bin] = imagL[bin]
            ringRReal[spectrumBase + bin] = realR[bin]; ringRImag[spectrumBase + bin] = imagR[bin]
        }
        analyze(inputSlot, spectrumBase, inputSlot * bins)
        frameIndex++
        if (frameIndex <= lookaheadHops) return false

        val outputSlot = ((frameIndex - 1L - lookaheadHops) % ringSize).toInt()
        if (config.enabled) {
            val withSend = depth > 0.0 && sendRealL != null && sendImagL != null && sendRealR != null && sendImagR != null
            applyScene(outputSlot, realL, imagL, realR, imagR,
                if (withSend) sendRealL else null, sendImagL, sendRealR, sendImagR, withSend)
        } else {
            val base = outputSlot * fftSize
            for (bin in 0 until fftSize) {
                realL[bin] = ringLReal[base + bin]; imagL[bin] = ringLImag[base + bin]
                realR[bin] = ringRReal[base + bin]; imagR[bin] = ringRImag[base + bin]
            }
        }
        return true
    }

    fun reset() {
        ringLReal.fill(0.0); ringLImag.fill(0.0); ringRReal.fill(0.0); ringRImag.fill(0.0)
        ringA.fill(0.0); ringB.fill(0.0); ringCr.fill(0.0); ringCi.fill(0.0)
        attacks.fill(0); pastA.fill(0.0); pastB.fill(0.0); pastCr.fill(0.0); pastCi.fill(0.0)
        pastReady = false
        fluxHistory.fill(0.0); fluxSorted.fill(0.0)
        bandEnergy.fill(0.0); bandFluxMagnitude.fill(0.0); previousBandMagnitude.fill(0.0); releaseState.fill(1.0)
        fluxCursor.fill(0); fluxCount.fill(0)
        frameIndex = 0
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
            ringA[binBase + bin] = pL; ringB[binBase + bin] = pR
            ringCr[binBase + bin] = crossR; ringCi[binBase + bin] = crossI
            totalLeft += pL; totalRight += pR; totalCross += crossR
            totalMid += (pL + pR + 2.0 * crossR) * 0.25
            totalSide += (pL + pR - 2.0 * crossR) * 0.25
            val band = binBand[bin]
            bandEnergy[band] += (pL + pR) * 0.5
            bandFluxMagnitude[band] += sqrt((pL + pR) * 0.5)
        }
        correlation = (totalCross / sqrt(max(1e-30, totalLeft * totalRight))).coerceIn(-1.0, 1.0)
        sideMidDb = 10.0 * log10(max(1e-30, totalSide) / max(1e-30, totalMid))

        for (band in 0 until BAND_COUNT) {
            val magnitude = bandFluxMagnitude[band] / max(1, bandBinCount(band))
            val previous = previousBandMagnitude[band]
            val flux = if (previous <= 1e-12) 0.0 else max(0.0, magnitude - previous) / previous
            previousBandMagnitude[band] = magnitude
            val fluxPosition = fluxCursor[band]
            val full = fluxCount[band] == FLUX_WINDOW_FRAMES
            updateSorted(band, fluxCount[band], full, fluxHistory[fluxPosition * BAND_COUNT + band], flux)
            fluxHistory[fluxPosition * BAND_COUNT + band] = flux
            fluxCursor[band] = (fluxPosition + 1) % FLUX_WINDOW_FRAMES
            fluxCount[band] = min(FLUX_WINDOW_FRAMES, fluxCount[band] + 1)
            attacks[slot * BAND_COUNT + band] = if (isAttack(band, fluxCount[band], flux)) 1 else 0
        }
    }

    private fun applyScene(
        outSlot: Int,
        realL: DoubleArray, imagL: DoubleArray, realR: DoubleArray, imagR: DoubleArray,
        sendRealL: DoubleArray?, sendImagL: DoubleArray?, sendRealR: DoubleArray?, sendImagR: DoubleArray?,
        withSend: Boolean,
    ) {
        val spectrumBase = outSlot * fftSize
        attackSuppressed.fill(false)
        for (ahead in 0..lookaheadHops) {
            val aheadSlot = ((frameIndex - 1L - ahead).coerceAtLeast(0) % ringSize).toInt()
            for (band in 0 until BAND_COUNT) if (attacks[aheadSlot * BAND_COUNT + band].toInt() != 0) attackSuppressed[band] = true
        }
        for (band in 0 until BAND_COUNT) {
            releaseState[band] = if (attackSuppressed[band]) 0.0
                else releaseState[band] + (1.0 - releaseState[band]) * attackRelease
        }

        // Двусторонняя ковариация: половина — рекурсивное прошлое (до этого кадра включительно), половина — среднее
        // по кадрам с этого и до самого нового в кольце. Новый источник виден с первых мсек.
        val lambda = powerSmoothing
        val invFrames = 1.0 / (lookaheadHops + 1)
        val ownBase = outSlot * bins
        for (bin in 0 until bins) {
            var fa = 0.0; var fb = 0.0; var fcr = 0.0; var fci = 0.0
            for (k in 0..lookaheadHops) {
                val index = ((outSlot + k) % ringSize) * bins + bin
                fa += ringA[index]; fb += ringB[index]; fcr += ringCr[index]; fci += ringCi[index]
            }
            val ia = ringA[ownBase + bin]; val ib = ringB[ownBase + bin]
            val icr = ringCr[ownBase + bin]; val ici = ringCi[ownBase + bin]
            if (!pastReady) {
                pastA[bin] = ia; pastB[bin] = ib; pastCr[bin] = icr; pastCi[bin] = ici
            } else {
                pastA[bin] = lambda * pastA[bin] + (1.0 - lambda) * ia
                pastB[bin] = lambda * pastB[bin] + (1.0 - lambda) * ib
                pastCr[bin] = lambda * pastCr[bin] + (1.0 - lambda) * icr
                pastCi[bin] = lambda * pastCi[bin] + (1.0 - lambda) * ici
            }
            preA[bin + 1] = preA[bin] + 0.5 * pastA[bin] + 0.5 * fa * invFrames
            preB[bin + 1] = preB[bin] + 0.5 * pastB[bin] + 0.5 * fb * invFrames
            preCr[bin + 1] = preCr[bin] + 0.5 * pastCr[bin] + 0.5 * fcr * invFrames
            preCi[bin + 1] = preCi[bin] + 0.5 * pastCi[bin] + 0.5 * fci * invFrames
        }
        pastReady = true

        var totalLeft = 0.0; var totalRight = 0.0; var totalCross = 0.0
        var totalMid = 0.0; var totalSide = 0.0
        val stretch = width > 0.0
        for (bin in 0 until bins) {
            val lo = smoothLo[bin]; val hi = smoothHi[bin]
            val inv = 1.0 / (hi - lo)
            val a = (preA[hi] - preA[lo]) * inv
            val b = (preB[hi] - preB[lo]) * inv
            val cr = (preCr[hi] - preCr[lo]) * inv
            val ci = (preCi[hi] - preCi[lo]) * inv

            val specIndex = spectrumBase + bin
            val lr = ringLReal[specIndex]; val li = ringLImag[specIndex]
            val rr = ringRReal[specIndex]; val ri = ringRImag[specIndex]
            var yLr = lr; var yLi = li; var yRr = rr; var yRi = ri
            var sLr = 0.0; var sLi = 0.0; var sRr = 0.0; var sRi = 0.0
            val tot = 0.5 * (a + b)
            if (tot > POWER_FLOOR) {
                val d = 0.5 * (a - b)
                val r = sqrt(cr * cr + ci * ci)
                val rad = sqrt(d * d + r * r)
                val rho = (rad / tot).coerceIn(0.0, 1.0)
                // Главная ось: v = (cosψ, sinψ·u), u = conj(c)/|c| — калибровка, где vL вещественна.
                val c2 = if (rad > 1e-30) d / rad else 0.0
                val cosP = sqrt(0.5 * (1.0 + c2)); val sinP = sqrt(0.5 * (1.0 - c2))
                val ur: Double; val ui: Double
                if (r > 1e-30) { ur = cr / r; ui = -ci / r } else { ur = 1.0; ui = 0.0 }
                // s = vᴴx
                val cuRr = ur * rr + ui * ri; val cuRi = ur * ri - ui * rr
                val sr = cosP * lr + sinP * cuRr
                val si = cosP * li + sinP * cuRi
                // Первичная часть и остаток (точный).
                val pLr = cosP * sr; val pLi = cosP * si
                val pRr = sinP * (ur * sr - ui * si); val pRi = sinP * (ur * si + ui * sr)
                val aLr = lr - pLr; val aLi = li - pLi
                val aRr = rr - pRr; val aRi = ri - pRi

                var dLr = 0.0; var dLi = 0.0; var dRr = 0.0; var dRi = 0.0
                val kappa = confidence(rho)
                if (stretch) {
                    if (kappa > 0.0) {
                        val delta = atan2(r, d) * 0.5 - QUARTER_PI
                        val dsig = delta * sigma[bin]
                        val adsig = abs(dsig)
                        val dNew = if (adsig <= QUARTER_PI) dsig else {
                            val om = omega[bin]
                            val mag = if (om > 1e-9) QUARTER_PI + om * (1.0 - exp(-(adsig - QUARTER_PI) / om)) else QUARTER_PI
                            if (dsig < 0.0) -mag else mag
                        }
                        val shift = kappa * (dNew - delta)
                        if (abs(shift) > 1e-12) {
                            val psiNew = QUARTER_PI + delta + shift
                            val dCos = cos(psiNew) - cosP
                            val dSin = sin(psiNew) - sinP
                            // Фазоры: когда одна компонента ≈ 0, её фаза следует фазе другой.
                            var elr = cosP + ETA * ur; var eli = ETA * ui
                            var n = sqrt(elr * elr + eli * eli); elr /= n; eli /= n
                            var err = sinP * ur + ETA; var eri = sinP * ui
                            n = sqrt(err * err + eri * eri); err /= n; eri /= n
                            dLr = dCos * (elr * sr - eli * si); dLi = dCos * (elr * si + eli * sr)
                            dRr = dSin * (err * sr - eri * si); dRi = dSin * (err * si + eri * sr)
                        }
                    }
                }
                val release = releaseState[binBand[bin]]
                val liftMid = ambientLiftMid[bin] * release; val liftSide = ambientLiftSide[bin] * release
                val keep = 0.5 * (liftMid + liftSide); val cross = 0.5 * (liftMid - liftSide)
                // Остаток плюс доля «первичного», которой нельзя доверять (диффузное поле: ось случайна).
                val diffuse = 1.0 - kappa
                val mLr = aLr + diffuse * pLr; val mLi = aLi + diffuse * pLi
                val mRr = aRr + diffuse * pRr; val mRi = aRi + diffuse * pRi
                yLr = lr + dLr + keep * mLr + cross * mRr; yLi = li + dLi + keep * mLi + cross * mRi
                yRr = rr + dRr + keep * mRr + cross * mLr; yRi = ri + dRi + keep * mRi + cross * mLi
                if (withSend) {
                    val w = sendWeight[bin]
                    sLr = w * (0.5 * (pLr + dLr) + aLr); sLi = w * (0.5 * (pLi + dLi) + aLi)
                    sRr = w * (0.5 * (pRr + dRr) + aRr); sRi = w * (0.5 * (pRi + dRi) + aRi)
                }
            } else if (withSend) {
                val w = sendWeight[bin] * 0.5
                sLr = w * lr; sLi = w * li; sRr = w * rr; sRi = w * ri
            }
            val pl = yLr * yLr + yLi * yLi; val pr = yRr * yRr + yRi * yRi
            val crossPower = yLr * yRr + yLi * yRi
            totalLeft += pl; totalRight += pr; totalCross += crossPower
            totalMid += (pl + pr + 2.0 * crossPower) * 0.25
            totalSide += (pl + pr - 2.0 * crossPower) * 0.25
            realL[bin] = yLr; imagL[bin] = yLi; realR[bin] = yRr; imagR[bin] = yRi
            val mirror = fftSize - bin
            val hasMirror = mirror != bin && mirror < fftSize
            if (hasMirror) {
                realL[mirror] = yLr; imagL[mirror] = -yLi
                realR[mirror] = yRr; imagR[mirror] = -yRi
            }
            if (withSend) {
                sendRealL!![bin] = sLr; sendImagL!![bin] = sLi; sendRealR!![bin] = sRr; sendImagR!![bin] = sRi
                if (hasMirror) {
                    sendRealL[mirror] = sLr; sendImagL[mirror] = -sLi
                    sendRealR[mirror] = sRr; sendImagR[mirror] = -sRi
                }
            }
        }
        correlation = (totalCross / sqrt(max(1e-30, totalLeft * totalRight))).coerceIn(-1.0, 1.0)
        sideMidDb = 10.0 * log10(max(1e-30, totalSide) / max(1e-30, totalMid))
    }

    /** Доверие к главной оси: при почти равных собственных числах направление случайно — перенос выключается плавно. */
    private fun confidence(rho: Double): Double {
        val t = ((rho - RHO_LOW) / (RHO_HIGH - RHO_LOW)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun raisedCosineLog(hz: Double, from: Double, to: Double): Double {
        if (hz <= from) return 0.0
        if (hz >= to) return 1.0
        return 0.5 - 0.5 * cos(PI * ln(hz / from) / ln(to / from))
    }

    private fun updateSorted(band: Int, countBefore: Int, removeOld: Boolean, old: Double, value: Double) {
        val base = band * FLUX_WINDOW_FRAMES
        var n = countBefore
        if (removeOld) {
            val index = java.util.Arrays.binarySearch(fluxSorted, base, base + n, old) - base
            System.arraycopy(fluxSorted, base + index + 1, fluxSorted, base + index, n - index - 1)
            n--
        }
        var pos = java.util.Arrays.binarySearch(fluxSorted, base, base + n, value)
        pos = if (pos >= 0) pos - base else -pos - 1 - base
        System.arraycopy(fluxSorted, base + pos, fluxSorted, base + pos + 1, n - pos)
        fluxSorted[base + pos] = value
    }

    /** Выброс потока: больше медианы + 4·MAD по окну (окно включает текущее значение). */
    private fun isAttack(band: Int, count: Int, currentFlux: Double): Boolean {
        if (count < 8) return false
        val base = band * FLUX_WINDOW_FRAMES
        val median = if (count and 1 == 1) fluxSorted[base + count / 2]
            else (fluxSorted[base + count / 2 - 1] + fluxSorted[base + count / 2]) * 0.5
        // Отклонения от медианы идут от центра наружу: сливаем левую и правую ветви, берём середину по рангу.
        var li = (count - 1) / 2
        var ri = li + 1
        val rankHigh = count / 2
        val rankLow = (count - 1) / 2
        var low = 0.0; var high = 0.0
        for (rank in 0..rankHigh) {
            val dl = if (li >= 0) median - fluxSorted[base + li] else Double.POSITIVE_INFINITY
            val dr = if (ri < count) fluxSorted[base + ri] - median else Double.POSITIVE_INFINITY
            val d = if (dl <= dr) { li--; dl } else { ri++; dr }
            if (rank == rankLow) low = d
            if (rank == rankHigh) high = d
        }
        // Пол ATTACK_MIN_FLUX: на шумоподобном сигнале медиана и MAD равны нулю, и «атакой» был каждый второй кадр.
        return currentFlux > max(ATTACK_MIN_FLUX, median + 4.0 * (low + high) * 0.5)
    }

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
        private const val FLUX_WINDOW_FRAMES = 188
        private const val ATTACK_MIN_FLUX = 0.25
        private const val POWER_SCALE = 1.0 / (FFT_SIZE.toDouble() * FFT_SIZE)
        private const val POWER_FLOOR = 1e-20
        private const val QUARTER_PI = PI / 4.0
        /** Максимальное растяжение панорамы при ширине 1. */
        private const val SIGMA_MAX = 2.0
        /** Запас выхода за базу колонок, рад: 250 Гц…1,2 кГц и на ВЧ (противофаза на ВЧ даёт только фазовость). */
        private const val OMEGA_MAX = 0.38
        private const val OMEGA_HF = 0.15
        private const val OMEGA_CAP = 0.6
        private const val AMBIENT_WIDTH_DB = 4.5
        private const val AMBIENT_DEPTH_DB = 2.0
        /** Сглаживание фазора почти нулевой компоненты оси. */
        private const val ETA = 0.02
        private const val RHO_LOW = 0.15
        private const val RHO_HIGH = 0.5
    }
}
