package app.soundfield.core

import app.soundfield.dsp.WindowedSinc
import kotlin.math.floor

/** Откуда Server берёт музыку: своя очередь файлов или живой поток другого приложения. */
enum class InputSource(val wire: String) {
    PLAYER("player"), SPOTIFY("spotify"), YOUTUBE("youtube");

    val live: Boolean get() = this != PLAYER

    companion object { fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: PLAYER }
}

/**
 * Живой источник: поток без известной длины и без перемотки. PCM (48 кГц, стерео) дописывается в кольцо по
 * абсолютному номеру кадра; воспроизведение идёт позади головы на запас [readyFrames] − позиция.
 * Пишущих потоков может быть несколько (приёмник и движок), поэтому запись синхронизирована; чтение без блокировок.
 */
class LiveSource(title: String, artist: String, capacitySeconds: Int = 30) : AudioSource {
    override val mode = SourceMode.MUSIC
    @Volatile override var title: String = title
        private set
    @Volatile override var artist: String = artist
        private set
    override val totalFrames = TOTAL_FRAMES
    override val complete: Boolean get() = false
    private val capacity = capacitySeconds.coerceAtLeast(2) * SF.SAMPLE_RATE
    private val ring = FloatArray(capacity * 2)
    @Volatile private var head = 0L
    override val readyFrames: Long get() = head

    fun describe(title: String, artist: String) { this.title = title; this.artist = artist }

    override fun sample(frame: Long, ch: Int): Float {
        val h = head
        if (ch !in 0..1 || frame < 0L || frame >= h || frame < h - capacity) return 0f
        return ring[((frame % capacity) * 2L + ch).toInt()]
    }

    /** Дописывает [frames] кадров из интерлив-массива L/R. Голова публикуется после записи данных. */
    @Synchronized fun append(interleaved: FloatArray, frames: Int) {
        var h = head
        for (i in 0 until frames) {
            val index = ((h % capacity) * 2L).toInt()
            ring[index] = interleaved[i * 2]; ring[index + 1] = interleaved[i * 2 + 1]
            h++
        }
        head = h
    }

    /** Тишина в конце потока: хвост последней дорожки доигрывается, а не обрезается запасом опережения. */
    @Synchronized fun appendSilence(frames: Int) {
        var h = head
        for (i in 0 until frames) {
            val index = ((h % capacity) * 2L).toInt()
            ring[index] = 0f; ring[index + 1] = 0f
            h++
        }
        head = h
    }

    companion object {
        /** Условная «длина» потока: 30 суток. Позиция до неё не доходит. */
        const val TOTAL_FRAMES = SF.SAMPLE_RATE * 86_400L * 30L
    }
}

/**
 * Правила автомата живого потока (`Engine.liveTick`). Запас = голова − позиция воспроизведения.
 * Нижняя граница запаса: опережение отправки на Point ([SF.LEAD_NS], 400 мс) + упреждение сцены и предлимитера
 * (~100 мс) + пакет.
 */
object LivePolicy {
    const val TARGET_MARGIN_FRAMES = SF.SAMPLE_RATE * 1L
    const val MIN_MARGIN_FRAMES = SF.SAMPLE_RATE * 6L / 10L
    const val MAX_MARGIN_FRAMES = SF.SAMPLE_RATE * 18L / 10L
    const val START_MARGIN_FRAMES = SF.SAMPLE_RATE * 9L / 10L
    /** Позиция паузы отстала от головы больше, чем приёмник держит впереди: она от другого участка потока. */
    const val STALE_FRAMES = SF.SAMPLE_RATE * 6L
    private const val MAX_PAD_FRAMES = SF.SAMPLE_RATE * 2L

    enum class Action {
        NONE,
        /** Дописать тишину до целевого запаса, шкалу не останавливать. */
        PAD,
        /** Перезапустить шкалу от головы. */
        REANCHOR,
        /** Остановить шкалу на текущем кадре и ждать, пока запас вернётся. */
        STARVED,
    }

    /**
     * Шкала идёт. [capture] — поток реального времени (захват YouTube): он встаёт, когда источник не играет,
     * поэтому разрыв закрывается тишиной. Иначе источник читается с опережением (Spotify): при нехватке — остановка.
     */
    fun whileRunning(capture: Boolean, marginFrames: Long): Action = when {
        capture && marginFrames < -SF.SAMPLE_RATE -> Action.REANCHOR   // позиция ушла далеко за голову: процесс стоял
        capture && marginFrames < MIN_MARGIN_FRAMES -> Action.PAD
        marginFrames < MIN_MARGIN_FRAMES -> Action.STARVED
        capture && marginFrames > MAX_MARGIN_FRAMES -> Action.REANCHOR
        else -> Action.NONE
    }

