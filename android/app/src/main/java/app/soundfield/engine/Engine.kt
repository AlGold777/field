package app.soundfield.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodec
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.util.Base64
import android.net.Uri
import androidx.core.content.ContextCompat
import app.soundfield.R
import app.soundfield.diagnostics.Telemetry
import app.soundfield.audio.AudioOutput
import app.soundfield.audio.AudioFileDecoder
import app.soundfield.audio.MicRecorder
import app.soundfield.audio.PreparedAudioFile
import app.soundfield.core.*
import app.soundfield.service.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Сердце приложения: жизненный цикл пары, роли Server/Point, управляющий (TCP) и медиа (UDP) контуры,
 * общая временная шкала, калибровка, диагностика.
 *
 * Потоки:
 *  • sf-engine — единственный поток, меняющий состояние сессии (всё через post {});
 *  • sf-udp    — приём UDP (PING/PONG/AUDIO/NACK), работает с @Volatile-снимками;
 *  • sf-tick   — каждые 5 мс: Server шлёт аудио вперёд на LEAD, Point шлёт PING и NACK;
 *  • sf-audio  — AudioOutput, вызывает ServerRenderer/PointRenderer.
 */
class Engine private constructor(private val app: Context) {

    private data class QueueItem(val id: String, val uri: Uri, val title: String, val artist: String) {
        fun ref() = PreparedAudioFile(id, uri, title, artist)
    }
    private data class IncomingFileTransfer(
        val id: String, val name: String, val size: Long, val temporaryFile: File,
        val output: BufferedOutputStream, val startedElapsedMs: Long, var received: Long = 0,
    )

    companion object {
        const val TAG = "SoundField"
        @Volatile private var inst: Engine? = null
        fun get(ctx: Context): Engine = inst ?: synchronized(this) {
            inst ?: Engine(ctx.applicationContext).also { inst = it; it.boot() }
        }
        private val rnd = SecureRandom()
        private fun hex(bytes: Int) = ByteArray(bytes).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        private const val TRANSFER_CHUNK_BYTES = 48 * 1024
        private const val MAX_TRANSFER_FILE_BYTES = 512L * 1024 * 1024
        private const val MAX_TRANSFER_BATCH_BYTES = 1024L * 1024 * 1024
        private val PROGRESSIVE_LOOKAHEAD_FRAMES =
            (SF.LEAD_NS + SF.PAUSE_LEAD_NS + 100_000_000L) * SF.SAMPLE_RATE / 1_000_000_000L
        private val PROGRESSIVE_RESUME_BUFFER_FRAMES = SF.SAMPLE_RATE.toLong()
        private val PROGRESSIVE_FADE_FRAMES = SF.SAMPLE_RATE / 10L
    }

    private val telemetry = Telemetry.get(app)

    // ------------------------------------------------------------------ инфраструктура
    private val ex = Executors.newSingleThreadScheduledExecutor { Thread(it, "sf-engine") }
    private val io = Executors.newCachedThreadPool { Thread(it, "sf-io") }
    private fun post(block: () -> Unit) {
        ex.execute { try { block() } catch (t: Throwable) { Log.e(TAG, "engine", t); telemetry.event("ENGINE_EXCEPTION", error = t) } }
    }
    private fun later(ms: Long, block: () -> Unit): ScheduledFuture<*> =
        ex.schedule({ try { block() } catch (t: Throwable) { Log.e(TAG, "engine", t); telemetry.event("ENGINE_EXCEPTION", error = t) } }, ms, TimeUnit.MILLISECONDS)

    private val prefs = app.getSharedPreferences("soundfield", Context.MODE_PRIVATE)
    val myId: String = prefs.getString("id", null) ?: hex(4).also { prefs.edit().putString("id", it).apply() }
    var myName: String = prefs.getString("name", null) ?: defaultName(); private set

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private fun ui(f: UiState.() -> UiState) = _ui.update { it.f() }
    private var msgCounter = 0
    private fun toast(text: String) { msgCounter++; val id = msgCounter; ui { copy(message = text, messageId = id) } }

    // ------------------------------------------------------------------ аудио
    val music: PcmSource = loadMusic()
    private val queue = mutableListOf<QueueItem>()
    private val queueIo = Executors.newSingleThreadExecutor { Thread(it, "sf-queue") }
    private val fileTransferIo = Executors.newSingleThreadExecutor { Thread(it, "sf-file-transfer") }
    private val fileTransferActive = AtomicBoolean(false)
    private val fileTransferCancelled = AtomicBoolean(false)
    private val fileTransferAcks = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private var incomingFileTransfer: IncomingFileTransfer? = null
    private var queueIndex = -1
    private var hasImportedQueue = false
    private var pendingTrackId: String? = null
    private var pendingShouldPlay = false
    @Volatile private var trackLoadGeneration = 0L
    private var currentQueueSource: AudioSource? = null
    @Volatile private var decodeProgress: Float? = null
    @Volatile private var decodeProgressTrackId: String? = null
    private var progressiveStopFrame = -1L
    private var progressivePausedFrame = -1L
    private var progressiveResumeAfterBuffer = true
    private var queueRevision = 0
    private var incomingQueueRevision = -1
    private var incomingQueueParts = 0
    private val incomingQueue = sortedMapOf<Int, List<QueueTrackUi>>()
    private val audio = AudioOutput(app)
    private val mic = MicRecorder(app)
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    // ------------------------------------------------------------------ сеть
    private lateinit var tcp: ServerSocket
    private lateinit var udp: DatagramSocket
    private lateinit var discovery: Discovery

