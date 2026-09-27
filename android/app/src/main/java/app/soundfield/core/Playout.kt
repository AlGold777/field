package app.soundfield.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * Временная шкала воспроизведения (эпоха). Кадр F звучит в момент
 * anchorNs + (F − anchorFrame) / SR (часы Server). Любые play/pause/seek/смена источника создают
 * новую эпоху с большим номером — старые пакеты по номеру эпохи распознаются как устаревшие.
 */
class Timeline(
    val epoch: Int,
    val playing: Boolean,
    val anchorFrame: Long,
    val anchorNs: Long,
    /**
     * Запланированная остановка в той же эпохе: кадры ≥ stopFrame не звучат (с коротким затуханием).
     * Пауза назначается на «сейчас + 150 мс» — оба телефона замолкают на одном и том же кадре, хотя
     * команда до Point идёт по сети. −1 — остановки нет.
     */
    val stopFrame: Long = -1,
) {
    /** Играет и не останавливается. */
    val running: Boolean get() = playing && stopFrame < 0

    fun frameAt(serverNs: Long): Double =
        anchorFrame + (serverNs - anchorNs).toDouble() * SF.SAMPLE_RATE / 1e9

    fun timeOf(frame: Long): Long =
        anchorNs + ((frame - anchorFrame).toDouble() * 1e9 / SF.SAMPLE_RATE).toLong()

    /** Позиция для UI (не раньше якоря). */
    fun positionFrame(serverNs: Long): Long {
        if (!playing) return anchorFrame
        val f = maxOf(anchorFrame, frameAt(serverNs).toLong())
        return if (stopFrame >= 0) minOf(f, stopFrame) else f
    }

    /**
     * Огибающая против щелчков: плавное нарастание 5 мс после якоря (старт, seek, смена трека)
     * и затухание 5 мс перед stopFrame (пауза). 0 — кадр не должен звучать.
     */
    fun envelope(frame: Long): Float {
        if (frame < anchorFrame) return 0f
        var g = 1f
        val sinceStart = frame - anchorFrame
        if (sinceStart < FADE_FRAMES) g = (sinceStart + 1).toFloat() / FADE_FRAMES
        if (stopFrame >= 0) {
            val left = stopFrame - frame
            if (left <= 0) return 0f
            if (left < FADE_FRAMES) g = minOf(g, left.toFloat() / FADE_FRAMES)
        }
        return g
    }

    companion object {
        val NONE = Timeline(-1, false, 0, 0)
        const val FADE_FRAMES = 240   // 5 мс
    }
}

/**
 * Держит фактическую позицию чтения аудиовыхода рядом с «идеальной» (вычисленной по часам).
 * Ошибка сглаживается; при |ошибке| > порога — пропуск или повтор одного сэмпла за колбэк
 * (≤ 1/240 → незаметно на слух). При ошибке > 20 мс — жёсткий прыжок (старт, seek, потеря связи).
 * Вызывается только из аудиопотока.
 */
class PlayoutCorrector {
    private var readPos = Long.MIN_VALUE
    private var filt = 0.0
    var start = 0L; private set
    var adjust = 0; private set

    @Volatile var syncErrorMs = 0.0; private set
    @Volatile var hardJumps = 0; private set
    @Volatile var slewEvents = 0L; private set

    fun reset() { readPos = Long.MIN_VALUE; filt = 0.0 }

    /** ideal — дробный индекс кадра, который должен прозвучать первым в этом буфере. */
    fun plan(ideal: Double, n: Int) {
        if (readPos == Long.MIN_VALUE || abs(ideal - readPos) > SF.HARD_RESYNC_FRAMES) {
            if (readPos != Long.MIN_VALUE) hardJumps++
            readPos = floor(ideal + 0.5).toLong()
            filt = 0.0
        }
        val err = ideal - readPos
        filt += 0.1 * (err - filt)
        adjust = when {
            filt > SF.SLEW_THRESHOLD_FRAMES -> 1     // отстаём — пропустить сэмпл
            filt < -SF.SLEW_THRESHOLD_FRAMES -> -1   // спешим — повторить сэмпл
            else -> 0
        }
        if (adjust != 0) slewEvents++
        start = readPos
        readPos += n + adjust
        filt -= adjust
        syncErrorMs = filt * 1000.0 / SF.SAMPLE_RATE
    }

    /** Индекс кадра для i-го сэмпла буфера с учётом пропуска/повтора в середине. */
    fun frame(i: Int, n: Int): Long {
        val half = n / 2
        return when {
            adjust > 0 && i >= half -> start + i + 1
            adjust < 0 && i > half -> start + i - 1
            else -> start + i
        }
    }
}

/** Плавное изменение громкости внутри колбэка (без щелчков). */
class GainRamp {
    private var current = 1f
    fun apply(out: FloatArray, n: Int, target: Float) {
        if (n <= 0) return
        val step = (target - current) / n
        var g = current
        for (i in 0 until n) { g += step; out[i] *= g }
        current = target
    }
}

/** Общий интерфейс: заполнить буфер, первый сэмпл которого прозвучит в presentLocalNs. */
interface RenderSource {
    fun render(out: FloatArray, n: Int, presentLocalNs: Long)
}
