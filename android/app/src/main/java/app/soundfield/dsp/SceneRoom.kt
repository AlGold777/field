package app.soundfield.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Комната для ползунка «Глубина»: ранние отражения (разреженные отводы, по два набора на выход, боковые и
 * декоррелированные) плюс поле позднего хвоста (FDN 8 линий, Householder, RT60 = 0,25 + 0,40·depth с, затухание ВЧ).
 * Вход — посыл из STFT-стадии (L и R), выход — [wetL], [wetR]. Без аллокаций на сэмпл.
 *
 * Калибровка (центральный розовый шум L=R, ширина 0, компенсация громкости выключена, весь тракт SpatialSource):
 * энергия (выход − сухой) к сухому: depth 1.0 = −8.1 дБ (цель −8±1.5), 0.75 = −10.9, 0.5 = −14.9 (цель −15±2),
 * 0.25 = −21.8. Ранние : поздние на «голой» комнате: 0.0 дБ (depth 1), −0.8 дБ (depth 0.5).
 * RT60 по Шрёдеру (T20×3) при depth 1 = 0.61 с, корреляция wetL/wetR ≈ 0.01.
 */
class SceneRoom(depth: Double, private val sampleRate: Int = 48_000) {
    private val depth = depth.coerceIn(0.0, 1.0)
    var wetL = 0.0
        private set
    var wetR = 0.0
        private set
    /** Раздельные вклады — для измерения и диагностики. */
    var earlyL = 0.0
        private set
    var earlyR = 0.0
        private set
    var lateL = 0.0
        private set
    var lateR = 0.0
        private set

    private val scale = sampleRate / 48_000.0
    private val bufferSize = 4_096
    private val mask = bufferSize - 1
    private val sendBufferL = DoubleArray(bufferSize)
    private val sendBufferR = DoubleArray(bufferSize)
    private var writePos = 0

    // Ранние отражения: для каждого выхода 11 отводов (6 с «своей» стороны, 5 с перекрёстной).
    private val erCount = 11
    private val erDelayL = IntArray(erCount)
    private val erDelayR = IntArray(erCount)
    private val erGainL = DoubleArray(erCount)
    private val erGainR = DoubleArray(erCount)
    private val erSameSide = BooleanArray(erCount) { it % 2 == 0 && it != 10 || it == 5 }

    // Поздний хвост.
    private val lineCount = 8
    private val lineLength = IntArray(lineCount) { (LINE_LENGTHS[it] * scale).toInt() }
    private val lines = Array(lineCount) { DoubleArray(lineLength[it]) }
    private val linePos = IntArray(lineCount)
    private val lineGain = DoubleArray(lineCount)
    private val dampPole = DoubleArray(lineCount)
    private val dampState = DoubleArray(lineCount)
    private val lineOut = DoubleArray(lineCount)
    private val lineFiltered = DoubleArray(lineCount)
    private val lateDelay = (0.014 * sampleRate).toInt()
    private val earlyWet: Double
    private val lateWet: Double

    init {
        val rt60 = RT60_BASE + RT60_SLOPE * this.depth
        for (i in 0 until lineCount) {
            val g = 10.0.pow(-3.0 * lineLength[i] / (sampleRate * rt60))
            lineGain[i] = g
            // Однополюсный ФНЧ с единичным усилением на постоянном токе: на 8 кГц усиление петли = g² (RT60 вдвое короче).
            val gg = g * g
            val cw = cos(2.0 * PI * 8_000.0 / sampleRate)
            val b = 1.0 - gg * cw
            val disc = (b * b - (1.0 - gg) * (1.0 - gg)).coerceAtLeast(0.0)
            dampPole[i] = if (gg >= 1.0) 0.0 else ((b - sqrt(disc)) / (1.0 - gg)).coerceIn(0.0, 0.99)
        }
        fillEarlyTable(erDelayL, erGainL, phase = 0.0, seed = 0x2F6B1)
        fillEarlyTable(erDelayR, erGainR, phase = 0.5, seed = 0x6D3A7)
        val level = depth.pow(DEPTH_EXPONENT) * ROOM_GAIN
        earlyWet = level * EARLY_SHARE
        // Энергия позднего поля при единичном входе растёт пропорционально RT60 — нормируем.
        lateWet = level * LATE_SHARE * sqrt(RT60_REFERENCE / rt60)
    }

    fun reset() {
        sendBufferL.fill(0.0); sendBufferR.fill(0.0); writePos = 0
        for (line in lines) line.fill(0.0)
        linePos.fill(0); dampState.fill(0.0)
        wetL = 0.0; wetR = 0.0; earlyL = 0.0; earlyR = 0.0; lateL = 0.0; lateR = 0.0
    }

