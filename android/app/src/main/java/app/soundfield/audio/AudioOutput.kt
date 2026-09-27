package app.soundfield.audio

import android.content.Context
import app.soundfield.diagnostics.Telemetry
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
import android.util.Log
import app.soundfield.core.AudioPlayoutClock
import app.soundfield.core.RenderSource
import app.soundfield.core.SF

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

    @Volatile private var thread: Thread? = null
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var track: AudioTrack? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = pinSpeaker()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = pinSpeaker()
    }

    val isRunning get() = thread?.isAlive == true

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
                    Log.w("SoundField", "Recreating audio output", e)
                    Telemetry.get(ctx).limited("AUDIO_RECOVERY", error = e)
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
                    if (t.getTimestamp(ts)) timing.timestamp(ts.framePosition, ts.nanoTime)
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
