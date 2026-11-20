package app.soundfield.spotify

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import app.soundfield.core.LiveSource
import app.soundfield.core.SF
import app.soundfield.core.SampleRateConverter
import java.io.File
import java.util.concurrent.Executors

/** Все вызовы приходят с потоков нативной библиотеки. */
interface SpotifySink {
    /** Интерлив стерео float32, 44,1 кГц. Может блокироваться: так приёмник не убегает вперёд воспроизведения. */
    fun onPcm(samples: FloatArray, frames: Int)
    fun onEvent(kind: Int, a: Long, b: Long, text1: String?, text2: String?)
    fun onLog(message: String)
}

/** JNI-мост к `libsfspotify.so` (крейт `spotify/`, librespot). */
internal object SpotifyNative {
    /** [initialVolume] — положение регулятора громкости 0..65535, которое устройство сообщит приложению Spotify. */
    external fun start(deviceName: String, cacheDir: String, initialVolume: Int, sink: SpotifySink): Long
    external fun stop(handle: Long)
    external fun command(handle: Long, command: Int)
    external fun setVolume(handle: Long, volume: Int)
    external fun version(): String
}

object SpotifyEvent {
    const val SESSION_CONNECTED = 1
    const val SESSION_DISCONNECTED = 2
    const val PLAYING = 3
    const val PAUSED = 4
    const val TRACK = 5
    const val FLUSH = 6
    const val STOPPED = 7
    const val ERROR = 8
    const val VOLUME = 9
    const val DISCOVERY_READY = 10
}

/**
 * Приёмник Spotify Connect: телефон виден в приложении Spotify как колонка. Декодированный звук
 * передискретизируется в 48 кГц и дописывается в [LiveSource]. Запуск и остановка идут по очереди
 * на собственном потоке: нативная остановка блокирующая.
 */
