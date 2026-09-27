package app.soundfield.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.soundfield.core.AudioPlayoutClock
import app.soundfield.core.RenderSource
import app.soundfield.core.SF
import app.soundfield.diagnostics.AtomicSignalMeter
import app.soundfield.diagnostics.SignalWindow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Аудиовыход: AudioTrack (моно, float, 48 кГц) с собственным потоком записи.
 *
 * Для каждого блока вычисляется момент, когда его первый сэмпл выйдет из динамика:
 *   present = ts.nanoTime + (framesWritten − ts.framePosition) / SR
 * где ts — AudioTrack.getTimestamp (CLOCK_MONOTONIC = System.nanoTime). Этот момент передаётся
 * RenderSource, который сам решает, какой кадр общей шкалы должен звучать. Таким образом
 * задержка аудиотракта конкретного телефона учитывается автоматически, а остаток — калибровкой.
 */
class AudioOutput(private val ctx: Context) {
    @Volatile var source: RenderSource? = null
    @Volatile var latencyMs = 0.0; private set
    @Volatile var underruns = 0L; private set
    @Volatile var timestampOk = false; private set
    private val outputSignal = AtomicSignalMeter()
    private val timestampFrames = AtomicLong()
    private val timestampGeneration = AtomicLong()
    private val recoveryCount = AtomicLong()
    private val recoveryError = AtomicReference<Throwable?>(null)
    private var lastMetricsAtMs = 0L
    private var lastMetricsFrames = 0L
    private var lastMetricsGeneration = -1L

    @Volatile private var thread: Thread? = null
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var track: AudioTrack? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = pinSpeaker()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = pinSpeaker()
    }

    val isRunning get() = thread?.isAlive == true

    internal data class Window(
        val signal: SignalWindow,
        val framesPerSecond: Double,
        val timestampValid: Boolean,
        val streamVolume: Int,
        val streamMinVolume: Int,
        val streamMaxVolume: Int,
        val streamMuted: Boolean,
        val route: String,
        val audioFocus: Boolean,
        val recoveryCount: Long,
        val recoveryError: Throwable?,
    )

    /** Called by heartbeat only. Audio processing updates the raw signal and timestamp counters atomically. */
    @Suppress("DEPRECATION")
    internal fun takeWindow(audioFocus: Boolean): Window {
        val now = android.os.SystemClock.elapsedRealtime()
        val generation = timestampGeneration.get()
        val frames = timestampFrames.get()
        val elapsed = if (lastMetricsAtMs == 0L || generation != lastMetricsGeneration) 0L else now - lastMetricsAtMs
        val advanced = if (generation != lastMetricsGeneration) 0L else (frames - lastMetricsFrames).coerceAtLeast(0L)
        val fps = if (elapsed > 0) advanced * 1000.0 / elapsed else 0.0
        lastMetricsAtMs = now
        lastMetricsFrames = frames
        lastMetricsGeneration = generation
        val device = if (Build.VERSION.SDK_INT >= 23) track?.routedDevice else null
        return Window(
            outputSignal.take(), fps, timestampOk,
            am.getStreamVolume(AudioManager.STREAM_MUSIC),
            if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0,
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), am.isStreamMute(AudioManager.STREAM_MUSIC),
            route(device), audioFocus, recoveryCount.getAndSet(0), recoveryError.getAndSet(null),
        )
    }

    private fun route(device: AudioDeviceInfo?): String = when (device?.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker_safe"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"
        null -> "unknown"
        else -> "other:${device.type}"
    }

    @Synchronized fun start() {
        if (isRunning) return
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        thread = Thread({ loop() }, "sf-audio").apply { priority = Thread.MAX_PRIORITY }
        thread!!.start()
    }

    @Synchronized fun stop() {
        val worker = thread ?: return
        thread = null
        try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        worker.interrupt()
        // Non-blocking writes let the worker release its own track before another start.
        worker.join(1000)
    }

    private fun active() = thread === Thread.currentThread() && !Thread.currentThread().isInterrupted

    /** Стереопара звучит именно из динамика телефона, а не из подключённых наушников. */
    private fun pinSpeaker() {
        val t = track ?: return
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val spk = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: if (Build.VERSION.SDK_INT >= 30) outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE } else null
        try { t.setPreferredDevice(spk) } catch (_: Exception) {}
    }

    private fun loop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (active()) {
                try {
                    playTrack()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    recoveryCount.incrementAndGet()
                    recoveryError.set(e)
                    if (active()) Thread.sleep(250)
                }
            }
        } catch (_: InterruptedException) {
            // stop() also interrupts retry backoff.
        }
    }

    private fun playTrack() {
        val sr = SF.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, 2048 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        try {
            check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack is not initialized" }
            track = t
            timestampGeneration.incrementAndGet()
            pinSpeaker()
            val n = 240 // 5 мс на блок
            val buf = FloatArray(n)
            val ts = AudioTimestamp()
            val timing = AudioPlayoutClock(sr, t.bufferSizeInFrames)
            var lastPoll = Long.MIN_VALUE
            var lastHead = 0L
            var lastProgress = System.nanoTime()
            t.play()
            while (active()) {
                val now = System.nanoTime()
                if (lastPoll == Long.MIN_VALUE || now - lastPoll >= 100_000_000L) {
                    lastPoll = now
                    if (t.getTimestamp(ts)) {
                        timing.timestamp(ts.framePosition, ts.nanoTime)
                        timestampFrames.set(ts.framePosition)
                    }
                    else timing.invalidateTimestamp()
                }
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (head != lastHead) { lastHead = head; lastProgress = now }
                check(now - lastProgress < 2_000_000_000L) { "AudioTrack playback stalled" }
                val present = timing.presentation(now, head)
                timestampOk = timing.timestampOk
                latencyMs = (present - now) / 1e6
                val src = source
                if (src != null) src.render(buf, n, present) else java.util.Arrays.fill(buf, 0f)
                var off = 0
                var lastWrite = now
                while (off < n && active()) {
                    val r = t.write(buf, off, n - off, AudioTrack.WRITE_NON_BLOCKING)
                    check(r >= 0) { "AudioTrack.write failed: $r" }
                    if (r == 0) {
                        check(System.nanoTime() - lastWrite < 2_000_000_000L) { "AudioTrack.write stalled" }
                        Thread.sleep(2)
                    } else {
                        outputSignal.add(buf, off, r)
                        off += r
                        timing.accepted(r)
                        lastWrite = System.nanoTime()
                    }
                }
                underruns = t.underrunCount.toLong()
            }
        } finally {
            timestampOk = false
            latencyMs = 0.0
            if (track === t) track = null
            try { t.pause(); t.flush(); t.stop() } catch (_: Exception) {}
            t.release()
        }
    }
}
