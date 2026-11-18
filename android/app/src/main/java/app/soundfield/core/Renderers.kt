package app.soundfield.core

import java.util.concurrent.atomic.AtomicLong
import app.soundfield.dsp.LimiterStats
import app.soundfield.dsp.PeakLimiter
import app.soundfield.dsp.Profile
import app.soundfield.dsp.SpeakerCorrector
import app.soundfield.dsp.WindowedSinc

/** Потолок финального true-peak лимитера устройства (A1.4): −1,0 dBTP. Сцепленный предлимитер Server — −1,5 dBTP. */
const val DEVICE_LIMITER_CEILING_DBTP = -1.0
const val PRE_LIMITER_CEILING_DBTP = -1.5

/**
 * Затухание ≈5 мс при переходе из воспроизведения в паузу без запланированного stopFrame
 * (образец — PauseFade в ios/SoundField/Core/Renderers.swift). Без аллокаций.
 * remember — на буфере воспроизведения до громкости; apply — на нулевом буфере паузы.
 */
private class PauseFade {
    private val frames = SF.SAMPLE_RATE / 200
    private var level = 0f
    private var left = 0
    fun remember(out: FloatArray, n: Int) {
        if (n <= 0) return
        level = out[n - 1]; left = frames
    }
    fun apply(out: FloatArray, n: Int) {
        var i = 0
        while (left > 0 && i < n) {
            out[i] = level * left / frames
            left--; i++
        }
    }
}

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
    private val fade = PauseFade()
    private val fadeRight = PauseFade()
    private val stereoAnalysis = FloatArray(4_096)
    private val stereoLeft = FloatArray(4_096)
    private val stereoRight = FloatArray(4_096)
    /** Финальный true-peak лимитер устройства в ядре; моно — для пары, сцепленный стерео — для Solo. */
    private val limiter = PeakLimiter(SF.SAMPLE_RATE, 1, DEVICE_LIMITER_CEILING_DBTP)
    private val limiterStereo = PeakLimiter(SF.SAMPLE_RATE, 2, DEVICE_LIMITER_CEILING_DBTP)
    private var stereoLimiterUsed = false
    /** Коррекция динамика (A2) стоит перед лимитером; без профиля — пустая цепочка (бит-в-бит). */
    private val speaker = SpeakerCorrector(SF.SAMPLE_RATE, 1)
    private val speakerStereo = SpeakerCorrector(SF.SAMPLE_RATE, 2)
    fun setSpeakerProfile(profile: Profile?, partnerKey: String?) {
        speaker.setProfile(profile, partnerKey); speakerStereo.setProfile(profile, partnerKey)
    }
    /** Задержка лимитера, кадров: вычитается из времени предъявления, чтобы звук вышел в запланированный момент. */
    val limiterLatencyFrames: Int get() = limiter.latencyFrames
    val underruns = AtomicLong()
    val sourceMissingFrames = AtomicLong()
    val windowSourceMissingFrames = AtomicLong()
    private var inSourceUnderrun = false
    @Volatile var positionFrame = 0L; private set
    fun takeLimiterStats(): LimiterStats {
        val m = limiter.takeStats()
        if (!stereoLimiterUsed) return m
        val s = limiterStereo.takeStats()
        return LimiterStats(m.blocks + s.blocks, m.activeBlocks + s.activeBlocks, maxOf(m.maxReductionDb, s.maxReductionDb))
    }

    override fun render(out: FloatArray, n: Int, presentLocalNs: Long) {
        val st = state()
        val tl = st.timeline
        if (!tl.playing) {
            java.util.Arrays.fill(out, 0, n, 0f); corrector.reset(); finish(out, n, st.gain, st.equalizer, playing = false); return
        }
        corrector.plan(tl.frameAt(presentLocalNs) - WindowedSinc.GROUP_DELAY_FRAMES + limiterLatencyFrames, n)
        val src = st.source
        requestAudioAhead(src, tl, n)
        var sourceMissing = 0L
        val readyFrames = src.readyFrames
        for (i in 0 until n) {
            val position = corrector.position(i)
            val f = kotlin.math.floor(position + 0.5).toLong()
            val g = tl.envelope(f)
            if (g == 0f) out[i] = 0f else {
                val sample = if (st.channel == Ch.BOTH) {
                    (WindowedSinc.interpolate(position) { src.readPrepared(it, Ch.L) } +
                        WindowedSinc.interpolate(position) { src.readPrepared(it, Ch.R) }) * 0.5f
                } else WindowedSinc.interpolate(position) { src.readPrepared(it, st.channel) }
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
        finish(out, n, st.gain, st.equalizer, playing = true)
    }

    override fun renderStereo(interleaved: FloatArray, n: Int, presentLocalNs: Long) {
        val st = state()
        val tl = st.timeline
        if (!tl.playing) {
            java.util.Arrays.fill(interleaved, 0, n * 2, 0f)
            corrector.reset()
            finishStereo(interleaved, n, st.gain, st.equalizer, playing = false)
            return
        }
        corrector.plan(tl.frameAt(presentLocalNs) - WindowedSinc.GROUP_DELAY_FRAMES + limiterLatencyFrames, n)
        val src = st.source
        requestAudioAhead(src, tl, n)
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
                val left = WindowedSinc.interpolate(position) { src.readPrepared(it, leftChannel) }
                val right = WindowedSinc.interpolate(position) { src.readPrepared(it, rightChannel) }
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
        finishStereo(interleaved, n, st.gain, st.equalizer, playing = true)
    }

    private fun requestAudioAhead(source: AudioSource, timeline: Timeline, frames: Int) {
        val worker = source as? PrefetchableAudioSource ?: return
        if (frames <= 0) return
        val blockEnd = maxOf(timeline.anchorFrame, corrector.position(frames - 1).toLong()) + WindowedSinc.HALF + 1
        val end = if (timeline.stopFrame >= 0) minOf(source.totalFrames, timeline.stopFrame + WindowedSinc.HALF)
            else source.totalFrames
        worker.requestThrough(minOf(blockEnd + SF.SAMPLE_RATE / 2, end))
    }

    private fun finishStereo(samples: FloatArray, n: Int, gain: Float, config: EqConfig, playing: Boolean) {
        equalizer.setConfig(config); equalizerRight.setConfig(config)
        for (i in 0 until n) { stereoLeft[i] = samples[i * 2]; stereoRight[i] = samples[i * 2 + 1] }
        if (playing) { fade.remember(stereoLeft, n); fadeRight.remember(stereoRight, n) }
        else { fade.apply(stereoLeft, n); fadeRight.apply(stereoRight, n) }
        equalizer.processTone(stereoLeft, n)
        equalizerRight.processTone(stereoRight, n)
        for (i in 0 until n) stereoAnalysis[i] = (stereoLeft[i] + stereoRight[i]) * 0.5f
        spectrumCapture.add(stereoAnalysis, n)
        ramp.apply(stereoLeft, n, gain)
        rampRight.apply(stereoRight, n, gain)
        for (i in 0 until n) { samples[i * 2] = stereoLeft[i]; samples[i * 2 + 1] = stereoRight[i] }
        stereoLimiterUsed = true
        speakerStereo.process(samples, 0, n)
        limiterStereo.process(samples, 0, n)
    }

    private fun finish(out: FloatArray, n: Int, gain: Float, config: EqConfig, playing: Boolean) {
        if (playing) fade.remember(out, n) else fade.apply(out, n)
        equalizer.setConfig(config)
        equalizer.processTone(out, n)
        spectrumCapture.add(out, n)
        ramp.apply(out, n, gain)
        speaker.process(out, 0, n)
        limiter.process(out, 0, n)
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
    private val fade = PauseFade()
    private val limiter = PeakLimiter(SF.SAMPLE_RATE, 1, DEVICE_LIMITER_CEILING_DBTP)
    private val speaker = SpeakerCorrector(SF.SAMPLE_RATE, 1)
    fun setSpeakerProfile(profile: Profile?, partnerKey: String?) { speaker.setProfile(profile, partnerKey) }
    val limiterLatencyFrames: Int get() = limiter.latencyFrames
    fun takeLimiterStats(): LimiterStats = limiter.takeStats()

    override fun render(out: FloatArray, n: Int, presentLocalNs: Long) {
        val tl = rx.timeline
        val p = params()
        if (!tl.playing || !clock.ready) {
            java.util.Arrays.fill(out, 0, n, 0f); corrector.reset(); finish(out, n, p, playing = false); return
        }
        val serverNs = clock.toServer(presentLocalNs) - p.trimNs
        corrector.plan(tl.frameAt(serverNs) - WindowedSinc.GROUP_DELAY_FRAMES + limiterLatencyFrames, n)
        val useTcp = audioPath() == "tcp"
        var missing = 0
        for (i in 0 until n) {
            val position = corrector.position(i)
            val f = kotlin.math.floor(position + 0.5).toLong()
            val g = tl.envelope(f)
            if (g == 0f) { out[i] = 0f; continue }
            val s = WindowedSinc.interpolate(position) { frame ->
                if (frame < tl.anchorFrame || (p.totalFrames > 0 && frame >= p.totalFrames)) 0f
                else if (useTcp) tcpAudio.read(tl.epoch, frame) else rx.read(frame)
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
        finish(out, n, p, playing = true)
        if (debugSilence) java.util.Arrays.fill(out, 0, n, 0f)
    }

    private fun finish(out: FloatArray, n: Int, p: PointParams, playing: Boolean) {
        if (playing) fade.remember(out, n) else fade.apply(out, n)
        equalizer.setConfig(p.equalizer)
        equalizer.processTone(out, n)
        spectrumCapture.add(out, n)
        ramp.apply(out, n, p.gain)
        speaker.process(out, 0, n)
        limiter.process(out, 0, n)
    }
}

class PointParams(val gain: Float, val trimNs: Long, val totalFrames: Long, val equalizer: EqConfig = EqConfig.neutral())
