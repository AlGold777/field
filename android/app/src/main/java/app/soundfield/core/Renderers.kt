package app.soundfield.core

/**
 * Рендер на Server: канал serverCh текущего источника, по общей временной шкале.
 * Часы Server — эталон, поэтому presentLocalNs уже в «часах Server».
 */
class ServerRenderer(
    private val state: () -> ServerPlayState,
) : RenderSource {
    val corrector = PlayoutCorrector()
    private val ramp = GainRamp()
    @Volatile var underruns = 0L; private set
    @Volatile var positionFrame = 0L; private set

    override fun render(out: FloatArray, n: Int, presentLocalNs: Long) {
        val st = state()
        val tl = st.timeline
        if (!tl.playing) {
            java.util.Arrays.fill(out, 0, n, 0f); corrector.reset(); ramp.apply(out, n, st.gain); return
        }
        corrector.plan(tl.frameAt(presentLocalNs), n)
        val src = st.source
        for (i in 0 until n) {
            val f = corrector.frame(i, n)
            out[i] = if (f < tl.anchorFrame) 0f else src.sample(f, st.channel)
        }
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
    private val params: () -> PointParams,
) : RenderSource {
    val corrector = PlayoutCorrector()
    private val ramp = GainRamp()
    @Volatile var underrunFrames = 0L; private set
    @Volatile var underrunEvents = 0L; private set
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
            val s = rx.read(f)
            if (s.isNaN()) {
                out[i] = 0f
                if (f >= tl.anchorFrame && (p.totalFrames <= 0 || f < p.totalFrames)) missing++
            } else out[i] = s
        }
        if (missing > 0) {
            underrunFrames += missing
            if (!inUnderrun) { underrunEvents++; inUnderrun = true }
        } else inUnderrun = false
        rx.lastRenderedFrame = corrector.frame(n - 1, n)
        ramp.apply(out, n, p.gain)
    }
}

class PointParams(val gain: Float, val trimNs: Long, val totalFrames: Long)
