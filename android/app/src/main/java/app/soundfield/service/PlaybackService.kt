package app.soundfield.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.soundfield.R
import app.soundfield.core.Role
import app.soundfield.engine.Engine
import app.soundfield.engine.Phase
import app.soundfield.ui.MainActivity
import app.soundfield.diagnostics.Telemetry

/**
 * Foreground-сервис типа mediaPlayback: держит процесс, Wi‑Fi (low latency) и CPU, пока телефон в паре —
 * воспроизведение и синхронизация продолжаются при выключенном экране и в фоне.
 * В уведомлении — управление воспроизведением.
 */
class PlaybackService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockBg: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: MediaSessionCompat? = null
    private var scope: CoroutineScope? = null
    private var lastInput: MediaInput? = null
    private var lastPos = 0L
    private var lastPosAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Telemetry.get(this).event("SERVICE_CREATE")
        ensureChannel(this)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // На API 29+ LOW_LATENCY минимизирует jitter и задержку доставки пакетов
        if (Build.VERSION.SDK_INT >= 29) {
            wifiLock = try {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "soundfield:wifi")
                    .apply { setReferenceCounted(false); acquire() }
            } catch (e: Exception) {
                Telemetry.get(this).event("WIFI_LOCK_LOW_LATENCY_FAILED", error = e)
                null
            }
        }
        // На Android < 34 (или если LOW_LATENCY недоступен) держим HIGH_PERF для защиты от энергосбережения в фоне
        if (wifiLock == null || Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            wifiLockBg = try {
                wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "soundfield:wifi-bg")
                    .apply { setReferenceCounted(false); acquire() }
            } catch (e: Exception) {
                Telemetry.get(this).event("WIFI_LOCK_HIGH_PERF_FAILED", error = e)
                null
            }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundfield:audio").apply { setReferenceCounted(false); acquire() }
        startMediaSession()
    }

    private fun startMediaSession() {
        val engine = Engine.get(this)
        val s = MediaSessionCompat(this, "SoundField")
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = engine.play()
            override fun onPause() = engine.pause()
            override fun onStop() = engine.pause()
            override fun onSkipToNext() = engine.nextTrack()
            override fun onSkipToPrevious() = engine.previousTrack()
            override fun onSeekTo(pos: Long) {
                if (!engine.ui.value.input.live) engine.seekMs(pos.coerceAtLeast(0L))
            }
        })
        s.setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        s.isActive = true
        session = s
        sessionToken = s.sessionToken
        syncSession(force = true)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch { engine.ui.collect { syncSession(force = false) } }
        // Перемотка и смена трека без смены состояния: раз в секунду сверяем позицию с экстраполяцией системы.
        sc.launch { while (true) { delay(1000); syncSession(force = false) } }
    }

    private fun syncSession(force: Boolean) {
        val s = session ?: return
        try {
            val engine = Engine.get(this)
            val input = engine.ui.value.toMediaInput()
            val pos = if (input.hasTrack && !input.live) engine.positionMs() else 0L
            val now = SystemClock.elapsedRealtime()
            val changed = force || input != lastInput
            if (!changed && !MediaSessionMapping.positionDrifted(input.playing, lastPos, lastPosAt, pos, now, speed = input.speed)) return
            val prev = lastInput
            lastInput = input; lastPos = pos; lastPosAt = now
            if (changed && (prev == null || prev.title != input.title || prev.artist != input.artist || prev.durationMs != input.durationMs || prev.live != input.live)) {
                val m = MediaSessionMapping.metadata(input)
                s.setMetadata(MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, m.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, m.artist)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, m.durationMs)
                    .build())
            }
            val p = MediaSessionMapping.playback(input, pos)
            s.setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(p.actions)
                .setState(p.state, p.positionMs, p.speed, now)
                .build())
            if (changed && prev != null && (prev.playing != input.playing || prev.title != input.title || prev.artist != input.artist)) refresh(this)
        } catch (e: Exception) {
            android.util.Log.w("SoundField", "media session sync: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = Engine.get(this)
        when (intent?.action) {
            ACTION_TOGGLE -> engine.togglePlay()
            ACTION_PREVIOUS -> engine.previousTrack()
            ACTION_NEXT -> engine.nextTrack()
        }
        val n = build(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
        } catch (e: Exception) {
            // Android 12+ запрещает запуск foreground-сервиса из фона в некоторых случаях — не падаем.
            android.util.Log.w("SoundField", "startForeground: ${e.message}")
            Telemetry.get(this).event("SERVICE_START_FAILED", error = e)
            running = false; stopSelf(); return START_NOT_STICKY
        }
        // Engine.disconnect() выполняется асинхронно: если пара уже остановлена, сервис не должен остаться с уведомлением
        if (!running) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Telemetry.get(this).event("SERVICE_DESTROY")
        scope?.cancel(); scope = null
        sessionToken = null
        try { session?.run { isActive = false; release() } } catch (_: Exception) {}
        session = null
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wifiLockBg?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "soundfield_pair"
        private const val ID = 42
        const val ACTION_TOGGLE = "app.soundfield.TOGGLE"
        const val ACTION_PREVIOUS = "app.soundfield.PREVIOUS"
        const val ACTION_NEXT = "app.soundfield.NEXT"
        @Volatile private var sessionToken: MediaSessionCompat.Token? = null
        @Volatile private var running = false

        fun start(ctx: Context) {
            running = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        }

        fun stop(ctx: Context) {
            running = false
            ctx.stopService(Intent(ctx, PlaybackService::class.java))
        }

        /** Обновить текст уведомления (без перезапуска сервиса). */
        fun refresh(ctx: Context) {
            if (!running) return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(ID, build(ctx))
            } catch (_: Exception) {}
        }

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Стереопара", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Воспроизведение в стереопаре SoundField"; setShowBadge(false) })
            }
        }

        private fun build(ctx: Context): Notification {
            ensureChannel(ctx)
            val s = Engine.get(ctx).ui.value
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
            val toggle = PendingIntent.getService(ctx, 1, Intent(ctx, PlaybackService::class.java).setAction(ACTION_TOGGLE), flags)
            val prev = PendingIntent.getService(ctx, 2, Intent(ctx, PlaybackService::class.java).setAction(ACTION_PREVIOUS), flags)
            val next = PendingIntent.getService(ctx, 3, Intent(ctx, PlaybackService::class.java).setAction(ACTION_NEXT), flags)
            val roleText = if (s.role == Role.SERVER) "Server" else "Point"
            val ch = if (s.myChannel == 0) "левый" else "правый"
            val title = when {
                s.soloMode -> "Соло на этом телефоне"
                s.phase == Phase.RECONNECTING -> "Переподключение к «${s.peerName}»…"
                else -> "Стереопара с «${s.peerName}»"
            }
            val playbackText = if (s.soloMode) "Локальный плеер" else "$roleText · $ch канал"
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_soundfield)
                .setContentTitle(title)
                .setContentText("$playbackText · ${if (s.playing) "играет" else "пауза"} — ${app.soundfield.engine.trackLabel(s.title, s.artist)}")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(android.R.drawable.ic_media_previous, "Предыдущий", prev)
                .addAction(if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (s.playing) "Пауза" else "Играть", toggle)
                .addAction(android.R.drawable.ic_media_next, "Следующий", next)
                .setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2).also { st -> sessionToken?.let { st.setMediaSession(it) } })
                .build()
        }
    }
}
