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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.soundfield.R
import app.soundfield.core.Role
import app.soundfield.engine.Engine
import app.soundfield.engine.Phase
import app.soundfield.ui.MainActivity

/**
 * Foreground-сервис типа mediaPlayback: держит процесс, Wi‑Fi (low latency) и CPU, пока телефон в паре —
 * воспроизведение и синхронизация продолжаются при выключенном экране и в фоне.
 * В уведомлении — «Пауза/Играть» и «Разорвать пару».
 */
class PlaybackService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wifiLockBg: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // LOW_LATENCY действует только пока приложение на экране; в фоне и при выключенном экране Wi‑Fi
        // держит HIGH_PERF (без энергосбережения) — иначе пакеты задерживаются на секунды и пара рвётся.
        @Suppress("DEPRECATION")
        wifiLockBg = try { wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "soundfield:wifi-bg")
            .apply { setReferenceCounted(false); acquire() } } catch (_: Exception) { null }
        if (Build.VERSION.SDK_INT >= 29) {
            wifiLock = try { wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "soundfield:wifi")
                .apply { setReferenceCounted(false); acquire() } } catch (_: Exception) { null }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundfield:audio").apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = Engine.get(this)
        when (intent?.action) {
            ACTION_TOGGLE -> engine.togglePlay()
            ACTION_DISCONNECT -> engine.disconnect()
        }
        val n = build(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
        } catch (e: Exception) {
            // Android 12+ запрещает запуск foreground-сервиса из фона в некоторых случаях — не падаем.
            android.util.Log.w("SoundField", "startForeground: ${e.message}")
            running = false; stopSelf(); return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wifiLockBg?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "soundfield_pair"
        private const val ID = 42
        const val ACTION_TOGGLE = "app.soundfield.TOGGLE"
        const val ACTION_DISCONNECT = "app.soundfield.DISCONNECT"
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
            val disc = PendingIntent.getService(ctx, 2, Intent(ctx, PlaybackService::class.java).setAction(ACTION_DISCONNECT), flags)
            val roleText = if (s.role == Role.SERVER) "Server" else "Point"
            val ch = if (s.myChannel == 0) "левый" else "правый"
            val title = when (s.phase) {
                Phase.RECONNECTING -> "Переподключение к «${s.peerName}»…"
                else -> "Стереопара с «${s.peerName}»"
            }
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_soundfield)
                .setContentTitle(title)
                .setContentText("$roleText · $ch канал · ${if (s.playing) "играет" else "пауза"} — ${s.title}")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, if (s.playing) "Пауза" else "Играть", toggle)
                .addAction(0, "Разорвать пару", disc)
                .build()
        }
    }
}