class SpotifyReceiver(
    private val ctx: Context,
    private val onEvent: (kind: Int, a: Long, b: Long, text1: String?, text2: String?) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sf-spotify-ctl") }
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("soundfield-spotify").apply { setReferenceCounted(false) }
    @Volatile private var session: Session? = null
    /** Сеанс аккаунта Spotify установлен: команды воспроизведения есть кому принять. */
    @Volatile var sessionActive = false
        private set

    private inner class Session(val name: String, val live: LiveSource, val bufferedFrames: () -> Long) : SpotifySink {
        @Volatile var handle = 0L
        @Volatile var released = false
        @Volatile var registration: NsdManager.RegistrationListener? = null
        private val converter = SampleRateConverter(SOURCE_RATE, SF.SAMPLE_RATE)
        private var converted = FloatArray(0)

        override fun onPcm(samples: FloatArray, frames: Int) {
            if (released || frames <= 0) return
            val need = converter.maxOutputFrames(frames) * 2
            if (converted.size < need) converted = FloatArray(need)
            val produced = converter.process(samples, frames, converted)
            if (produced > 0) live.append(converted, produced)
            // Противодавление: декодер идёт быстрее реального времени, держим его не дальше MAX_AHEAD от воспроизведения.
            // Ожидание ограничено: пока поток стоит здесь, librespot не обрабатывает команды Connect.
            var waitedMs = 0
            while (!released && waitedMs < MAX_BLOCK_MS && bufferedFrames() > MAX_AHEAD_FRAMES) {
                try { Thread.sleep(10) } catch (_: InterruptedException) { return }
                waitedMs += 10
            }
        }

        override fun onEvent(kind: Int, a: Long, b: Long, text1: String?, text2: String?) {
            if (released) return
            when (kind) {
                SpotifyEvent.SESSION_CONNECTED -> sessionActive = true
                SpotifyEvent.SESSION_DISCONNECTED -> sessionActive = false
                SpotifyEvent.DISCOVERY_READY -> { if (b == 0L) advertise(this, a.toInt(), text1 ?: "/"); return }
                // Граница нового участка фиксируется здесь, а не в очереди движка: библиотека не пишет звук новой
                // позиции, пока этот вызов не вернулся, а к разбору события движком первый пакет уже был бы записан.
                SpotifyEvent.FLUSH -> { this@SpotifyReceiver.onEvent(kind, live.readyFrames, b, text1, text2); return }
            }
            this@SpotifyReceiver.onEvent(kind, a, b, text1, text2)
        }

        override fun onLog(message: String) { this@SpotifyReceiver.onLog(message) }

        fun close() { converter.close() }
    }

    fun start(deviceName: String, live: LiveSource, initialVolume: Int, bufferedFrames: () -> Long) {
        worker.execute {
            stopNow()
            if (!available) return@execute
            val created = Session(deviceName, live, bufferedFrames)
            try { multicastLock.acquire() } catch (_: Exception) {}
            val cache = File(ctx.filesDir, "spotify").apply { mkdirs() }
            val handle = try { SpotifyNative.start(deviceName, cache.absolutePath, initialVolume.coerceIn(0, VOLUME_MAX), created) } catch (t: Throwable) {
                Log.w(TAG, "spotify start", t); 0L
            }
            if (handle == 0L) {
                created.released = true; created.close()
                try { multicastLock.release() } catch (_: Exception) {}
                onEvent(SpotifyEvent.ERROR, 0, 0, "Приёмник Spotify не запустился", null)
                return@execute
            }
            created.handle = handle
            session = created
        }
    }

    fun stop() { worker.execute { stopNow() } }

    /** 0 — играть, 1 — пауза, 2 — следующий, 3 — предыдущий. */
    fun command(command: Int) {
        val handle = session?.handle?.takeIf { it != 0L } ?: return
        try { SpotifyNative.command(handle, command) } catch (t: Throwable) { Log.w(TAG, "spotify command", t) }
    }

    /** Положение регулятора громкости SoundField (0..[VOLUME_MAX]) → приложению Spotify. */
    fun setVolume(volume: Int) {
        val handle = session?.handle?.takeIf { it != 0L } ?: return
        try { SpotifyNative.setVolume(handle, volume.coerceIn(0, VOLUME_MAX)) } catch (t: Throwable) { Log.w(TAG, "spotify volume", t) }
    }

    private fun stopNow() {
        val current = session ?: return
        session = null
        sessionActive = false
        current.released = true   // снимает блокировку onPcm до нативной остановки
        current.registration?.let { runCatching { nsd.unregisterService(it) } }
        current.registration = null
        try { SpotifyNative.stop(current.handle) } catch (t: Throwable) { Log.w(TAG, "spotify stop", t) }
        current.close()
        try { multicastLock.release() } catch (_: Exception) {}
    }

    /** Объявление `_spotify-connect._tcp` средствами Android, если нативная библиотека не объявляет службу сама. */
    private fun advertise(target: Session, port: Int, path: String) {
        if (target.released || port <= 0) return
        val info = NsdServiceInfo().apply {
            serviceName = target.name
            serviceType = "_spotify-connect._tcp"
            setPort(port)
            setAttribute("CPath", path)
            setAttribute("VERSION", "1.0")
            setAttribute("Stack", "SP")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                onEvent(SpotifyEvent.ERROR, code.toLong(), 0, "Не удалось объявить устройство в сети (код $code)", null)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            target.registration = listener
            // stopNow мог пройти между проверкой released и регистрацией: тогда объявление снимаем сами,
            // иначе устройство оставалось бы видно в Spotify до смерти процесса.
            if (target.released) { runCatching { nsd.unregisterService(listener) }; target.registration = null }
        } catch (e: Exception) {
            onEvent(SpotifyEvent.ERROR, 0, 0, "Не удалось объявить устройство в сети", null)
        }
    }

    companion object {
        private const val TAG = "SoundField"
        private const val SOURCE_RATE = 44_100
        /** Насколько декодер может опережать воспроизведение. */
        const val MAX_AHEAD_FRAMES = SF.SAMPLE_RATE * 3L / 2L
        const val VOLUME_MAX = 65_535
        /** Версия крейта `spotify/`, с которой согласован этот JNI-контракт. */
        const val NATIVE_VERSION = "0.1.2"
        /** Дольше держать поток плеера нельзя: на нём же librespot исполняет паузу, перемотку и смену трека. */
        private const val MAX_BLOCK_MS = 400

        /** Библиотека собирается отдельно (`tools/build_spotify.sh`); без неё источник Spotify недоступен. */
        val available: Boolean get() = unavailableReason == null

        /** null — источник доступен; иначе причина для пользователя. Устаревшая библиотека не используется: её JNI-контракт другой. */
        val unavailableReason: String? by lazy {
            val loaded = try { System.loadLibrary("sfspotify"); true } catch (_: Throwable) { false }
            if (!loaded) return@lazy "Приёмник Spotify не входит в эту сборку"
            nativeVersion = runCatching { SpotifyNative.version() }.getOrDefault("")
            if (nativeVersion.startsWith("sfspotify $NATIVE_VERSION ")) null
            else { Log.e(TAG, "libsfspotify: '$nativeVersion', нужна $NATIVE_VERSION"); "Приёмник Spotify в этой сборке устарел" }
        }

        @Volatile private var nativeVersion = ""

        fun version(): String { unavailableReason; return nativeVersion }
    }
}