    fun padFrames(marginFrames: Long): Int = (TARGET_MARGIN_FRAMES - marginFrames).coerceIn(0L, MAX_PAD_FRAMES).toInt()

    /**
     * Кадр старта потока реального времени: к моменту старта голова уйдёт на [startDelayFrames], запас станет целевым.
     * −1 — принятого звука пока мало.
     */
    fun captureStartFrame(head: Long, startDelayFrames: Long, segmentStart: Long): Long =
        (head + startDelayFrames - TARGET_MARGIN_FRAMES).let { if (it < segmentStart) -1L else it }

    /** Кадр, с которого продолжает источник с опережением: место паузы, а если оно устарело — голова. */
    fun resumeFrame(head: Long, anchorFrame: Long, segmentStart: Long): Long {
        val paused = maxOf(anchorFrame, segmentStart)
        return if (head - paused > STALE_FRAMES) head else paused
    }

    fun readyToStart(head: Long, frame: Long): Boolean = head - frame >= START_MARGIN_FRAMES
}

/**
 * Привязка потока реального времени к шкале воспроизведения. Захваченный звук идёт по часам аудиотракта,
 * шкала — по системным часам; расхождение в десятки ppm за час съедает запас. Поток передискретизируется
 * (32-точечный sinc ядра рендера) с отношением 1 ± [MAX_PPM]·10⁻⁶ так, чтобы запас держался у цели.
 * При отношении ровно 1 выход повторяет вход бит-в-бит (с задержкой [WindowedSinc.HALF] − 1 кадр).
 */
class LiveClockServo(private val out: LiveSource, private val targetMarginFrames: Int) {
    private val history = FloatArray(HISTORY * 2)
    private var inputHead = 0L
    private var position = (WindowedSinc.HALF - 1).toDouble()
    private var smoothedErrorS = 0.0
    private var primed = false
    private val block = FloatArray(BLOCK * 2 + 64)
    @Volatile var ratioPpm = 0.0
        private set

    /** [marginFrames] — текущий запас (голова − позиция воспроизведения) или null, пока воспроизведение не идёт. */
    fun write(interleaved: FloatArray, frames: Int, marginFrames: Long?) {
        if (marginFrames == null) { primed = false; smoothedErrorS = 0.0; ratioPpm = 0.0 }
        else {
            val errorS = (marginFrames - targetMarginFrames).toDouble() / SF.SAMPLE_RATE
            if (!primed) { smoothedErrorS = errorS; primed = true }
            else smoothedErrorS += (errorS - smoothedErrorS) * (frames.toDouble() / (SF.SAMPLE_RATE * SMOOTH_S)).coerceAtMost(1.0)
            ratioPpm = (smoothedErrorS * GAIN_PPM_PER_S).coerceIn(-MAX_PPM, MAX_PPM)
        }
        val step = 1.0 + ratioPpm * 1e-6
        var offset = 0
        while (offset < frames) {
            val n = minOf(BLOCK, frames - offset)
            for (i in 0 until n) {
                val index = ((inputHead and MASK) * 2L).toInt()
                history[index] = interleaved[(offset + i) * 2]; history[index + 1] = interleaved[(offset + i) * 2 + 1]
                inputHead++
            }
            offset += n
            var produced = 0
            // Самый поздний отвод интерполятора: floor(position) + HALF.
            while (floor(position).toLong() + WindowedSinc.HALF < inputHead && produced < BLOCK + 32) {
                block[produced * 2] = WindowedSinc.interpolate(position) { history[((it and MASK) * 2L).toInt()] }
                block[produced * 2 + 1] = WindowedSinc.interpolate(position) { history[((it and MASK) * 2L).toInt() + 1] }
                produced++
                position += step
            }
            if (produced > 0) out.append(block, produced)
        }
    }

    companion object {
        private const val HISTORY = 4_096
        private const val MASK = (HISTORY - 1).toLong()
        private const val BLOCK = 1_024
        /** 100 мс ошибки запаса → 500 ppm: постоянная времени 200 с, остаточная ошибка при уходе 50 ppm — 10 мс. */
        private const val GAIN_PPM_PER_S = 5_000.0
        const val MAX_PPM = 1_000.0
        private const val SMOOTH_S = 1.0
    }
}
