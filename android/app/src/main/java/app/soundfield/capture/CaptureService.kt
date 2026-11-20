package app.soundfield.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.soundfield.R
import app.soundfield.core.SF
import app.soundfield.diagnostics.Telemetry
import app.soundfield.engine.Engine
import app.soundfield.ui.MainActivity

/**
 * Захват звука другого приложения (YouTube) штатным AudioPlaybackCapture: foreground-сервис типа mediaProjection
 * держит разрешение пользователя и поток чтения. PCM уходит в движок ([Engine.captureWrite]).
 * Звук приложения, запретившего захват (Spotify), сюда не попадает — система отдаёт тишину.
 */
@RequiresApi(29)
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var thread: Thread? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // С Android 15 QPR1 система завершает захват при блокировке экрана.
            val locked = (getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked == true
            Telemetry.get(this@CaptureService).record("CAPTURE_STOPPED", mapOf("locked" to locked))
            finish(if (locked) "Система остановила захват звука при блокировке экрана" else "Захват звука остановлен")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val telemetry = Telemetry.get(this)
        if (intent?.action == ACTION_STOP) {
            // «Стоп» в уведомлении: выход из захвата без открытия приложения, движок возвращает плеер.
            telemetry.event("CAPTURE_STOP_FROM_NOTIFICATION")
            finish("Захват звука остановлен")
            return START_NOT_STICKY
        }
        try {
            startForeground(ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: Exception) {
            telemetry.event("CAPTURE_SERVICE_START_FAILED", error = e)
            finish("Захват звука не запустился"); return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (thread != null) return START_NOT_STICKY
        if (data == null || resultCode != Activity.RESULT_OK) { finish("Захват звука не разрешён"); return START_NOT_STICKY }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val granted = manager.getMediaProjection(resultCode, data) ?: error("projection is null")
            projection = granted
            granted.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            val created = createRecord(granted)
            record = created
            created.startRecording()
            check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "recording did not start" }
            telemetry.record("CAPTURE_START", mapOf("encoding" to created.audioFormat, "sampleRate" to created.sampleRate,
                "targets" to targetUids().size))
            // Сначала публикуем поток, потом запускаем: цикл чтения идёт, пока thread === currentThread,
            // и при раннем старте мог сразу завершиться, оставив захват без чтения.
            val worker = Thread({ readLoop(created) }, "sf-capture")
            thread = worker
            worker.start()
        } catch (e: Exception) {
            telemetry.event("CAPTURE_START_FAILED", error = e)
            finish("Захват звука не запустился")
        }
        return START_NOT_STICKY
    }

    /** YouTube и YouTube Music, если установлены; иначе — весь медиазвук других приложений. */
    private fun targetUids(): List<Int> = TARGET_PACKAGES.mapNotNull { name ->
        runCatching { packageManager.getApplicationInfo(name, 0).uid }.getOrNull()
    }.distinct()

    private fun createRecord(granted: MediaProjection): AudioRecord {
        val builder = AudioPlaybackCaptureConfiguration.Builder(granted)
        val uids = targetUids()
        if (uids.isNotEmpty()) uids.forEach(builder::addMatchingUid)
        else {
            builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid())
        }
        val config = builder.build()
        var failure: Exception? = null
        for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            try {
                val format = AudioFormat.Builder().setSampleRate(SF.SAMPLE_RATE).setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
                val minimum = AudioRecord.getMinBufferSize(SF.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, encoding)
                val bytesPerFrame = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
                val created = AudioRecord.Builder().setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minimum, SF.SAMPLE_RATE / 5 * bytesPerFrame))
                    .setAudioPlaybackCaptureConfig(config).build()
                if (created.state == AudioRecord.STATE_INITIALIZED) return created
                created.release()
            } catch (e: Exception) { failure = e }
        }
        throw IllegalStateException("AudioRecord is not initialized", failure)
    }

    private fun readLoop(input: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val engine = Engine.get(this)
        val frames = SF.SAMPLE_RATE / 100   // 10 мс
        val floats = FloatArray(frames * 2)
        val shorts = if (input.audioFormat == AudioFormat.ENCODING_PCM_16BIT) ShortArray(frames * 2) else null
        var reason: String? = null
        try {
            while (thread === Thread.currentThread()) {
                val read = if (shorts == null) input.read(floats, 0, floats.size, AudioRecord.READ_BLOCKING)
                    else input.read(shorts, 0, shorts.size).also { n -> for (i in 0 until n.coerceAtLeast(0)) floats[i] = shorts[i] / 32_768f }
                if (read < 0) { reason = "Захват звука прерван (код $read)"; break }
                if (read >= 2) engine.captureWrite(floats, read / 2)
            }
        } catch (e: Exception) {
            Telemetry.get(this).event("CAPTURE_READ_FAILED", error = e)
            reason = "Захват звука прерван"
        }
        reason?.let { text -> Handler(Looper.getMainLooper()).post { finish(text) } }
    }

    /** Захват кончился не по команде движка: отказ, ошибка чтения, пользователь остановил трансляцию. */
    private fun finish(reason: String) {
        Engine.get(this).captureEnded(reason)
        release()
        stopSelf()
    }

    private fun release() {
        val worker = thread
        thread = null
        try { record?.stop() } catch (_: Exception) {}
        if (worker != null && worker !== Thread.currentThread()) try { worker.join(500) } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        projection?.let { p ->
            try { p.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { p.stop() } catch (_: Exception) {}
        }
        projection = null
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "soundfield_capture"
        private const val ID = 43
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "app.soundfield.capture.STOP"
        val TARGET_PACKAGES = listOf("com.google.android.youtube", "com.google.android.apps.youtube.music")

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_DATA, data))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CaptureService::class.java)) }

        private fun notification(ctx: Context): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Звук из YouTube", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "SoundField берёт звук приложения YouTube"; setShowBadge(false) })
            }
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 3, Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Без установленного YouTube захватывается звук всех медиаприложений — уведомление говорит об этом прямо.
            val youtubeInstalled = TARGET_PACKAGES.any { name ->
                runCatching { ctx.packageManager.getApplicationInfo(name, 0) }.isSuccess
            }
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_soundfield)
                .setContentTitle(if (youtubeInstalled) "Источник: YouTube" else "Источник: звук других приложений")
                .setContentText(if (youtubeInstalled) "SoundField воспроизводит звук приложения YouTube"
                    else "SoundField воспроизводит звук медиаприложений этого телефона")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(0, "Стоп", stop)
                .build()
        }
    }
}
