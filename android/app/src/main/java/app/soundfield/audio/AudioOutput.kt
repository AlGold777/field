package app.soundfield.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import app.soundfield.core.AudioPlayoutClock
import app.soundfield.core.RenderSource
import app.soundfield.core.StereoRenderSource
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
    @Volatile var playbackActive: Boolean = false
    /** Стереопара выводит каналы через встроенный динамик; соло следует системному аудиомаршруту. */
    @Volatile var preferPhoneSpeaker: Boolean = true
    @Volatile var onRouteChanged: (() -> Unit)? = null
    /** Поток громкости вывода. При захвате звука другого приложения медиапоток заглушён, вывод идёт через будильник. */
    @Volatile var usage: Int = AudioAttributes.USAGE_MEDIA
    /** Поток, которым система регулирует громкость этого вывода (для метрик «выход заглушён»). */
    private val volumeStream: Int get() = if (usage == AudioAttributes.USAGE_ALARM) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC
    @Volatile var latencyMs = 0.0; private set
    @Volatile var underruns = 0L; private set
    @Volatile var timestampOk = false; private set
    /** true, если вывод остановлен после [MAX_RECOVERY_ATTEMPTS] подряд неудачных попыток восстановления
     *  (а не вызовом stop()); Engine перезапускает такой вывод из heartbeat. */
    @Volatile var failed = false; private set
    private val outputSignal = AtomicSignalMeter()
    private val timestampFrames = AtomicLong()
    private val timestampGeneration = AtomicLong()
    private val recoveryCount = AtomicLong()
    private val recoveryError = AtomicReference<Throwable?>(null)
    private var lastMetricsAtMs = 0L
    private var lastMetricsFrames = 0L
    private var lastMetricsGeneration = -1L

    @Volatile private var thread: Thread? = null
    /** Цикл, который stop() не дождался; start() ждёт его завершения перед новым циклом. */
    private var retiring: Thread? = null
    private companion object {
        const val MAX_RECOVERY_ATTEMPTS = 5
    }
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var track: AudioTrack? = null
    private val routeHandler = Handler(Looper.getMainLooper())
    @Volatile private var routeSignature = ""
    private var routeChangeTask: Runnable? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { pinSpeaker(); scheduleRouteCheck() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { pinSpeaker(); scheduleRouteCheck() }
    }
    private val routingListener = AudioRouting.OnRoutingChangedListener { scheduleRouteCheck() }

    val isRunning get() = thread?.isAlive == true

    fun routeLabel(): String {
        val device = if (Build.VERSION.SDK_INT >= 23) track?.routedDevice else null
        val name = device?.productName?.toString()?.takeIf { it.isNotBlank() }
        return when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Динамик телефона"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> name ?: "Проводные наушники"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> name ?: "Bluetooth"
            null -> "Аудиовыход не определён"
            else -> name ?: "Внешний аудиовыход"
        }
    }

    /** Stable local profile key. Bluetooth devices use their address when available. */
    fun routeKey(): String {
        val device = if (Build.VERSION.SDK_INT >= 23) track?.routedDevice else null
        return when (device?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                "bluetooth:${device.address.takeIf { it.isNotBlank() } ?: device.productName.toString()}"
            null -> "unknown"
            else -> "other:${device.type}:${device.address.ifBlank { device.productName.toString() }}"
        }
    }

    fun isBuiltInSpeakerRoute(): Boolean = routeKey() == "speaker"

    fun builtInSpeakerSupportsStereo(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }
        ?.channelCounts?.contains(2) == true

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
            am.getStreamVolume(volumeStream),
            if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(volumeStream) else 0,
            am.getStreamMaxVolume(volumeStream), am.isStreamMute(volumeStream),
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
        // Старый цикл, не успевший завершиться в stop(), выходит сам (active() == false после смены thread);
        // ждём его ограниченно, чтобы start() на главном потоке не мог зависнуть.
        retiring?.join(500)
        retiring = null
        failed = false
        routeSignature = currentRouteSignature()
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        thread = Thread({ loop() }, "sf-audio").apply { priority = Thread.MAX_PRIORITY }
        thread!!.start()
    }

    private fun currentRouteSignature(): String {
        val selected = if (Build.VERSION.SDK_INT >= 23) track?.routedDevice
            ?.let { "${it.type}:${it.address}:${it.productName}" } ?: "unknown" else "unknown"
        val available = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }.sortedBy { it.id }.joinToString("|") { "${it.type}:${it.address}" }
        return "$selected#$available"
    }

    private fun scheduleRouteCheck() {
        routeHandler.post {
            routeChangeTask?.let(routeHandler::removeCallbacks)
            val task = Runnable {
                val now = currentRouteSignature()
                if (routeSignature.isNotEmpty() && now != routeSignature) {
                    routeSignature = now
                    onRouteChanged?.invoke()
                } else routeSignature = now
            }
            routeChangeTask = task
            routeHandler.postDelayed(task, 300)
        }
    }

    /** Пересоздаёт трек (смена [usage]); остановленный вывод не запускает. */
    @Synchronized fun restart() {
        if (thread == null) return
        stop(); start()
    }

    @Synchronized fun stop() {
        failed = false   // остановка намеренная: Engine не должен перезапускать вывод
        val worker = thread ?: return
        thread = null
        try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        worker.interrupt()
        // Non-blocking writes let the worker release its own track before another start.
        worker.join(1000)
        if (worker.isAlive) retiring = worker
    }

    private fun active() = thread === Thread.currentThread() && !Thread.currentThread().isInterrupted

    /** Подряд неудачных пересозданий трека (только с аудиопотока). Сбрасывается после успешной записи. */
    private var failedAttempts = 0

    /** Стереопара звучит именно из динамика телефона, а не из подключённых наушников. */
    private fun pinSpeaker() {
        val t = track ?: return
        if (!preferPhoneSpeaker) {
            try { t.setPreferredDevice(null) } catch (_: Exception) {}
            return
        }
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
                    if (!active()) break
                    if (++failedAttempts >= MAX_RECOVERY_ATTEMPTS) {
                        Log.e("SoundField", "AudioTrack: лимит попыток восстановления исчерпан, вывод остановлен", e)
                        failed = true
                        break
                    }
                    // пауза перед пересозданием трека: не крутиться на неисправном треке
                    Thread.sleep(50)
                }
            }
        } catch (_: InterruptedException) {
            // stop() also interrupts retry backoff.
        }
    }

    private fun playTrack() {
        val sr = SF.SAMPLE_RATE
        val channels = if (preferPhoneSpeaker) 1 else 2
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sr, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(channelMask).build())
            .setBufferSizeInBytes(maxOf(minBuf, 2048 * channels * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        try {
            check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack is not initialized" }
            track = t
            if (Build.VERSION.SDK_INT >= 23) t.addOnRoutingChangedListener(routingListener, routeHandler)
            timestampGeneration.incrementAndGet()
            pinSpeaker()
            val n = 240 // 5 мс на блок
            val buf = FloatArray(n * channels)
            val mono = if (channels == 1) buf else FloatArray(n)
            val ts = AudioTimestamp()
            val timing = AudioPlayoutClock(sr, t.bufferSizeInFrames)
            var lastPoll = Long.MIN_VALUE
            var lastHead = 0L
            var lastProgress = System.nanoTime()
            var pausedAtMs = 0L
            var pausedByEngine = false
            t.play()
            while (active()) {
                val now = System.nanoTime()
                if (!playbackActive) {
                    if (pausedAtMs == 0L) pausedAtMs = android.os.SystemClock.elapsedRealtime()
                    if (!pausedByEngine && android.os.SystemClock.elapsedRealtime() - pausedAtMs >= 5_000L) {
                        t.pause()
                        pausedByEngine = true
                        timestampOk = false
                        lastPoll = Long.MIN_VALUE
                    }
                    if (pausedByEngine) {
                        Thread.sleep(50)
                        continue
                    }
                } else {
                    pausedAtMs = 0L
                    if (pausedByEngine) {
                        t.play()
                        pausedByEngine = false
                        timestampOk = false
                        lastPoll = Long.MIN_VALUE
                        lastProgress = now
                    }
                }
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
                if (src == null) java.util.Arrays.fill(buf, 0f)
                else if (channels == 1) src.render(buf, n, present)
                else if (src is StereoRenderSource) src.renderStereo(buf, n, present)
                else {
                    src.render(mono, n, present)
                    for (i in 0 until n) { buf[i * 2] = mono[i]; buf[i * 2 + 1] = mono[i] }
                }
                var off = 0
                val writeCount = n * channels
                var lastWrite = now
                while (off < writeCount && active()) {
                    val r = t.write(buf, off, writeCount - off, AudioTrack.WRITE_NON_BLOCKING)
                    check(r >= 0) { "AudioTrack.write failed: $r" }
                    val written = r - r % channels
                    if (written == 0) {
                        check(System.nanoTime() - lastWrite < 2_000_000_000L) { "AudioTrack.write stalled" }
                        Thread.sleep(2)
                    } else {
                        outputSignal.add(buf, off, written)
                        off += written
                        timing.accepted(written / channels)
                        lastWrite = System.nanoTime()
                        failedAttempts = 0
                    }
                }
                underruns = t.underrunCount.toLong()
            }
        } finally {
            timestampOk = false
            latencyMs = 0.0
            if (track === t) track = null
            if (Build.VERSION.SDK_INT >= 23) try { t.removeOnRoutingChangedListener(routingListener) } catch (_: Exception) {}
            try { t.pause(); t.flush(); t.stop() } catch (_: Exception) {}
            t.release()
        }
    }
}
