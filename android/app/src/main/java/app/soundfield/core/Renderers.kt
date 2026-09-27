package app.soundfield.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Рендер на Server: канал serverCh текущего источника, по общей временной шкале.
 * Часы Server — эталон, поэтому presentLocalNs уже в «часах Server».
 */
class ServerRenderer(
    private val state: () -> ServerPlayState,
) : RenderSource {
    val corrector = PlayoutCorrector()
    private val ramp = GainRamp()
    val underruns = AtomicLong()
    val sourceMissingFrames = AtomicLong()
    val windowSourceMissingFrames = AtomicLong()
    private var inSourceUnderrun = false
    @Volatile var positionFrame = 0L; private set

    override fun render(out: FloatArray, n: Int, presentLocalNs: Long) {
        val st = state()
        val tl = st.timeline
        if (!tl.playing) {
            java.util.Arrays.fill(out, 0, n, 0f); corrector.reset(); ramp.apply(out, n, st.gain); return
        }
        corrector.plan(tl.frameAt(presentLocalNs), n)
        val src = st.source
        var sourceMissing = 0L
        val progressive = src as? SegmentedPcmSource
        for (i in 0 until n) {
            val f = corrector.frame(i, n)
            val g = tl.envelope(f)
            if (g == 0f) out[i] = 0f else {
                out[i] = src.sample(f, st.channel) * g
                if (progressive != null && !progressive.complete && f < progressive.totalFrames && f >= progressive.readyFrames) {
                    sourceMissing++
                }
            }
        }
        if (sourceMissing > 0) {
            sourceMissingFrames.addAndGet(sourceMissing)
            windowSourceMissingFrames.addAndGet(sourceMissing)
            if (!inSourceUnderrun) underruns.incrementAndGet()
            inSourceUnderrun = true
        } else inSourceUnderrun = false
        positionFrame = corrector.frame(n - 1, n)
        ramp.apply(out, n, st.gain)
    }
}

/** Снимок состояния для аудиопотока Server (публикуется целиком, без блокировок). */
class ServerPlayState(
    val timeline: Timeline,
    val source: AudioSource,
    val channel: Int,
    val gain: Float,
)

/**
 * Рендер на Point: берёт кадры из jitter buffer. Идеальный кадр считается по часам Server
 * (ClockSync) минус калибровочный сдвиг trim (trim > 0 — Point звучит позже).
 */
class PointRenderer(
    private val rx: PointReceiver,
    private val clock: ClockSync,
    private val tcpAudio: TcpAudioBuffer,
    private val audioPath: () -> String,
    private val params: () -> PointParams,
) : RenderSource {
    val corrector = PlayoutCorrector()
    private val ramp = GainRamp()
    val underrunFrames = AtomicLong()
    val windowUnderrunFrames = AtomicLong()
    val underrunEvents = AtomicLong()
    @Volatile var debugSilence = false
    private var inUnderrun = false

    override fun render(out: FloatArray, n: Int, presentLocalNs: Long) {
        val tl = rx.timeline
        val p = params()
        if (!tl.playing || !clock.ready) {
            java.util.Arrays.fill(out, 0, n, 0f); corrector.reset(); ramp.apply(out, n, p.gain); return
        }
        val serverNs = clock.toServer(presentLocalNs) - p.trimNs
        corrector.plan(tl.frameAt(serverNs), n)
        var missing = 0
        for (i in 0 until n) {
            val f = corrector.frame(i, n)
            val g = tl.envelope(f)
            if (g == 0f) { out[i] = 0f; continue }
            val s = if (audioPath() == "tcp") tcpAudio.read(tl.epoch, f) else rx.read(f)
            if (s.isNaN()) {
                out[i] = 0f
                if (p.totalFrames <= 0 || f < p.totalFrames) missing++
            } else out[i] = s * g
        }
        if (missing > 0) {
            underrunFrames.addAndGet(missing.toLong())
            windowUnderrunFrames.addAndGet(missing.toLong())
            if (!inUnderrun) { underrunEvents.incrementAndGet(); inUnderrun = true }
        } else inUnderrun = false
        rx.lastRenderedFrame = corrector.frame(n - 1, n)
        ramp.apply(out, n, p.gain)
        if (debugSilence) java.util.Arrays.fill(out, 0, n, 0f)
    }
}

class PointParams(val gain: Float, val trimNs: Long, val totalFrames: Long)
