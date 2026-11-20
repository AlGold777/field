package app.soundfield.capture

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import app.soundfield.diagnostics.Telemetry

/**
 * Громкости медиа и будильника на время захвата YouTube. Режим захвата ставит медиа в ноль и выводит свой звук
 * через поток будильника, поэтому прежние значения обязаны вернуться при любом исходе:
 *
 * - обычный выход из режима — [leave];
 * - процесс убит — системный таймер ([WATCHDOG_MS]), который движок отодвигает, пока режим жив ([keepAlive]):
 *   если отодвигать некому, таймер срабатывает, система поднимает процесс, и громкости возвращаются;
 * - перезагрузка телефона — [CaptureVolumeReceiver] по `BOOT_COMPLETED`;
 * - принудительная остановка приложения снимает таймеры: остаётся возврат при следующем запуске ([restore] из движка).
 *
 * Значения лежат в тех же настройках, что и остальное состояние движка.
 */
object CaptureVolumeGuard {
    private const val PREFS = "soundfield"
    private const val KEY_MEDIA = "captureSavedMedia"
    private const val KEY_ALARM = "captureSavedAlarm"
    const val WATCHDOG_MS = 3 * 60_000L
    private const val KEEP_ALIVE_MS = 60_000L

    /** Режим захвата включён в этом процессе. В заново поднятом процессе — false. */
    @Volatile var active = false
        private set
    @Volatile private var armedAtMs = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Вход в режим: запоминает текущие громкости (если ещё не запомнены) до их изменения.
     * Запись синхронная: изменение громкости без сохранённого значения не должно пережить гибель процесса.
     */
    fun enter(ctx: Context, media: Int, alarm: Int) {
        val prefs = prefs(ctx)
        if (!prefs.contains(KEY_MEDIA)) prefs.edit().putInt(KEY_MEDIA, media).putInt(KEY_ALARM, alarm).commit()
        active = true
        arm(ctx)
    }

    /** Раз в минуту, пока режим жив: отодвигает таймер. */
    fun keepAlive(ctx: Context) {
        if (active && SystemClock.elapsedRealtime() - armedAtMs >= KEEP_ALIVE_MS) arm(ctx)
    }

    /** Обычный выход из режима. */
    fun leave(ctx: Context) {
        active = false
        disarm(ctx)
        restore(ctx, "leave")
    }

    /** Возвращает сохранённые громкости, если они есть. true — что-то возвращено. */
    fun restore(ctx: Context, reason: String): Boolean {
        val prefs = prefs(ctx)
        if (!prefs.contains(KEY_MEDIA)) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.setStreamVolume(AudioManager.STREAM_ALARM, prefs.getInt(KEY_ALARM, am.getStreamVolume(AudioManager.STREAM_ALARM)), 0)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt(KEY_MEDIA, 0), 0)
        } catch (e: Exception) { Telemetry.get(ctx).event("CAPTURE_VOLUME_FAILED", error = e) }
        prefs.edit().remove(KEY_MEDIA).remove(KEY_ALARM).commit()
        if (reason != "leave") Telemetry.get(ctx).record("CAPTURE_VOLUME_RESTORED", mapOf("reason" to reason))
        return true
    }

    /** Таймер сработал или телефон загрузился. */
    internal fun onWake(ctx: Context, reason: String) {
        if (active) arm(ctx) else restore(ctx, reason)
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, CaptureVolumeReceiver::class.java).setAction(CaptureVolumeReceiver.ACTION_WATCHDOG),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(ctx: Context) {
        armedAtMs = SystemClock.elapsedRealtime()
        try {
            // Неточный таймер: разрешений не требует и срабатывает в режиме энергосбережения.
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, armedAtMs + WATCHDOG_MS, pending(ctx))
        } catch (e: Exception) { Telemetry.get(ctx).event("CAPTURE_WATCHDOG_FAILED", error = e) }
    }

    private fun disarm(ctx: Context) {
        try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx)) } catch (_: Exception) {}
    }
}

/** Точка входа для таймера [CaptureVolumeGuard] и для загрузки телефона. */
class CaptureVolumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_WATCHDOG -> CaptureVolumeGuard.onWake(app, "watchdog")
            Intent.ACTION_BOOT_COMPLETED -> CaptureVolumeGuard.onWake(app, "boot")
        }
    }

    companion object { const val ACTION_WATCHDOG = "app.soundfield.capture.VOLUME_WATCHDOG" }
}
