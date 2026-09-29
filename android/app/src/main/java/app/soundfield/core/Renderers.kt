package app.soundfield.core

import java.util.concurrent.atomic.AtomicLong
import app.soundfield.dsp.WindowedSinc

/**
 * Рендер на Server: канал serverCh текущего источника, по общей временной шкале.
 * Часы Server — эталон, поэтому presentLocalNs уже в «часах Server».
 */
class ServerRenderer(
    private val state: () -> ServerPlayState,
) : StereoRenderSource {
    val corrector = PlayoutCorrector()
    private val ramp = GainRamp()
    private val equalizer = EqualizerProcessor()
    private val equalizerRight = EqualizerProcessor()
    val spectrumCapture = EqSpectrumCapture()
    private val rampRight = GainRamp()
    private val stereoAnalysis = FloatArray(4_096)
    private val stereoLeft = FloatArray(4_096)
    private val stereoRight = FloatArray(4_096)
    val underruns = AtomicLong()
    val sourceMissingFrames = AtomicLong()
    val windowSourceMissingFrames = AtomicLong()
    private var inSourceUnderrun = false
    @Volatile var positionFrame = 0L; private set
    fun takeLimiterStats() = equalizer.takeLimiterStats()

    override fun render(out: FloatArray, n: Int, presentLocalNs: Long) {
        val st = state()
        val tl = st.timeline
        if (!tl.playing) {
            java.util.Arrays.fill(out, 0, n, 0f); corrector.reset(); finish(out, n, st.gain, st.equalizer); return
        }
        corrector.plan(tl.frameAt(presentLocalNs) - WindowedSinc.GROUP_DELAY_FRAMES, n)
        val src = st.source
        var sourceMissing = 0L
        val readyFrames = src.readyFrames
        for (i in 0 until n) {
            val position = corrector.position(i)
            val f = kotlin.math.floor(position + 0.5).toLong()
            val g = tl.envelope(f)
            if (g == 0f) out[i] = 0f else {
                val sample = if (st.channel == Ch.BOTH) {
                    (WindowedSinc.interpolate(position) { src.sample(it, Ch.L) } +
                        WindowedSinc.interpolate(position) { src.sample(it, Ch.R) }) * 0.5f
                } else WindowedSinc.interpolate(position) { src.sample(it, st.channel) }
                if (sample.isNaN()) {
                    out[i] = 0f
                } else out[i] = sample * g
                if (!src.complete && f < src.totalFrames &&
                    (sample.isNaN() || f >= readyFrames)) {
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
        finish(out, n, st.gain, st.equalizer)
    }

    override fun renderStereo(interleaved: FloatArray, n: Int, presentLocalNs: Long) {
        val st = state()
        val tl = st.timeline
        if (!tl.playing) {
            java.util.Arrays.fill(interleaved, 0, n * 2, 0f)
            corrector.reset()
            finishStereo(interleaved, n, st.gain, st.equalizer)
            return
        }
        corrector.plan(tl.frameAt(presentLocalNs) - WindowedSinc.GROUP_DELAY_FRAMES, n)
        val src = st.source
        var sourceMissing = 0L
        val readyFrames = src.readyFrames
        for (i in 0 until n) {
            val position = corrector.position(i)
            val frame = kotlin.math.floor(position + 0.5).toLong()
            val envelope = tl.envelope(frame)
            if (envelope == 0f) {
                interleaved[i * 2] = 0f; interleaved[i * 2 + 1] = 0f
            } else {
                val leftChannel = if (st.channel == Ch.BOTH) Ch.L else st.channel
                val rightChannel = if (st.channel == Ch.BOTH) Ch.R else st.channel
                val left = WindowedSinc.interpolate(position) { src.sample(it, leftChannel) }
                val right = WindowedSinc.interpolate(position) { src.sample(it, rightChannel) }
                if (left.isNaN() || right.isNaN()) {
                    interleaved[i * 2] = 0f; interleaved[i * 2 + 1] = 0f
                } else {
                    interleaved[i * 2] = left * envelope
                    interleaved[i * 2 + 1] = right * envelope
                }
                if (!src.complete && frame < src.totalFrames && (left.isNaN() || right.isNaN() || frame >= readyFrames))
                    sourceMissing++
            }
        }
        if (sourceMissing > 0) {
            sourceMissingFrames.addAndGet(sourceMissing)
            windowSourceMissingFrames.addAndGet(sourceMissing)
            if (!inSourceUnderrun) underruns.incrementAndGet()
            inSourceUnderrun = true
        } else inSourceUnderrun = false
        positionFrame = corrector.frame(n - 1, n)
        finishStereo(interleaved, n, st.gain, st.equalizer)
    }

    private fun finishStereo(samples: FloatArray, n: Int, gain: Float, config: EqConfig) {
        equalizer.setConfig(config); equalizerRight.setConfig(config)
        for (i in 0 until n) { stereoLeft[i] = samples[i * 2]; stereoRight[i] = samples[i * 2 + 1] }
        equalizer.processTone(stereoLeft, n)
        equalizerRight.processTone(stereoRight, n)
        for (i in 0 until n) stereoAnalysis[i] = (stereoLeft[i] + stereoRight[i]) * 0.5f
        spectrumCapture.add(stereoAnalysis, n)
        ramp.apply(stereoLeft, n, gain)
        rampRight.apply(stereoRight, n, gain)
        equalizer.processLimiter(stereoLeft, n)
        equalizerRight.processLimiter(stereoRight, n)
        for (i in 0 until n) { samples[i * 2] = stereoLeft[i]; samples[i * 2 + 1] = stereoRight[i] }
    }

    private fun finish(out: FloatArray, n: Int, gain: Float, config: EqConfig) {
        equalizer.setConfig(config)
        equalizer.processTone(out, n)
        spectrumCapture.add(out, n)
        ramp.apply(out, n, gain)
        equalizer.processLimiter(out, n)
    }
}

/** Снимок состояния для аудиопотока Server (публикуется целиком, без блокировок). */
class ServerPlayState(
    val timeline: Timeline,
    val source: AudioSource,
    val channel: Int,
    val gain: Float,
    val equalizer: EqConfig = EqConfig.neutral(),
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
    private val equalizer = EqualizerProcessor()
    val spectrumCapture = EqSpectrumCapture()
    val underrunFrames = AtomicLong()
    val windowUnderrunFrames = AtomicLong()
    val underrunEvents = AtomicLong()
    @Volatile var debugSilence = false
    private var inUnderrun = false
    fun takeLimiterStats() = equalizer.takeLimiterStats()

    override fun render(out: FloatArray, n: Int, presentLocalNs: Long) {
        val tl = rx.timeline
        val p = params()
        if (!tl.playing || !clock.ready) {
            java.util.Arrays.fill(out, 0, n, 0f); corrector.reset(); finish(out, n, p); return
        }
        val serverNs = clock.toServer(presentLocalNs) - p.trimNs
        corrector.plan(tl.frameAt(serverNs) - WindowedSinc.GROUP_DELAY_FRAMES, n)
        var missing = 0
        for (i in 0 until n) {
            val position = corrector.position(i)
            val f = kotlin.math.floor(position + 0.5).toLong()
            val g = tl.envelope(f)
            if (g == 0f) { out[i] = 0f; continue }
            val s = WindowedSinc.interpolate(position) { frame ->
                if (audioPath() == "tcp") tcpAudio.read(tl.epoch, frame) else rx.read(frame)
            }
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
        finish(out, n, p)
        if (debugSilence) java.util.Arrays.fill(out, 0, n, 0f)
    }

    private fun finish(out: FloatArray, n: Int, p: PointParams) {
        equalizer.setConfig(p.equalizer)
        equalizer.processTone(out, n)
        spectrumCapture.add(out, n)
        ramp.apply(out, n, p.gain)
        equalizer.processLimiter(out, n)
    }
}

class PointParams(val gain: Float, val trimNs: Long, val totalFrames: Long, val equalizer: EqConfig = EqConfig.neutral())
