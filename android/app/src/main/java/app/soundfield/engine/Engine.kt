package app.soundfield.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import app.soundfield.R
import app.soundfield.audio.AudioOutput
import app.soundfield.audio.MicRecorder
import app.soundfield.core.*
import app.soundfield.service.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

    companion object {
        const val TAG = "SoundField"
        @Volatile private var inst: Engine? = null
        fun get(ctx: Context): Engine = inst ?: synchronized(this) {
            inst ?: Engine(ctx.applicationContext).also { inst = it; it.boot() }
        }
        private val rnd = SecureRandom()
        private fun hex(bytes: Int) = ByteArray(bytes).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ инфраструктура
    private val ex = Executors.newSingleThreadScheduledExecutor { Thread(it, "sf-engine") }
    private val io = Executors.newCachedThreadPool { Thread(it, "sf-io") }
    private fun post(block: () -> Unit) {
        ex.execute { try { block() } catch (t: Throwable) { Log.e(TAG, "engine", t) } }
    }
    private fun later(ms: Long, block: () -> Unit): ScheduledFuture<*> =
        ex.schedule({ try { block() } catch (t: Throwable) { Log.e(TAG, "engine", t) } }, ms, TimeUnit.MILLISECONDS)

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
    @Volatile private var role = Role.SERVER
    private var link: ControlLink? = null
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
        tcp = ServerSocket(0)
        udp = DatagramSocket(0)
        discovery = Discovery(app, myId) { peers -> ui { copy(nearby = peers) } }
        Thread({ acceptLoop() }, "sf-accept").apply { isDaemon = true; start() }
        Thread({ udpLoop() }, "sf-udp").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
        Thread({ tickLoop() }, "sf-tick").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
        discovery.advertise(tcp.localPort, myName)
        discovery.startBrowse()
        ex.scheduleAtFixedRate({ try { heartbeat() } catch (t: Throwable) { Log.e(TAG, "hb", t) } }, 1, 1, TimeUnit.SECONDS)
        ex.scheduleAtFixedRate({ try { serverTick() } catch (t: Throwable) { Log.e(TAG, "tick", t) } }, 100, 100, TimeUnit.MILLISECONDS)
        ui { copy(myId = myId, myName = myName, remembered = record, durationMs = music.totalFrames * 1000 / SF.SAMPLE_RATE,
            title = music.title, artist = music.artist) }
    }

    private fun defaultName(): String {
        val n = try { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null }
        return (n ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}").take(40)
    }

    private fun loadMusic(): PcmSource {
        val bytes = app.resources.openRawResource(R.raw.test_track).use { it.readBytes() }
        val d = Wav.decode(bytes)
        return PcmSource(d.pcm, "Тестовый трек SoundField", "Стерео-демо · L: аккорды и бас · R: мелодия")
    }

    private fun acceptLoop() {
        while (true) {
            val s = try { tcp.accept() } catch (e: Exception) { SystemClock.sleep(200); continue }
            post { onIncomingSocket(s) }
        }
    }

    // ================================================================== публичный API (любой поток)
    fun invite(p: Peer) = post { doInvite(p) }
    fun cancelInvite() = post { doCancelPending() }
    fun acceptIncoming() = post { doAccept() }
    fun rejectIncoming() = post { doReject() }
    fun resumeRemembered() = post { doManualResume() }
    fun forgetPair() = post { record = null; prefs.edit().remove("pair").apply(); ui { copy(remembered = null) } }
    fun disconnect() = post { goIdle(sendBye = true, reason = "Пара разорвана") }
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
        .put("platform", "android").put("proto", SF.PROTOCOL_VERSION).put("key", key)

    private fun doInvite(p: Peer) {
        if (phase != Phase.IDLE) return
        pendingKey = hex(16)
        phase = Phase.INVITING
        ui { copy(phase = Phase.INVITING, invitingName = p.name) }
        connectOutgoing(p, "invite-out", inviteJson(pendingKey), 30_000)
    }

    private fun doManualResume() {
        val rec = record ?: return
        if (phase != Phase.IDLE) return
        phase = Phase.INVITING
        ui { copy(phase = Phase.INVITING, invitingName = rec.peerName) }
        manualResumeDeadline = SystemClock.elapsedRealtime() + 8_000
        discovery.startBrowse()
        tryManualResume()
    }

    private fun tryManualResume() {
        if (phase != Phase.INVITING || pending != null) return
        val rec = record ?: return
        val p = discovery.peer(rec.peerId)
        if (p?.host != null) { connectOutgoing(p, "resume-out", resumeJson(rec), 4_000); return }
        if (SystemClock.elapsedRealtime() > manualResumeDeadline) {
            phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }
            toast("«${rec.peerName}» не найден. Откройте SoundField на втором телефоне в той же сети Wi‑Fi")
            return
        }
        later(500) { tryManualResume() }
    }

    private fun resumeJson(rec: PairRecord) = JSONObject().put("t", "resume").put("id", myId).put("name", myName)
        .put("platform", "android").put("proto", SF.PROTOCOL_VERSION).put("key", rec.key).put("role", rec.myRole.wire)

    private fun connectOutgoing(p: Peer, tag: String, first: JSONObject, timeoutMs: Long) {
        val host = p.host ?: return onConnectFailed(tag)
        pendingPeer = p
        io.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, p.port), 3_000)
                post {
                    val stillWanted = when (tag) {
                        "invite-out" -> phase == Phase.INVITING && pendingPeer?.id == p.id
                        else -> phase == Phase.RECONNECTING || phase == Phase.INVITING
                    }
                    if (!stillWanted || pending != null) { try { s.close() } catch (_: Exception) {}; return@post }
                    val l = newLink(s); l.tag = tag
                    pending = l
                    l.send(first)
                    pendingTimeout?.cancel(false)
                    pendingTimeout = later(timeoutMs) { if (pending === l) l.close() }
                }
            } catch (e: Exception) {
                Log.i(TAG, "connect $tag failed: ${e.message}")
                post { onConnectFailed(tag) }
            }
        }
    }

    private fun onConnectFailed(tag: String) {
        when {
            tag == "invite-out" && phase == Phase.INVITING -> {
                phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }; toast("Не удалось подключиться к телефону")
            }
            tag == "resume-out" && phase == Phase.RECONNECTING -> scheduleReconnect(1_500)
            tag == "resume-out" && phase == Phase.INVITING -> later(700) { tryManualResume() }
        }
    }

    private fun newLink(s: Socket) = ControlLink(s,
        onMessage = { l, o -> post { onMessage(l, o) } },
        onClosed = { l -> post { onClosed(l) } })

    private fun onIncomingSocket(s: Socket) {
        val l = newLink(s)
        l.tag = "new"
        later(5_000) { if (l.tag == "new") l.close() }
    }

    private fun doCancelPending() {
        val l = pending
        pending = null
        l?.let { it.tag = "dead"; it.sendAndClose(JSONObject().put("t", "cancel")) }
        if (phase == Phase.INVITING || phase == Phase.INCOMING) { phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) } }
    }

    private fun doAccept() {
        val l = pending ?: return
        val p = pendingPeer ?: return
        if (phase != Phase.INCOMING) return
        l.send(JSONObject().put("t", "accept").put("id", myId).put("name", myName).put("platform", "android")
            .put("key", pendingKey).put("role", Role.POINT.wire))
        becomePaired(l, Role.POINT, p.id, p.name, p.platform, pendingKey, resumed = false)
    }

    private fun doReject() {
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
        val pid = o.optString("id"); val pname = o.optString("name", pid); val pplat = o.optString("platform", "?")
        when (o.optString("t")) {
            "invite" -> {
                if (phase == Phase.IDLE && pending == null) {
                    l.tag = "incoming"; pending = l
                    pendingPeer = Peer(pid, pname, pplat, l.remoteAddress, 0)
                    pendingKey = o.optString("key")
                    phase = Phase.INCOMING
                    ui { copy(phase = Phase.INCOMING, incomingName = pname) }
                    pendingTimeout?.cancel(false)
                    pendingTimeout = later(60_000) { if (pending === l) doReject() }
                } else if (phase == Phase.INVITING && pendingPeer?.id == pid) {
                    // Встречные приглашения: уступает телефон с бо́льшим id (детерминированно на обеих сторонах).
                    if (myId > pid) {
                        pending?.let { it.tag = "dead"; it.close() }
                        l.tag = "incoming"; pending = l
                        pendingPeer = Peer(pid, pname, pplat, l.remoteAddress, 0)
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
                if (!ok) { l.tag = "dead"; l.sendAndClose(JSONObject().put("t", "reject").put("reason", "unknown")); return }
                // старое (возможно, «мёртвое») соединение заменяется новым
                link?.let { old -> link = null; old.tag = "dead"; old.close() }
                pending?.let { p -> pending = null; p.tag = "dead"; p.close() }
                l.send(JSONObject().put("t", "accept").put("id", myId).put("name", myName).put("platform", "android")
                    .put("key", rec!!.key).put("role", rec.myRole.wire))
                becomePaired(l, rec.myRole, pid, pname, pplat, rec.key, resumed = true)
            }
            else -> { l.tag = "dead"; l.close() }
        }
    }

    private fun onPendingMessage(l: ControlLink, o: JSONObject) {
        val pid = o.optString("id"); val pname = o.optString("name", pid); val pplat = o.optString("platform", "?")
        when (o.optString("t")) {
            "accept" -> when (l.tag) {
                "invite-out" -> becomePaired(l, Role.SERVER, pid, pname, pplat, pendingKey, resumed = false)
                "resume-out" -> {
                    val their = Role.fromWire(o.optString("role"))
                    val mine = if (their == Role.SERVER) Role.POINT else Role.SERVER
                    becomePaired(l, mine, pid, pname, pplat, o.optString("key"), resumed = true)
                }
            }
            "reject", "busy" -> {
                val reason = o.optString("reason")
                pending = null; l.tag = "dead"; l.close()
                when {
                    phase == Phase.INVITING && reason == "crossed" -> { /* ждём встречного accept от другой стороны */ }
                    phase == Phase.INVITING -> {
                        phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }
                        toast(if (o.optString("t") == "busy") "Телефон уже в другой паре" else "Подключение отклонено")
                    }
                    phase == Phase.RECONNECTING -> scheduleReconnect(2_000)
                }
            }
            "cancel" -> if (l.tag == "incoming") {
                pending = null; l.tag = "dead"; l.close()
                phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }; toast("Приглашение отменено")
            }
        }
    }

    private fun onClosed(l: ControlLink) {
        if (l === link) { onLinkLost(); return }
        if (l === pending) {
            pending = null
            when (phase) {
                Phase.INCOMING -> { phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) } }
                Phase.INVITING -> if (l.tag == "resume-out") later(700) { tryManualResume() } else {
                    phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }; toast("Нет ответа от второго телефона")
                }
                Phase.RECONNECTING -> scheduleReconnect(1_500)
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
        val continuing = phase == Phase.RECONNECTING && role == r && peerId == pid
        l.tag = "paired"
        link = l
        role = r; peerId = pid; peerName = pname; peerPlatform = pplat
        phase = Phase.PAIRED

        val old = record
        record = if (old != null && old.key == key) old.copy(peerName = pname, peerPlatform = pplat, myRole = r)
                 else PairRecord(key, pid, pname, pplat, r)
        persistRecord()
        discovery.stopBrowse()

        if (r == Role.SERVER) {
            record?.let { rec ->
                if (!continuing) { pointChannel = rec.pointChannel; trimNs = (rec.trimMs * 1e6).toLong()
                    volL = rec.volL; volR = rec.volR; master = rec.master }
            }
            sessionId = rnd.nextInt().let { if (it == 0) 1 else it }
            pointUdp = null; pointClockReady = false
            if (!continuing) { mode = SourceMode.MUSIC; source = music; newEpoch(false, 0, System.nanoTime()) }
            else rebuildPlayState()
            audio.source = serverRenderer
            l.send(JSONObject().put("t", "session").put("session", sessionId).put("udpPort", udp.localPort)
                .put("name", myName))
            sendTimeline()
            publishState()
        } else {
            sessionId = 0; serverUdp = null
            clock.reset(); rx.reset()
            audio.source = pointRenderer
        }
        audio.start()
        try { PlaybackService.start(app) } catch (e: Exception) { Log.w(TAG, "fgs", e) }
        ui { copy(phase = Phase.PAIRED, role = r, peerName = pname, peerPlatform = pplat, linkOk = true, remembered = record,
            calib = CalibUi()) }
        PlaybackService.refresh(app)
        toast(if (resumed) "Пара восстановлена" else "Стереопара создана")
    }

    private fun onLinkLost() {
        link = null
        if (phase != Phase.PAIRED) return
        phase = Phase.RECONNECTING
        ui { copy(phase = Phase.RECONNECTING, linkOk = false) }
        if (role == Role.SERVER) {
            pointUdp = null
            toast("Нет связи с «$peerName». Музыка продолжается, ждём возвращения…")
        } else {
            rx.reset(); serverUdp = null; sessionId = 0
            reconnectSince = SystemClock.elapsedRealtime()
            discovery.startBrowse()
            scheduleReconnect(300)
            toast("Связь потеряна — переподключаемся…")
        }
        PlaybackService.refresh(app)
    }

    private fun scheduleReconnect(delayMs: Long) {
        reconnectTask?.cancel(false)
        reconnectTask = later(delayMs) { attemptResume() }
    }

    private fun attemptResume() {
        if (phase != Phase.RECONNECTING || role != Role.POINT || pending != null) return
        val rec = record ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBrowseRestart > 10_000) { lastBrowseRestart = now; discovery.restartBrowse() }
        val p = discovery.peer(rec.peerId)
        if (p?.host == null) { scheduleReconnect(1_000); return }
        connectOutgoing(p, "resume-out", resumeJson(rec), 4_000)
    }

    private fun goIdle(sendBye: Boolean, reason: String) {
        reconnectTask?.cancel(false); pendingTimeout?.cancel(false)
        link?.let { l -> link = null; l.tag = "dead"; if (sendBye) l.sendAndClose(JSONObject().put("t", "bye")) else l.close() }
        pending?.let { p -> pending = null; p.tag = "dead"; p.close() }
        phase = Phase.IDLE
        audio.stop(); audio.source = null
        abandonFocus()
        try { PlaybackService.stop(app) } catch (_: Exception) {}
        serverPlay = ServerPlayState(Timeline.NONE, music, Ch.L, 0f)
        pointUdp = null; serverUdp = null; sessionId = 0
        rx.reset(); clock.reset()
        calibRunning = false
        discovery.startBrowse()
        ui { copy(phase = Phase.IDLE, linkOk = false, remembered = record, playing = false, calib = CalibUi(),
            serverDiag = Diag(), pointDiag = Diag()) }
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
            "bye" -> goIdle(sendBye = false, reason = "«$peerName» разорвал пару")
            "name" -> { peerName = o.optString("name", peerName); ui { copy(peerName = peerName) }
                        record = record?.copy(peerName = peerName); persistRecord() }
            // --- на Server ---
            "cmd" -> if (role == Role.SERVER) handleCmd(o)
            "diag" -> if (role == Role.SERVER) {
                val d = Diag.fromJson(o); pointClockReady = d.clockReady; ui { copy(pointDiag = d) }
            }
            // --- на Point ---
            "session" -> if (role == Role.POINT) {
                sessionId = o.getInt("session")
                val l = link ?: return
                serverUdp = InetSocketAddress(l.remoteAddress, o.getInt("udpPort"))
                pingBurst = 20
            }
            "state" -> if (role == Role.POINT) applyState(o)
            "timeline" -> if (role == Role.POINT) {
                rx.onTimeline(Timeline(o.getInt("epoch"), o.getBoolean("playing"),
                    o.getString("anchorFrame").toLong(), o.getString("anchorNs").toLong()))
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

    private fun newEpoch(playing: Boolean, frame: Long, anchorNs: Long) {
        epoch++
        serverPlay = ServerPlayState(Timeline(epoch, playing, frame, anchorNs), source, serverChannel(), serverGain())
        sendTimeline()
        publishState()
    }

    private fun sendTimeline() {
        val tl = serverPlay.timeline
        link?.send(JSONObject().put("t", "timeline").put("epoch", tl.epoch).put("playing", tl.playing)
            .put("anchorFrame", tl.anchorFrame.toString()).put("anchorNs", tl.anchorNs.toString()))
    }

    private fun stateJson(): JSONObject {
        val tl = serverPlay.timeline
        return JSONObject().put("t", "state").put("playing", tl.playing).put("durFrames", source.totalFrames)
            .put("title", source.title).put("artist", source.artist).put("mode", mode.wire)
            .put("volL", volL.toDouble()).put("volR", volR.toDouble()).put("master", master.toDouble())
            .put("pointCh", Ch.name(pointChannel)).put("trimMs", trimNs / 1e6).put("serverName", myName)
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
        ui { copy(playing = playing, durationMs = dur * 1000 / SF.SAMPLE_RATE, title = o.optString("title"),
            artist = o.optString("artist"), mode = SourceMode.fromWire(o.optString("mode")),
            volL = vL, volR = vR, master = m, pointChannel = pCh, trimMs = trim) }
        if (playing) requestFocus()
        PlaybackService.refresh(app)
    }

    private fun posFrame(): Long = serverPlay.timeline.positionFrame(System.nanoTime()).coerceIn(0L, source.totalFrames)

    private fun handleCmd(o: JSONObject) {
        val now = System.nanoTime()
        when (o.optString("a")) {
            "play" -> cmdPlay()
            "pause" -> cmdPause()
            "toggle" -> if (serverPlay.timeline.playing && mode != SourceMode.CALIBRATION) cmdPause() else cmdPlay()
            "seek" -> seekFrame(o.optLong("ms") * SF.SAMPLE_RATE / 1000)
            "skip" -> seekFrame(posFrame() + o.optLong("ms") * SF.SAMPLE_RATE / 1000)
            "vol" -> {
                val v = o.optDouble("v", 1.0).toFloat().coerceIn(0f, 1f)
                when (o.optString("ch")) { "L" -> volL = v; "R" -> volR = v; else -> master = v }
                rebuildPlayState(); saveServerParams(); publishState()
            }
            "swap" -> {
                if (calibRunning) return
                pointChannel = Ch.other(pointChannel); saveServerParams()
                val tl = serverPlay.timeline
                if (tl.playing) newEpoch(true, posFrame(), now + SF.START_DELAY_NS) else newEpoch(false, tl.anchorFrame, now)
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
        if (mode != SourceMode.MUSIC) restoreMusic(false)
        val tl = serverPlay.timeline
        if (tl.playing) return
        val f = if (tl.anchorFrame >= source.totalFrames) 0L else tl.anchorFrame
        newEpoch(true, f, System.nanoTime() + SF.START_DELAY_NS)
    }

    private fun cmdPause() {
        if (calibRunning) return
        if (mode != SourceMode.MUSIC) { restoreMusic(false); return }
        val tl = serverPlay.timeline
        if (!tl.playing) return
        newEpoch(false, posFrame(), System.nanoTime())
    }

    private fun seekFrame(frame: Long) {
        if (calibRunning) return
        if (mode != SourceMode.MUSIC) restoreMusic(false)
        val f = frame.coerceIn(0L, maxOf(0L, source.totalFrames - 1))
        val playing = serverPlay.timeline.playing
        val now = System.nanoTime()
        newEpoch(playing, f, if (playing) now + SF.START_DELAY_NS else now)
    }

    private fun startTestMode(src: AudioSource) {
        if (calibRunning) return
        if (mode == SourceMode.MUSIC) { savedFrame = posFrame(); savedPlaying = serverPlay.timeline.playing }
        mode = src.mode; source = src
        newEpoch(true, 0, System.nanoTime() + SF.START_DELAY_NS)
    }

    private fun restoreMusic(resume: Boolean) {
        mode = SourceMode.MUSIC; source = music
        val now = System.nanoTime()
        newEpoch(resume, savedFrame, if (resume) now + SF.START_DELAY_NS else now)
    }

    /** Каждые 100 мс: конец трека / конец тестового сигнала. */
    private fun serverTick() {
        if (role != Role.SERVER || !ui.value.inPair) return
        val tl = serverPlay.timeline
        if (!tl.playing || tl.positionFrame(System.nanoTime()) < source.totalFrames) return
        when (mode) {
            SourceMode.MUSIC -> newEpoch(false, 0, System.nanoTime())
            SourceMode.CHANNEL_TEST -> restoreMusic(savedPlaying)
            else -> {}
        }
    }

    // ================================================================== калибровка
    private fun startCalibration(apply: Boolean) {
        if (calibRunning) return
        if (phase != Phase.PAIRED || pointUdp == null) { broadcastCalib(false, "", "Point не на связи — калибровка невозможна", false, null); return }
        if (!pointClockReady) { broadcastCalib(false, "", "Часы ещё синхронизируются. Повторите через пару секунд", false, null); return }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ui { copy(calib = calib.copy(needsMicPermission = true)) }
            broadcastCalib(false, "", "Нужен доступ к микрофону на телефоне «$myName» (Server)", false, null, needsMic = true)
            return
        }
        calibRunning = true
        if (mode == SourceMode.MUSIC) { savedFrame = posFrame(); savedPlaying = serverPlay.timeline.playing }
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
        val l = link ?: return
        if (phase != Phase.PAIRED) return
        if (SystemClock.elapsedRealtime() - l.lastRx > SF.LINK_TIMEOUT_MS) { Log.i(TAG, "heartbeat timeout"); l.close(); return }
        l.send(JSONObject().put("t", "hb"))
        if (role == Role.SERVER) {
            val d = Diag(syncErrMs = serverRenderer.corrector.syncErrorMs, latencyMs = audio.latencyMs,
                underruns = audio.underruns, resent = resent.get(), clockReady = true, valid = true)
            ui { copy(serverDiag = d) }
            l.send(d.toJson("sdiag"))
        } else {
            val recv = rx.received; val lost = rx.lostFinal
            val dR = recv - lastRecv; val dL = lost - lastLost
            lastRecv = recv; lastLost = lost
            val loss = if (dR + dL > 0) dL * 100.0 / (dR + dL) else 0.0
            val d = Diag(rttMs = clock.rttAvgMs, rttJitterMs = clock.rttJitterMs, jitterMs = rx.jitterMs, lossPct = loss,
                recovered = rx.recovered, late = rx.late, stale = rx.stale, bufferMs = rx.bufferMs,
                driftPpm = clock.driftPpm, clockUncMs = clock.uncertaintyMs,
                underruns = pointRenderer.underrunEvents + audio.underruns, syncErrMs = pointRenderer.corrector.syncErrorMs,
                latencyMs = audio.latencyMs, clockReady = clock.ready, valid = true)
            ui { copy(pointDiag = d) }
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
                val m = Wire.decode(pkt.data, pkt.length) ?: continue
                if (m.session != sessionId || sessionId == 0) continue
                when (m) {
                    is UdpMsg.Ping -> if (role == Role.SERVER && phase == Phase.PAIRED) {
                        pointUdp = pkt.socketAddress as InetSocketAddress
                        val b = Wire.pong(sessionId, m.t1, now, System.nanoTime(), m.pingSeq)
                        udp.send(DatagramPacket(b, b.size, pkt.socketAddress))
                    }
                    is UdpMsg.Nack -> if (role == Role.SERVER) resend(m)
                    is UdpMsg.Pong -> if (role == Role.POINT) clock.addPong(m.t1, m.t2, m.t3, now)
                    is UdpMsg.Audio -> if (role == Role.POINT && phase == Phase.PAIRED) rx.onAudio(m, now)
                }
            } catch (e: Exception) {
                Log.w(TAG, "udp: ${e.message}"); SystemClock.sleep(10)
            }
        }
    }

    private fun tickLoop() {
        while (true) {
            try {
                if (phase == Phase.PAIRED) {
                    if (role == Role.SERVER) serverSend() else pointNet()
                }
            } catch (e: Exception) { Log.w(TAG, "tick: ${e.message}") }
            SystemClock.sleep(5)
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