    fun process(sendL: Float, sendR: Float) {
        writePos = (writePos + 1) and mask
        sendBufferL[writePos] = sendL.toDouble(); sendBufferR[writePos] = sendR.toDouble()

        var eL = 0.0; var eR = 0.0
        for (k in 0 until erCount) {
            val same = erSameSide[k]
            val indexL = (writePos - erDelayL[k]) and mask
            val indexR = (writePos - erDelayR[k]) and mask
            eL += erGainL[k] * (if (same) sendBufferL[indexL] else sendBufferR[indexL])
            eR += erGainR[k] * (if (same) sendBufferR[indexR] else sendBufferL[indexR])
        }

        val inL = sendBufferL[(writePos - lateDelay) and mask]
        val inR = sendBufferR[(writePos - lateDelay) and mask]
        var sum = 0.0
        var outL = 0.0; var outR = 0.0
        for (i in 0 until lineCount) {
            val y = lines[i][linePos[i]]
            lineOut[i] = y
            // Затухание и ФНЧ в петле; сдвиг ниже порога денормалей.
            var state = (1.0 - dampPole[i]) * lineGain[i] * y + dampPole[i] * dampState[i]
            if (state < 1e-20 && state > -1e-20) state = 0.0
            dampState[i] = state
            lineFiltered[i] = state
            sum += state
            outL += OUT_SIGN_L[i] * y
            outR += OUT_SIGN_R[i] * y
        }
        val reflect = sum * (2.0 / lineCount)
        for (i in 0 until lineCount) {
            val input = if (i % 2 == 0) inL else inR
            lines[i][linePos[i]] = IN_SIGN[i] * input * LINE_INPUT_GAIN + lineFiltered[i] - reflect
            linePos[i]++
            if (linePos[i] >= lineLength[i]) linePos[i] = 0
        }
        val norm = 1.0 / sqrt(lineCount.toDouble())
        earlyL = eL * earlyWet; earlyR = eR * earlyWet
        lateL = outL * norm * lateWet; lateR = outR * norm * lateWet
        wetL = earlyL + lateL; wetR = earlyR + lateR
    }

    /** Детерминированная таблица: сетка 6…45 мс с небольшим разбросом; L и R сдвинуты на полшага сетки (≥ 0,6 мс). */
    private fun fillEarlyTable(delays: IntArray, gains: DoubleArray, phase: Double, seed: Int) {
        var state = seed
        fun next(): Double {
            state = state * 1_103_515_245 + 12_345
            return ((state ushr 8) and 0xFFFF) / 65_536.0
        }
        val step = (ER_MAX_MS - ER_MIN_MS) / erCount
        var energy = 0.0
        for (k in 0 until erCount) {
            val ms = ER_MIN_MS + step * (k + 0.25 + phase * 0.5) + (next() - 0.5) * 2.0 * ER_JITTER_MS
            delays[k] = (ms * 1e-3 * sampleRate).toInt().coerceIn(1, bufferSize - 1)
            val sign = if (next() < 0.5) -1.0 else 1.0
            gains[k] = sign * exp(-ms / ER_DECAY_MS)
            energy += gains[k] * gains[k]
        }
        val norm = 1.0 / sqrt(energy)
        for (k in 0 until erCount) gains[k] *= norm
    }

    companion object {
        private val LINE_LENGTHS = intArrayOf(1123, 1291, 1447, 1619, 1811, 2029, 2269, 2503)
        private val IN_SIGN = doubleArrayOf(1.0, 1.0, -1.0, 1.0, 1.0, -1.0, -1.0, 1.0)
        private val OUT_SIGN_L = doubleArrayOf(1.0, 1.0, 1.0, 1.0, -1.0, -1.0, -1.0, -1.0)
        private val OUT_SIGN_R = doubleArrayOf(1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 1.0, -1.0)
        private const val RT60_BASE = 0.25
        private const val RT60_SLOPE = 0.40
        private const val RT60_REFERENCE = 0.65
        private const val DEPTH_EXPONENT = 1.2
        private const val ER_MIN_MS = 6.0
        private const val ER_MAX_MS = 45.0
        private const val ER_JITTER_MS = 0.45
        private const val ER_DECAY_MS = 40.0
        private const val LINE_INPUT_GAIN = 0.5
        private const val ROOM_GAIN = 1.0
        private const val EARLY_SHARE = 0.844
        private const val LATE_SHARE = 1.29
    }
}
