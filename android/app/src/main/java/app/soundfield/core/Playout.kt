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
 * Fractional-frame PI playout correction. The audio callback reads through a 32-tap sinc
 * interpolator, avoiding the clicks caused by dropping or duplicating one whole sample.
 * Called only from the audio thread.
 */
class PlayoutCorrector {
    private var readPos = Double.NaN
    private var filt = 0.0
    private var integral = 0.0
    private var start = 0.0
    private var step = 1.0

    @Volatile var syncErrorUs = 0.0; private set
    @Volatile var hardJumps = 0; private set
    @Volatile var slewEvents = 0L; private set

    fun reset() { readPos = Double.NaN; filt = 0.0; integral = 0.0; step = 1.0 }

    /** ideal — дробный индекс кадра, который должен прозвучать первым в этом буфере. */
    fun plan(ideal: Double, n: Int) {
        if (readPos.isNaN() || abs(ideal - readPos) > SF.HARD_RESYNC_FRAMES) {
            if (!readPos.isNaN()) hardJumps++
            readPos = ideal
            filt = 0.0
            integral = 0.0
        }
        val err = ideal - readPos
        filt += 0.1 * (err - filt)
        val dt = n.coerceAtLeast(1).toDouble() / SF.SAMPLE_RATE
        val errorSeconds = filt / SF.SAMPLE_RATE
        integral = (integral + errorSeconds * dt).coerceIn(-0.05, 0.05)
        // Critically damped 3-second loop; 100 us phase error initially asks for about 67 ppm.
        val correction = (KP_PER_SECOND * errorSeconds + KI_PER_SECOND2 * integral)
            .coerceIn(-MAX_RATE_CORRECTION, MAX_RATE_CORRECTION)
        step = 1.0 + correction
        if (abs(correction) > 1e-6) slewEvents++
        start = readPos
        readPos += n * step
        syncErrorUs = filt * 1e6 / SF.SAMPLE_RATE
    }

    /** Fractional index for one output frame in the current callback. */
    fun position(i: Int): Double = start + i * step

    /** Compatibility helper for position-only UI/telemetry call sites. */
    fun frame(i: Int, @Suppress("UNUSED_PARAMETER") n: Int): Long = floor(position(i) + 0.5).toLong()

    companion object {
        private const val KP_PER_SECOND = 2.0 / 3.0
        private const val KI_PER_SECOND2 = 1.0 / 9.0
        private const val MAX_RATE_CORRECTION = 200e-6
    }
}

/** Плавное изменение громкости внутри колбэка (без щелчков). */
class GainRamp {
    private var current = 1f
    fun apply(out: FloatArray, n: Int, target: Float) {
        if (n <= 0) return
        val k = (1.0 - kotlin.math.exp(-1.0 / (0.015 * SF.SAMPLE_RATE))).toFloat()
        var g = current
        for (i in 0 until n) { g += (target - g) * k; out[i] *= g }
        current = g
    }
}

/** Общий интерфейс: заполнить буфер, первый сэмпл которого прозвучит в presentLocalNs. */
interface RenderSource {
    fun render(out: FloatArray, n: Int, presentLocalNs: Long)
}
