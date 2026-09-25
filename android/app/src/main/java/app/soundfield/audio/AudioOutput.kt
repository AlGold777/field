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
import android.util.Log
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

    @Volatile private var running = false
    private var thread: Thread? = null
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var track: AudioTrack? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = pinSpeaker()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = pinSpeaker()
    }

    val isRunning get() = running

    fun start() {
        if (running) return
        running = true
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        thread = Thread({ loop() }, "sf-audio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        thread?.join(500)
        thread = null
    }

    /** Стереопара звучит именно из динамика телефона, а не из подключённых наушников. */
    private fun pinSpeaker() {
        val t = track ?: return
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val spk = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: if (Build.VERSION.SDK_INT >= 30) outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE } else null
        try { t.setPreferredDevice(spk) } catch (_: Exception) {}
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = SF.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = try {
            AudioTrack.Builder()
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
        } catch (e: Exception) {
            Log.e("SoundField", "AudioTrack", e); running = false; return
        }
        track = t
        pinSpeaker()
        val n = 240                             // 5 мс на блок
        val buf = FloatArray(n)
        val ts = AudioTimestamp()
        var written = 0L
        var tsFrame = 0L; var tsNano = 0L; var tsValid = false
        var lastPoll = 0L
        t.play()
        while (running) {
            val now = System.nanoTime()
            if (!tsValid || now - lastPoll > 100_000_000L) {
                lastPoll = now
                if (t.getTimestamp(ts) && ts.framePosition > 0) {
                    tsFrame = ts.framePosition; tsNano = ts.nanoTime; tsValid = true
                }
            }
            val present = if (tsValid) {
                tsNano + ((written - tsFrame) * 1e9 / sr).toLong()
            } else {
                // до появления таймстампа: грубая оценка по позиции головки + типичная задержка тракта
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                now + ((written - head) * 1e9 / sr).toLong() + 20_000_000L
            }
            timestampOk = tsValid
            latencyMs = (present - now) / 1e6
            val src = source
            if (src != null) src.render(buf, n, present) else java.util.Arrays.fill(buf, 0f)
            var off = 0
            while (off < n && running) {
                val r = t.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                if (r < 0) { Log.w("SoundField", "write $r"); break }
                off += r
            }
            written += n
            underruns = t.underrunCount.toLong()
        }
        try { t.pause(); t.flush(); t.stop() } catch (_: Exception) {}
        t.release()
        track = null
    }
}