    // ------------------------------------------------------------------ сессия
    @Volatile private var phase = Phase.IDLE
        set(value) { if (field != value) telemetry.event("PHASE", "$field -> $value"); field = value }
    @Volatile private var role = Role.SERVER
    @Volatile private var link: ControlLink? = null
    private var pending: ControlLink? = null
    private var pendingPeer: Peer? = null
    private var pendingKey = ""
    private var pendingTimeout: ScheduledFuture<*>? = null
    private var peerId = ""
    private var peerName = ""
    private var peerPlatform = ""
    @Volatile private var sessionId = 0
    private var record: PairRecord? = PairRecord.fromJson(prefs.getString("pair", null))
    private var reconnectTask: ScheduledFuture<*>? = null
    private var reconnectSince = 0L
    private var lastBrowseRestart = 0L
    private var manualResumeDeadline = 0L
    /** Идёт восстановление сохранённой пары с экрана «Знакомая пара» (вручную или автоматически). */
    private var manualResumeActive = false
    private var manualResumeAuto = false
    private var resumeAttempt = 0
    /** Порт TCP-сервера второго телефона, полученный в invite/resume/accept, — для быстрого переподключения. */
    private var peerTcpPortHint = 0
    /** Пара должна восстанавливаться сама (после запуска/возврата в приложение), пока её явно не разорвали. */
    private var autoResume = prefs.getBoolean("autoResume", false)
    /** До какого момента (elapsedRealtime) в состоянии IDLE тихо пробуем восстановить знакомую пару. */
    private var autoResumeUntil = 0L
    private var lastAutoAttempt = 0L
    private var lastNetSignature: String? = null
    /** Приложение на экране. В фоне без пары Android замораживает процесс — такой телефон не должен быть виден. */
    @Volatile private var appVisible = true
    private var inviteRinging = false
    private val incomingLinks = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<ControlLink, Boolean>())
    private var lastTelemetry = 0L
    private val udpPackets = AtomicLong()
    private val udpInvalid = AtomicLong()
    private val udpOtherSession = AtomicLong()
    private val udpLastRx = AtomicLong()
    private val connLog = ArrayDeque<String>()
    private val logTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    // ------------------------------------------------------------------ Server
    @Volatile private var serverPlay = ServerPlayState(Timeline.NONE, music, Ch.L, 0.8f)
    private var epoch = 0
    private var mode = SourceMode.MUSIC
    private var source: AudioSource = music
    private var savedFrame = 0L
    private var savedPlaying = false
    private var volL = 1f
    private var volR = 1f
    private var master = 0.8f
    @Volatile private var pointChannel = Ch.R
    private var trimNs = 0L
    @Volatile private var pointUdp: InetSocketAddress? = null
    private val lastAudioRxAt = java.util.concurrent.atomic.AtomicLong(0)
    private var pairedAtMs = 0L
    private var lastAudioSessionResetMs = 0L
    private val sentAudioPackets = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var fileTransferToken = ""
    @Volatile private var serverFileTransferToken = ""
    private val serverRenderer = ServerRenderer { serverPlay }
    private var calibRunning = false
    private var pointClockReady = false
    private val resent = AtomicLong()
    private val sendLock = Any()
    private var sendSeq = 0
    private var sentEpoch = -1
    private var nextFrame = 0L
    private val cacheSeq = IntArray(1024) { -1 }
    private val cacheEpoch = IntArray(1024)
    private val cacheStart = LongArray(1024)

    // ------------------------------------------------------------------ Point
    private val clock = ClockSync()
    private val rx = PointReceiver(clock)
    @Volatile private var pointParams = PointParams(0.8f, 0, 0)
    private val pointRenderer = PointRenderer(rx, clock) { pointParams }
    @Volatile private var serverUdp: InetSocketAddress? = null
    private var pingSeq = 0
    @Volatile private var pingBurst = 0
    private var lastPing = 0L
    private var lastRecv = 0L
    private var lastLost = 0L

    // ================================================================== запуск
    private fun boot() {
        AudioFileDecoder.clearCache(app)
        refreshPlaylists()
        // Стараемся занять тот же TCP-порт, что и в прошлый раз: второй телефон помнит его и подключается напрямую.
        tcp = try { ServerSocket(prefs.getInt("tcpPort", 0)) } catch (e: Exception) { telemetry.event("TCP_LISTEN_FALLBACK", error = e); ServerSocket(0) }
        prefs.edit().putInt("tcpPort", tcp.localPort).apply()
        udp = DatagramSocket(0)
        discovery = Discovery(app, myId) { peers -> ui { copy(nearby = peers) } }
        Thread({ acceptLoop() }, "sf-accept").apply { isDaemon = true; start() }
        Thread({ udpLoop() }, "sf-udp").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
        Thread({ tickLoop() }, "sf-tick").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
        discovery.advertise(tcp.localPort, myName)
        discovery.startBrowse()
        ex.scheduleAtFixedRate({ try { heartbeat() } catch (t: Throwable) { Log.e(TAG, "hb", t); telemetry.event("HEARTBEAT_EXCEPTION", error = t) } }, 1, 1, TimeUnit.SECONDS)
        ex.scheduleAtFixedRate({ try { serverTick() } catch (t: Throwable) { Log.e(TAG, "tick", t); telemetry.limited("ENGINE_TICK_EXCEPTION", error = t) } }, 100, 100, TimeUnit.MILLISECONDS)
        ui { copy(myId = myId, myName = myName, remembered = record, durationMs = music.totalFrames * 1000 / SF.SAMPLE_RATE,
            title = music.title, artist = music.artist, queueExpanded = prefs.getBoolean("queueExpanded", false)) }
        watchNetwork()
        log("Запуск: TCP ${tcp.localPort}, UDP ${udp.localPort}")
        if (autoResume && record != null) post { startAutoResume() }
    }

    /** Смена сети Wi‑Fi / IP (выход из сна, переподключение к роутеру) — сразу обновляем рекламу и пробуем связь. */
    private fun watchNetwork() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { telemetry.event("WIFI_AVAILABLE", "network=$network") }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    telemetry.event("WIFI_CAPABILITIES", "network=$network internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
                }
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    val sig = lp.linkAddresses.joinToString { it.address.hostAddress ?: "" }
                    telemetry.event("WIFI_ADDRESSES", "network=$network interface=${lp.interfaceName} addresses=$sig")
                    post {
                        val prev = lastNetSignature
                        lastNetSignature = sig
                        if (prev != null && prev != sig) onNetworkChanged()
                    }
                }
                override fun onLost(network: Network) { post { lastNetSignature = ""; log("Wi‑Fi потерян") } }
            })
        } catch (e: Exception) { Log.w(TAG, "network callback", e); telemetry.event("NETWORK_CALLBACK_FAILED", error = e) }
    }

    private fun onNetworkChanged() {
        log("Сеть изменилась")
        if (discovery.advertising || phase != Phase.IDLE) discovery.advertise(tcp.localPort, myName)
        if (discovery.browsing) { lastBrowseRestart = SystemClock.elapsedRealtime(); discovery.restartBrowse() }
        when (phase) {
            Phase.RECONNECTING -> if (pending == null) scheduleReconnect(200)
            Phase.PAIRED -> {
                // UDP-адрес второго телефона мог измениться вместе с сетью.
                // Обновляем из TCP-соединения, которое ядро Linux переключает автоматически.
                val l = link
                if (l != null && l.isOpen) {
                    val addr = l.remoteAddress
                    if (role == Role.SERVER) {
                        val old = pointUdp
                        if (old != null && old.address != addr)
                            pointUdp = InetSocketAddress(addr, old.port)
                    } else {
                        val old = serverUdp
                        if (old != null && old.address != addr)
                            serverUdp = InetSocketAddress(addr, old.port)
                    }
                }
            }
            else -> {}
        }
    }

    private fun log(text: String) {
        Log.i(TAG, text)
        telemetry.event("CONNECTION", "phase=$phase role=$role visible=$appVisible $text")
        val line = "${logTime.format(java.util.Date())}  $text"
        synchronized(connLog) { connLog.addLast(line); while (connLog.size > 60) connLog.removeFirst() }
        val snapshot = synchronized(connLog) { connLog.toList() }
        ui { copy(connLog = snapshot) }
    }

    /**
     * Тихое восстановление: экран остаётся обычным (можно создать новую пару), а в фоне раз в 3 с идёт
     * попытка resume к знакомому телефону. Модальный диалог здесь не используется — он блокировал
     * новые приглашения и «зацикливался» при каждом возврате в приложение.
     */
    private fun startAutoResume() {
        if (!autoResume || record == null || phase != Phase.IDLE) return
        autoResumeUntil = SystemClock.elapsedRealtime() + 30_000
        lastAutoAttempt = 0; resumeAttempt = 0
        if (!discovery.browsing) discovery.startBrowse()
        log("Фоновое восстановление пары с «${record?.peerName}»")
        ui { copy(autoResuming = true) }
        autoResumeTick()
    }

    private fun stopAutoResume() {
        autoResumeUntil = 0
        dropAutoPending()
        ui { copy(autoResuming = false) }
    }

    private fun autoResumeTick() {
        if (autoResumeUntil == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (!autoResume || record == null || now > autoResumeUntil) {
            if (phase == Phase.IDLE) log("Фоновое восстановление: «${record?.peerName}» не ответил")
            autoResumeUntil = 0; ui { copy(autoResuming = false) }; return
        }
        if (phase != Phase.IDLE || pending != null || now - lastAutoAttempt < 3_000) return
        val rec = record ?: return
        val p = resumeTarget(rec) ?: return
        lastAutoAttempt = now
        connectOutgoing(p, "resume-auto", resumeJson(rec), 3_000)
    }

    private fun dropAutoPending() {
        val p = pending ?: return
        if (p.tag != "resume-auto") return
        pending = null; p.tag = "dead"; p.close()
    }

    private fun setAutoResume(on: Boolean) {
        if (autoResume == on) return
        autoResume = on; prefs.edit().putBoolean("autoResume", on).apply()
    }

    /**
     * Запрашивает исключение из Doze (battery optimization) — без него Xiaomi/Samsung/Huawei замораживают
     * сокеты через 1–5 мин при выключенном экране. Запрос показывается один раз, при первом создании пары.
     */
    private fun requestDozeExemption() {
        val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(app.packageName)) return
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:${app.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) {
            telemetry.event("DOZE_REQUEST_FAILED", error = e)
        }
    }

    private fun defaultName(): String {
        val n = try { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        return (n ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}").take(40)
    }

    private fun loadMusic(): PcmSource {
        val bytes = app.resources.openRawResource(R.raw.test_track).use { it.readBytes() }
        val d = Wav.decode(bytes)
        return PcmSource(d.pcm, "Тестовый трек SoundField", "")
    }

    private fun acceptLoop() {
        var failCount = 0
        while (true) {
            val s = try {
                val sock = tcp.accept()
                failCount = 0
                sock
            } catch (e: Exception) {
                failCount++
                telemetry.limited("TCP_ACCEPT_FAILED", "fails=$failCount", e)
                val backoffMs = minOf(200L * (1L shl minOf(failCount, 4)), 3000L)
                SystemClock.sleep(backoffMs)
                // Если ServerSocket закрыт или повреждён, пробуем пересоздать
                if (tcp.isClosed) {
                    try {
                        val savedPort = prefs.getInt("tcpPort", 0)
                        tcp = ServerSocket(savedPort)
                        prefs.edit().putInt("tcpPort", tcp.localPort).apply()
                        telemetry.event("TCP_SERVER_RECREATED", "port=${tcp.localPort}")
                    } catch (ex: Exception) {
                        telemetry.limited("TCP_RECREATE_FAILED", error = ex)
                    }
                }
                continue
            }
            // Защита от исчерпания памяти и потоков: ограничиваем число одновременных необработанных подключений
            if (incomingLinks.size >= 5) {
                telemetry.limited("TCP_INCOMING_LIMIT_REACHED", "active=${incomingLinks.size}")
                try { s.close() } catch (_: Exception) {}
                continue
            }
            post { onIncomingSocket(s) }
        }
    }

    private fun telemetryState(): String {
        val now = SystemClock.elapsedRealtime()
        val state = ui.value
        return "phase=$phase role=$role visible=$appVisible peer=$peerId " +
            "tcpPort=${if (::tcp.isInitialized) tcp.localPort else 0} udpPort=${if (::udp.isInitialized) udp.localPort else 0} " +
            "link=${link?.id} lastTcpRxAgoMs=${link?.let { now - it.lastRx }} pending=${pending?.id}:${pending?.tag} " +
            "autoResume=$autoResume manualResume=$manualResumeActive attempt=$resumeAttempt " +
            "udpPackets=${udpPackets.get()} udpInvalid=${udpInvalid.get()} udpOtherSession=${udpOtherSession.get()} " +
            "udpLastRxAgoMs=${udpLastRx.get().takeIf { it > 0 }?.let { now - it }} " +
            "audioRunning=${audio.isRunning} playing=${state.playing} " +
            "serverDiag=${state.serverDiag.toJson("sdiag")} pointDiag=${state.pointDiag.toJson("diag")}"
    }

    fun telemetryPermissionDenied() = post {
        telemetry.event("EXPORT_PERMISSION_DENIED")
        toast("Для сохранения в Download разрешите доступ к файлам")
    }

    fun exportTelemetry() = post {
        if (ui.value.telemetrySaving) return@post
        ui { copy(telemetrySaving = true, telemetryFile = null) }
        val snapshot = buildString {
            appendLine(telemetryState())
            appendLine("protocol=${SF.PROTOCOL_VERSION} myId=$myId advertising=${discovery.advertising} browsing=${discovery.browsing}")
            appendLine("rememberedPeer=${record?.peerId} lastHost=${record?.lastHost} lastPort=${record?.lastPort}")
            ui.value.nearby.forEach { appendLine("nearby id=${it.id} platform=${it.platform} address=${it.host?.hostAddress}:${it.port} ringing=${it.supportsRinging}") }
            try {
                val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                appendLine("activeNetwork=$active wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} vpn=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
                val power = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                appendLine("interactive=${power.isInteractive} powerSave=${power.isPowerSaveMode} deviceIdle=${power.isDeviceIdleMode}")
            } catch (e: Exception) { appendLine("networkSnapshotError=${e.javaClass.simpleName}") }
        }
        telemetry.export(snapshot) { result -> post {
            ui { copy(telemetrySaving = false, telemetryFile = result.getOrNull()) }
            result.fold({ toast("Телеметрия сохранена: $it") }, { toast("Не удалось сохранить телеметрию: ${it.message ?: it.javaClass.simpleName}") })
        } }
    }

    // ================================================================== публичный API (любой поток)
    fun invite(p: Peer) = post { doInvite(p) }
    fun cancelInvite() = post { doCancelPending() }
    fun acceptIncoming() = post { doAccept() }
    fun rejectIncoming() = post { doReject() }
    fun resumeRemembered() = post { doManualResume() }
    fun forgetPair() = post { record = null; setAutoResume(false); stopAutoResume(); prefs.edit().remove("pair").apply(); ui { copy(remembered = null) } }
    fun disconnect() = post { setAutoResume(false); goIdle(sendBye = true, reason = "Пара разорвана") }

    /**
     * Приложение ушло с экрана. Без пары у SoundField нет foreground-сервиса, и Android вскоре замораживает
     * процесс: TCP-соединения ещё принимаются ядром, но никто не отвечает. Второй телефон видел такой телефон
     * в списке и ждал ответа «бесконечно». Поэтому без пары в фоне снимаем публикацию и поиск; вернёмся — включим.
     */
    fun onBackground() = post {
        telemetry.event("APP_BACKGROUND", "phase=$phase")
        appVisible = false
        if (role == Role.POINT) cancelPointFileTransfer()
        if (phase == Phase.IDLE) hideWhileBackground()
    }

    private fun hideWhileBackground() {
        stopAutoResume()
        if (discovery.advertising) { discovery.unadvertise(); log("Приложение в фоне без пары — телефон скрыт из поиска") }
        discovery.stopBrowse()
    }

    /** Приложение снова на экране (разблокировка, возврат из другого приложения). */
    fun onForeground() = post {
        telemetry.event("APP_FOREGROUND", "phase=$phase")
        appVisible = true
        if (!discovery.advertising) { discovery.advertise(tcp.localPort, myName); log("Телефон снова виден в сети") }
        when (phase) {
            Phase.IDLE -> {
                if (!discovery.browsing) discovery.startBrowse()
                if (autoResume && record != null && autoResumeUntil == 0L) startAutoResume()
            }
            Phase.RECONNECTING -> if (pending == null) { log("Возврат в приложение — переподключаемся сразу"); scheduleReconnect(0) }
            Phase.PAIRED -> {
                // Соединение могло «умереть» во сне, а таймаут ещё не истёк — не ждём его.
                // Порог совпадает с LINK_TIMEOUT_MS: Wi‑Fi после сна просыпается за 2–4 с, и 4 с давали ложные разрывы.
                val l = link
                if (l != null && SystemClock.elapsedRealtime() - l.lastRx > SF.LINK_TIMEOUT_MS) {
                    log("Связь молчит ${(SystemClock.elapsedRealtime() - l.lastRx) / 1000} с — переподключаемся"); l.close()
                }
            }
            else -> {}
        }
    }
    fun rename(name: String) = post {
        val n = name.trim().take(40).ifEmpty { defaultName() }
        myName = n; prefs.edit().putString("name", n).apply()
        discovery.advertise(tcp.localPort, n); ui { copy(myName = n) }
        link?.send(JSONObject().put("t", "name").put("name", n))
    }

    fun togglePlay() = cmd("toggle")
    fun play() = cmd("play")
    fun pause() = cmd("pause")
    fun seekMs(ms: Long) = cmd("seek") { put("ms", ms) }
    fun skipMs(delta: Long) = cmd("skip") { put("ms", delta) }
    fun swapChannels() = cmd("swap")
    fun setTrimMs(ms: Double) = cmd("trim") { put("ms", ms) }
    fun calibrate() = cmd("calibrate")
    fun measure() = cmd("measure")
    fun metronome(on: Boolean) = cmd("metronome") { put("on", on) }
    fun channelTest() = cmd("lrtest")
    fun nextTrack() = cmd("next")
    fun previousTrack() = cmd("previous")
    fun selectTrack(id: String) = cmd("select") { put("id", id) }
    /** Удалить треки из очереди (с Server или Point). */
    fun removeTracks(ids: Collection<String>) {
        if (ids.isEmpty()) return
        cmd("remove") { put("ids", org.json.JSONArray(ids.toList())) }
    }
    fun undoRemove() = cmd("undoRemove")
    fun savePlaylist(name: String) = post { if (role == Role.SERVER) doSavePlaylist(name) else toast("Плейлисты хранятся на телефоне Server") }
    fun openPlaylist(id: String, append: Boolean) = post {
        if (role == Role.SERVER) doOpenPlaylist(id, append) else toast("Плейлисты хранятся на телефоне Server")
    }
    fun renamePlaylist(id: String, name: String) = post { doRenamePlaylist(id, name) }
    fun deletePlaylist(id: String) = post { doDeletePlaylist(id) }
    fun setQueueExpanded(expanded: Boolean) = post {
        prefs.edit().putBoolean("queueExpanded", expanded).apply()
        ui { copy(queueExpanded = expanded) }
    }
    fun requestTrackPicker() {
        post {
            if (calibRunning) { toast("Сначала завершите калибровку"); return@post }
            if (role == Role.SERVER || (role == Role.POINT && peerPlatform.equals("android", true))) {
                ui { copy(filePickerRequest = filePickerRequest + 1) }
            } else if (role == Role.POINT) {
                toast("Отправка файлов доступна, только если оба телефона — Android")
            } else toast("Сначала подключите второй телефон")
        }
    }

    /** Decode picked files (or all audio files under a picked folder), then add them to the Server queue. */
    fun addAudioFiles(uris: List<Uri>, deleteIfImportFails: List<File> = emptyList()) {
        if (uris.isEmpty()) return
        post {
            if (calibRunning || (phase != Phase.PAIRED && phase != Phase.RECONNECTING)) {
                deleteIfImportFails.forEach { it.delete() }
                toast("Добавление музыки сейчас недоступно"); return@post
            }
            if (role == Role.POINT) {
                if (!peerPlatform.equals("android", true)) {
                    toast("Передача файлов с Point поддерживается, если оба телефона — Android"); return@post
                }
                beginPointFileTransfer(uris)
                return@post
            }
            ui { copy(loadingTracks = true) }
            queueIo.execute {
                val candidates = mutableListOf<Pair<Uri, String>>()
                uris.forEach { persistReadGrant(it) }   // доступ сохраняется после перезапуска — нужен для плейлистов
                uris.forEach { uri ->
                    if (DocumentsContract.isTreeUri(uri)) candidates += listAudioDocuments(uri)
                    else candidates += uri to displayName(uri)
                }
                val prepared = candidates.mapNotNull { (uri, name) ->
                    try { AudioFileDecoder.prepare(app, uri, name) }
                    catch (e: Exception) { Log.w(TAG, "Could not decode $name", e); null }
                }
                post {
                    if (role != Role.SERVER || (phase != Phase.PAIRED && phase != Phase.RECONNECTING) || calibRunning) {
                        deleteIfImportFails.forEach { it.delete() }
                        ui { copy(loadingTracks = false, fileTransferText = "") }
                        return@post
                    }
                    if (prepared.isEmpty()) {
                        deleteIfImportFails.forEach { it.delete() }
                        ui { copy(loadingTracks = false, fileTransferText = "") }
                        toast("Не удалось открыть аудиофайлы")
                        return@post
                    }
                    val isFirstImport = !hasImportedQueue
                    if (isFirstImport) {
                        deleteQueueFiles(queue.toList())
                        queue.clear()
                        currentQueueSource = null
                        hasImportedQueue = true
                        queueIndex = -1
                    }
                    queue.addAll(prepared.map { QueueItem(it.id, it.uri, it.title, it.artist) })
                    ui { copy(fileTransferText = "") }
                    if (isFirstImport) releaseUnusedGrants()
                    val count = prepared.size
                    val failedCount = candidates.size - count
                    ui { copy(queueTracks = queue.map { QueueTrackUi(it.id, it.title, it.artist) }) }
                    publishQueue()
                    if (isFirstImport) {
                        if (serverPlay.timeline.playing) { cancelScheduledPause(); newEpoch(false, posFrame(), System.nanoTime()) }
                        selectTrack(0, forcePaused = true)
                    } else {
                        ui { copy(loadingTracks = false) }
                        publishState()
                    }
                    toast(if (failedCount == 0) "Добавлено в очередь: $count ${if (count == 1) "трек" else "треков"}"
                        else "Добавлено: $count, пропущено файлов: $failedCount")
                }
            }
        }
    }

    fun cancelFileTransfer() = post {
        if (role == Role.POINT) cancelPointFileTransfer()
    }

    private data class TransferCandidate(val uri: Uri, val name: String, val size: Long)

    private fun beginPointFileTransfer(uris: List<Uri>) {
        if (!fileTransferActive.compareAndSet(false, true)) { toast("Передача файлов уже идёт"); return }
        val activeLink = link
        if (activeLink == null || !activeLink.isOpen) {
            telemetry.event("FILE_TRANSFER_REJECTED", "direction=send reason=no_link")
            fileTransferActive.set(false); toast("Нет соединения с Server — повторите после переподключения"); return
        }
        if (serverFileTransferToken.isBlank()) {
            telemetry.event("FILE_TRANSFER_REJECTED", "direction=send reason=missing_server_token")
            fileTransferActive.set(false); toast("Для передачи файлов обновите SoundField до версии 1.4.0 на обоих Android-телефонах"); return
        }
        fileTransferCancelled.set(false)
        val requestedAt = SystemClock.elapsedRealtime()
        telemetry.event("FILE_TRANSFER_PREPARING", "direction=send selections=${uris.size}")
        ui { copy(fileTransferText = "Подготовка отправки файлов…") }
        fileTransferIo.execute {
            var currentFileId: String? = null
            var totalFiles = 0
            var totalBytes = 0L
            var batchSent = 0L
            var currentFileIndex = 0
            var batchStartedAt = requestedAt
            var fileStartedAt = requestedAt
            var transferStarted = false
            try {
                val candidates = mutableListOf<Pair<Uri, String>>()
                uris.forEach { uri ->
                    if (DocumentsContract.isTreeUri(uri)) candidates += listAudioDocuments(uri)
                    else candidates += uri to displayName(uri)
                }
                val files = candidates.map { (uri, name) ->
                    val size = fileSize(uri)
                    require(name.substringAfterLast('.', "").lowercase() in audioExtensions) { "Не аудиофайл: $name" }
                    require(size in 1..MAX_TRANSFER_FILE_BYTES) { "Файл «$name» пуст или больше 512 МБ" }
                    TransferCandidate(uri, name, size)
                }
                require(files.isNotEmpty()) { "В выбранной папке нет поддерживаемых аудиофайлов" }
                require(files.sumOf { it.size } <= MAX_TRANSFER_BATCH_BYTES) { "Размер выбранных файлов превышает 1 ГБ" }
                val total = files.sumOf { it.size }
                totalFiles = files.size
                totalBytes = total
                batchStartedAt = SystemClock.elapsedRealtime()
                transferStarted = true
                telemetry.event("FILE_TRANSFER_START", "direction=send files=$totalFiles bytes=$totalBytes preparationMs=${batchStartedAt - requestedAt}")
                val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
                files.forEachIndexed { fileIndex, file ->
                    checkTransferStillActive(activeLink)
                    currentFileIndex = fileIndex + 1
                    fileStartedAt = SystemClock.elapsedRealtime()
                    val id = java.util.UUID.randomUUID().toString()
                    currentFileId = id
                    telemetry.event("FILE_TRANSFER_FILE_START", "direction=send id=$id file=$currentFileIndex/$totalFiles bytes=${file.size}")
                    val started = awaitFileReply(activeLink, id, JSONObject()
                        .put("t", "file-start").put("id", id).put("name", file.name).put("size", file.size)
                        .put("token", serverFileTransferToken))
                    check(started.optLong("offset", -1) == 0L) { "Server не подтвердил начало передачи" }
                    app.contentResolver.openInputStream(file.uri).use { input ->
                        requireNotNull(input) { "Не удалось открыть «${file.name}»" }
                        var offset = 0L
                        while (true) {
                            checkTransferStillActive(activeLink)
                            val count = input.read(buffer)
                            if (count < 0) break
                            val chunk = Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)
                            val ack = awaitFileReply(activeLink, id, JSONObject()
                                .put("t", "file-chunk").put("id", id).put("offset", offset).put("data", chunk))
                            offset += count
                            check(ack.optLong("offset", -1) == offset) { "Server подтвердил неверный размер передачи" }
                            batchSent += count
                            telemetry.limited("FILE_TRANSFER_PROGRESS",
                                "direction=send id=$id file=$currentFileIndex/$totalFiles fileBytes=$offset/${file.size} batchBytes=$batchSent/$totalBytes")
                            post { if (fileTransferActive.get()) ui { copy(fileTransferText =
                                "Отправка на Server · ${(batchSent * 100 / total).toInt()}%") } }
                        }
                        check(offset == file.size) { "Размер «${file.name}» изменился во время передачи" }
                    }
                    val completed = awaitFileReply(activeLink, id, JSONObject().put("t", "file-end").put("id", id))
                    check(completed.optBoolean("complete")) { "Server не сохранил «${file.name}»" }
                    telemetry.event("FILE_TRANSFER_FILE_COMPLETE",
                        "direction=send id=$id file=$currentFileIndex/$totalFiles bytes=${file.size} elapsedMs=${SystemClock.elapsedRealtime() - fileStartedAt}")
                    currentFileId = null
                }
                telemetry.event("FILE_TRANSFER_COMPLETE",
                    "direction=send files=$totalFiles bytes=$totalBytes elapsedMs=${SystemClock.elapsedRealtime() - batchStartedAt}")
                post { ui { copy(fileTransferText = "") }; toast("Файлы отправлены на Server") }
            } catch (e: Exception) {
                currentFileId?.let { id -> if (activeLink.isOpen) activeLink.send(JSONObject().put("t", "file-cancel").put("id", id)) }
                val cancelled = fileTransferCancelled.get()
                val elapsed = SystemClock.elapsedRealtime() - if (transferStarted) batchStartedAt else requestedAt
                telemetry.event(if (cancelled) "FILE_TRANSFER_CANCELLED" else "FILE_TRANSFER_ABORTED",
                    "direction=send stage=${if (transferStarted) "transfer" else "prepare"} file=$currentFileIndex/$totalFiles bytes=$batchSent/$totalBytes elapsedMs=$elapsed errorType=${e.javaClass.simpleName}")
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Передачу файлов прервали"
                post { ui { copy(fileTransferText = "") }; toast(message) }
            } finally {
                fileTransferAcks.values.forEach { it.completeExceptionally(IllegalStateException("Передача завершена")) }
                fileTransferAcks.clear()
                fileTransferActive.set(false)
            }
        }
    }

    private fun checkTransferStillActive(activeLink: ControlLink) {
        check(!fileTransferCancelled.get()) { "Передачу файлов отменили" }
        check(activeLink.isOpen && link === activeLink && phase == Phase.PAIRED && role == Role.POINT) {
            "Связь с Server потеряна — передача отменена"
        }
    }

    private fun awaitFileReply(activeLink: ControlLink, id: String, message: JSONObject): JSONObject {
        val waiter = CompletableFuture<JSONObject>()
        check(fileTransferAcks.putIfAbsent(id, waiter) == null) { "Передача уже ожидает ответ Server" }
        try {
            check(activeLink.isOpen) { "Нет соединения с Server" }
            activeLink.send(message)
            val reply = waiter.get(30, TimeUnit.SECONDS)
            check(reply.optString("t") == "file-ack") { reply.optString("message", "Server отклонил файл") }
            return reply
        } finally { fileTransferAcks.remove(id, waiter) }
    }

    private fun fileSize(uri: Uri): Long {
        try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it >= 0 }?.let { return it }
            }
        } catch (_: Exception) {}
        return try { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
        catch (_: Exception) { -1L }
    }

    private fun abortIncomingFileTransfer(reason: String? = null) {
        val transfer = incomingFileTransfer ?: return
        incomingFileTransfer = null
        try { transfer.output.close() } catch (_: Exception) {}
        transfer.temporaryFile.delete()
        ui { copy(fileTransferText = "") }
        if (reason != null) telemetry.event("FILE_TRANSFER_ABORTED",
            "direction=receive id=${transfer.id} bytes=${transfer.received}/${transfer.size} elapsedMs=${SystemClock.elapsedRealtime() - transfer.startedElapsedMs} reason=${reason.take(100)}")
    }

    private fun handleIncomingFileStart(o: JSONObject) {
        val id = o.optString("id")
        val name = File(o.optString("name")).name.take(160)
        val size = o.optLong("size", -1)
        if (role != Role.SERVER || calibRunning || !peerPlatform.equals("android", true) || o.optString("token") != fileTransferToken) {
            sendFileError(id, "Передача не поддерживается этой парой"); return
        }
        if (incomingFileTransfer != null) { sendFileError(id, "Server уже принимает другой файл"); return }
        if (id.isBlank() || name.substringAfterLast('.', "").lowercase() !in audioExtensions ||
            size !in 1..MAX_TRANSFER_FILE_BYTES) {
            sendFileError(id, "Неподдерживаемый или слишком большой аудиофайл"); return
        }
        val directory = File(app.filesDir, "audio_received").apply { mkdirs() }
        val tmp = File(directory, "$id.part")
        try {
            val output = BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)
            val startedElapsedMs = SystemClock.elapsedRealtime()
            incomingFileTransfer = IncomingFileTransfer(id, name, size, tmp, output, startedElapsedMs)
            telemetry.event("FILE_TRANSFER_START", "direction=receive id=$id bytes=$size extension=${name.substringAfterLast('.', "").lowercase().take(8)}")
            ui { copy(fileTransferText = "Получение от Point · 0%") }
            link?.send(JSONObject().put("t", "file-ack").put("id", id).put("offset", 0))
        } catch (e: Exception) { sendFileError(id, "Не удалось создать файл на Server") }
    }

    private fun handleIncomingFileChunk(o: JSONObject) {
        val transfer = incomingFileTransfer
        val id = o.optString("id")
        if (transfer == null || transfer.id != id) { sendFileError(id, "Передача уже завершена"); return }
        try {
            val offset = o.optLong("offset", -1)
            require(offset == transfer.received) { "Нарушен порядок блоков файла" }
            val bytes = Base64.decode(o.getString("data"), Base64.NO_WRAP)
            require(bytes.isNotEmpty() && bytes.size <= TRANSFER_CHUNK_BYTES && transfer.received + bytes.size <= transfer.size) {
                "Неверный размер блока файла"
            }
            transfer.output.write(bytes)
            transfer.received += bytes.size
            telemetry.limited("FILE_TRANSFER_PROGRESS",
                "direction=receive id=$id bytes=${transfer.received}/${transfer.size}")
            ui { copy(fileTransferText = "Получение от Point · ${(transfer.received * 100 / transfer.size).toInt()}%") }
            link?.send(JSONObject().put("t", "file-ack").put("id", id).put("offset", transfer.received))
        } catch (e: Exception) {
            abortIncomingFileTransfer("${transfer.received}/${transfer.size}")
            sendFileError(id, e.message ?: "Ошибка приёма файла")
        }
    }

    private fun handleIncomingFileEnd(o: JSONObject) {
        val transfer = incomingFileTransfer
        val id = o.optString("id")
        if (transfer == null || transfer.id != id) {
            sendFileError(id, "Передача уже завершена"); return
        }
        if (transfer.received != transfer.size) {
            abortIncomingFileTransfer("неполный файл")
            sendFileError(id, "Файл передан не полностью"); return
        }
        try {
            transfer.output.flush(); transfer.output.close()
            val ext = transfer.name.substringAfterLast('.', "audio").lowercase().take(8)
            val target = File(transfer.temporaryFile.parentFile, "${java.util.UUID.randomUUID()}.$ext")
            check(transfer.temporaryFile.renameTo(target)) { "Не удалось сохранить принятый файл" }
            incomingFileTransfer = null
            telemetry.event("FILE_TRANSFER_COMPLETE",
                "direction=receive id=$id bytes=${transfer.size} elapsedMs=${SystemClock.elapsedRealtime() - transfer.startedElapsedMs} extension=$ext")
            ui { copy(fileTransferText = "Подготовка аудиофайла…") }
            link?.send(JSONObject().put("t", "file-ack").put("id", id).put("offset", transfer.size).put("complete", true))
            addAudioFiles(listOf(Uri.fromFile(target)), deleteIfImportFails = listOf(target))
        } catch (e: Exception) {
            abortIncomingFileTransfer(e.message ?: "ошибка завершения")
            sendFileError(id, e.message ?: "Не удалось завершить передачу")
        }
    }

    private fun sendFileError(id: String, message: String) {
        link?.send(JSONObject().put("t", "file-error").put("id", id).put("message", message))
    }

    private fun cancelPointFileTransfer() {
        if (!fileTransferActive.get()) return
        fileTransferCancelled.set(true)
        fileTransferAcks.values.forEach { it.completeExceptionally(IllegalStateException("Передачу файлов отменили")) }
        ui { copy(fileTransferText = "") }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
        } catch (_: Exception) {}
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Аудиофайл"
    }

    private fun listAudioDocuments(tree: Uri): List<Pair<Uri, String>> {
        val result = mutableListOf<Pair<Uri, String>>()
        val visited = mutableSetOf<String>()
        val root = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { return emptyList() }
        fun walk(parentId: String, depth: Int) {
            if (depth > 32 || !visited.add(parentId)) return
            val children = mutableListOf<Triple<String, String, String?>>()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            try {
                app.contentResolver.query(childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        children += Triple(cursor.getString(0), cursor.getString(1) ?: "", cursor.getString(2))
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "Could not scan folder", e) }
            children.sortedBy { it.second.lowercase() }.forEach { (id, name, mime) ->
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, depth + 1)
                else if (mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in audioExtensions) {
                    result += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                }
            }
        }
        walk(root, 0)
        return result
    }

    private val audioExtensions = setOf("aac", "aif", "aiff", "alac", "amr", "flac", "m4a", "m4b", "mp3", "oga", "ogg", "opus", "wav", "wave", "weba", "wma")
    fun setVolume(ch: String, v: Float) {
        ui { when (ch) { "L" -> copy(volL = v); "R" -> copy(volR = v); else -> copy(master = v) } } // мгновенный отклик UI
        cmd("vol") { put("ch", ch); put("v", v.toDouble()) }
    }
    fun micPermissionGranted() = post { ui { copy(calib = calib.copy(needsMicPermission = false, resultText = "")) } }

    private fun cmd(a: String, extra: JSONObject.() -> Unit = {}) {
        val o = JSONObject().put("t", "cmd").put("a", a).apply(extra)
        post {
            if (!ui.value.inPair) return@post
            if (role == Role.SERVER) handleCmd(o) else link?.send(o) ?: toast("Нет связи с Server")
        }
    }

    /** Позиция воспроизведения для UI (мс). */
    fun positionMs(): Long {
        val now = System.nanoTime()
        val frame: Long; val total: Long
        if (role == Role.SERVER) {
            val st = serverPlay; frame = st.timeline.positionFrame(now); total = st.source.totalFrames
        } else {
            val tl = rx.timeline
            frame = if (clock.ready) tl.positionFrame(clock.toServer(now)) else tl.anchorFrame
            total = pointParams.totalFrames
        }
        return (frame.coerceIn(0L, maxOf(0L, total)) * 1000 / SF.SAMPLE_RATE)
    }

    // ================================================================== установка пары
    private fun inviteJson(key: String) = JSONObject().put("t", "invite").put("id", myId).put("name", myName)
        .put("platform", "android").put("proto", SF.PROTOCOL_VERSION).put("key", key).put("tcpPort", tcp.localPort)

    private fun doInvite(p: Peer) {
        if (phase != Phase.IDLE) return
        stopAutoResume()
        pendingKey = hex(16)
        phase = Phase.INVITING
        inviteRinging = false
        ui { copy(phase = Phase.INVITING, invitingName = p.name, resuming = false, inviteRinging = false) }
        log("Приглашение → «${p.name}» ${p.host?.hostAddress}:${p.port}")
        // Старые Android и iOS не объявляют ringing: отсутствие ответа не означает, что они спят.
        connectOutgoing(p, "invite-out", inviteJson(pendingKey), if (p.supportsRinging) 8_000 else 65_000)
    }

    private fun doManualResume(auto: Boolean = false) {
        val rec = record ?: return
        if (phase == Phase.IDLE) stopAutoResume()
        // Ручное действие пользователя перебивает фоновый auto-resume, даже если pending уже есть
        if (!auto) dropAutoPending()
        if (phase != Phase.IDLE || pending != null) return
        phase = Phase.INVITING
        manualResumeActive = true; manualResumeAuto = auto; resumeAttempt = 0
        ui { copy(phase = Phase.INVITING, invitingName = rec.peerName, resuming = true) }
        manualResumeDeadline = SystemClock.elapsedRealtime() + (if (auto) 20_000 else 15_000)
        discovery.startBrowse()
        log(if (auto) "Автовосстановление пары с «${rec.peerName}»" else "Подключение к знакомой паре «${rec.peerName}»")
        tryManualResume()
    }

    private fun tryManualResume() {
        if (!manualResumeActive || phase != Phase.INVITING || pending != null) return
        val rec = record ?: return
        if (SystemClock.elapsedRealtime() > manualResumeDeadline) {
            manualResumeActive = false
            phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }
            log("«${rec.peerName}» не ответил")
            toast(if (manualResumeAuto) "«${rec.peerName}» пока не отвечает. Откройте SoundField на втором телефоне — пара восстановится"
                  else "«${rec.peerName}» не найден. Откройте SoundField на втором телефоне в той же сети Wi‑Fi")
            return
        }
        val p = resumeTarget(rec)
        if (p != null) { connectOutgoing(p, "resume-out", resumeJson(rec), 4_000); return }
        later(500) { tryManualResume() }
    }

    /**
     * Куда стучаться при восстановлении пары: приоритет актуальному адресу из mDNS (если доступен),
     * с fallback на прямой адрес из сохранённого профиля.
     */
    private fun resumeTarget(rec: PairRecord): Peer? {
        resumeAttempt++
        val found = discovery.peer(rec.peerId)?.takeIf { it.host != null }
        val direct = rec.lastHost?.takeIf { rec.lastPort > 0 }?.let { h ->
            try { Peer(rec.peerId, rec.peerName, rec.peerPlatform, InetAddress.getByName(h), rec.lastPort) } catch (_: Exception) { null }
        }
        return when {
            found != null && direct == null -> found
            found == null && direct != null -> direct
            found != null && direct != null -> {
                // Если хост совпадает, но порт разный — mDNS-порт гарантированно актуальнее, чем сохранённый в кэше direct
                if (found.host == direct.host && found.port != direct.port) found
                else if (resumeAttempt % 2 == 1) found else direct
            }
            else -> null
        }
    }

    private fun resumeJson(rec: PairRecord) = JSONObject().put("t", "resume").put("id", myId).put("name", myName)
        .put("platform", "android").put("proto", SF.PROTOCOL_VERSION).put("key", rec.key).put("role", rec.myRole.wire)
        .put("tcpPort", tcp.localPort)

    private fun acceptJson(key: String, r: Role) = JSONObject().put("t", "accept").put("id", myId).put("name", myName)
        .put("platform", "android").put("key", key).put("role", r.wire).put("tcpPort", tcp.localPort)

    private fun connectOutgoing(p: Peer, tag: String, first: JSONObject, timeoutMs: Long) {
        val host = p.host ?: return onConnectFailed(tag)
        pendingPeer = p
        telemetry.event("TCP_CONNECT_START", "tag=$tag peer=${p.id} host=${host.hostAddress} port=${p.port} ringing=${p.supportsRinging} responseTimeoutMs=$timeoutMs")
        io.execute {
            val started = SystemClock.elapsedRealtime()
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, p.port), 2_500)
                telemetry.event("TCP_CONNECTED", "tag=$tag peer=${p.id} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                post {
                    val stillWanted = when (tag) {
                        "invite-out" -> phase == Phase.INVITING && pendingPeer?.id == p.id
                        "resume-auto" -> phase == Phase.IDLE && autoResumeUntil != 0L
                        else -> phase == Phase.RECONNECTING || (phase == Phase.INVITING && manualResumeActive)
                    }
                    if (!stillWanted || pending != null) { telemetry.event("TCP_CONNECT_DISCARDED", "tag=$tag phase=$phase"); try { s.close() } catch (_: Exception) {}; return@post }
                    val l = newLink(s); l.tag = tag
                    pending = l
                    l.send(first)
                    pendingTimeout?.cancel(false)
                    pendingTimeout = later(timeoutMs) { if (pending === l) l.close("response timeout ${timeoutMs}ms") }
                }
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) { }
                telemetry.event("TCP_CONNECT_FAILED", "tag=$tag peer=${p.id} host=${host.hostAddress}:${p.port} elapsedMs=${SystemClock.elapsedRealtime() - started}", e)
                post {
                    discovery.invalidatePeer(p.id)
                    log("Нет соединения с ${host.hostAddress}:${p.port} — ${e.message}")
                    onConnectFailed(tag)
                }
            }
        }
    }

    private fun onConnectFailed(tag: String) {
        when {
            tag == "invite-out" && phase == Phase.INVITING -> {
                phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }; toast("Не удалось подключиться к телефону")
            }
            tag == "resume-out" && phase == Phase.RECONNECTING -> scheduleReconnect(reconnectDelay())
            tag == "resume-out" && phase == Phase.INVITING -> later(700) { tryManualResume() }
        }
    }

    private fun newLink(s: Socket) = ControlLink(s,
        onMessage = { l, o -> post { onMessage(l, o) } },
        onClosed = { l -> post { onClosed(l) } }, telemetry = telemetry)

    private fun onIncomingSocket(s: Socket) {
        val l = newLink(s)
        l.tag = "new"
        incomingLinks.add(l)
        later(5_000) {
            if (l.tag == "new") {
                incomingLinks.remove(l)
                l.close("first message timeout 5000ms")
            }
        }
    }

    private fun doCancelPending() {
        telemetry.event("INVITE_CANCEL", "phase=$phase")
        manualResumeActive = false
        val l = pending
        pending = null
        l?.let { it.tag = "dead"; it.sendAndClose(JSONObject().put("t", "cancel")) }
        if (phase == Phase.INVITING || phase == Phase.INCOMING) { phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) } }
    }

    private fun doAccept() {
        val l = pending ?: return
        val p = pendingPeer ?: return
        if (phase != Phase.INCOMING) return
        telemetry.event("INVITE_ACCEPT", "peer=${p.id}")
        l.send(acceptJson(pendingKey, Role.POINT))
        peerTcpPortHint = p.port
        becomePaired(l, Role.POINT, p.id, p.name, p.platform, pendingKey, resumed = false)
    }

    private fun doReject() {
        telemetry.event("INVITE_REJECT", "phase=$phase")
        val l = pending ?: return
        pending = null; l.tag = "dead"
        l.sendAndClose(JSONObject().put("t", "reject").put("reason", "declined"))
        phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }
    }

    // ================================================================== входящие сообщения
    private fun onMessage(l: ControlLink, o: JSONObject) {
        when {
            l === link -> onPairedMessage(o)
            l === pending -> onPendingMessage(l, o)
            l.tag == "new" -> onFirstMessage(l, o)
        }
    }

    private fun onFirstMessage(l: ControlLink, o: JSONObject) {
        incomingLinks.remove(l)
        val pid = o.optString("id"); val pname = o.optString("name", pid); val pplat = o.optString("platform", "?")
        when (o.optString("t")) {
            "invite" -> {
                if (phase == Phase.IDLE) stopAutoResume()   // новое приглашение важнее фонового восстановления
                if (phase == Phase.IDLE && pending == null) {
                    l.tag = "incoming"; pending = l
                    pendingPeer = Peer(pid, pname, pplat, l.remoteAddress, o.optInt("tcpPort", 0))
                    pendingKey = o.optString("key")
                    phase = Phase.INCOMING
                    l.send(JSONObject().put("t", "ringing"))
                    log("Приглашение от «$pname»")
                    ui { copy(phase = Phase.INCOMING, incomingName = pname) }
                    pendingTimeout?.cancel(false)
                    pendingTimeout = later(60_000) { if (pending === l) { telemetry.event("INVITE_DECISION_TIMEOUT"); doReject() } }
                } else if (phase == Phase.INVITING && pendingPeer?.id == pid) {
                    // Встречные приглашения: уступает телефон с бо́льшим id (детерминированно на обеих сторонах).
                    if (myId > pid) {
                        pending?.let { it.tag = "dead"; it.close() }
                        l.tag = "incoming"; pending = l
                        pendingPeer = Peer(pid, pname, pplat, l.remoteAddress, o.optInt("tcpPort", 0))
                        pendingKey = o.optString("key")
                        phase = Phase.INCOMING
                        doAccept()
                    } else {
                        l.tag = "dead"; l.sendAndClose(JSONObject().put("t", "reject").put("reason", "crossed"))
                    }
                } else {
                    l.tag = "dead"; l.sendAndClose(JSONObject().put("t", "busy"))
                }
            }
            "resume" -> {
                val rec = record
                val ok = rec != null && rec.key == o.optString("key") && rec.peerId == pid &&
                    phase != Phase.INCOMING
                if (!ok) {
                    log("Отклонён resume от «$pname»: пара неизвестна")
                    l.tag = "dead"; l.sendAndClose(JSONObject().put("t", "reject").put("reason", "unknown")); return
                }
                // Оба телефона стучатся друг к другу одновременно — оставляем одно соединение детерминированно:
                // входящее принимает телефон с бо́льшим id (как и для встречных приглашений).
                val mine = pending
                if (mine != null && (mine.tag == "resume-out" || mine.tag == "resume-auto") && myId < pid) {
                    l.tag = "dead"; l.sendAndClose(JSONObject().put("t", "reject").put("reason", "crossed")); return
                }
                // старое (возможно, «мёртвое») соединение заменяется новым
                link?.let { old -> link = null; old.tag = "dead"; old.close() }
                pending?.let { p -> pending = null; p.tag = "dead"; p.close() }
                l.send(acceptJson(rec!!.key, rec.myRole))
                peerTcpPortHint = o.optInt("tcpPort", 0)
                log("Входящее восстановление от «$pname» (${l.remoteAddress.hostAddress})")
                becomePaired(l, rec.myRole, pid, pname, pplat, rec.key, resumed = true)
            }
            else -> { l.tag = "dead"; l.close() }
        }
    }

    private fun onPendingMessage(l: ControlLink, o: JSONObject) {
        val pid = o.optString("id"); val pname = o.optString("name", pid); val pplat = o.optString("platform", "?")
        when (o.optString("t")) {
            "accept" -> when (l.tag) {
                "invite-out" -> {
                    peerTcpPortHint = o.optInt("tcpPort", 0).takeIf { it > 0 } ?: (pendingPeer?.port ?: 0)
                    becomePaired(l, Role.SERVER, pid, pname, pplat, pendingKey, resumed = false)
                }
                "resume-out", "resume-auto" -> {
                    peerTcpPortHint = o.optInt("tcpPort", 0).takeIf { it > 0 } ?: (pendingPeer?.port ?: 0)
                    val their = Role.fromWire(o.optString("role"))
                    val mine = if (their == Role.SERVER) Role.POINT else Role.SERVER
                    becomePaired(l, mine, pid, pname, pplat, o.optString("key"), resumed = true)
                }
            }
            "reject", "busy" -> {
                val reason = o.optString("reason")
                val wasAuto = l.tag == "resume-auto"
                pending = null; l.tag = "dead"; l.close()
                if (wasAuto) {
                    log("Фоновое восстановление: ответ «${o.optString("t")}${if (reason.isNotEmpty()) ": $reason" else ""}»")
                    if (reason == "unknown") { setAutoResume(false); stopAutoResume() }   // второй телефон забыл пару
                    return
                }
                log("Ответ «${o.optString("t")}${if (reason.isNotEmpty()) ": $reason" else ""}»")
                when {
                    phase == Phase.INVITING && reason == "crossed" -> {
                        // ждём встречного accept от другой стороны; если он не придёт — попробуем снова
                        if (manualResumeActive) later(1_500) { tryManualResume() }
                    }
                    phase == Phase.INVITING && manualResumeActive && reason != "unknown" && o.optString("t") != "busy" ->
                        later(1_000) { tryManualResume() }
                    phase == Phase.INVITING -> {
                        manualResumeActive = false
                        if (reason == "unknown") setAutoResume(false)
                        phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }
                        toast(when {
                            o.optString("t") == "busy" -> "Телефон уже в другой паре"
                            reason == "unknown" -> "Второй телефон не помнит эту пару — создайте её заново"
                            else -> "Подключение отклонено"
                        })
                    }
                    phase == Phase.RECONNECTING -> scheduleReconnect(if (reason == "crossed") 1_500 else 2_000)
                }
            }
            "ringing" -> if (l.tag == "invite-out" && !inviteRinging) {
                inviteRinging = true
                ui { copy(inviteRinging = true) }
                pendingTimeout?.cancel(false)
                pendingTimeout = later(65_000) { if (pending === l) l.close("accept timeout 65000ms") }   // у второго диалог живёт 60 с
                log("«${pendingPeer?.name ?: "Второй телефон"}» получил приглашение, ждём подтверждения")
            }
            "cancel" -> if (l.tag == "incoming") {
                pending = null; l.tag = "dead"; l.close()
                phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }; toast("Приглашение отменено")
            }
        }
    }

    private fun onClosed(l: ControlLink) {
        incomingLinks.remove(l)
        telemetry.event("LINK_ENDED", "link=${l.id} tag=${l.tag} phase=$phase lastRxAgoMs=${SystemClock.elapsedRealtime() - l.lastRx}")
        if (l === link) { onLinkLost(); return }
        if (l === pending) {
            pending = null
            when (phase) {
                Phase.INCOMING -> { phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) } }
                Phase.INVITING -> if (l.tag == "resume-out") later(700) { tryManualResume() } else {
                    phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }
                    val who = ui.value.invitingName
                    log(if (inviteRinging) "«$who» не подтвердил приглашение" else "«$who» не ответил на приглашение")
                    toast(if (inviteRinging) "«$who» не подтвердил приглашение"
                          else "«$who» не отвечает. Откройте SoundField на втором телефоне (экран включён) и попробуйте снова")
                }
                Phase.RECONNECTING -> scheduleReconnect(reconnectDelay())
                else -> {}
            }
        }
    }

    // ================================================================== пара
    private fun becomePaired(l: ControlLink, r: Role, pid: String, pname: String, pplat: String, key: String, resumed: Boolean) {
        pendingTimeout?.cancel(false)
        reconnectTask?.cancel(false)
        if (pending === l) pending = null
        pending?.let { it.tag = "dead"; it.close() }; pending = null
        manualResumeActive = false
        autoResumeUntil = 0
        // «Продолжение» — пара та же, меняется только соединение (после обрыва, или второй телефон заметил
        // обрыв раньше нас и прислал resume, пока мы ещё считали связь живой). Музыка при этом не сбрасывается.
        val continuing = (phase == Phase.RECONNECTING || phase == Phase.PAIRED) && role == r && peerId == pid
        l.tag = "paired"
        link = l
        role = r; peerId = pid; peerName = pname; peerPlatform = pplat
        phase = Phase.PAIRED
        pairedAtMs = SystemClock.elapsedRealtime()
        lastAudioSessionResetMs = pairedAtMs
        udpLastRx.set(0)
        lastAudioRxAt.set(0)

        val old = record
        val host = l.remoteAddress.hostAddress
        val port = if (peerTcpPortHint > 0) peerTcpPortHint else if (old?.key == key) old.lastPort else 0
        peerTcpPortHint = 0
        record = if (old != null && old.key == key) old.copy(peerName = pname, peerPlatform = pplat, myRole = r, lastHost = host, lastPort = port)
                 else PairRecord(key, pid, pname, pplat, r, lastHost = host, lastPort = port)
        persistRecord()
        setAutoResume(true)
        requestDozeExemption()
        if (!discovery.advertising) discovery.advertise(tcp.localPort, myName)
        log("Пара ${if (continuing) "продолжена" else if (resumed) "восстановлена" else "создана"}: «$pname» $host:$port, роль ${r.wire}")
        discovery.stopBrowse()

        if (r == Role.SERVER) {
            record?.let { rec ->
                if (!continuing) { pointChannel = rec.pointChannel; trimNs = (rec.trimMs * 1e6).toLong()
                    volL = rec.volL; volR = rec.volR; master = rec.master }
            }
            sessionId = rnd.nextInt().let { if (it == 0) 1 else it }
            pointUdp = null; pointClockReady = false
            if (!continuing) {
                mode = SourceMode.MUSIC
                source = if (queueIndex in queue.indices) currentQueueSource ?: music else music
                newEpoch(false, 0, System.nanoTime())
                if (queueIndex in queue.indices && currentQueueSource == null) selectTrack(queueIndex, forcePaused = true)
            }
            else rebuildPlayState()
            audio.source = serverRenderer
            fileTransferToken = hex(24)
            sendSession(l)
            sendTimeline()
            publishState()
            publishQueue()
        } else {
            sessionId = 0; serverUdp = null
            serverFileTransferToken = ""
            clock.reset(); rx.reset()
            audio.source = pointRenderer
        }
        audio.start()
        try { PlaybackService.start(app) } catch (e: Exception) { Log.w(TAG, "fgs", e); telemetry.event("FOREGROUND_SERVICE_FAILED", error = e) }
        ui { copy(phase = Phase.PAIRED, role = r, peerName = pname, peerPlatform = pplat, linkOk = true, remembered = record,
            autoResuming = false, audioMissing = false, calib = CalibUi()) }
        PlaybackService.refresh(app)
        toast(if (resumed) "Пара восстановлена" else "Стереопара создана")
    }

    /** Экспоненциальный backoff: 1с → 2с → 4с → 8с → 15с (потолок). */
    private fun reconnectDelay(): Long {
        val base = 1_000L
        val exp = minOf(resumeAttempt / 2, 4)
        return minOf(base shl exp, 15_000L)
    }

    private fun onLinkLost() {
        cancelPointFileTransfer()
        abortIncomingFileTransfer("control link lost")
        link = null
        if (phase != Phase.PAIRED) return
        phase = Phase.RECONNECTING
        pairedAtMs = 0
        ui { copy(phase = Phase.RECONNECTING, linkOk = false, audioMissing = false) }
        log("Связь с «$peerName» потеряна")
        reconnectSince = SystemClock.elapsedRealtime()
        resumeAttempt = 0
        // NSD на Samsung/Xiaomi сбоит при быстром stopBrowse→startBrowse — пауза 500 мс.
        later(500) { if (phase == Phase.RECONNECTING) discovery.startBrowse() }
        // Переподключаются ОБА телефона: кто первым достучится, тот и восстановит пару (встречные попытки
        // разрешаются по id). Раньше это делал только Point — и если он спал, пара не возвращалась.
        if (role == Role.SERVER) {
            pointUdp = null
            scheduleReconnect(700)
            toast("Нет связи с «$peerName». Музыка продолжается, ждём возвращения…")
        } else {
            rx.reset(); serverUdp = null; sessionId = 0
            scheduleReconnect(200)
            toast("Связь потеряна — переподключаемся…")
        }
        PlaybackService.refresh(app)
    }

    private fun scheduleReconnect(delayMs: Long) {
        telemetry.event("RECONNECT_SCHEDULED", "delayMs=$delayMs role=$role attempt=$resumeAttempt")
        reconnectTask?.cancel(false)
        reconnectTask = later(delayMs) { attemptResume() }
    }

    private fun attemptResume() {
        if (phase != Phase.RECONNECTING || pending != null) return
        val rec = record ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBrowseRestart > 20_000) { lastBrowseRestart = now; discovery.restartBrowse() }
        val p = resumeTarget(rec)
        if (p == null) { scheduleReconnect(reconnectDelay()); return }
        connectOutgoing(p, "resume-out", resumeJson(rec), 4_000)
    }

    private fun goIdle(sendBye: Boolean, reason: String) {
        cancelPointFileTransfer()
        abortIncomingFileTransfer("pair disconnected")
        reconnectTask?.cancel(false); pendingTimeout?.cancel(false)
        link?.let { l -> link = null; l.tag = "dead"; if (sendBye) l.sendAndClose(JSONObject().put("t", "bye")) else l.close() }
        pending?.let { p -> pending = null; p.tag = "dead"; p.close() }
        phase = Phase.IDLE
        manualResumeActive = false
        log(reason)
        pendingTrackId = null
        clearProgressiveBufferState()
        audio.stop(); audio.source = null
        abandonFocus()
        try { PlaybackService.stop(app) } catch (_: Exception) {}
        serverPlay = ServerPlayState(Timeline.NONE, source, Ch.L, 0f)
        pointUdp = null; serverUdp = null; sessionId = 0
        rx.reset(); clock.reset()
        calibRunning = false
        discovery.startBrowse()
        if (!appVisible) hideWhileBackground()   // пару разорвали, пока приложение в фоне
        ui { copy(phase = Phase.IDLE, linkOk = false, remembered = record, playing = false, audioMissing = false, calib = CalibUi(),
            loadingTracks = false, loadingTrackId = null, serverDiag = Diag(), pointDiag = Diag()) }
        toast(reason)
    }

    private fun persistRecord() {
        record?.let { prefs.edit().putString("pair", it.toJson()).apply() }
    }

    private fun saveServerParams() {
        record = record?.copy(pointChannel = pointChannel, trimMs = trimNs / 1e6, volL = volL, volR = volR, master = master)
        persistRecord()
    }

    // ================================================================== сообщения в паре
    private fun onPairedMessage(o: JSONObject) {
        when (o.optString("t")) {
            "hb" -> {}
            "bye" -> { setAutoResume(false); goIdle(sendBye = false, reason = "«$peerName» разорвал пару") }
            "name" -> { peerName = o.optString("name", peerName); ui { copy(peerName = peerName) }
                        record = record?.copy(peerName = peerName); persistRecord() }
            // --- на Server ---
            "cmd" -> if (role == Role.SERVER) handleCmd(o)
            "diag" -> if (role == Role.SERVER) {
                val d = Diag.fromJson(o); pointClockReady = d.clockReady; ui { copy(pointDiag = d) }
            }
            // --- на Point ---
            "session" -> if (role == Role.POINT) {
                val newSession = o.getInt("session")
                if (sessionId != newSession) {
                    sessionId = newSession
                    lastAudioRxAt.set(0)
                    clock.reset(); rx.reset()
                }
                serverFileTransferToken = o.optString("fileToken")
                val l = link ?: return
                serverUdp = InetSocketAddress(l.remoteAddress, o.getInt("udpPort"))
                pingBurst = 20
            }
            "file-ack", "file-error" -> fileTransferAcks[o.optString("id")]?.complete(o)
            "file-start" -> handleIncomingFileStart(o)
            "file-chunk" -> handleIncomingFileChunk(o)
            "file-end" -> handleIncomingFileEnd(o)
            "file-cancel" -> if (incomingFileTransfer?.id == o.optString("id")) abortIncomingFileTransfer("cancelled by Point")
            "state" -> if (role == Role.POINT) applyState(o)
            "queue" -> if (role == Role.POINT) applyQueue(o)
            "timeline" -> if (role == Role.POINT) {
                rx.onTimeline(Timeline(o.getInt("epoch"), o.getBoolean("playing"),
                    o.getString("anchorFrame").toLong(), o.getString("anchorNs").toLong(),
                    o.optString("stopFrame", "-1").toLongOrNull() ?: -1L))
            }
            "sdiag" -> if (role == Role.POINT) ui { copy(serverDiag = Diag.fromJson(o)) }
            "calib" -> if (role == Role.POINT) applyCalib(o)
        }
    }

    // ================================================================== Server: шкала и команды
    private fun serverChannel() = Ch.other(pointChannel)
    private fun serverGain() = master * (if (serverChannel() == Ch.L) volL else volR)

    private fun rebuildPlayState() {
        serverPlay = ServerPlayState(serverPlay.timeline, source, serverChannel(), serverGain())
    }

    private fun sendSession(target: ControlLink? = link) {
        target?.send(JSONObject().put("t", "session").put("session", sessionId)
            .put("udpPort", udp.localPort).put("name", myName).put("fileToken", fileTransferToken))
    }

    /** Replaces only the UDP audio session; TCP pairing and the playback epoch remain intact. */
    private fun renewAudioSession(reason: String) {
        if (phase != Phase.PAIRED || role != Role.SERVER) return
        val now = SystemClock.elapsedRealtime()
        sessionId = rnd.nextInt().let { if (it == 0) 1 else it }
        pointUdp = null
        pointClockReady = false
        fileTransferToken = hex(24)
        abortIncomingFileTransfer("audio session changed")
        lastAudioSessionResetMs = now
        telemetry.event("AUDIO_SESSION_RENEW", "reason=$reason session=$sessionId sent=${sentAudioPackets.get()} point=${ui.value.pointDiag.audioAgeMs}ms")
        log("UDP-аудиосессия пересоздана: $reason")
        sendSession()
        sendTimeline()
    }

    private fun newEpoch(playing: Boolean, frame: Long, anchorNs: Long) {
        epoch++
        serverPlay = ServerPlayState(Timeline(epoch, playing, frame, anchorNs), source, serverChannel(), serverGain())
        sendTimeline()
        publishState()
    }

    private fun sendTimeline() {
        val tl = serverPlay.timeline
        link?.send(JSONObject().put("t", "timeline").put("epoch", tl.epoch).put("playing", tl.playing)
            .put("anchorFrame", tl.anchorFrame.toString()).put("anchorNs", tl.anchorNs.toString())
            .put("stopFrame", tl.stopFrame.toString()))
    }

    private fun stateJson(): JSONObject {
        val tl = serverPlay.timeline
        return JSONObject().put("t", "state").put("playing", tl.running || ui.value.audioBuffering).put("durFrames", source.totalFrames)
            .put("title", source.title).put("artist", source.artist).put("mode", mode.wire)
            .put("queueIndex", queueIndex).put("queueCount", queue.size).put("trackId", queue.getOrNull(queueIndex)?.id ?: "")
            .put("volL", volL.toDouble()).put("volR", volR.toDouble()).put("master", master.toDouble())
            .put("pointCh", Ch.name(pointChannel)).put("trimMs", trimNs / 1e6).put("serverName", myName)
            .put("undo", undoText).put("decodeProgress", decodeProgress ?: JSONObject.NULL)
            .put("audioBuffering", ui.value.audioBuffering)
    }

    private fun publishQueue() {
        val tracks = queue.map { QueueTrackUi(it.id, it.title, it.artist) }
        ui { copy(queueTracks = tracks, queueIndex = queueIndex) }
        queueRevision++
        val parts = maxOf(1, (tracks.size + 99) / 100)
        for (part in 0 until parts) {
            val items = org.json.JSONArray()
            tracks.drop(part * 100).take(100).forEach { track ->
                items.put(JSONObject().put("id", track.id).put("title", track.title).put("artist", track.artist))
            }
            link?.send(JSONObject().put("t", "queue").put("revision", queueRevision).put("part", part)
                .put("parts", parts).put("items", items))
        }
    }

    private fun applyQueue(o: JSONObject) {
        val revision = o.optInt("revision", -1)
        val part = o.optInt("part", -1)
        val parts = o.optInt("parts", 0)
        if (revision < 0 || part !in 0 until parts || parts !in 1..10_000) return
        if (revision != incomingQueueRevision) {
            incomingQueueRevision = revision
            incomingQueueParts = parts
            incomingQueue.clear()
        }
        if (incomingQueueParts != parts) return
        val items = o.optJSONArray("items") ?: return
        incomingQueue[part] = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            QueueTrackUi(item.optString("id"), item.optString("title"), item.optString("artist"))
                .takeIf { it.id.isNotBlank() }
        }
        if (incomingQueue.size == incomingQueueParts) ui { copy(queueTracks = incomingQueue.values.flatten()) }
    }

    private fun publishState() {
        val s = stateJson()
        applyState(s)
        link?.send(s)
    }

    /** Общий для обеих ролей разбор состояния плеера → UI (и параметры рендера на Point). */
    private fun applyState(o: JSONObject) {
        val playing = o.optBoolean("playing")
        val dur = o.optLong("durFrames")
        val vL = o.optDouble("volL", 1.0).toFloat(); val vR = o.optDouble("volR", 1.0).toFloat()
        val m = o.optDouble("master", 0.8).toFloat()
        val pCh = Ch.parse(o.optString("pointCh", "R"))
        val trim = o.optDouble("trimMs", 0.0)
        if (role == Role.POINT) {
            pointParams = PointParams(m * (if (pCh == Ch.L) vL else vR), (trim * 1e6).toLong(), dur)
        }
        val progress = if (o.isNull("decodeProgress")) null else o.optDouble("decodeProgress").toFloat().coerceIn(0f, 1f)
        ui { copy(playing = playing, durationMs = dur * 1000 / SF.SAMPLE_RATE, title = o.optString("title"),
            artist = o.optString("artist"), mode = SourceMode.fromWire(o.optString("mode")), audioBuffering = o.optBoolean("audioBuffering"),
            queueIndex = o.optInt("queueIndex", -1), undoText = o.optString("undo", ""),
            volL = vL, volR = vR, master = m, pointChannel = pCh, trimMs = trim, decodingProgress = progress) }
        if (playing) requestFocus()
        PlaybackService.refresh(app)
    }

    private fun posFrame(): Long = serverPlay.timeline.positionFrame(System.nanoTime()).coerceIn(0L, source.totalFrames)

    private fun handleCmd(o: JSONObject) {
        val now = System.nanoTime()
        when (o.optString("a")) {
            "play" -> cmdPlay()
            "pause" -> cmdPause()
            "toggle" -> if ((serverPlay.timeline.running || ui.value.audioBuffering) && mode != SourceMode.CALIBRATION) cmdPause() else cmdPlay()
            "seek" -> seekFrame(o.optLong("ms") * SF.SAMPLE_RATE / 1000)
            "skip" -> seekFrame(posFrame() + o.optLong("ms") * SF.SAMPLE_RATE / 1000)
            "next" -> selectTrack(navigationIndex() + 1, startPlayback = true)
            // Как в обычных плеерах: если трек играет дольше 3 с (или это первый трек) — «назад» возвращает
            // в начало текущего, иначе переходит к предыдущему.
            "previous" -> {
                val idx = navigationIndex()
                if (idx <= 0 || (pendingTrackId == null && mode == SourceMode.MUSIC && posFrame() > 3L * SF.SAMPLE_RATE)) seekFrame(0)
                else selectTrack(idx - 1, startPlayback = true)
            }
            "select" -> selectTrack(queue.indexOfFirst { it.id == o.optString("id") }, startPlayback = true)
            "remove" -> {
                val arr = o.optJSONArray("ids") ?: return
                doRemoveTracks((0 until arr.length()).map { arr.optString(it) }.toSet())
            }
            "undoRemove" -> doUndoRemove()
            "vol" -> {
                val v = o.optDouble("v", 1.0).toFloat().coerceIn(0f, 1f)
                when (o.optString("ch")) { "L" -> volL = v; "R" -> volR = v; else -> master = v }
                rebuildPlayState(); saveServerParams(); publishState()
            }
            "swap" -> {
                if (calibRunning) return
                pointChannel = Ch.other(pointChannel); saveServerParams()
                val tl = serverPlay.timeline
                cancelScheduledPause()
                if (tl.running) newEpoch(true, posFrame(), now + SF.START_DELAY_NS) else newEpoch(false, posFrame(), now)
                toast("Каналы поменяны: этот телефон — ${Ch.name(serverChannel())}")
            }
            "trim" -> {
                val ms = o.optDouble("ms", 0.0).coerceIn(-SF.MAX_TRIM_MS.toDouble(), SF.MAX_TRIM_MS.toDouble())
                trimNs = (ms * 1e6).toLong(); saveServerParams(); publishState()
            }
            "calibrate" -> startCalibration(apply = true)
            "measure" -> startCalibration(apply = false)
            "metronome" -> if (o.optBoolean("on")) startTestMode(MetronomeSource()) else if (mode == SourceMode.METRONOME) restoreMusic(false)
            "lrtest" -> startTestMode(ChannelTestSource())
        }
    }

    private fun cmdPlay() {
        if (calibRunning) return
        if (pendingTrackId != null) { pendingShouldPlay = true; return }
        if (mode != SourceMode.MUSIC) restoreMusic(false)
        val tl = serverPlay.timeline
        if (tl.running) return
        clearProgressiveBufferState()
        cancelScheduledPause()
        val pos = posFrame()   // при отменённой паузе — кадр, на котором она бы наступила
        val f = if (pos >= source.totalFrames) 0L else pos
        newEpoch(true, f, System.nanoTime() + SF.START_DELAY_NS)
    }

    private fun cmdPause() {
        if (calibRunning) return
        pendingShouldPlay = false
        if (mode != SourceMode.MUSIC) { restoreMusic(false); return }
        val tl = serverPlay.timeline
        if (!tl.running) {
            if (progressiveStopFrame >= 0) {
                progressiveResumeAfterBuffer = false
                setProgressiveBuffering(false)
            } else if (progressivePausedFrame >= 0) {
                clearProgressiveBufferState(); publishState()
            }
            return
        }
        clearProgressiveBufferState()
        // Пауза по общей шкале: оба телефона замолкают на одном кадре через PAUSE_LEAD. Раньше Server
        // замолкал сразу, а Point — только когда до него доходила команда (на 10–200 мс позже), со щелчком.
        val at = System.nanoTime() + SF.PAUSE_LEAD_NS
        val stop = maxOf(tl.anchorFrame, tl.frameAt(at).toLong()).coerceAtMost(source.totalFrames)
        serverPlay = ServerPlayState(Timeline(tl.epoch, true, tl.anchorFrame, tl.anchorNs, stop), source, serverChannel(), serverGain())
        sendTimeline()
        publishState()
        pauseTask?.cancel(false)
        pauseTask = later(SF.PAUSE_LEAD_NS / 1_000_000 + 60) {
            val cur = serverPlay.timeline
            if (cur.epoch == tl.epoch && cur.stopFrame == stop) newEpoch(false, stop, System.nanoTime())
        }
    }

    private fun seekFrame(frame: Long) {
        if (calibRunning) return
        if (mode != SourceMode.MUSIC) restoreMusic(false)
        val progressive = source as? app.soundfield.core.SegmentedPcmSource
        val readyLimit = progressive?.takeUnless { it.complete }?.let { (it.readyFrames - 1).coerceAtLeast(0) }
        val requested = frame.coerceIn(0L, maxOf(0L, source.totalFrames - 1))
        val f = readyLimit?.let { requested.coerceAtMost(it) } ?: requested
        if (readyLimit != null && f != requested) toast("Можно перематывать до подготовленного участка (${f * 1000 / SF.SAMPLE_RATE / 1000} с)")
        val playing = serverPlay.timeline.running
        clearProgressiveBufferState()
        cancelScheduledPause()
        val now = System.nanoTime()
        newEpoch(playing, f, if (playing) now + SF.START_DELAY_NS else now)
    }

    private fun startTestMode(src: AudioSource) {
        if (calibRunning) return
        pendingTrackId = null
        ui { copy(loadingTracks = false, loadingTrackId = null) }
        if (mode == SourceMode.MUSIC) { savedFrame = posFrame(); savedPlaying = serverPlay.timeline.running }
        clearProgressiveBufferState()
        cancelScheduledPause()
        mode = src.mode; source = src
        newEpoch(true, 0, System.nanoTime() + SF.START_DELAY_NS)
    }

    private fun restoreMusic(resume: Boolean) {
        clearProgressiveBufferState()
        mode = SourceMode.MUSIC
        source = if (queueIndex in queue.indices) currentQueueSource ?: music else music
        val now = System.nanoTime()
        newEpoch(resume, savedFrame, if (resume) now + SF.START_DELAY_NS else now)
    }

    private var pauseTask: ScheduledFuture<*>? = null
    private fun cancelScheduledPause() { pauseTask?.cancel(false); pauseTask = null }

    private fun navigationIndex(): Int = queue.indexOfFirst { it.id == pendingTrackId }.takeIf { it >= 0 } ?: queueIndex

    private fun selectTrack(index: Int, forcePaused: Boolean = false, startPlayback: Boolean = false) {
        if (calibRunning || (phase != Phase.PAIRED && phase != Phase.RECONNECTING) || index !in queue.indices) return
        val item = queue[index]
        if (queueIndex == index && pendingTrackId == null && currentQueueSource != null) {
            if (startPlayback) cmdPlay()
            return
        }
        pendingShouldPlay = !forcePaused && (startPlayback || serverPlay.timeline.running)
        clearProgressiveBufferState()
        val generation = ++trackLoadGeneration
        pendingTrackId = item.id
        decodeProgress = null; decodeProgressTrackId = item.id
        ui { copy(loadingTracks = true, loadingTrackId = item.id, decodingProgress = null) }
        queueIo.execute {
            var initialSource: AudioSource? = null
            val cancelled = { generation != trackLoadGeneration || phase !in setOf(Phase.PAIRED, Phase.RECONNECTING) || role != Role.SERVER }
            val loaded = loadTrack(item, cancelled,
                onReady = { ready ->
                    initialSource = ready
                    post { activateLoadedTrack(index, item, generation, ready) }
                },
                onProgress = { progressSource ->
                    val value = progressSource.progress()
                    post {
                        if (generation == trackLoadGeneration &&
                            (queueIndex == index && currentQueueSource === progressSource || pendingTrackId == item.id)) {
                            decodeProgress = value
                            ui { copy(decodingProgress = value) }
                            publishState()
                        }
                    }
                })
            post {
                if (generation != trackLoadGeneration) return@post
                if (loaded != null) {
                    decodedTracks[item.id] = loaded
                    if (initialSource == null) activateLoadedTrack(index, item, generation, loaded)
                    if (currentQueueSource === loaded) {
                        decodeProgress = null; decodeProgressTrackId = null
                        ui { copy(decodingProgress = null, loadingTracks = false, loadingTrackId = null) }
                        publishState()
                    }
                    Log.i(TAG, "decoded «${item.title}» fully")
                } else if (initialSource == null) {
                    failTrackLoad(index, item, generation)
                } else if (currentQueueSource === initialSource && queueIndex == index) {
                    val progressiveSource = initialSource as? app.soundfield.core.SegmentedPcmSource
                    progressiveSource?.finish(progressiveSource.readyFrames)
                    val lastReady = (progressiveSource?.readyFrames?.minus(1)
                        ?: ((initialSource?.totalFrames ?: 1L) - 1L)).coerceAtLeast(0)
                    clearProgressiveBufferState()
                    newEpoch(false, posFrame().coerceAtMost(lastReady), System.nanoTime())
                    decodeProgress = null; decodeProgressTrackId = null
                    ui { copy(decodingProgress = null, loadingTracks = false, loadingTrackId = null) }
                    publishState(); toast("Не удалось завершить подготовку «${item.title}»")
                }
            }
        }
    }

    /** Запускает источник, когда доступен первый полный PCM-сегмент, не дожидаясь длинного файла целиком. */
    private fun activateLoadedTrack(index: Int, item: QueueItem, generation: Long, loaded: AudioSource) {
        if (generation != trackLoadGeneration || pendingTrackId != item.id || queue.getOrNull(index)?.id != item.id ||
            role != Role.SERVER || calibRunning) return
        pendingTrackId = null
        clearProgressiveBufferState()
        queueIndex = index
        mode = SourceMode.MUSIC
        source = loaded
        currentQueueSource = loaded
        savedFrame = 0
        decodeProgress = (loaded as? app.soundfield.core.SegmentedPcmSource)?.takeUnless { it.complete }?.progress()
        ui { copy(loadingTracks = false, loadingTrackId = null, queueIndex = queueIndex, decodingProgress = decodeProgress) }
        val now = System.nanoTime()
        cancelScheduledPause()
        newEpoch(pendingShouldPlay, 0, if (pendingShouldPlay) now + SF.START_DELAY_NS else now)
        prefetchAround(index)
    }

    private fun failTrackLoad(index: Int, item: QueueItem, generation: Long) {
        if (generation != trackLoadGeneration || pendingTrackId != item.id) return
        pendingTrackId = null
        decodeProgress = null; decodeProgressTrackId = null
        ui { copy(loadingTracks = false, loadingTrackId = null, decodingProgress = null) }
        toast("Не удалось загрузить «${item.title}»")
    }

    // ------------------------------------------------------------------ декодированные треки
    /** Готовые (декодированные в файл) треки: текущий, следующий и предыдущий. */
    private val decodedTracks = java.util.concurrent.ConcurrentHashMap<String, AudioSource>()

    /** Декодирование в фоне (queueIo). Ловим Throwable: OutOfMemoryError раньше ронял приложение. */
    private fun loadTrack(
        item: QueueItem,
        cancelled: () -> Boolean = { false },
        onReady: (AudioSource) -> Unit = {},
        onProgress: (app.soundfield.core.SegmentedPcmSource) -> Unit = {},
    ): AudioSource? {
        decodedTracks[item.id]?.let {
            telemetry.event("AUDIO_DECODE_CACHE_HIT", "trackId=${item.id} frames=${it.totalFrames}")
            onReady(it)
            return it
        }
        val inputBytes = runCatching { fileSize(item.ref().uri) }.getOrDefault(-1L)
        val mime = runCatching { app.contentResolver.getType(item.ref().uri) }.getOrNull() ?: "unknown"
        val startedAt = SystemClock.elapsedRealtime()
        var firstAudioAt: Long? = null
        telemetry.event("AUDIO_DECODE_START", "trackId=${item.id} mime=$mime bytes=$inputBytes")
        return try {
            val decoded = AudioFileDecoder.decode(app, item.ref(), cancelled,
                onReady = { ready ->
                    val now = SystemClock.elapsedRealtime()
                    if (firstAudioAt == null) {
                        firstAudioAt = now
                        telemetry.event("AUDIO_DECODE_FIRST_AUDIO",
                            "trackId=${item.id} elapsedMs=${now - startedAt} readyFrames=${ready.readyFrames} estimatedFrames=${ready.totalFrames}")
                    }
                    onReady(ready)
                }, onProgress = { progress ->
                    telemetry.limited("AUDIO_DECODE_PROGRESS",
                        "trackId=${item.id} readyFrames=${progress.readyFrames} estimatedFrames=${progress.totalFrames}")
                    onProgress(progress)
                })
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val durationMs = (decoded.totalFrames * 1_000.0 / SF.SAMPLE_RATE).toLong()
            telemetry.event("AUDIO_DECODE_COMPLETE",
                "trackId=${item.id} mime=$mime bytes=$inputBytes elapsedMs=$elapsed durationMs=$durationMs frames=${decoded.totalFrames} firstAudioMs=${firstAudioAt?.minus(startedAt) ?: -1L}")
            Log.i(TAG, "decoded «${item.title}» in $elapsed ms")
            decoded
        } catch (t: Throwable) {
            val wasCancelled = cancelled()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val codecDiagnostic = (t as? MediaCodec.CodecException)?.diagnosticInfo
                ?.take(160)?.let { " codecDiagnostic=$it" }.orEmpty()
            telemetry.event(if (wasCancelled) "AUDIO_DECODE_CANCELLED" else "AUDIO_DECODE_FAILED",
                "trackId=${item.id} mime=$mime bytes=$inputBytes elapsedMs=$elapsed firstAudioMs=${firstAudioAt?.minus(startedAt) ?: -1L} errorType=${t.javaClass.simpleName}$codecDiagnostic")
            if (!wasCancelled) Log.w(TAG, "Could not load ${item.title}", t)
            null
        }
    }

    /**
     * После загрузки трека заранее готовим следующий — переход между треками происходит сразу,
     * без паузы на декодирование. Лишние декодированные файлы удаляем (диск).
     */
    private fun prefetchAround(index: Int) {
        val keep = listOfNotNull(queue.getOrNull(index - 1), queue.getOrNull(index), queue.getOrNull(index + 1)).map { it.id }.toSet()
        decodedTracks.keys.filter { it !in keep }.forEach { id ->
            decodedTracks.remove(id)
            queue.firstOrNull { it.id == id }?.let { AudioFileDecoder.pcmFile(app, it.ref()).deleteRecursively() }
        }
        val next = queue.getOrNull(index + 1) ?: return
        if (decodedTracks.containsKey(next.id)) return
        val generation = trackLoadGeneration
        queueIo.execute { loadTrack(next) { generation != trackLoadGeneration } }
    }

    // ------------------------------------------------------------------ удаление треков
    private var removedBackup: List<Pair<Int, QueueItem>> = emptyList()
    private var undoText = ""
    private var undoToken = 0

    private fun doRemoveTracks(ids: Set<String>) {
        if (calibRunning) { toast("Сначала завершите калибровку"); return }
        val removed = queue.withIndex().filter { it.value.id in ids }.map { it.index to it.value }
        if (removed.isEmpty()) return
        val current = queue.getOrNull(queueIndex)
        val oldIndex = queueIndex
        val wasPlaying = serverPlay.timeline.running
        queue.removeAll { it.id in ids }
        removedBackup = removed

        if (pendingTrackId in ids) {                       // удалили трек, который сейчас загружается
            pendingTrackId = null; trackLoadGeneration++
            ui { copy(loadingTracks = false, loadingTrackId = null) }
        }
        if (current != null && current.id in ids) {
            // Удалён играющий трек: переходим к следующему за ним (с тем же состоянием play/pause);
            // если он был последним — останавливаемся на первом треке очереди.
            val nextIndex = removed.count { it.first < oldIndex }.let { oldIndex - it }
            queueIndex = -1; currentQueueSource = null
            if (queue.isEmpty()) {
                cancelScheduledPause()
                mode = SourceMode.MUSIC; source = music; hasImportedQueue = false
                newEpoch(false, 0, System.nanoTime())
            } else if (nextIndex < queue.size) {
                selectTrack(nextIndex, forcePaused = !wasPlaying, startPlayback = wasPlaying)
            } else {
                selectTrack(0, forcePaused = true)
            }
        } else if (current != null) {
            queueIndex = queue.indexOfFirst { it.id == current.id }
        }
        removed.forEach { (_, item) -> decodedTracks.remove(item.id); AudioFileDecoder.pcmFile(app, item.ref()).deleteRecursively() }

        undoText = if (removed.size == 1) "Удалено: ${trackLabel(removed[0].second.title, removed[0].second.artist)}"
                   else "Удалено треков: ${removed.size}"
        val token = ++undoToken
        later(5_000) {
            if (undoToken != token) return@later
            undoText = ""; removedBackup = emptyList()
            publishState(); releaseUnusedGrants()
        }
        publishQueue(); publishState()
    }

    private fun doUndoRemove() {
        val backup = removedBackup
        if (backup.isEmpty()) return
        removedBackup = emptyList(); undoText = ""; undoToken++
        val currentId = queue.getOrNull(queueIndex)?.id
        backup.sortedBy { it.first }.forEach { (index, item) -> queue.add(index.coerceIn(0, queue.size), item) }
        if (currentId != null) queueIndex = queue.indexOfFirst { it.id == currentId }
        else if (queueIndex < 0 && hasImportedQueue.not() && queue.isNotEmpty()) { hasImportedQueue = true; selectTrack(0, forcePaused = true) }
        publishQueue(); publishState()
    }

    // ------------------------------------------------------------------ плейлисты (внутри приложения)
    private val playlistDir get() = File(app.filesDir, "playlists").apply { mkdirs() }

    private fun readPlaylist(file: File): JSONObject? = try { JSONObject(file.readText()) } catch (_: Exception) { null }

    private fun refreshPlaylists() {
        val list = playlistDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            val o = readPlaylist(f) ?: return@mapNotNull null
            PlaylistInfo(f.nameWithoutExtension, o.optString("name", "Плейлист"),
                o.optJSONArray("tracks")?.length() ?: 0, o.optLong("savedAt", f.lastModified()))
        }.sortedByDescending { it.savedAt }
        ui { copy(playlists = list) }
    }

    private fun doSavePlaylist(rawName: String) {
        if (queue.isEmpty()) { toast("Очередь пуста — сохранять нечего"); return }
        val name = rawName.trim().take(60).ifEmpty { "Плейлист" }
        // Плейлист с тем же названием перезаписывается.
        val existing = ui.value.playlists.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val id = existing?.id ?: java.util.UUID.randomUUID().toString()
        val tracks = org.json.JSONArray()
        queue.forEach { tracks.put(JSONObject().put("uri", it.uri.toString()).put("title", it.title).put("artist", it.artist)) }
        val o = JSONObject().put("name", name).put("savedAt", System.currentTimeMillis()).put("tracks", tracks)
        try {
            val tmp = File(playlistDir, "$id.json.tmp")
            tmp.writeText(o.toString())
            check(tmp.renameTo(File(playlistDir, "$id.json")))
        } catch (e: Exception) { Log.w(TAG, "save playlist", e); toast("Не удалось сохранить плейлист"); return }
        refreshPlaylists()
        toast(if (existing != null) "Плейлист «$name» обновлён" else "Плейлист «$name» сохранён")
    }

    private fun doOpenPlaylist(id: String, append: Boolean) {
        if (calibRunning) { toast("Сначала завершите калибровку"); return }
        val o = readPlaylist(File(playlistDir, "$id.json")) ?: run { toast("Плейлист не найден"); refreshPlaylists(); return }
        val name = o.optString("name", "Плейлист")
        val arr = o.optJSONArray("tracks") ?: org.json.JSONArray()
        ui { copy(loadingTracks = true) }
        queueIo.execute {
            val items = (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = try { Uri.parse(t.optString("uri")) } catch (_: Exception) { return@mapNotNull null }
                if (!AudioFileDecoder.isReadable(app, uri)) return@mapNotNull null   // файл удалён или доступ отозван
                QueueItem(java.util.UUID.randomUUID().toString(), uri, t.optString("title", "Трек"), t.optString("artist", ""))
            }
            val missing = arr.length() - items.size
            post {
                if (role != Role.SERVER || !ui.value.inPair || calibRunning) { ui { copy(loadingTracks = false) }; return@post }
                if (items.isEmpty()) {
                    ui { copy(loadingTracks = false) }
                    toast("Файлы плейлиста «$name» не найдены"); return@post
                }
                val replace = !append || !hasImportedQueue
                if (replace) {
                    if (serverPlay.timeline.playing) { cancelScheduledPause(); newEpoch(false, posFrame(), System.nanoTime()) }
                    deleteQueueFiles(queue.toList())
                    queue.clear(); queueIndex = -1; currentQueueSource = null; pendingTrackId = null
                    hasImportedQueue = true
                    removedBackup = emptyList(); undoText = ""
                }
                queue.addAll(items)
                publishQueue()
                if (replace) releaseUnusedGrants()
                if (replace) selectTrack(0, forcePaused = true) else { ui { copy(loadingTracks = false) }; publishState() }
                toast(buildString {
                    append(if (replace) "Открыт плейлист «$name»" else "Добавлено из «$name»: ${items.size}")
                    if (missing > 0) append(" · не найдено файлов: $missing")
                })
            }
        }
    }

    private fun doRenamePlaylist(id: String, rawName: String) {
        val f = File(playlistDir, "$id.json")
        val o = readPlaylist(f) ?: return
        val name = rawName.trim().take(60).ifEmpty { return }
        try { f.writeText(o.put("name", name).toString()) } catch (_: Exception) { toast("Не удалось переименовать"); return }
        refreshPlaylists()
    }

    private fun doDeletePlaylist(id: String) {
        File(playlistDir, "$id.json").delete()
        refreshPlaylists()
        releaseUnusedGrants()
    }

    // ------------------------------------------------------------------ постоянный доступ к файлам
    private fun persistReadGrant(uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* провайдер не поддерживает постоянный доступ — трек будет доступен до перезапуска */ }
    }

    /**
     * Android ограничивает число постоянных разрешений (128–512). Отпускаем те, на которые не ссылаются
     * ни очередь, ни сохранённые плейлисты. Доступ к папке покрывает все файлы внутри неё.
     */
    private fun releaseUnusedGrants() {
        val used = HashSet<String>()
        queue.forEach { used += it.uri.toString() }
        removedBackup.forEach { used += it.second.uri.toString() }
        playlistDir.listFiles { f -> f.extension == "json" }.orEmpty().forEach { f ->
            val arr = readPlaylist(f)?.optJSONArray("tracks") ?: return@forEach
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("uri")?.let { used += it }
        }
        try {
            app.contentResolver.persistedUriPermissions.forEach { p ->
                val g = p.uri.toString()
                if (used.none { it == g || it.startsWith("$g/") }) {
                    try { app.contentResolver.releasePersistableUriPermission(p.uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { Log.w(TAG, "release grants", e) }
    }

    private fun deleteQueueFiles(items: List<QueueItem>) {
        items.forEach { item ->
            decodedTracks.remove(item.id)
            AudioFileDecoder.pcmFile(app, item.ref()).deleteRecursively()
        }
    }

    private fun clearProgressiveBufferState() {
        progressiveStopFrame = -1L
        progressivePausedFrame = -1L
        progressiveResumeAfterBuffer = true
        if (ui.value.audioBuffering) ui { copy(audioBuffering = false) }
    }

    private fun setProgressiveBuffering(buffering: Boolean) {
        if (ui.value.audioBuffering == buffering) return
        ui { copy(audioBuffering = buffering) }
        publishState()
    }

    /** Каждые 100 мс: конец трека / конец тестового сигнала. */
    private fun serverTick() {
        if (role != Role.SERVER || !ui.value.inPair) return
        val tl = serverPlay.timeline
        val nowNs = System.nanoTime()
        val progressive = if (mode == SourceMode.MUSIC) source as? app.soundfield.core.SegmentedPcmSource else null

        if (progressivePausedFrame >= 0) {
            val pausedAt = progressivePausedFrame
            if (progressive == null || progressive.complete && pausedAt >= progressive.totalFrames) {
                progressivePausedFrame = -1L
                setProgressiveBuffering(false)
                if (queueIndex + 1 < queue.size) selectTrack(queueIndex + 1, startPlayback = true)
                else newEpoch(false, source.totalFrames, nowNs)
                return
            }
            if (progressive.complete || progressive.readyFrames >= pausedAt + PROGRESSIVE_RESUME_BUFFER_FRAMES) {
                progressivePausedFrame = -1L
                setProgressiveBuffering(false)
                newEpoch(true, pausedAt.coerceAtMost((source.totalFrames - 1).coerceAtLeast(0)), nowNs + SF.START_DELAY_NS)
            }
            return
        }

        if (progressiveStopFrame >= 0) {
            if (progressive?.complete == true && progressiveResumeAfterBuffer) {
                progressiveStopFrame = -1L
                setProgressiveBuffering(false)
                newEpoch(true, tl.positionFrame(nowNs), nowNs + SF.START_DELAY_NS)
                return
            }
            val stop = progressiveStopFrame
            if (nowNs >= tl.timeOf(stop)) {
                progressiveStopFrame = -1L
                progressivePausedFrame = if (progressiveResumeAfterBuffer) stop else -1L
                newEpoch(false, stop, nowNs)
            }
            return
        }

        if (!tl.playing || pendingTrackId != null) return
        val position = tl.positionFrame(nowNs)
        if (progressive != null && !progressive.complete) {
            if (position >= source.totalFrames || progressive.readyFrames < position + PROGRESSIVE_LOOKAHEAD_FRAMES) {
                val safeReady = (progressive.readyFrames - PROGRESSIVE_FADE_FRAMES).coerceAtLeast(0)
                val scheduled = minOf(tl.positionFrame(nowNs + SF.PAUSE_LEAD_NS), safeReady)
                progressiveResumeAfterBuffer = true
                setProgressiveBuffering(true)
                if (scheduled <= position) {
                    progressivePausedFrame = position
                    newEpoch(false, position, nowNs)
                } else {
                    progressiveStopFrame = scheduled
                    serverPlay = ServerPlayState(
                        Timeline(tl.epoch, true, tl.anchorFrame, tl.anchorNs, scheduled), source, serverChannel(), serverGain())
                    sendTimeline(); publishState()
                }
                return
            }
        }
        if (position < source.totalFrames) return
        // Метаданные иногда занижают длительность. Не переключаем очередь до завершения декодирования.
        if (progressive != null && !progressive.complete) return
        when (mode) {
            SourceMode.MUSIC -> if (queueIndex + 1 < queue.size) selectTrack(queueIndex + 1)
                else newEpoch(false, source.totalFrames, System.nanoTime())
            SourceMode.CHANNEL_TEST -> restoreMusic(savedPlaying)
            else -> {}
        }
    }

    // ================================================================== калибровка
    private fun startCalibration(apply: Boolean) {
        if (calibRunning) return
        pendingTrackId = null
        ui { copy(loadingTracks = false, loadingTrackId = null) }
        if (phase != Phase.PAIRED || pointUdp == null) { broadcastCalib(false, "", "Point не на связи — калибровка невозможна", false, null); return }
        if (!pointClockReady) { broadcastCalib(false, "", "Часы ещё синхронизируются. Повторите через пару секунд", false, null); return }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ui { copy(calib = calib.copy(needsMicPermission = true)) }
            broadcastCalib(false, "", "Нужен доступ к микрофону на телефоне «$myName» (Server)", false, null, needsMic = true)
            return
        }
        calibRunning = true
        if (mode == SourceMode.MUSIC) { savedFrame = posFrame(); savedPlaying = serverPlay.timeline.running }
        mode = SourceMode.CALIBRATION; source = CalibrationSource(pointChannel)
        newEpoch(false, 0, System.nanoTime())
        broadcastCalib(true, "Подготовка микрофона…", "", true, null)
        val trimUsed = trimNs
        val anchor = AtomicLong(0)
        io.execute {
            try {
                val rec = mic.record(8_600) {
                    post {
                        val a = System.nanoTime() + 700_000_000L
                        anchor.set(a)
                        newEpoch(true, 0, a)
                        broadcastCalib(true, "Звучат тестовые сигналы — тишина, пожалуйста…", "", true, null)
                    }
                }
                post { broadcastCalib(true, "Анализ записи…", "", true, null) }
                val res = CalibrationAnalyzer.analyze(rec.samples, rec.sampleRate, rec.startNs, anchor.get(), trimUsed)
                post { finishCalibration(res, apply, trimUsed) }
            } catch (e: Exception) {
                Log.e(TAG, "calibration", e)
                post {
                    calibRunning = false; restoreMusic(savedPlaying)
                    broadcastCalib(false, "", "Ошибка микрофона: ${e.message}", false, null)
                }
            }
        }
    }

    private fun finishCalibration(res: CalibrationAnalyzer.Result, apply: Boolean, trimUsed: Long) {
        calibRunning = false
        restoreMusic(savedPlaying)
        if (!res.ok) { broadcastCalib(false, "", res.message, false, null); return }
        val who = if (res.lateMs >= 0) "опаздывал" else "спешил"
        if (apply) {
            val newTrim = (trimUsed - (res.lateMs * 1e6).toLong())
                .coerceIn(-SF.MAX_TRIM_MS * 1_000_000L, SF.MAX_TRIM_MS * 1_000_000L)
            trimNs = newTrim; saveServerParams(); publishState()
            broadcastCalib(false, "", "Готово. Point $who на %.1f мс — компенсировано.\nСдвиг Point: %+.1f мс · точность ±%.1f мс"
                .format(abs(res.lateMs), newTrim / 1e6, maxOf(res.spreadMs, 0.1)), true, null)
        } else {
            broadcastCalib(false, "", "Фактическое рассогласование L/R: Point $who на %.1f мс (±%.1f мс)"
                .format(abs(res.lateMs), maxOf(res.spreadMs, 0.1)), true, res.lateMs)
        }
    }

    private fun broadcastCalib(running: Boolean, stage: String, result: String, ok: Boolean, measured: Double?, needsMic: Boolean = false) {
        val o = JSONObject().put("t", "calib").put("running", running).put("stage", stage).put("result", result)
            .put("ok", ok).put("needsMic", needsMic)
        if (measured != null) o.put("measured", measured)
        applyCalib(o)
        link?.send(o)
    }

    private fun applyCalib(o: JSONObject) {
        val measured = if (o.has("measured")) o.getDouble("measured") else null
        ui { copy(calib = calib.copy(
            running = o.optBoolean("running"), stage = o.optString("stage"),
            resultText = if (o.optBoolean("running")) calib.resultText else o.optString("result"),
            resultOk = o.optBoolean("ok", true),
            lastMeasuredMs = measured ?: calib.lastMeasuredMs,
            needsMicPermission = role == Role.SERVER && o.optBoolean("needsMic"))) }
    }

    // ================================================================== heartbeat + диагностика
    private fun heartbeat() {
        autoResumeTick()
        val nowMs = SystemClock.elapsedRealtime()
        if (phase != Phase.IDLE && nowMs - lastTelemetry >= 10_000) {
            lastTelemetry = nowMs
            telemetry.event("HEALTH", telemetryState())
        }
        val l = link ?: return
        if (phase != Phase.PAIRED) return
        if (SystemClock.elapsedRealtime() - l.lastRx > SF.LINK_TIMEOUT_MS) { log("Нет данных ${SF.LINK_TIMEOUT_MS / 1000} с — соединение закрыто"); l.close("heartbeat timeout"); return }
        l.send(JSONObject().put("t", "hb"))
        if (role == Role.SERVER) {
            val d = Diag(syncErrMs = serverRenderer.corrector.syncErrorMs, latencyMs = audio.latencyMs,
                underruns = audio.underruns, resent = resent.get(), clockReady = true, valid = true)
            ui { copy(serverDiag = d) }
            l.send(d.toJson("sdiag"))
            val pointAge = ui.value.pointDiag.audioAgeMs
            val audioMissing = serverPlay.timeline.running && SystemClock.elapsedRealtime() - pairedAtMs > 5_000 &&
                (pointUdp == null || pointAge < 0 || pointAge >= 5_000)
            ui { copy(audioMissing = audioMissing) }
            if (audioMissing && SystemClock.elapsedRealtime() - lastAudioSessionResetMs >= 5_000) {
                renewAudioSession("Point не получает аудиопакеты")
            }
        } else {
            val recv = rx.received; val lost = rx.lostFinal
            val dR = recv - lastRecv; val dL = lost - lastLost
            lastRecv = recv; lastLost = lost
            val loss = if (dR + dL > 0) dL * 100.0 / (dR + dL) else 0.0
            val d = Diag(rttMs = clock.rttAvgMs, rttJitterMs = clock.rttJitterMs, jitterMs = rx.jitterMs, lossPct = loss,
                recovered = rx.recovered, late = rx.late, stale = rx.stale, bufferMs = rx.bufferMs,
                driftPpm = clock.driftPpm, clockUncMs = clock.uncertaintyMs,
                underruns = pointRenderer.underrunEvents + audio.underruns, syncErrMs = pointRenderer.corrector.syncErrorMs,
                latencyMs = audio.latencyMs, clockReady = clock.ready,
                audioAgeMs = lastAudioRxAt.get().let { if (it == 0L) -1 else SystemClock.elapsedRealtime() - it }, valid = true)
            ui { copy(pointDiag = d) }
            ui { copy(audioMissing = rx.timeline.playing && SystemClock.elapsedRealtime() - pairedAtMs > 5_000 &&
                (d.audioAgeMs < 0 || d.audioAgeMs >= 5_000)) }
            l.send(d.toJson("diag"))
        }
    }

    // ================================================================== UDP
    private fun udpLoop() {
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        while (true) {
            try {
                pkt.length = buf.size
                udp.receive(pkt)
                val now = System.nanoTime()
                udpPackets.incrementAndGet()
                val m = Wire.decode(pkt.data, pkt.length)
                if (m == null) { udpInvalid.incrementAndGet(); telemetry.limited("UDP_INVALID", "bytes=${pkt.length} from=${pkt.socketAddress}"); continue }
                if (m.session != sessionId || sessionId == 0) { udpOtherSession.incrementAndGet(); continue }
                if (udpLastRx.getAndSet(SystemClock.elapsedRealtime()) == 0L) telemetry.event("UDP_FIRST_PACKET", "from=${pkt.socketAddress}")
                when (m) {
                    is UdpMsg.Ping -> if (role == Role.SERVER && phase == Phase.PAIRED) {
                        pointUdp = pkt.socketAddress as InetSocketAddress
                        val b = Wire.pong(sessionId, m.t1, now, System.nanoTime(), m.pingSeq)
                        udp.send(DatagramPacket(b, b.size, pkt.socketAddress))
                    }
                    is UdpMsg.Nack -> if (role == Role.SERVER) resend(m)
                    is UdpMsg.Pong -> if (role == Role.POINT) clock.addPong(m.t1, m.t2, m.t3, now)
                    is UdpMsg.Audio -> if (role == Role.POINT && phase == Phase.PAIRED) {
                        lastAudioRxAt.set(SystemClock.elapsedRealtime())
                        rx.onAudio(m, now)
                    }
                }
            } catch (e: Exception) {
                telemetry.limited("UDP_RECEIVE_FAILED", error = e); Log.w(TAG, "udp: ${e.message}"); SystemClock.sleep(10)
            }
        }
    }

    private fun tickLoop() {
        while (true) {
            try {
                if (phase == Phase.PAIRED) {
                    if (role == Role.SERVER) serverSend() else pointNet()
                    SystemClock.sleep(5)
                } else {
                    // Не в паре — нет смысла крутиться каждые 5 мс, спим 200 мс.
                    SystemClock.sleep(200)
                }
            } catch (e: Exception) { telemetry.limited("UDP_SEND_FAILED", error = e); Log.w(TAG, "tick: ${e.message}") }
        }
    }

    private fun packet(st: ServerPlayState, tl: Timeline, seq: Int, start: Long, flags: Int): ByteArray {
        val count = minOf(SF.PACKET_FRAMES.toLong(), st.source.totalFrames - start).toInt().coerceAtLeast(0)
        val pCh = Ch.other(st.channel)
        val s = ShortArray(count) { i -> (st.source.sample(start + i, pCh) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
        return Wire.audio(sessionId, tl.epoch, seq, start, tl.timeOf(start), System.nanoTime(), flags, s, count)
    }

    private fun serverSend() { synchronized(sendLock) {
        val addr = pointUdp ?: return
        val st = serverPlay
        val tl = st.timeline
        if (!tl.playing) return
        if (tl.epoch != sentEpoch) { sentEpoch = tl.epoch; nextFrame = tl.anchorFrame }
        val target = minOf(tl.frameAt(System.nanoTime() + SF.LEAD_NS).toLong(), st.source.totalFrames)
        var k = 0
        while (nextFrame < target && k < 40) {
            val seq = sendSeq++
            val b = packet(st, tl, seq, nextFrame, 0)
            val i = seq and 1023
            cacheSeq[i] = seq; cacheEpoch[i] = tl.epoch; cacheStart[i] = nextFrame
            udp.send(DatagramPacket(b, b.size, addr))
            sentAudioPackets.incrementAndGet()
            nextFrame += SF.PACKET_FRAMES; k++
        }
    } }

    private fun resend(n: UdpMsg.Nack) { synchronized(sendLock) {
        val addr = pointUdp ?: return
        val st = serverPlay; val tl = st.timeline
        if (!tl.playing || n.epoch != tl.epoch) return
        val now = System.nanoTime()
        for (seq in n.seqs) {
            val i = seq and 1023
            if (cacheSeq[i] != seq || cacheEpoch[i] != tl.epoch) continue
            if (tl.timeOf(cacheStart[i] + SF.PACKET_FRAMES) < now) continue   // уже поздно
            val b = packet(st, tl, seq, cacheStart[i], UdpMsg.FLAG_RETRANSMIT)
            udp.send(DatagramPacket(b, b.size, addr)); resent.incrementAndGet()
        }
    } }

    private fun pointNet() {
        val addr = serverUdp ?: return
        if (sessionId == 0) return
        val now = System.nanoTime()
        val interval = if (pingBurst > 0) 50_000_000L else 200_000_000L
        if (now - lastPing >= interval) {
            lastPing = now
            if (pingBurst > 0) pingBurst--
            val b = Wire.ping(sessionId, now, pingSeq++)
            udp.send(DatagramPacket(b, b.size, addr))
        }
        val nacks = rx.collectNacks(now)
        if (nacks.isNotEmpty()) {
            val b = Wire.nack(sessionId, rx.timeline.epoch, nacks)
            udp.send(DatagramPacket(b, b.size, addr))
        }
    }

    // ================================================================== аудиофокус / прерывания
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                post { onInterrupted("Звук прерван (звонок или другое приложение) — пара на паузе") }
        }
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .setWillPauseWhenDucked(false)
            .build()
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
    }

    private fun abandonFocus() {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Прерывание на любом телефоне ставит на паузу всю пару — состояние остаётся согласованным. */
    private fun onInterrupted(reason: String) {
        focusRequest = null
        if (!ui.value.inPair) return
        if (role == Role.SERVER) cmdPause() else link?.send(JSONObject().put("t", "cmd").put("a", "pause"))
        toast(reason)
    }
}
