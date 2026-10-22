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
import app.soundfield.BuildConfig
import app.soundfield.diagnostics.Telemetry
import app.soundfield.diagnostics.TelemetryContext
import app.soundfield.diagnostics.TelemetryJson
import app.soundfield.diagnostics.TelemetrySummary
import app.soundfield.diagnostics.SnapshotRing
import app.soundfield.diagnostics.IncidentRuleEngine
import app.soundfield.diagnostics.AndroidTelemetryRules
import app.soundfield.diagnostics.IncidentUpdate
import app.soundfield.diagnostics.AtomicLatencyWindow
import app.soundfield.diagnostics.AtomicSignalMeter
import app.soundfield.diagnostics.TelemetryIdentifiers
import app.soundfield.diagnostics.TelemetryText
import app.soundfield.dsp.DSP_VERSION
import app.soundfield.dsp.SpatialProfile
import app.soundfield.dsp.SpatialProfiles
import app.soundfield.dsp.XtcFilterDesigner
import app.soundfield.dsp.XtcFilterSet
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
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
        val output: BufferedOutputStream, val startedElapsedMs: Long, val op: String, var received: Long = 0,
    )
    private data class IncomingTelemetryTransfer(
        val id: String, val temporaryFile: File, var received: Long = 0,
    )
    private data class RemoteTelemetryExport(val journal: String, val state: String)
    private data class NetworkRoute(val network: Network, val linkProperties: LinkProperties) {
        val interfaceName: String get() = linkProperties.interfaceName ?: "unknown"
        val label: String get() = "$interfaceName@${network}"
        val networkId: String get() = network.toString()
    }
    private data class UdpBindResult(val success: Boolean, val portChanged: Boolean)
    private data class ConnectionCandidate(val host: InetAddress, val route: NetworkRoute?) {
        fun label() = "${route?.label ?: "process_default"} → ${host.hostAddress}"
    }
    private data class ConnectedSocket(val socket: Socket, val route: NetworkRoute?, val host: InetAddress)
    private class ConnectionAttemptsException(val outcomes: List<String>, val candidates: List<PeerPathDiagnosis.Candidate>, cause: Throwable?) :
        Exception("All ${outcomes.size} connection candidates failed", cause)
    private data class AudioPreload(
        val epoch: Int, val startFrame: Long, val source: AudioSource, val channel: Int,
        val trackId: String, val startedAtMs: Long,
    )

    companion object {
        const val TAG = "SoundField"
        private const val SOLO_SERVICE_IDLE_MS = 5 * 60_000L
        @Volatile private var inst: Engine? = null
        fun get(ctx: Context): Engine = inst ?: synchronized(this) {
            inst ?: Engine(ctx.applicationContext).also { inst = it; it.boot() }
        }
        private val rnd = SecureRandom()
        private fun hex(bytes: Int) = ByteArray(bytes).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        private const val TRANSFER_CHUNK_BYTES = 48 * 1024
        private const val TELEMETRY_MAX_BYTES = 8L * 1024 * 1024
        private const val CONNECT_STAGGER_MS = 200L
        private const val TCP_AUDIO_TARGET_FRAMES = 30L * SF.SAMPLE_RATE
        private const val TCP_AUDIO_MIN_START_FRAMES = SF.SAMPLE_RATE / 2
        private const val TCP_AUDIO_MAX_WAIT_MS = 2_000L
        private const val MAX_TRANSFER_FILE_BYTES = 512L * 1024 * 1024
        private const val MAX_TRANSFER_BATCH_BYTES = 1024L * 1024 * 1024
        private val PROGRESSIVE_LOOKAHEAD_FRAMES =
            (SF.LEAD_NS + SF.PAUSE_LEAD_NS + 100_000_000L) * SF.SAMPLE_RATE / 1_000_000_000L
        private val PROGRESSIVE_RESUME_BUFFER_FRAMES = SF.SAMPLE_RATE.toLong()
        private val PROGRESSIVE_FADE_FRAMES = SF.SAMPLE_RATE / 10L
    }

    private val telemetry = Telemetry.get(app)
    private val blackBox = SnapshotRing<Map<String, Any?>>(120)
    private val incidentRules = IncidentRuleEngine(AndroidTelemetryRules.all)
    private val tickIntervals = AtomicLatencyWindow()
    private val lastTickAtNs = AtomicLong()
    private val telemetryAcks = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val telemetryReady = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val telemetryExports = ConcurrentHashMap<String, CompletableFuture<RemoteTelemetryExport>>()
    private val levelCaptureAcks = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val pendingRemoteStates = ConcurrentHashMap<String, String>()
    private val incomingTelemetry = ConcurrentHashMap<String, IncomingTelemetryTransfer>()
    private val telemetryRateSamples = ArrayDeque<Long>()
    private var lastHeartbeatAtMs = 0L
    private var lastHeartbeatState: Map<String, Any?>? = null
    private var previousUdpPackets = 0L
    private var previousSentAudioPackets = 0L
    private var previousNacksSent = 0L
    private var previousUdpInvalid = 0L
    private var previousUdpOtherSession = 0L
    private var lastSlewEvents = 0L
    private val pointChannelSignal = AtomicSignalMeter()
    @Volatile private var debugUdpAudioStopped = false
    @Volatile private var audioFocusHeld = false
    private var currentDecodeOp: String? = null
    private var calibrationOp: String? = null
    private var fileTransferOp: String? = null
    @Volatile private var transferLastProgressAtMs = 0L
    private var decodeReadyAtLastCheck = -1L
    private var decodeReadyChangedAtMs = 0L
    private var decodeReadyTrackTag: String? = null
    private var previousPointAudioPackets = 0L
    private var reconnectSeriesOp: String? = null
    private var reconnectAttemptOp: String? = null
    private val reconnectCandidateHistory = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var peerUnreachableReported = false
    private var lastCommandOp: String? = null
    private var lastCommandAppliedSrvNs = 0L
    private var lastCommandIssuedSrvNs = 0L
    private var lastObservedCommandOp: String? = null

    // ------------------------------------------------------------------ инфраструктура
    private val ex = Executors.newSingleThreadScheduledExecutor { Thread(it, "sf-engine") }
    private val io = Executors.newCachedThreadPool { Thread(it, "sf-io") }
    private fun post(block: () -> Unit) {
        ex.execute { try { block() } catch (t: Throwable) { Log.e(TAG, "engine", t); telemetry.event("ENGINE_EXCEPTION", error = t) } }
    }
    private fun later(ms: Long, block: () -> Unit): ScheduledFuture<*> =
        ex.schedule({ try { block() } catch (t: Throwable) { Log.e(TAG, "engine", t); telemetry.event("ENGINE_EXCEPTION", error = t) } }, ms, TimeUnit.MILLISECONDS)

    private val prefs = app.getSharedPreferences("soundfield", Context.MODE_PRIVATE)
    private var processFreezeCount = prefs.getInt("processFreezeCount", 0)
    private var processFreezeTotalMs = prefs.getLong("processFreezeTotalMs", 0L)
    val myId: String = prefs.getString("id", null) ?: hex(4).also { prefs.edit().putString("id", it).apply() }
    var myName: String = prefs.getString("name", null) ?: defaultName(); private set

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    private fun ui(f: UiState.() -> UiState) = _ui.update { it.f() }
    private var msgCounter = 0
    private fun toast(text: String) {
        telemetry.record("USER_MESSAGE", mapOf("text" to text))
        msgCounter++; val id = msgCounter; ui { copy(message = text, messageId = id) }
    }

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
    private var pendingStartFrame = 0L
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
    private lateinit var tcpAudioServer: ServerSocket
    @Volatile private lateinit var udp: DatagramSocket
    private val udpRebindLock = Any()
    @Volatile private var udpBoundNetworkLabel: String = "unbound"
    @Volatile private var udpBoundLocalAddress: String = ""
    private val pointPingPacketsSent = AtomicLong()
    private val pointPingSendErrors = AtomicLong()
    private var zeroSendRenewStreak = 0
    private var udpNetworkRebindAttempted = false
    private var udpFailureNoticeShown = false
    private var sessionSentBaseline = 0L
    private var audioRenewIntervalMs = 5_000L
    private var lastMultiWifiSignature = ""
    private val tcpAudioBuffer = TcpAudioBuffer()
    private val pointAudioConnecting = AtomicBoolean(false)
    @Volatile private var currentAudioPath = "udp"
    @Volatile private var currentClockPath = "udp"
    @Volatile private var serverTcpAudioSocket: Socket? = null
    @Volatile private var pointTcpAudioSocket: Socket? = null
    @Volatile private var pointAudioPort = 0
    @Volatile private var tcpAudioPreload: AudioPreload? = null
    private var tcpAudioPreloadTask: ScheduledFuture<*>? = null
    @Volatile private var pointAudioPreloadEpoch = Int.MIN_VALUE
    @Volatile private var pointAudioPreloadFrame = 0L
    private val pointAudioReadyEpoch = AtomicLong(Int.MIN_VALUE.toLong())
    private val tcpAudioFramesSent = AtomicLong()
    private val tcpAudioFramesReceived = AtomicLong()
    private val lastTcpAudioRxAt = AtomicLong()
    private val tcpAudioFramesSinceMetric = AtomicLong()
    private var previousTcpAudioFrames = 0L
    @Volatile private var tcpAudioFillFramesPerSec = 0.0
    private var lastTcpAudioMetricAtMs = 0L
    private val lastUdpPongAtMs = AtomicLong()
    private var lastTcpClockPingAtNs = 0L
    private var tcpClockPingSeq = 0
    @Volatile private var debugStopUdpTraffic = false
    private lateinit var discovery: Discovery

    // ------------------------------------------------------------------ сессия
    @Volatile private var phase = Phase.IDLE
        set(value) { if (field != value) telemetry.event("PHASE", "$field -> $value"); field = value }
    @Volatile private var soloMode = false
    @Volatile private var role = Role.SERVER
    @Volatile private var link: ControlLink? = null
    private var pending: ControlLink? = null
    private var pendingPeer: Peer? = null
    private var pendingKey = ""
    private var pendingTimeout: ScheduledFuture<*>? = null
    private var peerId = ""
    private var peerName = ""
    private var peerPlatform = ""
    private var peerAddressSnapshot: List<PeerAddress> = emptyList()
    @Volatile private var sessionId = 0
    private var record: PairRecord? = PairRecord.fromJson(prefs.getString("pair", null))
    private var eqSettings = record?.eq ?: EqSettings()
    private var spatialSettings = record?.spatial ?: SpatialSettings()
    @Volatile private var eqConfig = EqualizerMath.config(eqSettings)
    private var reconnectTask: ScheduledFuture<*>? = null
    private var reconnectSince = 0L
    private var lastBrowseRestart = 0L
    private var manualResumeDeadline = 0L
    /** Идёт восстановление сохранённой пары с экрана «Знакомая пара» (вручную или автоматически). */
    private var manualResumeActive = false
    private var manualResumeAuto = false
    /** Manual action that started while this peer already had the sticky LAN isolation verdict. */
    private var manualLanBlockedPeerId: String? = null
    private var resumeAttempt = 0
    /** Порт TCP-сервера второго телефона, полученный в invite/resume/accept, — для быстрого переподключения. */
    private var peerTcpPortHint = 0
    private var peerCapabilities: Set<String> = emptySet()
    @Volatile private var negotiatedCapabilities: Set<String> = emptySet()
    /** Пара должна восстанавливаться сама (после запуска/возврата в приложение), пока её явно не разорвали. */
    private var autoResume = prefs.getBoolean("autoResume", false)
    /** До какого момента (elapsedRealtime) в состоянии IDLE тихо пробуем восстановить знакомую пару. */
    private var autoResumeUntil = 0L
    private var lastAutoAttempt = 0L
    private val networkLinks = ConcurrentHashMap<Network, String>()
    /** Приложение на экране. В фоне без пары Android замораживает процесс — такой телефон не должен быть виден. */
    @Volatile private var appVisible = true
    private var inviteRinging = false
    private val incomingLinks = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<ControlLink, Boolean>())
    private val peerPathDiagnoses = ConcurrentHashMap<String, PeerPathDiagnosis>()
    private val gatewayProbeAt = ConcurrentHashMap<String, Long>()
    private val gatewayProbeState = ConcurrentHashMap<String, String>()
    private val lanHintAt = ConcurrentHashMap<String, Long>()
    private val lanBlockedLogAt = ConcurrentHashMap<String, Long>()
    private val lanBlockedLogCount = ConcurrentHashMap<String, Int>()
    private var lastTelemetry = 0L
    private var lastLimiterWindowAtMs = 0L
    private var limiterWindowCount = 0L
    private var limiterWindowMaxDb = 0.0
    private var lastSpatialPerfAtMs = 0L
    private val udpPackets = AtomicLong()
    private val udpInvalid = AtomicLong()
    private val udpOtherSession = AtomicLong()
    private val udpLastRx = AtomicLong()
    private val udpFirstPacketPending = AtomicBoolean(false)
    private val udpFirstPacketAtMs = AtomicLong()
    private val udpFirstPacketFrom = AtomicReference<String?>(null)
    private val udpReceiveFailures = AtomicLong()
    private val udpSendFailures = AtomicLong()
    private val udpReceiveError = AtomicReference<Throwable?>(null)
    private val udpSendError = AtomicReference<Throwable?>(null)
    private val connLog = ArrayDeque<String>()
    private val logTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    // ------------------------------------------------------------------ Server
    @Volatile private var serverPlay = ServerPlayState(Timeline.NONE, music, Ch.L, 0.8f, eqConfig)
    @Volatile private var activeSpatialInput: AudioSource? = null
    @Volatile private var activeSpatialSource: SpatialSource? = null
    @Volatile private var spatialReprocessFrame = 0L
    @Volatile private var spatialReprocessToken = 0L
    @Volatile private var spatialBypassedInput: AudioSource? = null
    @Volatile private var spatialBypassLocked = false
    private var spatialAutoBypassCount = 0
    private val spatialAutoBypassPending = AtomicBoolean(false)
    @Volatile private var xtcReport = XtcDeveloperReport()
    @Volatile private var spatialPerfInput: AudioSource? = null
    private var spatialPerfStartedAtMs = 0L
    private var spatialSlowSinceMs = 0L
    private val spatialProfiles by lazy {
        runCatching { app.assets.open("spatial_profiles.json").bufferedReader().use { SpatialProfiles.parse(it.readText()) } }
            .getOrDefault(emptyMap())
    }
    private val spatialDeviceTable by lazy {
        runCatching { app.assets.open("device_speakers.json").bufferedReader().use { JSONObject(it.readText()).optJSONObject("devices") } }
            .getOrNull()
    }
    private var epoch = 0
    private var preloadEpochCounter = 0
    private var mode = SourceMode.MUSIC
    @Volatile private var source: AudioSource = music
    private var savedFrame = 0L
    private var savedPlaying = false
    private var volL = 1f
    private var volR = 1f
    private var master = 0.96f
    private var balance = 0.5f
    private var levelTrimServerDb = 0f
    private var levelTrimPointDb = 0f
    private var delayCalib = DelayCalib()
    private var levelCalib = LevelCalib()
    private var levelMatchStartServerDb = 0f
    private var levelMatchStartPointDb = 0f
    private var peerGainLawReady = false
    private var didAcknowledgeGainLaw = false
    private var micReferenceOne: LevelAnalyzer.Result? = null
    private var micReferenceDeltaOne: Double? = null
    private var micReferenceDeltaTwo: Double? = null
    private var micSensitivityDeltaDb: Double? = null
    private var levelMeasureText = ""
    private var levelMeasureSavedServerDb = 0f
    private var levelMeasureSavedPointDb = 0f
    private val gainThrottleLock = Any()
    private val lastGainCommandAtNs = HashMap<String, Long>()
    private val pendingGainCommands = HashMap<String, () -> Unit>()
    private val scheduledGainCommands = HashMap<String, java.util.concurrent.ScheduledFuture<*>>()
    @Volatile private var pointChannel = Ch.R
    private var activeSoloEqRouteKey = ""
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
    @Volatile private var syncProbeActive = false
    private var syncProbeOp: String? = null
    private var syncProbeSavedPointChannel = Ch.R
    private var syncProbePreviousAudioPath = "udp"
    private var pointClockReady = false
    private val resent = AtomicLong()
    private val sendLock = Any()
    private var sendSeq = 0
    private var sentEpoch = -1
    private var nextFrame = 0L
    private val cacheSeq = IntArray(1024) { -1 }
    private val cacheEpoch = IntArray(1024)
    private val cacheStart = LongArray(1024)
    private val cacheCount = IntArray(1024)

    // ------------------------------------------------------------------ Point
    private val clock = ClockSync()
    private val rx = PointReceiver(clock)
    @Volatile private var pointParams = PointParams(0.8f, 0, 0)
    private val pointRenderer = PointRenderer(rx, clock, tcpAudioBuffer, { currentAudioPath }) { pointParams }
    @Volatile private var serverUdp: InetSocketAddress? = null
    private var pingSeq = 0
    @Volatile private var pingBurst = 0
    private var lastPing = 0L
    private var lastRecv = 0L
    private var lastLost = 0L

    init {
        audio.onRouteChanged = { post { handleAudioRouteChanged() } }
        telemetry.configure(context = {
            val currentRole = role
            val currentEpoch = if (currentRole == Role.SERVER) serverPlay.timeline.epoch else rx.timeline.epoch
            val currentTrack = if (currentRole == Role.SERVER) queue.getOrNull(queueIndex)?.id
                else ui.value.queueTracks.getOrNull(ui.value.queueIndex)?.id
            TelemetryContext(
                deviceId = myId,
                role = currentRole.name.lowercase(),
                pair = record?.key?.let(TelemetryIdentifiers::hash),
                session = sessionId.takeIf { it != 0 }?.toString(),
                epoch = currentEpoch,
                track = currentTrack?.takeIf { it.isNotBlank() }?.let(TelemetryIdentifiers::hash),
                serverNs = { ns -> if (role == Role.SERVER) ns else if (clock.ready) clock.toServer(ns) else null },
            )
        }, localRole = { if (role == Role.SERVER) "server" else "point" })
        telemetry.setCrashSnapshotProvider { blackBox.drain() }
    }

    // ================================================================== запуск
    private fun boot() {
        AudioFileDecoder.clearCache(app)
        refreshPlaylists()
        // Стараемся занять тот же TCP-порт, что и в прошлый раз: второй телефон помнит его и подключается напрямую.
        tcp = try { ServerSocket(prefs.getInt("tcpPort", 0)) } catch (e: Exception) { telemetry.event("TCP_LISTEN_FALLBACK", error = e); ServerSocket(0) }
        prefs.edit().putInt("tcpPort", tcp.localPort).apply()
        tcpAudioServer = ServerSocket(0)
        udp = DatagramSocket(0)
        discovery = Discovery(app, myId, onPeers = { peers ->
            ui { copy(nearby = peers) }
            post {
                val knownPeer = record?.peerId
                if (knownPeer != null && peers.any { it.id == knownPeer }) {
                    if (phase == Phase.RECONNECTING && pending == null) scheduleReconnect(0)
                    else if (manualResumeActive && phase == Phase.INVITING && pending == null) tryManualResume()
                }
            }
        }, onResolved = { peer -> post {
            peerPathDiagnoses.getOrPut(peer.id) { PeerPathDiagnosis() }
                .onResolved(peer.host?.hostAddress.orEmpty(), peer.port, SystemClock.elapsedRealtime())
        } }, onLost = { peerId -> post {
            val diagnosis = peerPathDiagnoses[peerId]
            val wasLanBlocked = diagnosis?.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED
            diagnosis?.onLost()
            if (manualLanBlockedPeerId == peerId) manualLanBlockedPeerId = null
            if (wasLanBlocked) ui { copy(showLanBlocked = false) }
        } })
        Thread({ acceptLoop() }, "sf-accept").apply { isDaemon = true; start() }
        Thread({ tcpAudioAcceptLoop() }, "sf-tcp-audio-accept").apply { isDaemon = true; start() }
        Thread({ udpLoop() }, "sf-udp").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
        Thread({ tickLoop() }, "sf-tick").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }
        val systemEffects = runCatching { android.media.audiofx.AudioEffect.queryEffects()?.map { effect ->
            mapOf("name" to effect.name, "implementor" to effect.implementor, "type" to effect.type.toString())
        }.orEmpty() }.getOrDefault(emptyList())
        telemetry.record("SYSTEM_EFFECTS_SEEN", mapOf("model" to Build.MODEL, "effects" to systemEffects))
        discovery.advertise(tcp.localPort, myName)
        discovery.startBrowse()
        ex.scheduleAtFixedRate({ try { heartbeat() } catch (t: Throwable) { Log.e(TAG, "hb", t); telemetry.event("HEARTBEAT_EXCEPTION", error = t) } }, 1, 1, TimeUnit.SECONDS)
        ex.scheduleAtFixedRate({ try { serverTick() } catch (t: Throwable) { Log.e(TAG, "tick", t); telemetry.limited("ENGINE_TICK_EXCEPTION", error = t) } }, 100, 100, TimeUnit.MILLISECONDS)
        ui { copy(myId = myId, myName = myName, remembered = record, durationMs = music.totalFrames * 1000 / SF.SAMPLE_RATE,
            freezeCount = processFreezeCount, freezeDurationMs = processFreezeTotalMs,
            title = music.title, artist = music.artist, queueExpanded = prefs.getBoolean("queueExpanded", false),
            eq = eqSettings, eqExpanded = prefs.getBoolean("eqExpanded", false), eqPreampDb = eqConfig.preampDb.toFloat(),
            eqRouteLabel = audio.routeLabel(), customEqPresets = readCustomEqPresets(), eqCurveDb = eqConfig.curveDb.map { it.toFloat() },
            spatial = spatialSettings, spatialAvailable = spatialAvailableNow()) }
        watchNetwork()
        log("Запуск: TCP ${tcp.localPort}, UDP ${udp.localPort}")
        // Главная страница — плеер: соло стартует сразу, без пары. Пара — надстройка (см. becomePaired / goIdle).
        post { if (!soloMode && phase == Phase.IDLE) startSoloPlayback() }
        if (autoResume && record != null) post { startAutoResume() }
    }

    /** Смена сети Wi‑Fi / IP (выход из сна, переподключение к роутеру) — сразу обновляем рекламу и пробуем связь. */
    private fun watchNetwork() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    telemetry.record("WIFI_AVAILABLE", mapOf("network" to network.toString()))
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    telemetry.record("WIFI_CAPABILITIES", mapOf("network" to network.toString(),
                        "internet" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        "validated" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)))
                }
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    val addresses = networkAddresses(lp)
                    val ipv4 = addresses.filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }
                    val ipv6 = addresses.filterIsInstance<java.net.Inet6Address>().mapNotNull { it.hostAddress }
                    telemetry.record("WIFI_ADDRESSES", mapOf("network" to network.toString(),
                        "interface" to (lp.interfaceName ?: ""), "ipv4" to ipv4, "ipv6" to ipv6,
                        "addresses" to (ipv4 + ipv6)))
                    val signature = networkSignature(lp)
                    val previous = networkLinks.put(network, signature)
                    if (previous == null || previous != signature) post { onNetworkChanged() }
                }
                override fun onLost(network: Network) {
                    val existed = networkLinks.remove(network) != null
                    telemetry.record("WIFI_LOST", mapOf("network" to network.toString()))
                    post {
                        log("Wi‑Fi потерян: $network")
                        if (existed) onNetworkChanged()
                    }
                }
            })
        } catch (e: Exception) { Log.w(TAG, "network callback", e); telemetry.event("NETWORK_CALLBACK_FAILED", error = e) }
        post { refreshMultiWifiWarning(); sendLocalAddressesIfPaired("initial_network_snapshot") }
    }

    private fun networkAddresses(lp: LinkProperties): List<InetAddress> {
        val result = linkedMapOf<String, InetAddress>()
        lp.linkAddresses.forEach { address -> result[address.address.hostAddress ?: address.address.toString()] = address.address }
        try {
            lp.interfaceName?.let { name ->
                java.net.NetworkInterface.getByName(name)?.inetAddresses?.toList()?.forEach { address ->
                    result[address.hostAddress ?: address.toString()] = address
                }
            }
        } catch (e: Exception) { telemetry.limited("WIFI_INTERFACE_ADDRESSES_FAILED", "interface=${lp.interfaceName}", e) }
        return result.values.toList()
    }

    private fun networkSignature(lp: LinkProperties): String = buildString {
        append(lp.interfaceName.orEmpty()).append(':')
        append(networkAddresses(lp).mapNotNull { it.hostAddress }.sorted().joinToString(","))
        append(':').append(lp.routes.joinToString(",") { "${it.destination}-${it.gateway?.hostAddress.orEmpty()}" })
        append(':').append(lp.dnsServers.mapNotNull { it.hostAddress }.sorted().joinToString(","))
    }

    @Suppress("DEPRECATION")
    private fun availableNetworkRoutes(wifiOnly: Boolean = false): List<NetworkRoute> {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (wifiOnly && !wifi) return@mapNotNull null
            val lp = cm.getLinkProperties(network) ?: return@mapNotNull null
            NetworkRoute(network, lp)
        }
    }

    private fun localWifiAddresses(): List<PeerAddress> {
        val fromNetworks = availableNetworkRoutes(wifiOnly = true).flatMap { route ->
            networkAddresses(route.linkProperties).filterIsInstance<java.net.Inet4Address>().mapNotNull { address ->
            address.hostAddress?.let { PeerAddress(it, route.interfaceName, route.networkId) }
            }
        }
        // Some OEM SoftAP implementations expose the tethering IPv4 only through NetworkInterface,
        // not as a ConnectivityManager Network. Keep it discoverable; a local-address bind still
        // works for an accepted TCP path even when Android has no Network handle for the AP.
        val fromInterfaces = try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { iface ->
                val name = iface.name.orEmpty()
                if (!iface.isUp || !name.startsWith("wlan", true) && !name.startsWith("ap", true) &&
                    !name.startsWith("swlan", true) && !name.startsWith("p2p", true)) return@flatMap emptyList()
                iface.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .mapNotNull { address -> address.hostAddress?.let { PeerAddress(it, name, "iface:$name") } }
            }
        } catch (e: Exception) {
            telemetry.limited("WIFI_INTERFACE_SNAPSHOT_FAILED", error = e)
            emptyList()
        }
        return (fromNetworks + fromInterfaces).distinctBy { it.ip to it.networkId }
    }

    private fun addressesJson(addresses: List<PeerAddress> = localWifiAddresses()) =
        org.json.JSONArray().also { array -> addresses.forEach { array.put(it.toJson()) } }

    private fun parsePeerAddresses(o: JSONObject): List<PeerAddress> {
        val array = o.optJSONArray("addrs") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val ip = item.optString("ip").trim()
            if (ip.isBlank()) null else PeerAddress(ip, item.optString("iface"), item.optString("networkId"))
        }.distinctBy { it.ip }
    }

    private fun parsePeerCaps(o: JSONObject): Set<String> {
        val caps = o.optJSONArray("caps") ?: return emptySet()
        return (0 until caps.length()).mapNotNull { caps.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    private fun localCapabilitiesJson() = org.json.JSONArray().also { array ->
        listOf("addrs", "tcpClock", "tcpAudio", "eq", "spatialV1").forEach(array::put)
        array.put("fileTransferV1")
    }

    private fun updatePeerAddressBook(addresses: List<PeerAddress>) {
        if (addresses.isEmpty()) return
        val current = record ?: return
        val merged = TransportPlan.mergeAddresses(addresses, current.peerAddresses)
        if (merged != current.peerAddresses) {
            record = current.copy(peerAddresses = merged)
            persistRecord()
        }
    }

    private fun addressMessage(type: String = "network-addresses") = JSONObject()
        .put("t", type).put("addrs", addressesJson()).put("caps", localCapabilitiesJson())

    private fun sendLocalAddressesIfPaired(reason: String) {
        if (phase != Phase.PAIRED || link == null) return
        refreshMultiWifiWarning()
        if ("addrs" !in negotiatedCapabilities) return
        link?.send(addressMessage())
        telemetry.record("LOCAL_ADDRESSES_SENT", mapOf("reason" to reason, "count" to localWifiAddresses().size))
    }

    private fun refreshMultiWifiWarning() {
        val routes = availableNetworkRoutes(wifiOnly = true)
        val addresses = localWifiAddresses()
        val byNetwork = addresses.groupBy { it.networkId }
        val signature = routes.map { it.networkId }.sorted().joinToString("|")
        val warning = if (routes.size >= 2) {
            "Телефон подключён к двум сетям Wi‑Fi. Если связь нестабильна, отключите «двойной Wi‑Fi» в настройках Wi‑Fi. " +
                byNetwork.entries.joinToString(", ") { (network, items) ->
                    val iface = items.firstOrNull()?.iface ?: routes.firstOrNull { it.networkId == network }?.interfaceName ?: "Wi‑Fi"
                    "$iface (${items.joinToString { it.ip }})"
                }
        } else ""
        ui { copy(multiWifiWarning = warning) }
        if (routes.size >= 2 && signature != lastMultiWifiSignature) {
            lastMultiWifiSignature = signature
            telemetry.record("MULTI_WIFI", mapOf("networks" to routes.map { it.networkId },
                "addresses" to addresses.map { mapOf("ip" to it.ip, "iface" to it.iface, "networkId" to it.networkId) }))
        } else if (routes.size < 2) lastMultiWifiSignature = ""
    }

    private fun routeForLocalAddress(address: InetAddress): NetworkRoute? =
        availableNetworkRoutes().firstOrNull { route -> networkAddresses(route.linkProperties).any { it == address } }

    /** Bind a fresh UDP socket to the exact interface used by the current TCP control link. */
    private fun bindUdpToControlLink(reason: String): UdpBindResult = synchronized(udpRebindLock) {
        val activeLink = link?.takeIf { it.isOpen } ?: return@synchronized UdpBindResult(false, false)
        val localAddress = activeLink.localAddress
        val route = routeForLocalAddress(localAddress)
        var candidate: DatagramSocket? = null
        var networkBindError: Throwable? = null
        try {
            val newSocket = DatagramSocket(null)
            candidate = newSocket
            newSocket.bind(InetSocketAddress(localAddress, 0))
            if (route != null) {
                try { route.network.bindSocket(newSocket) }
                catch (e: Exception) { networkBindError = e }
            }
            val old = udp
            val oldPort = old.localPort
            udp = newSocket
            udpBoundNetworkLabel = if (route == null) "unmatched" else if (networkBindError == null) route.label else "${route.label}:local_bind_only"
            udpBoundLocalAddress = localAddress.hostAddress ?: localAddress.toString()
            old.close()
            telemetry.record("UDP_SOCKET_BOUND", mapOf(
                "reason" to reason, "role" to role.name.lowercase(), "network" to udpBoundNetworkLabel,
                "interface" to (route?.interfaceName ?: ""), "localAddress" to udpBoundLocalAddress,
                "port" to newSocket.localPort, "previousPort" to oldPort,
                "tcpLocalAddress" to (activeLink.localAddress.hostAddress ?: activeLink.localAddress.toString()),
                "tcpLinkId" to activeLink.id, "networkBind" to when {
                    route == null -> "no_matching_network"
                    networkBindError == null -> "ok"
                    else -> "failed_local_address_fallback"
                }), error = networkBindError)
            telemetry.record("SOCKET_BOUND", mapOf("kind" to "udp", "networkId" to (route?.networkId ?: "unknown"),
                "network" to udpBoundNetworkLabel, "interface" to (route?.interfaceName ?: ""),
                "localAddress" to udpBoundLocalAddress, "localPort" to newSocket.localPort,
                "tcpLinkId" to activeLink.id, "result" to if (networkBindError == null) "ok" else "local_address_only"),
                error = networkBindError)
            UdpBindResult(true, newSocket.localPort != oldPort)
        } catch (e: Exception) {
            candidate?.close()
            telemetry.record("UDP_SOCKET_BIND_FAILED", mapOf("reason" to reason, "role" to role.name.lowercase(),
                "network" to (route?.label ?: "unmatched"), "localAddress" to (localAddress.hostAddress ?: "")), error = e)
            UdpBindResult(false, false)
        }
    }

    private fun onNetworkChanged() {
        peerPathDiagnoses.values.forEach { it.onNetworkChanged() }
        manualLanBlockedPeerId = null
        gatewayProbeAt.clear(); gatewayProbeState.clear()
        ui { copy(showLanBlocked = false) }
        log("Сеть изменилась")
        refreshMultiWifiWarning()
        sendLocalAddressesIfPaired("network_changed")
        if (discovery.advertising || phase != Phase.IDLE) discovery.advertise(tcp.localPort, myName)
        if (discovery.browsing) { lastBrowseRestart = SystemClock.elapsedRealtime(); discovery.restartBrowse() }
        when (phase) {
            Phase.RECONNECTING -> if (pending == null) scheduleReconnect(200)
            Phase.PAIRED -> {
                // UDP-адрес второго телефона мог измениться вместе с сетью.
                // Обновляем из TCP-соединения, которое ядро Linux переключает автоматически.
                val l = link
                if (l != null && l.isOpen) {
                    val activeRoute = routeForLocalAddress(l.localAddress)
                    if (l.localNetworkId != "unknown" && activeRoute?.networkId != l.localNetworkId) {
                        telemetry.record("TCP_PATH_LOST", mapOf("localAddress" to l.localAddress.hostAddress,
                            "oldNetworkId" to l.localNetworkId, "newNetworkId" to (activeRoute?.networkId ?: "unavailable"),
                            "availableWifiNetworks" to availableNetworkRoutes(wifiOnly = true).map { it.label }))
                        l.close("control network changed")
                        return
                    }
                    zeroSendRenewStreak = 0
                    udpNetworkRebindAttempted = false
                    udpFailureNoticeShown = false
                    audioRenewIntervalMs = 5_000L
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
                    val bindResult = bindUdpToControlLink("network_changed")
                    if (bindResult.portChanged && role == Role.SERVER) sendSession(l)
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
        if (soloMode && serverPlay.timeline.playing && rec.myRole == Role.POINT) {
            // Point подхватил бы очередь Server и оборвал музыку на этом телефоне — молча так не делаем.
            autoResumeUntil = 0; ui { copy(autoResuming = false) }
            telemetry.record("AUTO_RESUME_DEFERRED", mapOf("reason" to "solo_playing_as_point"))
            log("Восстановление пары отложено: на этом телефоне играет музыка")
            return
        }
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

    private fun maybeShowStabilitySettings(reason: String) {
        val now = System.currentTimeMillis()
        val previous = prefs.getLong("stabilitySettingsShownAt", 0L)
        if (previous > 0L && now - previous < TimeUnit.DAYS.toMillis(1)) return
        prefs.edit().putLong("stabilitySettingsShownAt", now).apply()
        telemetry.record("STABILITY_SETTINGS_SUGGESTED", mapOf("reason" to reason))
        ui { copy(showStabilitySettings = true) }
    }

    fun dismissStabilitySettings() = post { ui { copy(showStabilitySettings = false) } }

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

    private fun tcpAudioAcceptLoop() {
        while (!tcpAudioServer.isClosed) {
            val socket = try { tcpAudioServer.accept() } catch (e: Exception) {
                if (!tcpAudioServer.isClosed) telemetry.limited("TCP_AUDIO_ACCEPT_FAILED", error = e)
                continue
            }
            io.execute { acceptTcpAudioSocket(socket) }
        }
    }

    private fun closeServerTcpAudio(reason: String) {
        val socket = serverTcpAudioSocket
        serverTcpAudioSocket = null
        try { socket?.close() } catch (_: Exception) {}
        if (currentAudioPath == "tcp") setAudioPath("udp", reason)
    }

    private fun acceptTcpAudioSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 5_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val (remoteId, remoteSession) = TcpAudioWire.readHello(input)
            socket.soTimeout = 0
            val accepted = phase == Phase.PAIRED && role == Role.SERVER && peerId == remoteId &&
                remoteSession == sessionId && "tcpAudio" in negotiatedCapabilities
            if (!accepted) {
                telemetry.record("TCP_AUDIO_REJECTED", mapOf("peer" to remoteId, "session" to remoteSession,
                    "expectedPeer" to peerId, "expectedSession" to sessionId, "phase" to phase.name,
                    "capability" to ("tcpAudio" in negotiatedCapabilities)))
                socket.close(); return
            }
            val route = routeForLocalAddress(socket.localAddress)
            telemetry.record("SOCKET_BOUND", mapOf("kind" to "tcp-audio", "networkId" to (route?.networkId ?: "unknown"),
                "network" to (route?.label ?: "unmatched"), "interface" to (route?.interfaceName ?: ""),
                "localAddress" to socket.localAddress.hostAddress, "localPort" to socket.localPort,
                "remoteAddress" to socket.inetAddress.hostAddress, "remotePort" to socket.port, "result" to "accepted"))
            val old = serverTcpAudioSocket
            serverTcpAudioSocket = socket
            try { old?.close() } catch (_: Exception) {}
            io.execute { tcpAudioSendLoop(socket) }
            telemetry.record("TCP_AUDIO_CONNECTED", mapOf("network" to (route?.label ?: "unmatched"),
                "networkId" to (route?.networkId ?: "unknown"), "localAddress" to socket.localAddress.hostAddress,
                "localPort" to socket.localPort, "remoteAddress" to socket.inetAddress.hostAddress,
                "remotePort" to socket.port))
        } catch (e: Exception) {
            telemetry.record("TCP_AUDIO_OPEN_FAILED", mapOf("localAddress" to socket.localAddress?.hostAddress,
                "remoteAddress" to socket.inetAddress?.hostAddress), error = e)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun tcpAudioSendLoop(socket: Socket) {
        var epochOnWire = Int.MIN_VALUE
        var startFrameOnWire = Long.MIN_VALUE
        var trackOnWire = ""
        var cursor = 0L
        var observedSpatialReprocess = spatialReprocessToken
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            while (!socket.isClosed && serverTcpAudioSocket === socket && phase == Phase.PAIRED) {
                val preload = tcpAudioPreload
                val timeline = serverPlay.timeline
                val isPreload = preload != null
                val activeEpoch = preload?.epoch ?: timeline.epoch
                val audioSource = preload?.source ?: serverPlay.source
                val channel = preload?.channel ?: pointChannel
                val trackId = preload?.trackId ?: queue.getOrNull(queueIndex)?.id.orEmpty()
                val start = preload?.startFrame ?: timeline.anchorFrame
                if (activeEpoch != epochOnWire || (isPreload &&
                        (start != startFrameOnWire || trackId != trackOnWire))) {
                    epochOnWire = activeEpoch
                    startFrameOnWire = start
                    trackOnWire = trackId
                    cursor = if (isPreload) start else if (timeline.playing)
                        maxOf(start, timeline.positionFrame(System.nanoTime()).toLong().coerceAtLeast(0)) else start
                }
                val currentReprocess = spatialReprocessToken
                if (!isPreload && currentReprocess != observedSpatialReprocess) {
                    cursor = spatialReprocessFrame.coerceIn(start, audioSource.totalFrames)
                    observedSpatialReprocess = currentReprocess
                }
                if (!isPreload && !timeline.playing) {
                    Thread.sleep(40)
                    continue
                }
                val now = System.nanoTime()
                val playback = if (timeline.playing && timeline.epoch == activeEpoch)
                    timeline.positionFrame(now).toLong().coerceAtLeast(start) else start
                val target = minOf(audioSource.totalFrames, (if (isPreload) start else playback) + TCP_AUDIO_TARGET_FRAMES)
                (audioSource as? PrefetchableAudioSource)?.requestThrough(target)
                val ready = audioSource.readyFrames
                val limit = minOf(target, ready, audioSource.totalFrames)
                if (cursor >= limit) {
                    Thread.sleep(30)
                    continue
                }
                val count = minOf(TcpAudioWire.CHUNK_FRAMES.toLong(), limit - cursor).toInt()
                if (count <= 0) { Thread.sleep(20); continue }
                val samples = ShortArray(count) { index ->
                    (audioSource.sample(cursor + index, channel) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
                TcpAudioWire.writeChunk(out, TcpAudioChunk(activeEpoch, trackId, cursor, samples))
                cursor += count
                tcpAudioFramesSent.addAndGet(count.toLong())
                // Fill at up to 4× real time, leaving capacity for control and other network traffic.
                Thread.sleep(25)
            }
        } catch (e: Exception) {
            if (serverTcpAudioSocket === socket) {
                telemetry.record("TCP_AUDIO_SEND_FAILED", mapOf("framesSent" to tcpAudioFramesSent.get()), error = e)
                serverTcpAudioSocket = null
                try { socket.close() } catch (_: Exception) {}
                setAudioPath("udp", "tcp_audio_send_failed")
            }
        }
    }

    private fun startPointTcpAudioConnection(port: Int) {
        if (port <= 0 || "tcpAudio" !in negotiatedCapabilities || phase != Phase.PAIRED || role != Role.POINT) return
        pointAudioPort = port
        if (!pointAudioConnecting.compareAndSet(false, true)) return
        io.execute {
            var ownedSocket: Socket? = null
            var existingSocket = false
            try {
                if (pointTcpAudioSocket?.let { it.isConnected && !it.isClosed } == true) {
                    existingSocket = true
                    return@execute
                }
                val activeLink = link?.takeIf { it.isOpen } ?: return@execute
                val activeRoute = routeForLocalAddress(activeLink.localAddress)
                val routes = (listOfNotNull(activeRoute) + availableNetworkRoutes(wifiOnly = true))
                    .distinctBy { it.networkId }
                val networkIds = routes.map { it.networkId }.ifEmpty { listOf("process_default") }
                val routeById = routes.associateBy { it.networkId }
                val destinations = (listOfNotNull(activeLink.remoteAddress.hostAddress) +
                    record?.peerAddresses.orEmpty().map { it.ip } + listOfNotNull(record?.lastSuccessfulHost, record?.lastHost)).distinct()
                val candidates = destinations.flatMap { ip ->
                    val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return@flatMap emptyList()
                    networkIds.map { id -> ConnectionCandidate(address, routeById[id]) }
                }.distinctBy { it.route?.networkId to it.host.hostAddress }
                val connected = connectAcrossWifi(candidates, port, "tcp-audio")
                val socket = connected.socket
                ownedSocket = socket
                socket.soTimeout = 0
                val route = routeForLocalAddress(socket.localAddress) ?: connected.route
                telemetry.record("SOCKET_BOUND", mapOf("kind" to "tcp-audio-client", "networkId" to (route?.networkId ?: "unknown"),
                    "network" to (route?.label ?: "process_default"), "interface" to (route?.interfaceName ?: ""),
                    "localAddress" to socket.localAddress.hostAddress, "localPort" to socket.localPort,
                    "remoteAddress" to socket.inetAddress.hostAddress, "remotePort" to socket.port, "result" to "connected"))
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                TcpAudioWire.writeHello(out, myId, sessionId)
                pointTcpAudioSocket = socket
                telemetry.record("TCP_AUDIO_CONNECTED", mapOf("network" to (route?.label ?: "process_default"),
                    "networkId" to (route?.networkId ?: "unknown"), "localAddress" to socket.localAddress.hostAddress,
                    "localPort" to socket.localPort, "remoteAddress" to socket.inetAddress.hostAddress,
                    "remotePort" to socket.port))
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
                while (phase == Phase.PAIRED && pointTcpAudioSocket === socket && !socket.isClosed) {
                    val chunk = TcpAudioWire.readChunk(input)
                    tcpAudioBuffer.write(chunk)
                    tcpAudioFramesReceived.addAndGet(chunk.samples.size.toLong())
                    tcpAudioFramesSinceMetric.addAndGet(chunk.samples.size.toLong())
                    val nowMs = SystemClock.elapsedRealtime()
                    lastTcpAudioRxAt.set(nowMs)
                    lastAudioRxAt.set(nowMs)
                    if (rx.timeline.epoch == chunk.epoch && tcpAudioBuffer.activeEpoch != chunk.epoch)
                        tcpAudioBuffer.activate(chunk.epoch)
                    maybeSendAudioReady(chunk.epoch)
                    maybeActivateTcpAudio(chunk.epoch)
                }
            } catch (e: Exception) {
                if (phase == Phase.PAIRED) telemetry.record("TCP_AUDIO_RECEIVE_FAILED", mapOf("port" to port,
                    "audioFramesReceived" to tcpAudioFramesReceived.get()), error = e)
            } finally {
                val socket = ownedSocket
                if (socket != null) {
                    if (pointTcpAudioSocket === socket) pointTcpAudioSocket = null
                    try { socket.close() } catch (_: Exception) {}
                }
                if (!existingSocket && phase == Phase.PAIRED && role == Role.POINT) {
                    if (currentAudioPath == "tcp")
                        link?.send(JSONObject().put("t", "audio-path").put("path", "udp").put("reason", "tcp_audio_disconnected"))
                    later(3_000) { if (phase == Phase.PAIRED && role == Role.POINT) startPointTcpAudioConnection(pointAudioPort) }
                }
                pointAudioConnecting.set(false)
            }
        }
    }

    private fun maybeSendAudioReady(epoch: Int) {
        if (pointAudioPreloadEpoch != epoch || pointAudioReadyEpoch.get() == epoch.toLong()) return
        val contiguous = tcpAudioBuffer.contiguousFrames(epoch, pointAudioPreloadFrame, TCP_AUDIO_MIN_START_FRAMES)
        if (!TcpAudioStartPolicy.ready(contiguous, TCP_AUDIO_MIN_START_FRAMES)) return
        if (!pointAudioReadyEpoch.compareAndSet(Int.MIN_VALUE.toLong(), epoch.toLong())) return
        telemetry.record("START_WAIT", mapOf("epoch" to epoch, "ready" to true, "bufferedFrames" to contiguous,
            "audioPath" to "tcp", "state" to "ready"))
        link?.send(JSONObject().put("t", "audio-ready").put("epoch", epoch).put("bufferedFrames", contiguous))
    }

    private fun maybeActivateTcpAudio(epoch: Int) {
        if (currentAudioPath == "tcp" || rx.timeline.epoch != epoch || !rx.timeline.playing) return
        val position = if (clock.ready) rx.timeline.positionFrame(clock.toServer(System.nanoTime()) - pointParams.trimNs)
            .toLong().coerceAtLeast(0) else rx.timeline.anchorFrame
        val readyFrames = tcpAudioBuffer.contiguousFrames(epoch, position, TCP_AUDIO_MIN_START_FRAMES)
        if (TcpAudioStartPolicy.ready(readyFrames, TCP_AUDIO_MIN_START_FRAMES))
            setAudioPath("tcp", "tcp_audio_buffered")
    }

    private fun setAudioPath(path: String, reason: String, notifyPeer: Boolean = true) {
        if (currentAudioPath == path) return
        currentAudioPath = path
        val socket = if (role == Role.SERVER) serverTcpAudioSocket else pointTcpAudioSocket
        val route = socket?.let { routeForLocalAddress(it.localAddress) }
        telemetry.record("AUDIO_PATH", mapOf("path" to path, "reason" to reason,
            "network" to (route?.label ?: udpBoundNetworkLabel),
            "networkId" to (route?.networkId ?: "unknown"),
            "localAddress" to (socket?.localAddress?.hostAddress ?: udpBoundLocalAddress)))
        ui { copy(audioPath = path) }
        if (notifyPeer && phase == Phase.PAIRED) link?.send(JSONObject().put("t", "audio-path").put("path", path).put("reason", reason))
    }

    private fun setClockPath(path: String, reason: String) {
        if (currentClockPath == path) return
        currentClockPath = path
        telemetry.record("CLOCK_PATH", mapOf("path" to path, "reason" to reason))
    }

    private fun telemetryStateSnapshot(): String {
        val now = SystemClock.elapsedRealtime()
        val state = ui.value
        val pairTag = record?.key?.let(TelemetryIdentifiers::hash).orEmpty()
        return JSONObject().apply {
            put("app", JSONObject().put("version", BuildConfig.VERSION_NAME).put("build", BuildConfig.VERSION_CODE))
            put("device", JSONObject().put("id", myId).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
            put("role", role.name.lowercase()); put("phase", phase.name); put("visible", appVisible)
            put("audioPath", currentAudioPath); put("clockPath", currentClockPath)
            put("processFreezeCount", processFreezeCount); put("processFreezeTotalMs", processFreezeTotalMs)
            put("pair", pairTag); put("session", sessionId); put("epoch", if (role == Role.SERVER) serverPlay.timeline.epoch else rx.timeline.epoch)
            put("track", (if (role == Role.SERVER) queue.getOrNull(queueIndex)?.id else state.queueTracks.getOrNull(state.queueIndex)?.id)
                ?.let(TelemetryIdentifiers::hash) ?: "")
            put("mode", state.mode.wire); put("playing", state.playing); put("positionMs", positionMs())
            put("link", JSONObject().put("id", link?.id ?: 0).put("lastRxAgoMs", link?.let { now - it.lastRx } ?: -1))
            put("udp", JSONObject().put("packets", udpPackets.get()).put("invalid", udpInvalid.get())
                .put("otherSession", udpOtherSession.get()).put("lastRxAgoMs", udpLastRx.get().takeIf { it > 0 }?.let { now - it } ?: -1)
                .put("localAddress", udpBoundLocalAddress).put("network", udpBoundNetworkLabel)
                .put("port", udp.localPort).put("pointPingSent", pointPingPacketsSent.get())
                .put("pointPingSendErrors", pointPingSendErrors.get()))
            put("serverDiag", state.serverDiag.toJson("sdiag")); put("pointDiag", state.pointDiag.toJson("diag"))
            put("activeIncidents", org.json.JSONArray().apply { state.activeIncidents.forEach(::put) })
        }.toString()
    }

    fun telemetryPermissionDenied() = post {
        telemetry.event("EXPORT_PERMISSION_DENIED")
        toast("Для сохранения в Download разрешите доступ к файлам")
    }

    fun exportTelemetry() = post {
        if (ui.value.telemetrySaving) return@post
        ui { copy(telemetrySaving = true, telemetryFile = null) }
        val op = java.util.UUID.randomUUID().toString()
        telemetry.record("EXPORT_REQUEST", mapOf("role" to role.name.lowercase()), op = op)
        flushBlackBox("export:$op")
        val localSnapshot = telemetryStateSnapshot()
        val peerLink = link?.takeIf { phase == Phase.PAIRED && it.isOpen }
        val unavailable = when {
            phase != Phase.PAIRED -> "not paired at export time"
            peerPlatform.equals("ios", true) -> "the iOS app does not yet provide telemetry export"
            !peerPlatform.equals("android", true) -> "peer platform does not support Android journal transfer"
            peerLink == null -> "peer TCP link is unavailable"
            else -> null
        }
        if (unavailable != null || peerLink == null) {
            finishTelemetryExport(op, localSnapshot, null, unavailable)
        } else {
            requestPeerTelemetry(peerLink).whenComplete { remote, failure ->
                post {
                    val reason = failure?.cause?.message ?: failure?.message
                    finishTelemetryExport(op, localSnapshot, remote, reason)
                }
            }
        }
    }

    private fun finishTelemetryExport(op: String, localSnapshot: String, remote: RemoteTelemetryExport?, missingReason: String?) {
        val clockOffset = when {
            role == Role.POINT && clock.ready -> clock.model.a / 1e6
            role == Role.SERVER && ui.value.pointDiag.clockReady -> ui.value.pointDiag.offsetMs
            else -> null
        }
        telemetry.export(localSnapshot, if (role == Role.SERVER) "server" else "point", remote?.journal,
            remote?.state, missingReason, clockOffset, op) { result -> post {
            ui { copy(telemetrySaving = false, telemetryFile = result.getOrNull()) }
            result.fold({ toast("Телеметрия сохранена: $it") }, { toast("Не удалось сохранить телеметрию: ${it.message ?: it.javaClass.simpleName}") })
        } }
    }

    private fun requestPeerTelemetry(activeLink: ControlLink): CompletableFuture<RemoteTelemetryExport> {
        val id = java.util.UUID.randomUUID().toString()
        val result = CompletableFuture<RemoteTelemetryExport>()
        val ready = CompletableFuture<JSONObject>()
        val file = File(app.cacheDir, "peer-telemetry-$id.jsonl")
        file.delete()
        incomingTelemetry[id] = IncomingTelemetryTransfer(id, file)
        telemetryExports[id] = result
        telemetryReady[id] = ready
        activeLink.send(JSONObject().put("t", "telemetry-request").put("id", id))
        io.execute {
            try {
                ready.get(15, TimeUnit.SECONDS)
                // The peer streams its journal to us after telemetry-ready. Chunks are written by
                // receiveTelemetryChunk; this side only waits for telemetry-end to complete it.
                result.get(90, TimeUnit.SECONDS)
            } catch (e: Exception) {
                result.completeExceptionally(e)
            } finally {
                telemetryReady.remove(id)
                telemetryExports.remove(id, result)
                telemetryAcks.keys.filter { it.startsWith("$id:") }.forEach(telemetryAcks::remove)
                pendingRemoteStates.remove(id)
                incomingTelemetry.remove(id)?.temporaryFile?.delete()
            }
        }
        return result
    }

    private fun receiveTelemetryRequest(activeLink: ControlLink, o: JSONObject) {
        val id = o.optString("id")
        if (id.isBlank() || activeLink !== link || phase != Phase.PAIRED || !peerPlatform.equals("android", true)) {
            activeLink.send(JSONObject().put("t", "telemetry-refuse").put("id", id)
                .put("reason", "journal transfer is available only in an Android pair"))
            return
        }
        telemetry.record("EXPORT_PEER_REQUEST", mapOf("requestId" to id))
        flushBlackBox("peer-export:$id")
        val state = telemetryStateSnapshot()
        telemetry.readJournalSnapshot { result ->
            result.fold({ journal ->
                io.execute {
                    val bytes = journal.toByteArray(Charsets.UTF_8)
                    if (bytes.size > TELEMETRY_MAX_BYTES) {
                        activeLink.send(JSONObject().put("t", "telemetry-refuse").put("id", id).put("reason", "journal exceeds 8 MiB"))
                        return@execute
                    }
                    activeLink.send(JSONObject().put("t", "telemetry-ready").put("id", id).put("bytes", bytes.size)
                        .put("state", state))
                    var offset = 0
                    while (offset < bytes.size) {
                        if (!activeLink.isOpen || activeLink !== link) return@execute
                        val count = minOf(TRANSFER_CHUNK_BYTES, bytes.size - offset)
                        val chunk = bytes.copyOfRange(offset, offset + count)
                        val ackId = "$id:$offset"
                        val ack = CompletableFuture<JSONObject>()
                        telemetryAcks[ackId] = ack
                        activeLink.send(JSONObject().put("t", "telemetry-chunk").put("id", id).put("ackId", ackId)
                            .put("offset", offset).put("data", Base64.encodeToString(chunk, Base64.NO_WRAP)))
                        try {
                            val response = ack.get(20, TimeUnit.SECONDS)
                            check(response.optLong("offset", -1L) == (offset + count).toLong()) { "Peer confirmed an invalid telemetry offset" }
                        } catch (e: Exception) {
                            telemetry.record("EXPORT_PEER_TRANSFER_FAILED", mapOf("requestId" to id, "offset" to offset), error = e)
                            return@execute
                        } finally { telemetryAcks.remove(ackId, ack) }
                        offset += count
                    }
                    val doneAckId = "$id:done"
                    val doneAck = CompletableFuture<JSONObject>()
                    telemetryAcks[doneAckId] = doneAck
                    activeLink.send(JSONObject().put("t", "telemetry-end").put("id", id).put("bytes", bytes.size).put("ackId", doneAckId))
                    try { doneAck.get(20, TimeUnit.SECONDS) }
                    catch (e: Exception) { telemetry.record("EXPORT_PEER_TRANSFER_FAILED", mapOf("requestId" to id), error = e) }
                    finally { telemetryAcks.remove(doneAckId, doneAck) }
                }
            }, { error ->
                activeLink.send(JSONObject().put("t", "telemetry-refuse").put("id", id)
                    .put("reason", error.message ?: "could not read peer journal"))
            })
        }
    }

    private fun receiveTelemetryChunk(activeLink: ControlLink, o: JSONObject) {
        val id = o.optString("id")
        val transfer = incomingTelemetry[id]
        val ackId = o.optString("ackId")
        if (transfer == null || ackId.isBlank()) return
        try {
            val offset = o.optLong("offset", -1L)
            val bytes = Base64.decode(o.getString("data"), Base64.NO_WRAP)
            require(offset == transfer.received) { "Telemetry transfer offset mismatch" }
            require(bytes.isNotEmpty() && bytes.size <= TRANSFER_CHUNK_BYTES && transfer.received + bytes.size <= TELEMETRY_MAX_BYTES) {
                "Invalid telemetry transfer chunk size"
            }
            val nextOffset = transfer.received + bytes.size
            transfer.received = nextOffset
            telemetry.writeTransferChunk(transfer.temporaryFile, offset, bytes) { result ->
                result.fold(
                    { activeLink.send(JSONObject().put("t", "telemetry-ack").put("ackId", ackId).put("offset", nextOffset)) },
                    { error -> activeLink.send(JSONObject().put("t", "telemetry-refuse").put("id", id).put("ackId", ackId)
                        .put("reason", error.message ?: "failed writing peer journal")) },
                )
            }
        } catch (e: Exception) {
            activeLink.send(JSONObject().put("t", "telemetry-refuse").put("id", id).put("ackId", ackId)
                .put("reason", e.message ?: "invalid telemetry transfer chunk"))
        }
    }

    private fun finishTelemetryReceive(activeLink: ControlLink, o: JSONObject) {
        val id = o.optString("id")
        val transfer = incomingTelemetry[id] ?: return
        val totalBytes = o.optLong("bytes", -1L)
        val ackId = o.optString("ackId")
        if (totalBytes != transfer.received || totalBytes !in 0..TELEMETRY_MAX_BYTES) {
            activeLink.send(JSONObject().put("t", "telemetry-refuse").put("id", id).put("ackId", ackId)
                .put("reason", "incomplete peer journal"))
            telemetryExports[id]?.completeExceptionally(IllegalStateException("incomplete peer journal"))
            incomingTelemetry.remove(id)?.temporaryFile?.delete()
            return
        }
        telemetry.readTransferFile(transfer.temporaryFile) { result ->
            result.fold({ journal ->
                val remote = RemoteTelemetryExport(journal, pendingRemoteStates[id].orEmpty())
                telemetryExports[id]?.complete(remote)
                telemetry.record("EXPORT_PEER_RECEIVED", mapOf("requestId" to id, "bytes" to totalBytes))
                activeLink.send(JSONObject().put("t", "telemetry-done").put("ackId", ackId))
            }, { error ->
                telemetryExports[id]?.completeExceptionally(error)
                activeLink.send(JSONObject().put("t", "telemetry-refuse").put("id", id).put("ackId", ackId)
                    .put("reason", error.message ?: "failed to read peer journal"))
            })
            incomingTelemetry.remove(id)?.temporaryFile?.delete()
        }
    }

    private fun receiveUserMark(o: JSONObject) {
        val id = o.optString("markId").takeIf { it.isNotBlank() } ?: return
        telemetry.record("USER_MARK", mapOf("markId" to id,
            "comment" to TelemetryText.clean(o.optString("comment").take(200)),
            "role" to o.optString("role", "unknown"), "peer" to true))
        flushBlackBox("mark:$id")
    }

    private fun activeCommandOp(): String =
        lastCommandOp?.takeIf { System.nanoTime() - lastCommandAppliedSrvNs < 5_000_000_000L }.orEmpty()

    private fun observeCommand(o: JSONObject) {
        val op = o.optString("op").takeIf { it.isNotBlank() } ?: return
        if (op == lastObservedCommandOp) return
        lastObservedCommandOp = op
        val applied = o.optLong("cmdAppliedSrvNs", 0)
        val issued = o.optLong("cmdIssuedSrvNs", 0)
        val observedServerNs = if (clock.ready) clock.toServer(System.nanoTime()) else null
        telemetry.record("CMD_RESULT_OBSERVED", mapOf(
            "cmdAppliedSrvNs" to applied, "cmdIssuedSrvNs" to issued, "observedSrvNs" to observedServerNs,
            "totalLatencyMs" to if (observedServerNs != null && issued > 0) (observedServerNs - issued) / 1e6 else null,
            "deliveryLatencyMs" to if (observedServerNs != null && applied > 0) (observedServerNs - applied) / 1e6 else null,
        ), op = op)
    }

    // ================================================================== публичный API (любой поток)
    fun invite(p: Peer) = post { doInvite(p) }
    fun cancelInvite() = post { doCancelPending() }
    fun acceptIncoming() = post { doAccept() }
    fun rejectIncoming() = post { doReject() }
    fun resumeRemembered() = post { doManualResume() }
    fun forgetPair() = post { record = null; setAutoResume(false); stopAutoResume(); prefs.edit().remove("pair").apply(); ui { copy(remembered = null) } }
    fun disconnect() = post { setAutoResume(false); goIdle(sendBye = true, reason = "Пара разорвана") }

    fun startSolo() = post {
        if (soloMode || phase != Phase.IDLE) return@post
        startSoloPlayback()
    }

    /** Solo — локальный плеер для любой прежней роли; повторное удержание восстанавливает пару. */
    fun toggleSoloMode() = post {
        if (soloMode) {
            if (phase == Phase.IDLE) doManualResume()
            return@post
        }
        if (phase != Phase.PAIRED && phase != Phase.RECONNECTING) return@post
        setAutoResume(false)
        stopAutoResume()
        goIdle(sendBye = true, reason = "Solo · локальный плеер")
    }

    // ---- фоновый сервис соло: нужен только пока играет музыка (и 5 мин после паузы), иначе процесс замораживается зря
    private var soloServiceActive = false
    private var soloServiceStopTask: ScheduledFuture<*>? = null
    private var soloHandoffFrame = -1L
    private var soloHandoffPlaying = false
    private var soloResumeFrame = 0L

    private fun ensureSoloService() {
        soloServiceStopTask?.cancel(false); soloServiceStopTask = null
        if (soloServiceActive) return
        soloServiceActive = true
        try { PlaybackService.start(app) } catch (e: Exception) {
            Log.w(TAG, "fgs", e); telemetry.event("FOREGROUND_SERVICE_FAILED", error = e)
            toast("Фоновый режим не запущен: при блокировке экрана звук может прерваться")
        }
    }

    private fun scheduleSoloServiceStop() {
        if (!soloServiceActive || soloServiceStopTask != null) return
        soloServiceStopTask = later(SOLO_SERVICE_IDLE_MS) {
            soloServiceStopTask = null
            if (soloMode && soloServiceActive && !serverPlay.timeline.playing) {
                soloServiceActive = false
                try { PlaybackService.stop(app) } catch (_: Exception) {}
                if (!appVisible && phase == Phase.IDLE) hideWhileBackground()
            }
        }
    }

    private fun dropSoloService() {
        soloServiceStopTask?.cancel(false); soloServiceStopTask = null
        soloServiceActive = false
    }

    /**
     * Соло → пара. Server продолжает ту же музыку с той же позиции (переход в becomePaired берёт [soloHandoffFrame]);
     * Point ставит свою музыку на паузу, очередь остаётся в памяти и вернётся после разрыва пары.
     */
    private fun leaveSoloForPair(newRole: Role) {
        val timeline = serverPlay.timeline
        val frame = if (mode == SourceMode.MUSIC) posFrame() else 0L
        val playing = timeline.playing && mode == SourceMode.MUSIC
        telemetry.record("SOLO_TO_PAIR", mapOf("asRole" to newRole.name.lowercase(), "wasPlaying" to playing,
            "positionMs" to frame * 1000 / SF.SAMPLE_RATE))
        dropSoloService()
        if (newRole == Role.SERVER) {
            saveSoloEqProfile(activeSoloEqRouteKey)
            soloMode = false
            activeSoloEqRouteKey = ""
            audio.playbackActive = false
            audio.source = null
            audio.stop()
            audio.preferPhoneSpeaker = true
            soloHandoffFrame = frame
            soloHandoffPlaying = playing
            ui { copy(soloMode = false, eqAvailable = false) }
        } else {
            soloResumeFrame = frame
            stopSoloPlayback()
        }
    }

    private fun startSoloPlayback(frame: Long = 0L, playing: Boolean = false, reloadInterrupted: Boolean = false) {
        soloMode = true
        role = Role.SERVER
        negotiatedCapabilities = emptySet()
        audio.preferPhoneSpeaker = false
        mode = SourceMode.MUSIC
        source = if (queueIndex in queue.indices) currentQueueSource ?: music else music
        master = prefs.getFloat("soloMaster", master)
        balance = 0.5f
        activeSoloEqRouteKey = ""
        val routeKey = audio.routeKey()
        spatialSettings = if (routeKey.isNotBlank() && routeKey != "unknown") {
            SpatialSettings.fromJson(runCatching { prefs.getString("soloSpatial:$routeKey", null)?.let(::JSONObject) }.getOrNull())
        } else SpatialSettings()
        if (routeKey != "unknown") applySoloEqRoute(routeKey, savePrevious = false)
        else {
            eqSettings = EqSettings()
            eqConfig = EqualizerMath.config(eqSettings)
        }
        audio.source = serverRenderer
        audio.playbackActive = false
        newEpoch(playing, frame.coerceIn(0L, source.totalFrames), System.nanoTime())
        audio.start()
        if (playing) { requestFocus(); ensureSoloService() }
        ui { copy(soloMode = true, phase = Phase.IDLE, role = Role.SERVER, playing = playing,
            title = source.title, artist = source.artist, durationMs = source.totalFrames * 1000 / SF.SAMPLE_RATE,
            mode = SourceMode.MUSIC, eq = eqSettings, eqAvailable = true, eqRouteLabel = audio.routeLabel(),
            eqPreampDb = eqConfig.preampDb.toFloat(), eqCurveDb = eqConfig.curveDb.map { it.toFloat() },
            queueTracks = queue.map { QueueTrackUi(it.id, it.title, it.artist) }, queueIndex = queueIndex,
            spatial = spatialSettings, spatialAvailable = spatialAvailableNow()) }
        telemetry.record("EQ_ROUTE_PROFILE", mapOf("route" to audio.routeLabel(), "applied" to true))
        later(350) { if (soloMode) applySoloEqRoute(audio.routeKey(), savePrevious = true) }
        PlaybackService.refresh(app)
        val item = queue.getOrNull(queueIndex)
        if (reloadInterrupted && item != null && currentQueueSource is app.soundfield.core.SegmentedPcmSource &&
            !File(AudioFileDecoder.pcmFile(app, item.ref()), "complete").isFile) {
            // Becoming Point cancels the local decoder and removes its incomplete PCM directory.
            currentQueueSource = null; decodedTracks.remove(item.id)
            selectTrack(queueIndex, forcePaused = !playing, startPlayback = playing, startFrame = frame)
        }
    }

    private fun stopSoloPlayback() {
        if (!soloMode) return
        dropSoloService()
        saveSoloEqProfile(activeSoloEqRouteKey)
        soloMode = false
        activeSoloEqRouteKey = ""
        audio.playbackActive = false
        audio.source = null
        audio.stop()
        audio.preferPhoneSpeaker = true
        try { PlaybackService.stop(app) } catch (_: Exception) {}
        abandonFocus()
        serverPlay = ServerPlayState(Timeline.NONE, source, Ch.L, 0f, EqConfig.neutral())
        ui { copy(soloMode = false, playing = false, audioBuffering = false, eqAvailable = false) }
    }

    private fun applySoloEqRoute(routeKey: String, savePrevious: Boolean) {
        if (!soloMode || routeKey.isBlank() || routeKey == "unknown" || routeKey == activeSoloEqRouteKey) return
        if (savePrevious) saveSoloEqProfile(activeSoloEqRouteKey)
        activeSoloEqRouteKey = routeKey
        val saved = prefs.getString("eqProfile:$routeKey", null)
        eqSettings = if (saved == null) EqSettings() else parseEqSettings(runCatching { JSONObject(saved) }.getOrNull())
        eqConfig = EqualizerMath.config(eqSettings)
        rebuildPlayState()
        ui { copy(eq = eqSettings, eqAvailable = true, eqRouteLabel = audio.routeLabel(),
            eqPreampDb = eqConfig.preampDb.toFloat(), eqCurveDb = eqConfig.curveDb.map { it.toFloat() }) }
        telemetry.record("EQ_ROUTE_PROFILE", mapOf("route" to audio.routeLabel(), "applied" to true))
    }

    private fun saveSoloEqProfile(routeKey: String) {
        if (routeKey.isBlank() || routeKey == "unknown") return
        prefs.edit().putString("eqProfile:$routeKey", eqSettingsJson(eqSettings).toString()).apply()
    }

    /** Без пары и активного соло в фоне снимаем публикацию/поиск, чтобы не оставлять недоступный TCP listener. */
    fun onBackground() = post {
        telemetry.event("APP_BACKGROUND", "phase=$phase")
        appVisible = false
        if (role == Role.POINT) cancelPointFileTransfer()
        // Без пары процесс замораживается вскоре после ухода с экрана, если его не держит сервис (играющее соло).
        if (phase == Phase.IDLE && !(soloMode && soloServiceActive)) hideWhileBackground()
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
    fun setTrimMs(ms: Double) = cmd("trim") { put("ms", ms); put("reset", false) }
    fun resetTrim() = cmd("trim") { put("ms", 0.0); put("reset", true) }
    fun calibrate() = cmd("calibrate")
    fun measure() = cmd("measure")
    fun startSyncProbe() = cmd("syncProbe")
    fun setLevelTrim(device: String, db: Float) = cmd("levelTrim") { put("dev", device); put("db", db.toDouble()) }
    fun setLevelOffsetDb(db: Float, finished: Boolean = false) {
        sendGainCommand("levelOffset", finished) {
            cmd("levelOffset", telemetry = finished) { put("db", db.toDouble()); put("finished", finished) }
        }
    }
    fun resetLevelTrims() = cmd("levelReset")
    fun startLevelMatch() = cmd("levelStart")
    fun stopLevelMatch(commit: Boolean) = cmd("levelStop") { put("commit", commit) }
    fun startCenterCheck() = cmd("centerStart")
    fun centerCheckAnswer(answer: String) = cmd("centerAnswer") { put("answer", answer) }
    fun measureLevels() = cmd("levelMeasure")
    fun measureMicReference(position: Int) = cmd("levelMicReference") { put("position", position.coerceIn(1, 2)) }
    fun metronome(on: Boolean) = cmd("metronome") { put("on", on) }
    fun channelTest() = cmd("lrtest")

    private fun handleAudioRouteChanged() {
        if (soloMode) {
            applySoloEqRoute(audio.routeKey(), savePrevious = true)
            val routeKey = audio.routeKey()
            spatialSettings = if (routeKey.isNotBlank() && routeKey != "unknown")
                SpatialSettings.fromJson(runCatching { prefs.getString("soloSpatial:$routeKey", null)?.let(::JSONObject) }.getOrNull())
                else SpatialSettings()
            refreshSpatialAvailability()
            rebuildPlayState()
            ui { copy(eqRouteLabel = audio.routeLabel(), spatial = spatialSettings) }
            return
        }
        if (!ui.value.inPair) { refreshSpatialAvailability(); return }
        refreshSpatialAvailability()
        if (role == Role.POINT) { cmd("routeChanged"); return }
        resetCalibrationsForRoute()
    }

    private fun resetCalibrationsForRoute() {
        trimNs = 0L; delayCalib = DelayCalib(); levelTrimServerDb = 0f; levelTrimPointDb = 0f; levelCalib = LevelCalib()
        rebuildPlayState(); saveServerParams(); publishState()
        telemetry.record("route_change_reset", mapOf("role" to role.name.lowercase()))
        toast("Аудиомаршрут изменился: повторите калибровку задержки и уровня")
    }

    private fun levelStageReady() = delayCalib.state != "none" && peerGainLawReady
    private fun blockCalibrationIfNeeded(what: String): Boolean {
        if (calibRunning) { toast("Дождитесь окончания калибровки задержки"); return true }
        if (levelStageReady()) return false
        telemetry.record("calib_gate_blocked", mapOf("what" to what))
        toast(levelGateMessage())
        return true
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun levelGateMessage() = if (delayCalib.state == "none")
        "Сначала выполните калибровку задержки: пока телефоны звучат с разным запаздыванием, звук тянет к одному из них, и уровнем это не исправить"
    else "Обновите второй телефон для калибровки уровня"

    private fun normalizeLevelTrims() {
        val peak = maxOf(levelTrimServerDb, levelTrimPointDb)
        levelTrimServerDb = (levelTrimServerDb - peak).coerceIn(-12f, 0f)
        levelTrimPointDb = (levelTrimPointDb - peak).coerceIn(-12f, 0f)
    }

    private fun setDeviceLevelTrim(device: String, db: Float) {
        val value = (db.coerceIn(-12f, 0f) * 2f).roundToInt() / 2f
        when (device) {
            "server" -> levelTrimServerDb = value
            "point" -> levelTrimPointDb = value
            else -> return
        }
        normalizeLevelTrims()
        levelCalib = LevelCalib("manual", System.currentTimeMillis(), abs(levelTrimServerDb - levelTrimPointDb).toDouble())
    }

    private fun applyLevelOffset(db: Float) {
        val value = (db.coerceIn(-12f, 12f) * 2f).roundToInt() / 2f
        if (value < 0f) {
            if (serverChannel() == Ch.L) { levelTrimServerDb = value; levelTrimPointDb = 0f }
            else { levelTrimPointDb = value; levelTrimServerDb = 0f }
        } else if (value > 0f) {
            if (serverChannel() == Ch.R) { levelTrimServerDb = -value; levelTrimPointDb = 0f }
            else { levelTrimPointDb = -value; levelTrimServerDb = 0f }
        } else { levelTrimServerDb = 0f; levelTrimPointDb = 0f }
    }

    private fun finishLevelMatch(commit: Boolean) {
        if (mode != SourceMode.LEVEL_MATCH) return
        if (commit) {
            val offset = if (serverChannel() == Ch.L) levelTrimServerDb - levelTrimPointDb else levelTrimPointDb - levelTrimServerDb
            levelCalib = LevelCalib("ear", System.currentTimeMillis(), offset.toDouble())
            telemetry.record("level_trim_set", mapOf("dev" to "pair", "db" to offset.toDouble(), "source" to "ear"))
        } else {
            levelTrimServerDb = levelMatchStartServerDb; levelTrimPointDb = levelMatchStartPointDb
        }
        saveServerParams()
        restoreMusic(savedPlaying)
        publishState()
    }

    private fun resultJson(result: LevelAnalyzer.Result): JSONObject {
        val levels = org.json.JSONArray(); result.levelsDb.forEach { levels.put(it ?: JSONObject.NULL) }
        val snr = org.json.JSONArray(); result.snrDb.forEach { snr.put(it ?: JSONObject.NULL) }
        return JSONObject().put("levels", levels).put("snr", snr).put("valid", result.validBands)
            .put("peak", result.peakDbfs.takeIf { it.isFinite() } ?: -120.0)
    }

    private fun readResult(o: JSONObject): LevelAnalyzer.Result {
        fun values(key: String): List<Double?> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).map { if (a.isNull(it)) null else a.optDouble(it) }
        }
        return LevelAnalyzer.Result(values("levels"), values("snr"), o.optInt("valid"), o.optDouble("peak", Double.POSITIVE_INFINITY))
    }

    private fun captureResultMessage(id: String, kind: String, result: LevelAnalyzer.Result, startServerNs: Long = 0L) =
        JSONObject().put("t", "levelCaptureResult").put("id", id).put("kind", kind)
            .put("startServerNs", startServerNs).put("result", resultJson(result))

    private fun analyzeLevelSweeps(samples: FloatArray, sampleRate: Int, recordingStartServerNs: Long,
                                   anchorServerNs: Long, pointSpeaker: Boolean, trimNsForPoint: Long = 0L): List<LevelAnalyzer.Result> =
        (0 until LevelMeasureSource.REPEATS).map { repeat ->
            val offset = LevelMeasureSource.INITIAL_SECONDS + repeat * LevelMeasureSource.CYCLE_SECONDS +
                if (pointSpeaker) LevelMeasureSource.ACTIVE_SECONDS + LevelMeasureSource.GAP_SECONDS else 0.0
            val signalStartNs = anchorServerNs + (offset * 1e9).toLong() + if (pointSpeaker) trimNsForPoint else 0L
            val start = (signalStartNs - recordingStartServerNs) / 1e9
            LevelAnalyzer.analyze(samples, sampleRate, start, LevelMeasureSource.ACTIVE_SECONDS,
                backgroundStart = start - 0.45, backgroundDuration = 0.4)
        }

    private fun receiveLevelCapture(o: JSONObject) {
        val id = o.optString("id"); val kind = o.optString("kind")
        if (id.isBlank() || !hasMicPermission()) {
            link?.send(JSONObject().put("t", "levelCaptureResult").put("id", id).put("kind", kind)
                .put("error", if (id.isBlank()) "Некорректный запрос" else "Разрешите микрофон на Point"))
            return
        }
        val durationMs = o.optLong("durationMs", if (kind == "reference") 4_200L else (LevelMeasureSource.CAPTURE_SECONDS * 1000).toLong())
        val anchorNs = o.optLong("anchorSrvNs")
        io.execute {
            try {
                val rec = mic.record(durationMs) {}
                val startServerNs = if (clock.ready) clock.toServer(rec.startNs) else 0L
                val payload = if (kind == "reference") {
                    val result = LevelAnalyzer.analyze(rec.samples, rec.sampleRate, 0.2, 3.8)
                    captureResultMessage(id, kind, result, startServerNs)
                } else {
                    check(anchorNs > 0 && startServerNs > 0) { "Часы Point не синхронизированы" }
                    val results = analyzeLevelSweeps(rec.samples, rec.sampleRate, startServerNs, anchorNs, pointSpeaker = false)
                    val array = org.json.JSONArray(); results.forEach { array.put(resultJson(it)) }
                    JSONObject().put("t", "levelCaptureResult").put("id", id).put("kind", kind)
                        .put("startServerNs", startServerNs).put("results", array)
                }
                link?.send(payload)
            } catch (e: Exception) {
                link?.send(JSONObject().put("t", "levelCaptureResult").put("id", id).put("kind", kind)
                    .put("error", e.message ?: "Ошибка записи микрофона"))
            }
        }
    }

    private fun startLevelMicReference(position: Int) {
        if (blockCalibrationIfNeeded("mic_reference_$position")) return
        if (role != Role.SERVER || position !in 1..2) { toast("Сверку микрофонов запускайте на Server"); return }
        if (mode != SourceMode.MUSIC) { toast("Сначала завершите текущую звуковую проверку"); return }
        if (!hasMicPermission()) { toast("Разрешите доступ к микрофону на этом телефоне") ; return }
        if (position == 2 && micReferenceDeltaOne == null) { toast("Сначала запишите положение 1"); return }
        val activeLink = link ?: run { toast("Point не на связи"); return }
        val id = java.util.UUID.randomUUID().toString()
        val future = CompletableFuture<JSONObject>(); levelCaptureAcks[id] = future
        levelMeasureText = "Сверка микрофонов · положение $position: запись 4 с. Включите внешний шумовой источник по центру."
        ui { copy(levelMeasureText = this@Engine.levelMeasureText) }
        if (mode == SourceMode.MUSIC) { savedFrame = posFrame(); savedPlaying = serverPlay.timeline.running }
        newEpoch(false, posFrame(), System.nanoTime())
        activeLink.send(JSONObject().put("t", "levelCapture").put("id", id).put("kind", "reference").put("durationMs", 4_200))
        io.execute {
            try {
                val own = mic.record(4_200) {}
                val ownResult = LevelAnalyzer.analyze(own.samples, own.sampleRate, 0.2, 3.8)
                val peer = future.get(8, TimeUnit.SECONDS)
                check(!peer.has("error")) { peer.optString("error", "Микрофон Point недоступен") }
                val peerResult = readResult(peer.getJSONObject("result"))
                val measured = LevelAnalyzer.differenceDb(ownResult, peerResult)
                    ?: error("Недостаточно пригодных частотных полос")
                post {
                    if (position == 1) micReferenceDeltaOne = measured else micReferenceDeltaTwo = measured
                    micSensitivityDeltaDb = if (position == 2) {
                        val d1 = micReferenceDeltaOne ?: 0.0
                        if (abs(d1 - measured) > 3.0) null else (d1 + measured) / 2.0
                    } else null
                    val d1 = micReferenceDeltaOne
                    val d2 = micReferenceDeltaTwo
                    levelMeasureText = when {
                        position == 1 -> "ΔM: ожидается второе измерение после перестановки телефонов · d1 = %+.1f дБ".format(measured)
                        abs((d1 ?: 0.0) - measured) > 3.0 -> "Сверка микрофонов отклонена: |d1 − d2| > 3 дБ. Поставьте внешний источник по центру и повторите."
                        else -> "Сверка микрофонов готова · ΔM = %+.1f дБ · d1 = %+.1f · d2 = %+.1f дБ".format(micSensitivityDeltaDb, d1, d2)
                    }
                    restoreMusic(savedPlaying); publishState()
                }
            } catch (e: Exception) {
                post {
                    levelMeasureText = "Сверка микрофонов не удалась: ${e.message ?: "ошибка записи"}"
                    restoreMusic(savedPlaying); publishState()
                }
            } finally { levelCaptureAcks.remove(id, future) }
        }
    }

    private fun startLevelMicMeasurement() {
        if (blockCalibrationIfNeeded("mic_measure")) return
        if (role != Role.SERVER) { toast("Запустите измерение на Server"); return }
        if (mode != SourceMode.MUSIC) { toast("Сначала завершите текущую звуковую проверку"); return }
        val sensitivity = micSensitivityDeltaDb ?: run { toast("Сначала завершите сверку микрофонов в двух положениях"); return }
        if (!hasMicPermission()) { toast("Разрешите доступ к микрофону на этом телефоне"); return }
        if (master < 0.5f) { toast("Для измерения поднимите громкость Sound не ниже ${(AudioGain.masterToSlider(0.5f) * 100).toInt() + 1} %"); return }
        val activeLink = link ?: run { toast("Point не на связи"); return }
        val id = java.util.UUID.randomUUID().toString()
        val future = CompletableFuture<JSONObject>(); levelCaptureAcks[id] = future
        levelMeasureSavedServerDb = levelTrimServerDb; levelMeasureSavedPointDb = levelTrimPointDb
        levelTrimServerDb = 0f; levelTrimPointDb = 0f
        levelMeasureText = "Измерение динамиков: 3 взаимных прохода. Не двигайте телефоны."
        ui { copy(levelMeasureText = this@Engine.levelMeasureText) }
        publishState()
        val anchor = System.nanoTime() + 1_500_000_000L
        activeLink.send(JSONObject().put("t", "levelCapture").put("id", id).put("kind", "output")
            .put("anchorSrvNs", anchor).put("durationMs", (LevelMeasureSource.CAPTURE_SECONDS * 1000).toLong()))
        startTestMode(LevelMeasureSource(serverChannel(), pointChannel), anchor)
        io.execute {
            try {
                val own = mic.record((LevelMeasureSource.CAPTURE_SECONDS * 1000).toLong()) {}
                val ownResults = analyzeLevelSweeps(own.samples, own.sampleRate, own.startNs, anchor, pointSpeaker = true, trimNsForPoint = trimNs)
                val peer = future.get(30, TimeUnit.SECONDS)
                check(!peer.has("error")) { peer.optString("error", "Микрофон Point недоступен") }
                val arr = peer.optJSONArray("results") ?: error("Point не передал результаты измерения")
                val remoteResults = (0 until arr.length()).map { readResult(arr.getJSONObject(it)) }
                check(remoteResults.size == LevelMeasureSource.REPEATS) { "Неполный результат Point" }
                val deltas = remoteResults.indices.map { i ->
                    check(remoteResults[i].validBands >= 6 && ownResults[i].validBands >= 6) { "SNR < 15 дБ или менее 6 полос" }
                    LevelAnalyzer.differenceDb(remoteResults[i], ownResults[i]) ?: error("Недостаточно общих частотных полос")
                }.map { it + sensitivity }
                val spread = (deltas.maxOrNull() ?: 0.0) - (deltas.minOrNull() ?: 0.0)
                val delta = LevelAnalyzer.median(deltas) ?: error("Нет валидных проходов")
                val validBands = (remoteResults + ownResults).minOf { it.validBands }
                val snr = (remoteResults + ownResults).flatMap { r -> r.snrDb.filterNotNull() }.let { LevelAnalyzer.median(it) ?: 0.0 }
                val accepted = spread <= 1.0 && abs(delta) <= 12.0
                post {
                    levelTrimServerDb = levelMeasureSavedServerDb; levelTrimPointDb = levelMeasureSavedPointDb
                    restoreMusic(savedPlaying)
                    levelMeasureText = "ΔM %+.1f дБ · Δ %+.1f дБ · ΔP %+.1f дБ · разброс %.1f дБ · SNR %.1f дБ · полос %d/10 · %s".format(
                        sensitivity, delta - sensitivity, delta, spread, snr, validBands, if (accepted) "результат экспериментальный, не применён" else "качество недостаточно, повторите")
                    telemetry.record("level_measure", mapOf("dM" to sensitivity, "d1" to (micReferenceDeltaOne ?: 0.0),
                        "d2" to (micReferenceDeltaTwo ?: 0.0), "delta" to (delta - sensitivity), "dP" to delta,
                        "spread" to spread, "snr" to snr, "bands" to validBands, "accepted" to accepted, "applied" to false))
                    saveServerParams(); publishState()
                }
            } catch (e: Exception) {
                post {
                    levelTrimServerDb = levelMeasureSavedServerDb; levelTrimPointDb = levelMeasureSavedPointDb
                    restoreMusic(savedPlaying)
                    levelMeasureText = "Измерение отклонено: ${e.message ?: "проверьте шум и разрешения"}. Результат не применён."
                    telemetry.record("level_measure", mapOf("result" to "rejected", "reason" to (e.message ?: "unknown"), "applied" to false))
                    saveServerParams(); publishState()
                }
            } finally { levelCaptureAcks.remove(id, future) }
        }
    }
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
    private fun supportsFileTransferPeer() = peerPlatform.equals("android", true) || "fileTransferV1" in negotiatedCapabilities

    fun requestTrackPicker() {
        post {
            if (calibRunning) { toast("Сначала завершите калибровку"); return@post }
            if (role == Role.SERVER || (role == Role.POINT && supportsFileTransferPeer())) {
                ui { copy(filePickerRequest = filePickerRequest + 1) }
            } else if (role == Role.POINT) {
                toast("Обновите SoundField на Server для передачи файлов")
            } else toast("Сначала подключите второй телефон")
        }
    }

    /** Decode picked files (or all audio files under a picked folder), then add them to the Server queue. */
    fun addAudioFiles(uris: List<Uri>, deleteIfImportFails: List<File> = emptyList(), displayNames: Map<Uri, String> = emptyMap()) {
        if (uris.isEmpty()) return
        post {
            if (calibRunning || (!soloMode && phase !in setOf(Phase.PAIRED, Phase.RECONNECTING))) {
                deleteIfImportFails.forEach { it.delete() }
                toast("Добавление музыки сейчас недоступно"); return@post
            }
            if (role == Role.POINT) {
                if (!supportsFileTransferPeer()) {
                    toast("Обновите SoundField на Server для передачи файлов"); return@post
                }
                beginPointFileTransfer(uris)
                return@post
            }
            ui { copy(loadingTracks = true) }
            queueIo.execute {
                val candidates = mutableListOf<Pair<Uri, String>>()
                uris.forEach { persistReadGrant(it) }   // доступ сохраняется после перезапуска — нужен для плейлистов
                uris.forEach { uri ->
                    if (isAudioDirectory(uri)) candidates += listAudioDocuments(uri)
                    else candidates += uri to (displayNames[uri] ?: displayName(uri))
                }
                val prepared = candidates.mapNotNull { (uri, name) ->
                    try { AudioFileDecoder.prepare(app, uri, name) }
                    catch (e: Exception) { Log.w(TAG, "Could not decode $name", e); null }
                }
                post {
                    if (role != Role.SERVER || (!soloMode && phase !in setOf(Phase.PAIRED, Phase.RECONNECTING)) || calibRunning) {
                        deleteIfImportFails.forEach { it.delete() }
                        ui { copy(loadingTracks = false, fileTransferText = if (incomingFileTransfer == null && !fileTransferActive.get()) "" else fileTransferText) }
                        return@post
                    }
                    if (prepared.isEmpty()) {
                        deleteIfImportFails.forEach { it.delete() }
                        ui { copy(loadingTracks = false, fileTransferText = if (incomingFileTransfer == null && !fileTransferActive.get()) "" else fileTransferText) }
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
                    // A later file in the same batch may already be arriving while this import finishes.
                    ui { copy(fileTransferText = if (incomingFileTransfer == null && !fileTransferActive.get()) "" else fileTransferText) }
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
        val op = java.util.UUID.randomUUID().toString()
        fileTransferOp = op
        transferLastProgressAtMs = requestedAt
        telemetry.record("FILE_TRANSFER_PREPARING", mapOf("direction" to "send", "selections" to uris.size), op = op)
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
                    if (isAudioDirectory(uri)) candidates += listAudioDocuments(uri)
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
                telemetry.record("FILE_TRANSFER_START", mapOf("direction" to "send", "files" to totalFiles, "bytes" to totalBytes,
                    "preparationMs" to batchStartedAt - requestedAt), op = op)
                transferLastProgressAtMs = batchStartedAt
                val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
                files.forEachIndexed { fileIndex, file ->
                    checkTransferStillActive(activeLink)
                    currentFileIndex = fileIndex + 1
                    fileStartedAt = SystemClock.elapsedRealtime()
                    val id = java.util.UUID.randomUUID().toString()
                    currentFileId = id
                    telemetry.record("FILE_TRANSFER_FILE_START", mapOf("direction" to "send", "transferId" to id,
                        "fileIndex" to currentFileIndex, "fileCount" to totalFiles, "bytes" to file.size,
                        "extension" to file.name.substringAfterLast('.', "").lowercase().take(8)), op = op)
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
                            transferLastProgressAtMs = SystemClock.elapsedRealtime()
                            telemetry.limited("FILE_TRANSFER_PROGRESS",
                                "direction=send id=$id file=$currentFileIndex/$totalFiles fileBytes=$offset/${file.size} batchBytes=$batchSent/$totalBytes", op = op)
                            post { if (fileTransferActive.get()) ui { copy(fileTransferText =
                                "Отправка на Server · ${(batchSent * 100 / total).toInt()}%") } }
                        }
                        check(offset == file.size) { "Размер «${file.name}» изменился во время передачи" }
                    }
                    val completed = awaitFileReply(activeLink, id, JSONObject().put("t", "file-end").put("id", id))
                    check(completed.optBoolean("complete")) { "Server не сохранил «${file.name}»" }
                    telemetry.record("FILE_TRANSFER_FILE_COMPLETE", mapOf("direction" to "send", "transferId" to id,
                        "fileIndex" to currentFileIndex, "fileCount" to totalFiles, "bytes" to file.size,
                        "elapsedMs" to SystemClock.elapsedRealtime() - fileStartedAt, "result" to "ok"), op = op)
                    currentFileId = null
                }
                telemetry.record("FILE_TRANSFER_COMPLETE", mapOf("direction" to "send", "files" to totalFiles, "bytes" to totalBytes,
                    "elapsedMs" to SystemClock.elapsedRealtime() - batchStartedAt, "result" to "ok"), op = op)
                post { ui { copy(fileTransferText = "") }; toast("Файлы отправлены на Server") }
            } catch (e: Exception) {
                currentFileId?.let { id -> if (activeLink.isOpen) activeLink.send(JSONObject().put("t", "file-cancel").put("id", id)) }
                val cancelled = fileTransferCancelled.get()
                val elapsed = SystemClock.elapsedRealtime() - if (transferStarted) batchStartedAt else requestedAt
                telemetry.record(if (cancelled) "FILE_TRANSFER_CANCELLED" else "FILE_TRANSFER_ABORTED", mapOf(
                    "direction" to "send", "stage" to if (transferStarted) "transfer" else "prepare",
                    "fileIndex" to currentFileIndex, "fileCount" to totalFiles, "bytesSent" to batchSent,
                    "totalBytes" to totalBytes, "elapsedMs" to elapsed,
                    "result" to if (cancelled) "cancelled" else "error"), op = op, error = e)
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Передачу файлов прервали"
                post { ui { copy(fileTransferText = "") }; toast(message) }
            } finally {
                fileTransferAcks.values.forEach { it.completeExceptionally(IllegalStateException("Передача завершена")) }
                fileTransferAcks.clear()
                fileTransferActive.set(false)
                fileTransferOp = null
                transferLastProgressAtMs = 0L
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
        if (reason != null) telemetry.record("FILE_TRANSFER_ABORTED", mapOf(
            "direction" to "receive", "transferId" to transfer.id, "bytesReceived" to transfer.received,
            "totalBytes" to transfer.size, "elapsedMs" to SystemClock.elapsedRealtime() - transfer.startedElapsedMs,
            "reason" to reason.take(100), "result" to "error"), op = transfer.op)
        transferLastProgressAtMs = 0L
    }

    private fun handleIncomingFileStart(o: JSONObject) {
        val id = o.optString("id")
        val name = File(o.optString("name")).name.take(160)
        val size = o.optLong("size", -1)
        if (role != Role.SERVER || calibRunning || !supportsFileTransferPeer() || o.optString("token") != fileTransferToken) {
            sendFileError(id, "Передача не поддерживается этой парой"); return
        }
        if (incomingFileTransfer != null) { sendFileError(id, "Server уже принимает другой файл"); return }
        if (runCatching { java.util.UUID.fromString(id) }.isFailure || name.substringAfterLast('.', "").lowercase() !in audioExtensions ||
            size !in 1..MAX_TRANSFER_FILE_BYTES) {
            sendFileError(id, "Неподдерживаемый или слишком большой аудиофайл"); return
        }
        val directory = File(app.filesDir, "audio_received").apply { mkdirs() }
        val tmp = File(directory, "$id.part")
        try {
            val output = BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)
            val startedElapsedMs = SystemClock.elapsedRealtime()
            val op = java.util.UUID.randomUUID().toString()
            incomingFileTransfer = IncomingFileTransfer(id, name, size, tmp, output, startedElapsedMs, op)
            transferLastProgressAtMs = startedElapsedMs
            telemetry.record("FILE_TRANSFER_START", mapOf("direction" to "receive", "transferId" to id, "bytes" to size,
                "extension" to name.substringAfterLast('.', "").lowercase().take(8)), op = op)
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
            transferLastProgressAtMs = SystemClock.elapsedRealtime()
            telemetry.limited("FILE_TRANSFER_PROGRESS",
                "direction=receive id=$id bytes=${transfer.received}/${transfer.size}", op = transfer.op)
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
            telemetry.record("FILE_TRANSFER_COMPLETE", mapOf("direction" to "receive", "transferId" to id, "bytes" to transfer.size,
                "elapsedMs" to SystemClock.elapsedRealtime() - transfer.startedElapsedMs, "extension" to ext, "result" to "ok"), op = transfer.op)
            transferLastProgressAtMs = 0L
            ui { copy(fileTransferText = "Подготовка аудиофайла…") }
            link?.send(JSONObject().put("t", "file-ack").put("id", id).put("offset", transfer.size).put("complete", true))
            val receivedUri = Uri.fromFile(target)
            addAudioFiles(listOf(receivedUri), deleteIfImportFails = listOf(target), displayNames = mapOf(receivedUri to transfer.name))
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

    private fun isAudioDirectory(uri: Uri): Boolean =
        runCatching { app.contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR }.getOrDefault(false) ||
            (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(app, uri))

    private fun listAudioDocuments(tree: Uri): List<Pair<Uri, String>> {
        val result = mutableListOf<Pair<Uri, String>>()
        val visited = mutableSetOf<String>()
        val root = runCatching { DocumentsContract.getDocumentId(tree) }
            .getOrElse { runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyList() }
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
    fun setVolume(ch: String, v: Float, finished: Boolean = true) {
        if (ch == "M") ui { copy(master = v) }
        sendGainCommand("vol:$ch", finished) {
            cmd("vol", telemetry = finished) { put("ch", ch); put("v", v.toDouble()); put("law", 2); put("finished", finished) }
        }
    }
    fun setBalance(v: Float, finished: Boolean = true) {
        val value = v.coerceIn(0f, 1f)
        ui { copy(balance = value) }
        sendGainCommand("balance", finished) {
            cmd("balance", telemetry = finished) { put("v", value.toDouble()); put("finished", finished) }
        }
    }

    fun setEq(settings: EqSettings, finished: Boolean = true) {
        val clean = settings.sanitized()
        if (!ui.value.eqAvailable) return
        ui { copy(eq = clean) }
        sendGainCommand("eq", finished) {
            if (soloMode) post { applySoloEq(clean, finished) }
            else cmd("eq", telemetry = finished) {
                put("enabled", clean.enabled)
                put("gains", org.json.JSONArray().also { array -> clean.gainsDb.forEach { array.put(it.toDouble()) } })
                put("magnet", clean.magnet.name)
                put("preset", clean.preset)
                put("preserveLoudness", clean.preserveLoudness)
                put("bypass", clean.bypass)
                put("finished", finished)
            }
        }
    }

    fun setSpatial(settings: SpatialSettings, finished: Boolean = true) {
        val clean = settings.sanitized()
        if (!ui.value.spatialAvailable) return
        ui { copy(spatial = clean) }
        sendGainCommand("spatial", finished) {
            if (soloMode) post { applySpatialSettings(clean, finished) }
            else cmd("spatial", telemetry = finished) {
                put("enabled", clean.enabled); put("level", clean.level.wire); put("profile", clean.profile)
                clean.custom?.let { custom -> put("custom", JSONObject()
                    .put("gMidDb", custom.gMidDb).put("gHighDb", custom.gHighDb).put("gAmbientDb", custom.gAmbientDb)
                    .put("depthDb", custom.depthDb).put("reflectionDb", custom.reflectionDb)) }
                put("preserveLoudness", clean.preserveLoudness)
                put("abFixedCompensationDb", spatialAbFixedCompensationDb ?: JSONObject.NULL)
                put("finished", finished)
            }
        }
    }

    fun spatialAbCompensationSnapshotDb(): Double = activeSpatialSource?.loudnessCompensationDb ?: 0.0

    fun spatialAbAudition(level: SpatialLevel, session: SpatialAbSession) {
        spatialAbFixedCompensationDb = session.fixedCompensationDb
        val settings = session.originalSettings.copy(enabled = level != SpatialLevel.OFF, level = level,
            custom = null, preserveLoudness = true)
        pause()
        seekMs(session.anchorMs)
        setSpatial(settings, finished = false)
    }

    fun spatialAbReadyAt(ms: Long, expectedLevel: SpatialLevel): Boolean {
        val state = ui.value.spatial
        if (state.level != expectedLevel) return false
        if (expectedLevel == SpatialLevel.OFF) return activeSpatialSource == null
        val spatial = activeSpatialSource ?: return false
        val profileId = if (soloMode) "solo_stereo_speaker" else state.profile
        val expectedSignature = "${System.identityHashCode(source)}:$profileId:${state.toJson()}:$spatialAbFixedCompensationDb"
        if (activeSpatialInput !== source || activeSpatialSignature != expectedSignature) return false
        val frame = (ms.coerceAtLeast(0L) * SF.SAMPLE_RATE / 1_000L).coerceIn(0L, spatial.totalFrames)
        val target = minOf(spatial.totalFrames, frame + 8L * SF.SAMPLE_RATE)
        spatial.requestThrough(target)
        if (frame < spatial.totalFrames) spatial.sample(frame, 0)
        return spatial.readyFrames >= target
    }

    fun finishSpatialAb(session: SpatialAbSession) {
        spatialAbFixedCompensationDb = null
        setSpatial(session.originalSettings, finished = true)
        if (session.resumeAfter) play() else pause()
    }

    fun recordSpatialAbTrial(session: SpatialAbSession, trialIndex: Int, answer: SpatialAbAnswer) = post {
        val trial = session.trials.getOrNull(trialIndex) ?: return@post
        if (!answer.complete) return@post
        telemetry.record("SPATIAL_AB_TRIAL", mapOf("sessionId" to session.id, "trial" to trial.index,
            "control" to trial.control, "detectedDifference" to answer.detectsDifference,
            "correct" to (answer.detectsDifference == !trial.control)))
    }

    fun saveSpatialAbResult(session: SpatialAbSession) = post {
        if (!session.complete) return@post
        val trials = org.json.JSONArray()
        session.trials.zip(session.answers).forEach { (trial, answer) ->
            trials.put(JSONObject().put("trial", trial.index).put("control", trial.control)
                .put("variantA", trial.variantA.wire).put("variantB", trial.variantB.wire)
                .put("detectedDifference", answer.detectsDifference)
                .put("width", answer.width).put("center", answer.center).put("naturalness", answer.naturalness))
        }
        val result = JSONObject().put("sessionId", session.id).put("createdAt", System.currentTimeMillis())
            .put("comparison", session.comparison.wire).put("anchorMs", session.anchorMs)
            .put("fixedLoudnessCompensationDb", session.fixedCompensationDb)
            .put("correctDetections", session.correctDetections).put("trials", trials)
        val pValue = session.pValue
        result.put("pValue", pValue)
        val directory = File(app.filesDir, "spatial_ab").apply { mkdirs() }
        File(directory, "${System.currentTimeMillis()}_${session.id}.json").writeText(result.toString(2))
        telemetry.record("SPATIAL_AB_RESULT", mapOf("sessionId" to session.id,
            "comparison" to session.comparison.wire, "correctDetections" to session.correctDetections,
            "trials" to session.trials.size, "pValue" to pValue))
    }

    fun spatialMeterSnapshot(): Pair<Double, Double> = activeSpatialSource?.let { it.sideMidDb to it.correlation }
        ?: (0.0 to 1.0)

    fun spatialDeveloperSnapshot(): SpatialDeveloperSnapshot {
        val current = activeSpatialSource
        return SpatialDeveloperSnapshot(
            realtimeFactor = current?.realtimeFactor ?: 0.0,
            cacheAheadMs = current?.let { (it.readyFrames - positionFrameSnapshot()).coerceAtLeast(0) * 1000.0 / SF.SAMPLE_RATE } ?: 0.0,
            restarts = current?.restarts ?: 0,
            misses = current?.misses ?: 0,
            sideMidDb = current?.sideMidDb ?: 0.0,
            correlation = current?.correlation ?: 1.0,
            limiterDb = current?.limiterReductionDb ?: 0.0,
            loudnessCompDb = current?.loudnessCompensationDb ?: 0.0,
            xtcApplied = current?.xtcApplied ?: false,
            xtcDesignTimedOut = current?.xtcDesignTimedOut ?: false,
        )
    }

    fun resetSpatial() = setSpatial(SpatialSettings(profile = if (soloMode) "solo_stereo_speaker" else "pair_wide"))

    private fun applySoloEq(settings: EqSettings, finished: Boolean) {
        if (!soloMode) return
        val previousBypass = eqSettings.bypass
        eqSettings = settings.sanitized()
        eqConfig = EqualizerMath.config(eqSettings)
        rebuildPlayState()
        if (finished) saveSoloEqProfile(activeSoloEqRouteKey)
        ui { copy(eq = eqSettings, eqAvailable = true, eqPreampDb = eqConfig.preampDb.toFloat(),
            eqRouteLabel = audio.routeLabel(), eqCurveDb = eqConfig.curveDb.map { it.toFloat() }) }
        if (finished) {
            telemetry.record("EQ_CHANGE", mapOf("preset" to eqSettings.preset, "gains" to eqSettings.gainsDb,
                "preampDb" to eqConfig.preampDb, "magnet" to eqSettings.magnet.name.lowercase(),
                "by" to "solo", "route" to audio.routeLabel()))
            if (previousBypass != eqSettings.bypass) telemetry.record("EQ_BYPASS", mapOf(
                "on" to eqSettings.bypass, "loudnessCompDb" to eqConfig.bypassCompDb))
        }
    }

    fun setEqExpanded(expanded: Boolean) = post {
        prefs.edit().putBoolean("eqExpanded", expanded).apply()
        ui { copy(eqExpanded = expanded) }
    }

    fun setEqAnalyzer(enabled: Boolean) = post {
        eqSettings = eqSettings.copy(analyzerEnabled = enabled)
        ui { copy(eq = eqSettings) }
    }

    fun applyEqPreset(preset: EqPreset) = setEq(eqSettings.copy(
        enabled = true, gainsDb = preset.gainsDb, preset = preset.name, bypass = false,
    ), finished = true)

    fun saveEqPreset(name: String) = post {
        val cleanName = name.trim().take(32)
        if (cleanName.isBlank()) return@post
        val preset = EqPreset(cleanName, eqSettings.sanitized().gainsDb)
        val presets = (ui.value.customEqPresets.filterNot { it.name.equals(cleanName, true) } + preset)
        persistCustomEqPresets(presets)
        ui { copy(customEqPresets = presets) }
    }

    fun renameEqPreset(oldName: String, newName: String) = post {
        val cleanName = newName.trim().take(32)
        if (cleanName.isBlank()) return@post
        val current = ui.value.customEqPresets.firstOrNull { it.name == oldName } ?: return@post
        val presets = (ui.value.customEqPresets.filterNot { it.name == oldName || it.name.equals(cleanName, true) } + current.copy(name = cleanName))
        persistCustomEqPresets(presets)
        ui { copy(customEqPresets = presets) }
    }

    fun deleteEqPreset(name: String) = post {
        val presets = ui.value.customEqPresets.filterNot { it.name == name }
        persistCustomEqPresets(presets)
        ui { copy(customEqPresets = presets) }
    }

    private fun persistCustomEqPresets(presets: List<EqPreset>) {
        val json = org.json.JSONArray().also { array -> presets.forEach { preset ->
            array.put(JSONObject().put("name", preset.name).put("gains", org.json.JSONArray().also { gains ->
                preset.gainsDb.forEach { gains.put(it.toDouble()) }
            }))
        } }
        prefs.edit().putString("eqCustomPresets", json.toString()).apply()
    }

    private fun readCustomEqPresets(): List<EqPreset> = try {
        val array = org.json.JSONArray(prefs.getString("eqCustomPresets", "[]"))
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { item ->
            val gains = item.optJSONArray("gains")
            EqPreset(item.optString("name").take(32), List(EqualizerMath.BAND_COUNT) { j -> gains?.optDouble(j, 0.0)?.toFloat() ?: 0f })
        } }.filter { it.name.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    fun eqSpectrumSnapshot(): List<Float> {
        val capture = if (role == Role.POINT) pointRenderer.spectrumCapture else serverRenderer.spectrumCapture
        val window = FloatArray(4096)
        return if (capture.copyLatest(window)) EqSpectrumCapture().spectrumDb(window).toList()
        else List(30) { -100f }
    }

    private fun sendGainCommand(key: String, finished: Boolean, send: () -> Unit) {
        val now = System.nanoTime()
        var immediate = false
        synchronized(gainThrottleLock) {
            val last = lastGainCommandAtNs[key] ?: Long.MIN_VALUE
            val dueNs = if (last == Long.MIN_VALUE) now else last + 33_333_333L
            if (finished || now >= dueNs) {
                scheduledGainCommands.remove(key)?.cancel(false)
                pendingGainCommands.remove(key)
                lastGainCommandAtNs[key] = now
                immediate = true
            } else {
                pendingGainCommands[key] = send
                if (scheduledGainCommands[key] == null) {
                    scheduledGainCommands[key] = ex.schedule({
                        val pending = synchronized(gainThrottleLock) {
                            scheduledGainCommands.remove(key)
                            lastGainCommandAtNs[key] = System.nanoTime()
                            pendingGainCommands.remove(key)
                        }
                        pending?.invoke()
                    }, dueNs - now, TimeUnit.NANOSECONDS)
                }
            }
        }
        if (immediate) send()
    }
    fun micPermissionGranted() = post { ui { copy(calib = calib.copy(needsMicPermission = false, resultText = "")) } }

    private fun cmd(a: String, telemetry: Boolean = true, extra: JSONObject.() -> Unit = {}) {
        val op = java.util.UUID.randomUUID().toString()
        val issuedAt = if (role == Role.POINT && clock.ready) clock.toServer(System.nanoTime()) else System.nanoTime()
        val o = JSONObject().put("t", "cmd").put("a", a).put("op", op)
            .put("originRole", role.name.lowercase()).put("issuedSrvNs", issuedAt).apply(extra)
        val detail = JSONObject(o.toString())
        detail.remove("t"); detail.remove("op"); detail.remove("originRole"); detail.remove("issuedSrvNs")
        if (detail.has("id")) detail.put("id", TelemetryIdentifiers.hash(detail.optString("id")))
        if (telemetry) this.telemetry.record("USER_COMMAND", mapOf("cmd" to a, "role" to role.name.lowercase(), "args" to detail.toString()), op = op)
        post {
            if (!ui.value.inPair && !soloMode) return@post
            if (role == Role.SERVER) handleCmd(o) else link?.send(o) ?: toast("Нет связи с Server")
        }
    }

    fun setDebugUdpAudioStopped(enabled: Boolean) = post {
        if (!BuildConfig.DEBUG || role != Role.SERVER) return@post
        if (debugUdpAudioStopped == enabled) return@post
        debugUdpAudioStopped = enabled
        debugStopUdpTraffic = enabled
        link?.send(JSONObject().put("t", "debug-stop-udp").put("enabled", enabled))
        ui { copy(debugUdpAudioStopped = enabled) }
        telemetry.record(if (enabled) "DEBUG_FAILURE_START" else "DEBUG_FAILURE_END", mapOf(
            "failure" to "stop_udp_traffic", "expectedPath" to "tcp", "expectedRule" to "POINT_BUFFER_LOW"))
    }

    fun setDebugPointOutputSilenced(enabled: Boolean) = post {
        if (!BuildConfig.DEBUG || role != Role.POINT) return@post
        if (pointRenderer.debugSilence == enabled) return@post
        pointRenderer.debugSilence = enabled
        ui { copy(debugPointOutputSilenced = enabled) }
        telemetry.record(if (enabled) "DEBUG_FAILURE_START" else "DEBUG_FAILURE_END", mapOf(
            "failure" to "silence_point_output", "expectedRule" to "POINT_SILENT_OUTPUT"))
    }

    fun markProblem(comment: String) = post {
        val markId = java.util.UUID.randomUUID().toString()
        val safeComment = TelemetryText.clean(comment.take(200))
        telemetry.record("USER_MARK", mapOf("markId" to markId, "comment" to safeComment, "role" to role.name.lowercase()))
        flushBlackBox("mark:$markId")
        if (phase == Phase.PAIRED) link?.send(JSONObject().put("t", "mark").put("markId", markId)
            .put("comment", safeComment).put("role", role.name.lowercase()))
        toast("Отметка сохранена, ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
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
        .put("caps", localCapabilitiesJson()).put("addrs", addressesJson())

    private fun doInvite(p: Peer) {
        if (phase != Phase.IDLE) return
        manualLanBlockedPeerId = p.id.takeIf {
            peerPathDiagnoses[it]?.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED
        }
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
        manualLanBlockedPeerId = rec.peerId.takeIf { !auto &&
            peerPathDiagnoses[it]?.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED
        }
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
            val verdict = peerPathDiagnoses[rec.peerId]?.currentVerdict()
            toast(if (manualResumeAuto && verdict == PeerPathDiagnosis.Verdict.NONE) "«${rec.peerName}» пока не отвечает. Откройте SoundField на втором телефоне — пара восстановится"
                else peerVerdictMessage(rec.peerName, verdict))
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
        val storedHosts = rec.peerAddresses.mapNotNull { address ->
            runCatching { InetAddress.getByName(address.ip) }.getOrNull()
        }
        val successful = rec.lastSuccessfulHost ?: rec.lastHost
        val preferred = successful?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val mdnsHosts = found?.hosts.orEmpty().ifEmpty { listOfNotNull(found?.host) }
        val allHosts = (listOfNotNull(preferred) + mdnsHosts + storedHosts).distinctBy { it.hostAddress }
        if (allHosts.isEmpty()) return null
        val port = found?.port?.takeIf { it > 0 } ?: rec.lastPort
        if (port <= 0) return null
        return Peer(rec.peerId, rec.peerName, rec.peerPlatform, preferred ?: mdnsHosts.firstOrNull() ?: storedHosts.firstOrNull(),
            port, found?.supportsRinging ?: false, hosts = mdnsHosts.ifEmpty { allHosts },
            addresses = TransportPlan.mergeAddresses(found?.addresses.orEmpty(), rec.peerAddresses),
            lastSuccessfulHost = successful,
            historyHosts = listOfNotNull(rec.lastHost) + rec.peerAddresses.map { it.ip })
    }

    private fun resumeJson(rec: PairRecord) = JSONObject().put("t", "resume").put("id", myId).put("name", myName)
        .put("platform", "android").put("proto", SF.PROTOCOL_VERSION).put("key", rec.key).put("role", rec.myRole.wire)
        .put("tcpPort", tcp.localPort).put("caps", localCapabilitiesJson()).put("addrs", addressesJson())

    private fun acceptJson(key: String, r: Role) = JSONObject().put("t", "accept").put("id", myId).put("name", myName)
        .put("platform", "android").put("key", key).put("role", r.wire).put("tcpPort", tcp.localPort)
        .put("caps", localCapabilitiesJson()).put("addrs", addressesJson())

    private fun connectOutgoing(p: Peer, tag: String, first: JSONObject, timeoutMs: Long) {
        val routes = availableNetworkRoutes(wifiOnly = true)
        val networks = routes.map { it.networkId }.ifEmpty { listOf("process_default") }
        val planned = TransportPlan.candidates(
            lastSuccessfulHost = p.lastSuccessfulHost,
            mdns = p.hosts.mapNotNull { it.hostAddress },
            exchanged = p.addresses.map { it.ip },
            history = p.historyHosts + listOfNotNull(p.host?.hostAddress),
            networkIds = networks,
        )
        val routeById = routes.associateBy { it.networkId }
        val candidates = planned.mapNotNull { plannedCandidate ->
            val host = runCatching { InetAddress.getByName(plannedCandidate.host) }.getOrNull() ?: return@mapNotNull null
            ConnectionCandidate(host, routeById[plannedCandidate.networkId])
        }.distinctBy { it.route?.networkId to it.host.hostAddress }
        if (candidates.isEmpty()) return onConnectFailed(tag)
        val firstHost = candidates.first().host
        pendingPeer = p
        val attemptOp = if (tag == "resume-out" && phase == Phase.RECONNECTING) java.util.UUID.randomUUID().toString() else null
        if (attemptOp != null) {
            reconnectAttemptOp = attemptOp
            telemetry.record("RECONNECT_ATTEMPT_START", mapOf("parentOp" to reconnectSeriesOp,
                "attempt" to resumeAttempt, "method" to "last_ip_or_mdns"), op = attemptOp)
        }
        telemetry.record("CONNECT_CANDIDATES", mapOf("tag" to tag, "peer" to p.id,
            "candidates" to candidates.mapIndexed { index, candidate -> mapOf("index" to index,
                "network" to (candidate.route?.label ?: "process_default"),
                "networkId" to (candidate.route?.networkId ?: "process_default"),
                "localAddresses" to candidate.route?.let { networkAddresses(it.linkProperties)
                    .filterIsInstance<java.net.Inet4Address>().mapNotNull { address -> address.hostAddress } }.orEmpty(),
                "remoteAddress" to candidate.host.hostAddress, "remotePort" to p.port) }))
        telemetry.record("TCP_CONNECT_START", mapOf("tag" to tag, "peer" to p.id,
            "host" to firstHost.hostAddress, "hosts" to candidates.map { it.host.hostAddress },
            "port" to p.port, "ringing" to p.supportsRinging, "responseTimeoutMs" to timeoutMs,
            "wifiNetworks" to routes.map { it.label }))
        io.execute {
            val started = SystemClock.elapsedRealtime()
            var s: Socket? = null
            try {
                val connected = connectAcrossWifi(candidates, p.port, tag)
                val connectedSocket = connected.socket
                s = connectedSocket
                val connectedRoute = routeForLocalAddress(connectedSocket.localAddress) ?: connected.route
                val localAddress = connectedSocket.localAddress.hostAddress ?: connectedSocket.localAddress.toString()
                val network = connectedRoute?.label ?: "process_default"
                if (attemptOp != null) telemetry.record("RECONNECT_TCP_CONNECTED", mapOf(
                    "elapsedMs" to SystemClock.elapsedRealtime() - started, "result" to "ok",
                    "localAddress" to localAddress, "localPort" to connectedSocket.localPort,
                    "network" to network), op = attemptOp)
                telemetry.record("TCP_CONNECTED", mapOf("tag" to tag, "peer" to p.id,
                    "elapsedMs" to SystemClock.elapsedRealtime() - started, "localAddress" to localAddress,
                    "localPort" to connectedSocket.localPort, "network" to network,
                    "networkId" to (connectedRoute?.networkId ?: "unknown"),
                    "remoteAddress" to connected.host.hostAddress, "remotePort" to p.port))
                post {
                    val stillWanted = when (tag) {
                        "invite-out" -> phase == Phase.INVITING && pendingPeer?.id == p.id
                        "resume-auto" -> phase == Phase.IDLE && autoResumeUntil != 0L
                        else -> phase == Phase.RECONNECTING || (phase == Phase.INVITING && manualResumeActive)
                    }
                    if (!stillWanted || pending != null) { telemetry.event("TCP_CONNECT_DISCARDED", "tag=$tag phase=$phase"); try { connectedSocket.close() } catch (_: Exception) {}; return@post }
                    val l = newLink(connectedSocket, tag)
                    pending = l
                    l.send(first)
                    pendingTimeout?.cancel(false)
                    pendingTimeout = later(timeoutMs) { if (pending === l) l.close("response timeout ${timeoutMs}ms") }
                }
            } catch (e: Exception) {
                try { s?.close() } catch (_: Exception) { }
                val failure = e as? ConnectionAttemptsException
                val candidateResults = failure?.outcomes.orEmpty()
                if (tag == "resume-out" && phase == Phase.RECONNECTING) {
                    candidateResults.forEach(reconnectCandidateHistory::add)
                    while (reconnectCandidateHistory.size > 400) reconnectCandidateHistory.poll()
                }
                if (attemptOp != null) {
                    telemetry.record("RECONNECT_ATTEMPT_END", mapOf("elapsedMs" to SystemClock.elapsedRealtime() - started,
                        "result" to "error"), op = attemptOp, error = e)
                    if (reconnectAttemptOp == attemptOp) reconnectAttemptOp = null
                }
                telemetry.record("TCP_CONNECT_FAILED", mapOf("tag" to tag, "peer" to p.id,
                    "host" to firstHost.hostAddress, "hosts" to candidates.map { it.host.hostAddress },
                    "port" to p.port, "remote" to candidates.map { "${it.host.hostAddress}:${p.port}" },
                    "local" to routes.map { route -> mapOf("addresses" to networkAddresses(route.linkProperties)
                        .filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress },
                        "network" to route.label, "networkId" to route.networkId) },
                    "errorMessage" to (e.message ?: e.javaClass.simpleName),
                    "elapsedMs" to SystemClock.elapsedRealtime() - started,
                    "wifiNetworks" to routes.map { it.label },
                    "candidateResults" to failure?.candidates.orEmpty().map { mapOf("network" to it.networkLabel,
                        "networkId" to it.networkId, "localIpv4" to it.localIpv4, "remoteAddress" to it.remoteHost,
                        "remotePort" to it.remotePort, "reason" to it.reason.name) }), error = e)
                post {
                    failure?.let { recordPeerPathRound(p, tag, it.candidates, routes) }
                    val failedAllByTimeout = failure?.candidates?.let {
                        PeerPathDiagnosis.classifyRound(it.map(PeerPathDiagnosis.Candidate::reason)) ==
                            PeerPathDiagnosis.RoundVerdict.ALL_TIMEOUT
                    } == true
                    val manualLanRetry = manualLanBlockedPeerId == p.id && failedAllByTimeout && phase == Phase.INVITING &&
                        peerPathDiagnoses[p.id]?.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED &&
                        (tag == "invite-out" || tag == "resume-out" && manualResumeActive && !manualResumeAuto)
                    if (manualLanRetry) {
                        manualLanBlockedPeerId = null
                        manualResumeActive = false
                        phase = Phase.IDLE
                        ui { copy(phase = Phase.IDLE) }
                        log("«${p.name}» не ответил")
                        showLanBlockedHint(p, routes, if (tag == "invite-out") "invite-out" else "resume-out", manual = true)
                        toast(peerVerdictMessage(p.name, peerPathDiagnoses[p.id]?.currentVerdict()))
                        return@post
                    }
                    if (peerPathDiagnoses[p.id]?.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED) {
                        val count = (lanBlockedLogCount[p.id] ?: 0) + 1
                        lanBlockedLogCount[p.id] = count
                        val last = lanBlockedLogAt[p.id] ?: 0L
                        if (SystemClock.elapsedRealtime() - last >= 30_000) {
                            lanBlockedLogAt[p.id] = SystemClock.elapsedRealtime()
                            log("Нет соединения с «${p.name}» (ещё $count попыток)")
                            lanBlockedLogCount[p.id] = 0
                        }
                    } else log("Нет соединения с ${candidateResults.joinToString()} — ${e.cause?.message ?: e.message}")
                    onConnectFailed(tag)
                }
            }
        }
    }

    private fun connectAcrossWifi(
        candidates: List<ConnectionCandidate>,
        port: Int,
        tag: String,
    ): ConnectedSocket {
        val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
        val socketsByCandidate = ConcurrentHashMap<ConnectionCandidate, Socket>()
        val reasons = ConcurrentHashMap<ConnectionCandidate, PeerPathDiagnosis.Reason>()
        val winningSocket = AtomicReference<Socket?>(null)
        val race = try {
            HappyEyeballs.connect(candidates, CONNECT_STAGGER_MS,
                cancelInFlight = { sockets.forEach { socket ->
                    if (socket !== winningSocket.get()) try { socket.close() } catch (_: Exception) { }
                } },
                onAttempt = { index, candidate, status, error ->
                    reasons[candidate] = when {
                        status == "connected" -> PeerPathDiagnosis.Reason.OTHER
                        status.contains("cancelled") -> PeerPathDiagnosis.Reason.CANCELLED
                        else -> PeerPathDiagnosis.classify(error ?: java.io.IOException(status))
                    }
                    val socket = socketsByCandidate[candidate]
                    if (status == "connected" && socket != null) winningSocket.set(socket)
                    val localAddress = socket?.localAddress?.hostAddress ?: candidate.route?.let { route ->
                        networkAddresses(route.linkProperties).firstOrNull { it is java.net.Inet4Address }?.hostAddress
                    } ?: "unknown"
                    val event = when {
                        status == "connected" -> "TCP_CONNECT_NETWORK_CONNECTED"
                        status.startsWith("failed:") -> "TCP_CONNECT_NETWORK_FAILED"
                        else -> "TCP_CONNECT_NETWORK_CANCELLED"
                    }
                    telemetry.record(event,
                        mapOf("tag" to tag, "attempt" to (index + 1), "network" to (candidate.route?.label ?: "process_default"),
                            "networkId" to (candidate.route?.networkId ?: "process_default"), "localAddress" to localAddress,
                            "localPort" to (socket?.localPort ?: 0), "remoteAddress" to candidate.host.hostAddress,
                            "remotePort" to port, "result" to status,
                            "reason" to (reasons[candidate]?.name ?: "OTHER"), "errorMessage" to error?.message), error = error)
                }) { candidate ->
                val socket = candidate.route?.network?.socketFactory?.createSocket() ?: Socket()
                sockets += socket
                socketsByCandidate[candidate] = socket
                try {
                    socket.connect(InetSocketAddress(candidate.host, port), 2_500)
                    socket
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) { }
                    throw e
                }
            }
        } catch (e: AllCandidatesFailed) {
            val outcomes = e.attempts.map { attempt ->
                val candidate = attempt.candidate as? ConnectionCandidate
                "${candidate?.label() ?: attempt.candidate}=${attempt.result}"
            }
            val candidates = e.attempts.mapNotNull { attempt ->
                val candidate = attempt.candidate as? ConnectionCandidate ?: return@mapNotNull null
                val route = candidate.route
                PeerPathDiagnosis.Candidate(route?.networkId ?: "process_default", route?.label ?: "process_default",
                    route?.let { networkAddresses(it.linkProperties).filterIsInstance<java.net.Inet4Address>().mapNotNull { a -> a.hostAddress } }.orEmpty(),
                    candidate.host.hostAddress ?: "", port, reasons[candidate] ?: PeerPathDiagnosis.classify(java.io.IOException(attempt.result)))
            }
            telemetry.record("CONNECT_CANDIDATES", mapOf("tag" to tag, "result" to "failed", "routes" to outcomes))
            throw ConnectionAttemptsException(outcomes, candidates, e)
        }
        val outcomes = race.attempts.map { attempt -> "${attempt.candidate.label()}=${attempt.result}" }
        telemetry.record("CONNECT_CANDIDATES", mapOf("tag" to tag, "result" to "connected",
            "winner" to race.candidate.host.hostAddress, "routes" to outcomes))
        return ConnectedSocket(race.connection, race.candidate.route, race.candidate.host)
    }

    private fun recordPeerPathRound(peer: Peer, tag: String, candidates: List<PeerPathDiagnosis.Candidate>, routes: List<NetworkRoute>) {
        val now = SystemClock.elapsedRealtime()
        val gateways = probeGateways(routes, now)
        val round = PeerPathDiagnosis.Round(candidates.firstOrNull()?.remoteHost.orEmpty(), peer.port, now, candidates)
        val diagnosis = peerPathDiagnoses.getOrPut(peer.id) { PeerPathDiagnosis() }
        val discoveredPeer = discovery.peer(peer.id)
        val result = diagnosis.onRound(round, routes.map { it.networkId }.toSet(), gateways,
            peerPresent = discoveredPeer != null, presentPort = discoveredPeer?.port)
        if (!result.changed || result.verdict == PeerPathDiagnosis.Verdict.NONE) return
        val trigger = when {
            tag == "invite-out" -> "invite-out"
            tag == "resume-auto" -> "resume-auto"
            tag == "resume-out" && phase == Phase.RECONNECTING -> "reconnect"
            else -> "resume-out"
        }
        val localNetworks = routes.map { route -> mapOf("label" to route.label, "networkId" to route.networkId,
            "ipv4" to networkAddresses(route.linkProperties).filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }) }
        val shown = when (result.verdict) {
            PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED -> {
                log("Сеть блокирует соединение с «${peer.name}»: найден через mDNS (${round.host}:${peer.port}), TCP не проходит (${result.rounds} попыток)")
                lanBlockedLogAt[peer.id] = now
                lanBlockedLogCount[peer.id] = 0
                trigger != "resume-auto" && showLanBlockedHint(peer, routes, trigger,
                    manual = phase == Phase.INVITING && (tag == "invite-out" || manualResumeActive && !manualResumeAuto))
            }
            PeerPathDiagnosis.Verdict.NO_ROUTE -> { log("Нет маршрута до «${peer.name}». Проверьте, что оба телефона в одной сети Wi‑Fi"); false }
            else -> false
        }
        telemetry.record("PEER_PATH_DIAGNOSIS", mapOf("peer" to peer.id, "peerName" to peer.name,
            "verdict" to result.verdict.name, "confidence" to result.confidence?.name?.lowercase(),
            "rounds" to result.rounds, "spanMs" to result.spanMs, "lastResolveAgeMs" to result.lastResolveAgeMs,
            "resolvedPort" to result.resolvedPort, "triedPort" to result.triedPort,
            "localNetworks" to localNetworks, "gatewayProbe" to gateways,
            "candidateResults" to result.candidates.map { mapOf("network" to it.networkLabel, "networkId" to it.networkId,
                "remoteAddress" to it.remoteHost, "remotePort" to it.remotePort, "reason" to it.reason.name) },
            "shownToUser" to shown, "trigger" to trigger),
            category = if (result.verdict == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED) "incident" else "network")
    }

    private fun probeGateways(routes: List<NetworkRoute>, nowMs: Long): Map<String, String> = routes.associate { route ->
        val id = route.networkId
        val prevAt = gatewayProbeAt[id] ?: 0L
        if (nowMs - prevAt < 60_000) return@associate id to (gatewayProbeState[id] ?: "unknown")
        gatewayProbeAt[id] = nowMs
        val gateway = route.linkProperties.routes.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway
        val state = if (gateway == null) "unknown" else try {
            fun attempt(port: Int): String {
                val socket = route.network.socketFactory.createSocket()
                try { socket.connect(InetSocketAddress(gateway, port), 1_000); return "ok" }
                catch (e: java.net.ConnectException) {
                    if (e.message.orEmpty().contains("refused", true) || e.message.orEmpty().contains("ECONNREFUSED", true)) return "ok"
                    throw e
                } finally { try { socket.close() } catch (_: Exception) {} }
            }
            try { attempt(80) } catch (e: java.net.SocketTimeoutException) {
                try { attempt(53) } catch (_: java.net.SocketTimeoutException) { "timeout" }
            }
        } catch (_: Exception) { "unknown" }
        gatewayProbeState[id] = state
        id to state
    }

    private fun showLanBlockedHint(peer: Peer, routes: List<NetworkRoute>, trigger: String, manual: Boolean = false): Boolean {
        val signature = routes.map { it.networkId }.sorted().joinToString(",")
        val key = "lanHint:${peer.id}:$signature"
        val lastShown = prefs.getLong(key, 0L)
        val wallNow = System.currentTimeMillis()
        if (!manual && wallNow - lastShown in 0 until 30 * 60_000L) return false
        prefs.edit().putLong(key, wallNow).apply()
        if (phase == Phase.INVITING && trigger != "resume-auto") {
            manualResumeActive = false
            pending?.let { it.tag = "dead"; it.close() }; pending = null
            phase = Phase.IDLE
            ui { copy(phase = Phase.IDLE) }
            toast("Роутер блокирует соединение между телефонами")
        }
        ui { copy(showLanBlocked = true, lanBlockedPeerName = peer.name,
            lanBlockedDetails = if (routes.size >= 2) "double_wifi" else if (record?.peerId == peer.id &&
                record?.lastSuccessfulNetworkId != null && routes.none { it.networkId == record?.lastSuccessfulNetworkId }) "previous_wifi" else "") }
        telemetry.record("LAN_BLOCKED_HINT_SHOWN", mapOf("peer" to peer.id, "trigger" to trigger))
        telemetry.record("PEER_PATH_DIAGNOSIS_HINT", mapOf("peer" to peer.id, "shownToUser" to true, "trigger" to trigger))
        return true
    }

    fun dismissLanBlockedHint(action: String = "dismiss") = post {
        telemetry.record("LAN_BLOCKED_HINT_ACTION", mapOf("peerName" to ui.value.lanBlockedPeerName, "action" to action))
        ui { copy(showLanBlocked = false) }
    }

    private fun onConnectFailed(tag: String) {
        when {
            tag == "invite-out" && phase == Phase.INVITING -> {
                val p = pendingPeer
                val verdict = p?.let { peerPathDiagnoses[it.id]?.currentVerdict() }
                if (verdict != PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED || !ui.value.showLanBlocked)
                    toast(peerVerdictMessage(p?.name.orEmpty(), verdict, invite = true))
                phase = Phase.IDLE; ui { copy(phase = Phase.IDLE) }
            }
            tag == "resume-out" && phase == Phase.RECONNECTING -> scheduleReconnect(reconnectDelay())
            tag == "resume-out" && phase == Phase.INVITING -> later(700) { tryManualResume() }
        }
    }

    private fun peerVerdictMessage(name: String, verdict: PeerPathDiagnosis.Verdict?, invite: Boolean = false): String = when (verdict) {
        PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED -> "Роутер блокирует соединение между телефонами"
        PeerPathDiagnosis.Verdict.NO_ROUTE -> "Нет маршрута до «$name». Проверьте, что оба телефона в одной сети Wi‑Fi"
        PeerPathDiagnosis.Verdict.PEER_NOT_LISTENING -> "«$name» не отвечает. Откройте SoundField на втором телефоне"
        PeerPathDiagnosis.Verdict.NOT_DISCOVERED -> "«$name» не найден. Откройте SoundField на втором телефоне в той же сети Wi‑Fi"
        else -> if (invite) "Не удалось подключиться к телефону" else "«$name» пока не отвечает. Откройте SoundField на втором телефоне"
    }

    private fun newLink(s: Socket, tag: String = "") = ControlLink(s,
        onMessage = { l, o -> post { onMessage(l, o) } },
        onClosed = { l -> post { onClosed(l) } }, telemetry = telemetry,
        initialTag = tag, localNetwork = routeForLocalAddress(s.localAddress)?.label ?: "unknown",
        localNetworkId = routeForLocalAddress(s.localAddress)?.networkId ?: "unknown")

    private fun onIncomingSocket(s: Socket) {
        val l = newLink(s, "new")
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
                val advertised = parsePeerAddresses(o)
                if (phase == Phase.IDLE) stopAutoResume()   // новое приглашение важнее фонового восстановления
                if (phase == Phase.IDLE && pending == null) {
                    peerCapabilities = parsePeerCaps(o); peerAddressSnapshot = advertised
                    l.tag = "incoming"; pending = l
                    pendingPeer = Peer(pid, pname, pplat, l.remoteAddress, o.optInt("tcpPort", 0),
                        hosts = (listOf(l.remoteAddress) + advertised.mapNotNull { runCatching { InetAddress.getByName(it.ip) }.getOrNull() }).distinct(),
                        addresses = advertised)
                    pendingKey = o.optString("key")
                    phase = Phase.INCOMING
                    l.send(JSONObject().put("t", "ringing"))
                    log("Приглашение от «$pname»")
                    ui { copy(phase = Phase.INCOMING, incomingName = pname) }
                    pendingTimeout?.cancel(false)
                    pendingTimeout = later(60_000) { if (pending === l) { telemetry.event("INVITE_DECISION_TIMEOUT"); doReject() } }
                } else if (phase == Phase.INVITING && pendingPeer?.id == pid) {
                    // Встречные приглашения: уступает телефон с бо́льшим id (детерминированно на обеих сторонах).
                    if (CrossedConnectionPolicy.acceptInbound(myId, pid)) {
                        peerCapabilities = parsePeerCaps(o); peerAddressSnapshot = advertised
                        pending?.let { it.tag = "dead"; it.close() }
                        l.tag = "incoming"; pending = l
                        pendingPeer = Peer(pid, pname, pplat, l.remoteAddress, o.optInt("tcpPort", 0),
                            hosts = (listOf(l.remoteAddress) + advertised.mapNotNull { runCatching { InetAddress.getByName(it.ip) }.getOrNull() }).distinct(),
                            addresses = advertised)
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
                val advertised = parsePeerAddresses(o)
                val rec = record
                val ok = rec != null && rec.key == o.optString("key") && rec.peerId == pid &&
                    phase != Phase.INCOMING
                if (!ok) {
                    log("Отклонён resume от «$pname»: пара неизвестна")
                    l.tag = "dead"; l.sendAndClose(JSONObject().put("t", "reject").put("reason", "unknown")); return
                }
                if (soloMode && serverPlay.timeline.playing && rec!!.myRole == Role.POINT && !manualResumeActive) {
                    log("Resume от «$pname» отклонён: на этом телефоне играет музыка")
                    telemetry.record("AUTO_RESUME_DEFERRED", mapOf("reason" to "solo_playing_as_point", "inbound" to true))
                    l.tag = "dead"; l.sendAndClose(JSONObject().put("t", "busy")); return
                }
                // Оба телефона стучатся друг к другу одновременно — оставляем одно соединение детерминированно:
                // входящее принимает телефон с бо́льшим id (как и для встречных приглашений).
                val mine = pending
                if (mine != null && (mine.tag == "resume-out" || mine.tag == "resume-auto") &&
                    !CrossedConnectionPolicy.acceptInbound(myId, pid)) {
                    l.tag = "dead"; l.sendAndClose(JSONObject().put("t", "reject").put("reason", "crossed")); return
                }
                peerCapabilities = parsePeerCaps(o); peerAddressSnapshot = advertised
                updatePeerAddressBook(advertised)
                // старое (возможно, «мёртвое») соединение заменяется новым
                link?.let { old -> link = null; old.tag = "dead"; old.close() }
                pending?.let { p -> pending = null; p.tag = "dead"; p.close() }
                l.send(acceptJson(rec!!.key, rec.myRole))
                peerTcpPortHint = o.optInt("tcpPort", 0)
                log("Входящее восстановление от «$pname» (${l.remoteAddress.hostAddress})")
                becomePaired(l, rec.myRole, pid, pname, pplat, rec.key, resumed = true)
            }
            else -> telemetry.record("TCP_UNKNOWN_MESSAGE_IGNORED", mapOf("type" to o.optString("t", ""),
                "linkId" to l.id, "phase" to phase.name))
        }
    }

    private fun onPendingMessage(l: ControlLink, o: JSONObject) {
        val pid = o.optString("id"); val pname = o.optString("name", pid); val pplat = o.optString("platform", "?")
        when (o.optString("t")) {
            "accept" -> when (l.tag) {
                "invite-out" -> {
                    peerCapabilities = parsePeerCaps(o)
                    peerAddressSnapshot = parsePeerAddresses(o)
                    peerTcpPortHint = o.optInt("tcpPort", 0).takeIf { it > 0 } ?: (pendingPeer?.port ?: 0)
                    becomePaired(l, Role.SERVER, pid, pname, pplat, pendingKey, resumed = false)
                }
                "resume-out", "resume-auto" -> {
                    peerCapabilities = parsePeerCaps(o)
                    peerAddressSnapshot = parsePeerAddresses(o)
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
            reconnectAttemptOp?.let { op ->
                telemetry.record("RECONNECT_ATTEMPT_END", mapOf("result" to "timeout",
                    "elapsedMs" to (SystemClock.elapsedRealtime() - reconnectSince)), op = op)
                reconnectAttemptOp = null
            }
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
        if (soloMode) leaveSoloForPair(r)
        soloMode = false
        audio.preferPhoneSpeaker = true
        peerPathDiagnoses.getOrPut(pid) { PeerPathDiagnosis() }.onConnected()
        if (manualLanBlockedPeerId == pid) manualLanBlockedPeerId = null
        pendingTimeout?.cancel(false)
        reconnectTask?.cancel(false)
        if (pending === l) pending = null
        pending?.let { it.tag = "dead"; it.close() }; pending = null
        manualResumeActive = false
        autoResumeUntil = 0
        // «Продолжение» — пара та же, меняется только соединение (после обрыва, или второй телефон заметил
        // обрыв раньше нас и прислал resume, пока мы ещё считали связь живой). Музыка при этом не сбрасывается.
        val continuing = (phase == Phase.RECONNECTING || phase == Phase.PAIRED) && role == r && peerId == pid
        if (continuing) {
            val elapsed = SystemClock.elapsedRealtime() - reconnectSince
            reconnectAttemptOp?.let { op ->
                telemetry.record("RECONNECT_ATTEMPT_END", mapOf("result" to "ok", "elapsedMs" to elapsed), op = op)
            }
            reconnectAttemptOp = null
            reconnectSeriesOp?.let { op ->
                telemetry.record("RECONNECT_END", mapOf("result" to "ok", "durMs" to elapsed), op = op)
            }
            reconnectSeriesOp = null
        }
        l.tag = "paired"
        link = l
        role = r; peerId = pid; peerName = pname; peerPlatform = pplat
        negotiatedCapabilities = peerCapabilities.intersect(setOf("addrs", "tcpClock", "tcpAudio", "eq", "spatialV1", "fileTransferV1"))
        phase = Phase.PAIRED
        zeroSendRenewStreak = 0
        udpNetworkRebindAttempted = false
        udpFailureNoticeShown = false
        audioRenewIntervalMs = 5_000L
        sessionSentBaseline = sentAudioPackets.get()
        bindUdpToControlLink("paired")
        pairedAtMs = SystemClock.elapsedRealtime()
        lastAudioSessionResetMs = pairedAtMs
        udpLastRx.set(0)
        udpFirstPacketPending.set(false)
        udpFirstPacketAtMs.set(0)
        udpFirstPacketFrom.set(null)
        lastAudioRxAt.set(0)

        val old = record
        val host = l.remoteAddress.hostAddress
        val discoveredAddresses = (pendingPeer?.addresses.orEmpty() + peerAddressSnapshot).distinctBy { it.ip }
        val allPeerAddresses = TransportPlan.mergeAddresses(discoveredAddresses,
            (pendingPeer?.hosts.orEmpty() + l.remoteAddress).mapNotNull { address ->
                address.hostAddress?.let { PeerAddress(it, "", "") }
            })
        val port = if (peerTcpPortHint > 0) peerTcpPortHint else if (old?.key == key) old.lastPort else 0
        peerTcpPortHint = 0
        record = if (old != null && old.key == key) old.copy(peerName = pname, peerPlatform = pplat, myRole = r,
            lastHost = host, lastPort = port, peerAddresses = TransportPlan.mergeAddresses(allPeerAddresses, old.peerAddresses),
            lastSuccessfulHost = host, lastSuccessfulNetworkId = l.localNetworkId.takeIf { it != "unknown" })
                 else PairRecord(key, pid, pname, pplat, r, lastHost = host, lastPort = port,
                     peerAddresses = allPeerAddresses, lastSuccessfulHost = host,
                     lastSuccessfulNetworkId = l.localNetworkId.takeIf { it != "unknown" })
        peerAddressSnapshot = emptyList()
        peerCapabilities = emptySet()
        peerUnreachableReported = false
        reconnectCandidateHistory.clear()
        persistRecord()
        if (!continuing) spatialSettings = record?.spatial ?: SpatialSettings()
        setAutoResume(true)
        requestDozeExemption()
        if (!discovery.advertising) discovery.advertise(tcp.localPort, myName)
        log("Пара ${if (continuing) "продолжена" else if (resumed) "восстановлена" else "создана"}: «$pname» $host:$port, роль ${r.wire}")
        discovery.stopBrowse()
        didAcknowledgeGainLaw = false
        peerGainLawReady = false
        if (!continuing) {
            micReferenceOne = null; micReferenceDeltaOne = null; micReferenceDeltaTwo = null; micSensitivityDeltaDb = null
            levelMeasureText = ""
        }

        if (r == Role.SERVER) {
            record?.let { rec ->
                if (!continuing) {
                    pointChannel = rec.pointChannel; trimNs = (rec.trimMs * 1e6).toLong()
                    volL = rec.volL; volR = rec.volR
                    master = if (rec.volTaper < 2) AudioGain.migrateMaster(rec.master) else rec.master
                    balance = rec.balance; levelTrimServerDb = rec.levelTrimServerDb; levelTrimPointDb = rec.levelTrimPointDb
                    if (rec.levelTrimVersion < 1) {
                        val legacyServer = if (Ch.other(pointChannel) == Ch.L) volL else volR
                        val legacyPoint = if (pointChannel == Ch.L) volL else volR
                        val migrated = AudioGain.migrateChannelGains(legacyServer, legacyPoint)
                        levelTrimServerDb = migrated.first; levelTrimPointDb = migrated.second
                    }
                    delayCalib = rec.delayCalib.let {
                        if (it.state == "none" && rec.trimMs != 0.0) DelayCalib("manual", System.currentTimeMillis(), 0.0) else it
                    }
                    levelCalib = rec.levelCalib
                    eqSettings = rec.eq.sanitized()
                    eqConfig = EqualizerMath.config(eqSettings)
                    record = rec.copy(master = master, volTaper = 2, levelTrimServerDb = levelTrimServerDb,
                        levelTrimPointDb = levelTrimPointDb, levelTrimVersion = 1, delayCalib = delayCalib, eq = eqSettings)
                    persistRecord()
                }
            }
            sessionId = rnd.nextInt().let { if (it == 0) 1 else it }
            pointUdp = null; pointClockReady = false
            closeServerTcpAudio("control_pair_reset")
            if (!continuing) {
                mode = SourceMode.MUSIC
                source = if (queueIndex in queue.indices) currentQueueSource ?: music else music
                // Соло → Server: та же музыка с той же позиции и в том же состоянии (играет/пауза), без сброса.
                val handoff = soloHandoffFrame >= 0L
                newEpoch(handoff && soloHandoffPlaying, if (handoff) soloHandoffFrame.coerceIn(0L, source.totalFrames) else 0L,
                    System.nanoTime())
                soloHandoffFrame = -1L; soloHandoffPlaying = false
                if (queueIndex in queue.indices && currentQueueSource == null) selectTrack(queueIndex, forcePaused = true)
            }
            else rebuildPlayState()
            audio.playbackActive = serverPlay.timeline.playing
            audio.source = serverRenderer
            fileTransferToken = hex(24)
            sendSession(l)
            sendTimeline()
            publishState()
            publishQueue()
        } else {
            sessionId = 0; serverUdp = null
            serverFileTransferToken = ""
            removedBackup = emptyList(); undoText = ""; undoToken++
            ui { copy(undoText = "") }
            if (!continuing) { clock.reset(); rx.reset(); tcpAudioBuffer.clear() }
            audio.playbackActive = rx.timeline.playing
            audio.source = pointRenderer
        }
        audio.start()
        var fgsFailed = false
        try { PlaybackService.start(app) } catch (e: Exception) { Log.w(TAG, "fgs", e); telemetry.event("FOREGROUND_SERVICE_FAILED", error = e); fgsFailed = true }
        ui { copy(phase = Phase.PAIRED, role = r, peerName = pname, peerPlatform = pplat, linkOk = true, remembered = record,
            fileTransferAvailable = supportsFileTransferPeer(),
            autoResuming = false, audioMissing = false, calib = CalibUi(), eqAvailable = eqAvailableInPair(),
            eq = if (r == Role.SERVER) eqSettingsForWire() else eq, eqPreampDb = activeEqConfig().preampDb.toFloat(),
            eqRouteLabel = audio.routeLabel(), eqCurveDb = activeEqConfig().curveDb.map { it.toFloat() }) }
        PlaybackService.refresh(app)
        val text = if (resumed) "Пара восстановлена" else "Стереопара создана"
        toast(if (fgsFailed) "$text. Фоновый режим не запущен: при блокировке экрана звук может прерваться" else text)
    }

    /** Экспоненциальный backoff: 1с → 2с → 4с → 8с → 15с (потолок). */
    private fun reconnectDelay(): Long {
        val delay = ReconnectBackoff.delayMs(resumeAttempt, availableNetworkRoutes(wifiOnly = true).isNotEmpty())
        val diagnosis = record?.peerId?.let(peerPathDiagnoses::get)?.currentVerdict()
        return if (diagnosis == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED) maxOf(delay, 10_000) else delay
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
        peerUnreachableReported = false
        reconnectCandidateHistory.clear()
        reconnectSeriesOp = java.util.UUID.randomUUID().toString()
        telemetry.record("RECONNECT_START", mapOf("reason" to "tcp_link_lost", "role" to role.name.lowercase()),
            op = reconnectSeriesOp)
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
            serverUdp = null; sessionId = 0
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
        if (!peerUnreachableReported && now - reconnectSince >= 60_000L) {
            peerUnreachableReported = true
            val attempts = reconnectCandidateHistory.toList()
            telemetry.record("PEER_UNREACHABLE", mapOf("peer" to rec.peerId, "peerName" to rec.peerName,
                "elapsedMs" to now - reconnectSince, "attemptedRoutes" to attempts))
            if (peerPathDiagnoses[rec.peerId]?.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED) {
                showLanBlockedHint(resumeTarget(rec) ?: Peer(rec.peerId, rec.peerName, rec.peerPlatform, null, rec.lastPort),
                    availableNetworkRoutes(wifiOnly = true), "reconnect")
            } else {
                maybeShowStabilitySettings("peer_unreachable")
                toast("Не удаётся найти «${rec.peerName}» больше минуты. Проверьте Wi‑Fi на обоих телефонах; при двойном Wi‑Fi попробуйте оставить одну сеть.")
            }
        }
        if (now - lastBrowseRestart > 20_000) { lastBrowseRestart = now; discovery.restartBrowse() }
        val p = resumeTarget(rec)
        if (p == null) { scheduleReconnect(reconnectDelay()); return }
        connectOutgoing(p, "resume-out", resumeJson(rec), 4_000)
    }

    private fun goIdle(sendBye: Boolean, reason: String) {
        // Пара → соло: бывший Server продолжает ту же музыку в стерео с той же позиции; бывший Point возвращается
        // к своей очереди на месте, где остановился при создании пары.
        val leavingRole = role
        val resumePlaying = leavingRole == Role.SERVER && mode == SourceMode.MUSIC && serverPlay.timeline.playing
        val resumeFrame = if (leavingRole == Role.SERVER) (if (mode == SourceMode.MUSIC) posFrame() else 0L) else soloResumeFrame
        cancelSyncProbe("pair_disconnected")
        if (soloMode) saveSoloEqProfile(activeSoloEqRouteKey)
        cancelPointFileTransfer()
        abortIncomingFileTransfer("pair disconnected")
        reconnectTask?.cancel(false); pendingTimeout?.cancel(false)
        link?.let { l -> link = null; l.tag = "dead"; if (sendBye) l.sendAndClose(JSONObject().put("t", "bye")) else l.close() }
        pending?.let { p -> pending = null; p.tag = "dead"; p.close() }
        phase = Phase.IDLE
        soloMode = false
        activeSoloEqRouteKey = ""
        audio.preferPhoneSpeaker = true
        manualResumeActive = false
        log(reason)
        pendingTrackId = null
        clearProgressiveBufferState()
        audio.stop(); audio.source = null
        abandonFocus()
        try { PlaybackService.stop(app) } catch (_: Exception) {}
        serverPlay = ServerPlayState(Timeline.NONE, source, Ch.L, 0f, EqConfig.neutral())
        pointUdp = null; serverUdp = null; sessionId = 0
        closeServerTcpAudio("pair_disconnected")
        pointTcpAudioSocket?.let { try { it.close() } catch (_: Exception) {} }
        pointTcpAudioSocket = null
        tcpAudioBuffer.clear(); cancelTcpAudioPreload()
        setAudioPath("udp", "pair_disconnected", notifyPeer = false)
        setClockPath("udp", "pair_disconnected")
        rx.reset(); clock.reset()
        calibRunning = false
        discovery.startBrowse()
        if (!appVisible && !resumePlaying) hideWhileBackground()   // пару разорвали, пока приложение в фоне
        ui { copy(phase = Phase.IDLE, soloMode = false, eqAvailable = false, linkOk = false, remembered = record, playing = false, audioMissing = false, calib = CalibUi(),
            loadingTracks = false, loadingTrackId = null, serverDiag = Diag(), pointDiag = Diag()) }
        telemetry.record("PAIR_TO_SOLO", mapOf("fromRole" to leavingRole.name.lowercase(), "resumedPlaying" to resumePlaying,
            "positionMs" to resumeFrame * 1000 / SF.SAMPLE_RATE))
        startSoloPlayback(resumeFrame, resumePlaying, reloadInterrupted = leavingRole == Role.POINT)
        toast(reason)
    }

    private fun persistRecord() {
        record?.let { prefs.edit().putString("pair", it.toJson()).apply() }
    }

    private fun saveServerParams() {
        record = record?.copy(pointChannel = pointChannel, trimMs = trimNs / 1e6, volL = volL, volR = volR,
            master = master, balance = balance, levelTrimServerDb = levelTrimServerDb, levelTrimPointDb = levelTrimPointDb,
            volTaper = 2, levelTrimVersion = 1, delayCalib = delayCalib, levelCalib = levelCalib,
            eq = eqSettings, spatial = spatialSettings)
        persistRecord()
    }

    // ================================================================== сообщения в паре
    private fun onPairedMessage(o: JSONObject) {
        when (o.optString("t")) {
            "hb" -> {}
            "bye" -> { setAutoResume(false); goIdle(sendBye = false, reason = "«$peerName» разорвал пару") }
            "name" -> { peerName = o.optString("name", peerName); ui { copy(peerName = peerName) }
                        record = record?.copy(peerName = peerName); persistRecord() }
            "gainAck" -> if (role == Role.SERVER && o.optInt("law") >= 2 && !peerGainLawReady) {
                peerGainLawReady = true; publishState()
            }
            "levelCapture" -> if (role == Role.POINT) receiveLevelCapture(o)
            "levelCaptureResult" -> if (role == Role.SERVER) {
                levelCaptureAcks[o.optString("id")]?.complete(o)
            }
            // --- на Server ---
            "cmd" -> if (role == Role.SERVER) handleCmd(o)
            "diag" -> if (role == Role.SERVER) {
                val d = Diag.fromJson(o); pointClockReady = d.clockReady; ui { copy(pointDiag = d) }
            }
            // --- на Point ---
            "session" -> if (role == Role.POINT) {
                peerCapabilities = parsePeerCaps(o)
                negotiatedCapabilities = peerCapabilities.intersect(setOf("addrs", "tcpClock", "tcpAudio", "eq", "spatialV1", "fileTransferV1"))
                updatePeerAddressBook(parsePeerAddresses(o))
                val newSession = o.getInt("session")
                if (sessionId != newSession) {
                    sessionId = newSession
                    lastAudioRxAt.set(0)
                    if (!clock.ready) { clock.reset(); rx.reset() }
                }
                serverFileTransferToken = o.optString("fileToken")
                val l = link ?: return
                serverUdp = InetSocketAddress(l.remoteAddress, o.getInt("udpPort"))
                pingBurst = 20
                val audioPort = o.optInt("audioPort", 0)
                if ("tcpAudio" in negotiatedCapabilities && audioPort > 0) startPointTcpAudioConnection(audioPort)
            }
            "audio-preload" -> if (role == Role.POINT && "tcpAudio" in negotiatedCapabilities) {
                val preloadEpoch = o.optInt("epoch", Int.MIN_VALUE)
                pointAudioPreloadEpoch = preloadEpoch
                pointAudioPreloadFrame = o.optString("startFrame", "0").toLongOrNull() ?: 0L
                pointAudioReadyEpoch.set(Int.MIN_VALUE.toLong())
                tcpAudioBuffer.prepare(preloadEpoch)
                telemetry.record("START_WAIT", mapOf("epoch" to preloadEpoch, "startFrame" to pointAudioPreloadFrame,
                    "targetBufferFrames" to TCP_AUDIO_MIN_START_FRAMES, "audioPath" to "tcp", "state" to "waiting"))
                maybeSendAudioReady(preloadEpoch)
            }
            "audio-ready" -> if (role == Role.SERVER) finishTcpAudioPreload(o.optInt("epoch", Int.MIN_VALUE), true,
                o.optInt("bufferedFrames", 0))
            "audio-path" -> {
                val path = o.optString("path", "udp")
                val reason = o.optString("reason", "peer_update")
                if (role == Role.POINT && path == "udp" && currentAudioPath == "tcp") {
                    telemetry.record("AUDIO_PATH_FALLBACK_WAIT", mapOf("path" to "udp", "reason" to reason,
                        "tcpBufferSeconds" to tcpAudioBuffer.bufferedSeconds(rx.timeline.epoch, positionFrameSnapshot())))
                } else setAudioPath(path, reason, notifyPeer = role == Role.POINT)
            }
            "clock-ping" -> if (role == Role.SERVER && "tcpClock" in negotiatedCapabilities) {
                val t1 = o.optString("t1", "0").toLongOrNull() ?: 0L
                val t2 = System.nanoTime()
                val t3 = System.nanoTime()
                link?.send(JSONObject().put("t", "clock-pong").put("seq", o.optInt("seq")).put("t1", t1.toString())
                    .put("t2", t2.toString()).put("t3", t3.toString()))
            }
            "clock-pong" -> if (role == Role.POINT && "tcpClock" in negotiatedCapabilities) {
                val t4 = System.nanoTime()
                val t1 = o.optString("t1", "0").toLongOrNull() ?: return
                val t2 = o.optString("t2", "0").toLongOrNull() ?: return
                val t3 = o.optString("t3", "0").toLongOrNull() ?: return
                clock.addPong(t1, t2, t3, t4)
                if (SystemClock.elapsedRealtime() - lastUdpPongAtMs.get() >= 1_500L)
                    setClockPath("tcp", "udp_pong_timeout")
            }
            "debug-stop-udp" -> {
                debugStopUdpTraffic = o.optBoolean("enabled")
                debugUdpAudioStopped = debugStopUdpTraffic
                ui { copy(debugUdpAudioStopped = debugStopUdpTraffic) }
                telemetry.record("DEBUG_UDP_TRAFFIC", mapOf("enabled" to debugStopUdpTraffic,
                    "role" to role.name.lowercase()))
            }
            "network-addresses" -> {
                peerCapabilities = parsePeerCaps(o)
                negotiatedCapabilities = peerCapabilities.intersect(setOf("addrs", "tcpClock", "tcpAudio", "eq", "spatialV1", "fileTransferV1"))
                updatePeerAddressBook(parsePeerAddresses(o))
                telemetry.record("PEER_ADDRESSES_RECEIVED", mapOf("count" to parsePeerAddresses(o).size,
                    "networkIds" to parsePeerAddresses(o).map { it.networkId }.distinct()))
            }
            "file-ack", "file-error" -> fileTransferAcks[o.optString("id")]?.complete(o)
            "file-start" -> handleIncomingFileStart(o)
            "file-chunk" -> handleIncomingFileChunk(o)
            "file-end" -> handleIncomingFileEnd(o)
            "file-cancel" -> if (incomingFileTransfer?.id == o.optString("id")) abortIncomingFileTransfer("cancelled by Point")
            "state" -> if (role == Role.POINT) applyState(o)
            "queue" -> if (role == Role.POINT) applyQueue(o)
            "timeline" -> if (role == Role.POINT) {
                observeCommand(o)
                val timeline = Timeline(o.getInt("epoch"), o.getBoolean("playing"),
                    o.getString("anchorFrame").toLong(), o.getString("anchorNs").toLong(),
                    o.optString("stopFrame", "-1").toLongOrNull() ?: -1L)
                rx.onTimeline(timeline)
                audio.playbackActive = timeline.playing
                if (currentAudioPath == "tcp") tcpAudioBuffer.activate(timeline.epoch)
            }
            "sdiag" -> if (role == Role.POINT) ui { copy(serverDiag = Diag.fromJson(o)) }
            "calib" -> if (role == Role.POINT) applyCalib(o)
            "network-warning" -> if (role == Role.POINT) toast(o.optString("message").take(500))
            "mark" -> receiveUserMark(o)
            "telemetry-request" -> link?.let { receiveTelemetryRequest(it, o) }
            "telemetry-ready" -> {
                val id = o.optString("id")
                val ready = telemetryReady[id]
                if (ready != null) {
                    val size = o.optLong("bytes", -1L)
                    if (size !in 0..TELEMETRY_MAX_BYTES) {
                        ready.completeExceptionally(IllegalStateException("Peer journal exceeds the 8 MiB export limit"))
                        telemetryExports[id]?.completeExceptionally(IllegalStateException("Peer journal exceeds the 8 MiB export limit"))
                    } else {
                        pendingRemoteStates[id] = o.optString("state", "{}")
                        ready.complete(o)
                    }
                }
            }
            "telemetry-chunk" -> link?.let { receiveTelemetryChunk(it, o) }
            "telemetry-ack" -> telemetryAcks[o.optString("ackId")]?.complete(o)
            "telemetry-end" -> link?.let { finishTelemetryReceive(it, o) }
            "telemetry-done" -> telemetryAcks[o.optString("ackId")]?.complete(o)
            "telemetry-refuse" -> {
                val error = IllegalStateException(o.optString("reason", "second phone declined telemetry export"))
                val id = o.optString("id")
                telemetryReady[id]?.completeExceptionally(error)
                telemetryExports[id]?.completeExceptionally(error)
                telemetryAcks[o.optString("ackId")]?.completeExceptionally(error)
            }
        }
    }

    // ================================================================== Server: шкала и команды
    private fun serverChannel() = Ch.other(pointChannel)
    private fun balanceGain(channel: Int) = if (mode == SourceMode.MUSIC) BalanceLaw.gain(channel, balance) else 1f
    private fun serverGain() = if (mode == SourceMode.SYNC_PROBE) 0.6f else if (soloMode) AudioGain.taper(master)
        else AudioGain.taper(master) * AudioGain.fromDb(levelTrimServerDb) * balanceGain(serverChannel())
    private fun pointGain() = if (mode == SourceMode.SYNC_PROBE) 0.6f
        else AudioGain.taper(master) * AudioGain.fromDb(levelTrimPointDb) * balanceGain(pointChannel)

    private fun eqAvailableInPair() = soloMode ||
        (phase in setOf(Phase.PAIRED, Phase.RECONNECTING) && "eq" in negotiatedCapabilities)

    private fun spatialAvailableInPair() = mode == SourceMode.MUSIC && phase in setOf(Phase.PAIRED, Phase.RECONNECTING) &&
        "spatialV1" in negotiatedCapabilities && audio.isBuiltInSpeakerRoute()

    private fun deviceSpeakerRecord(): JSONObject? = spatialDeviceTable?.optJSONObject(Build.MODEL)

    private fun deviceHasStereoSpeakers(): Boolean = deviceSpeakerRecord()?.optBoolean("stereoSpeakers")
        ?: audio.builtInSpeakerSupportsStereo()

    private fun deviceSpeakerSpacingM(): Double = deviceSpeakerRecord()?.optDouble("spacingM", 0.14)
        ?.takeIf { it > 0.0 } ?: 0.14

    /** Только для разработчика: включить XTC на этом телефоне для проверки на слух, не правя таблицу устройств. */
    @Volatile private var xtcDeveloperOverride = prefs.getBoolean("xtcDevOverride:${Build.MODEL}", false)

    fun xtcDeveloperOverrideEnabled(): Boolean = xtcDeveloperOverride

    fun setXtcDeveloperOverride(on: Boolean) = post {
        if (xtcDeveloperOverride == on) return@post
        xtcDeveloperOverride = on
        prefs.edit().putBoolean("xtcDevOverride:${Build.MODEL}", on).apply()
        telemetry.record("XTC_DEV_OVERRIDE", mapOf("model" to Build.MODEL, "enabled" to on))
        prewarmXtc()
        if (soloMode && spatialAvailableNow() && spatialSettings.enabled)
            applySpatialSettings(spatialSettings, finished = false, reason = "xtc_override")
    }

    private fun deviceXtcVerified(): Boolean =
        xtcDeveloperOverride || (deviceSpeakerRecord()?.optBoolean("xtcVerified") ?: false)

    /** Расчёт фильтра занимает сотни миллисекунд: делаем его заранее, до первого воспроизведения. */
    private fun prewarmXtc() {
        if (!soloMode || !deviceXtcVerified() || !deviceHasStereoSpeakers()) return
        val beta = spatialProfile("solo_stereo_speaker").xtcBeta ?: return
        listOf(SpatialLevel.SOFT, SpatialLevel.STRONG).forEach {
            XtcFilterDesigner.request(Build.MODEL, deviceSpeakerSpacingM(), xtcBeta(it, beta))
        }
    }

    private fun xtcBeta(level: SpatialLevel, profileBeta: Double): Double = when (level) {
        SpatialLevel.SOFT -> profileBeta
        SpatialLevel.STRONG -> profileBeta / 3.0
        SpatialLevel.OFF -> profileBeta
    }

    fun xtcDeveloperSnapshot(): XtcDeveloperReport = xtcReport

    /** Model-only report for the hidden developer diagnostics; it does not mark a device verified. */
    fun requestXtcDeveloperReport(level: SpatialLevel) {
        if (!soloMode || mode != SourceMode.MUSIC || !spatialAvailableNow() || level == SpatialLevel.OFF) return
        val profileBeta = spatialProfile("solo_stereo_speaker").xtcBeta ?: 0.06
        val beta = xtcBeta(level, profileBeta)
        val spacing = deviceSpeakerSpacingM()
        xtcReport = XtcDeveloperReport(status = "расчёт")
        XtcFilterDesigner.request(Build.MODEL, spacing, beta).whenComplete { design, error ->
            if (error != null || design == null) {
                xtcReport = XtcDeveloperReport(status = "ошибка расчёта")
                telemetry.record("XTC_DESIGN", mapOf("spacing" to spacing, "beta" to beta, "status" to "error"), error = error)
            } else {
                xtcReport = XtcDeveloperReport("готово", design.spacingMeters, design.beta, design.designMs,
                    design.predictedMedianDb, design.predictedWorstDb)
                recordXtcDesign(design, "developer_report")
            }
        }
    }

    private fun recordXtcDesign(design: XtcFilterSet, source: String) {
        telemetry.record("XTC_DESIGN", mapOf(
            "spacing" to design.spacingMeters, "beta" to design.beta, "designMs" to design.designMs,
            "predictedMedianDb" to design.predictedMedianDb, "predictedWorstDb" to design.predictedWorstDb,
            "model" to Build.MODEL, "source" to source, "modelOnly" to true,
        ))
    }

    private fun spatialAvailableNow(): Boolean = when {
        soloMode -> mode == SourceMode.MUSIC && audio.isBuiltInSpeakerRoute() && deviceHasStereoSpeakers()
            && app.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        else -> spatialAvailableInPair()
    }

    private fun refreshSpatialAvailability() {
        prewarmXtc()
        val available = spatialAvailableNow()
        if (ui.value.spatialAvailable != available || ui.value.spatial != spatialSettings)
            ui { copy(spatialAvailable = available, spatial = spatialSettings) }
    }

    private fun spatialProfile(id: String): SpatialProfile = spatialProfiles[id] ?: SpatialProfiles.fallback(id)

    private fun spatialSettingsForWire(): SpatialSettings {
        return spatialSettings.sanitized()
    }

    private fun spatialEnabledFor(input: AudioSource): Boolean = input.mode == SourceMode.MUSIC &&
        spatialSettings.enabled && spatialSettings.level != SpatialLevel.OFF && spatialAvailableNow() &&
        !spatialBypassLocked && spatialBypassedInput !== input &&
        (!soloMode || deviceHasStereoSpeakers())

    private fun spatialSignature(input: AudioSource): String {
        val profileId = if (soloMode) "solo_stereo_speaker" else spatialSettings.profile
        return "${System.identityHashCode(input)}:$profileId:${spatialSettings.toJson()}:$spatialAbFixedCompensationDb:" +
            "xtc=${soloMode && deviceXtcVerified()}"
    }

    private fun renderSourceFor(input: AudioSource, startAtFrame: Long = 0L): AudioSource {
        if (!spatialEnabledFor(input)) {
            val old = activeSpatialSource
            activeSpatialSource = null; activeSpatialInput = null; activeSpatialSignature = ""
            old?.let(::closeSpatialLater)
            return input
        }
        val signature = spatialSignature(input)
        if (activeSpatialInput === input && activeSpatialSignature == signature)
            return activeSpatialSource ?: input
        val old = activeSpatialSource
        val profileId = if (soloMode) "solo_stereo_speaker" else spatialSettings.profile
        val profileData = spatialProfile(profileId)
        val xtcDesign = if (soloMode && deviceXtcVerified() && spatialSettings.enabled && profileData.xtcBeta != null) {
            XtcFilterDesigner.request(Build.MODEL, deviceSpeakerSpacingM(), xtcBeta(spatialSettings.level, profileData.xtcBeta))
        } else null
        xtcDesign?.thenAccept { recordXtcDesign(it, "verified_playback") }
        val created = SpatialSource(
            input,
            startAtFrame = startAtFrame.coerceIn(0L, input.totalFrames),
            spatialConfig = spatialSettings.dspConfig(profileData).copy(
                fixedLoudnessCompensationDb = spatialAbFixedCompensationDb),
            xtcDesign = xtcDesign,
        )
        activeSpatialInput = input
        activeSpatialSource = created
        activeSpatialSignature = signature
        if (old != null && old !== created) closeSpatialLater(old)
        return created
    }

    private fun currentRenderedSource(): AudioSource {
        val current = serverPlay.source
        if (current is CrossfadeAudioSource) return current
        return renderSourceFor(source)
    }

    private fun closeSpatialLater(source: AudioSource) {
        later(5_000) { io.execute { closeSpatialTree(source) } }
    }

    private fun closeSpatialTree(source: AudioSource) {
        when (source) {
            is SpatialSource -> source.close()
            is CrossfadeAudioSource -> { closeSpatialTree(source.previous); closeSpatialTree(source.next) }
        }
    }

    @Volatile private var activeSpatialSignature = ""
    @Volatile private var spatialAbFixedCompensationDb: Double? = null

    private fun monitorSpatialOverload(spatial: SpatialSource, playing: Boolean, positionFrame: Long, nowMs: Long) {
        val input = activeSpatialInput
        if (input == null || activeSpatialSource !== spatial) return
        if (spatialPerfInput !== input) {
            spatialPerfInput = input
            spatialPerfStartedAtMs = nowMs
            spatialSlowSinceMs = 0L
        }
        if (!playing || !spatialSettings.enabled || spatialBypassLocked || spatialBypassedInput === input) {
            spatialSlowSinceMs = 0L
            return
        }
        val decodedAhead = spatial.decodedReadyFrames - positionFrame
        val processedBehind = positionFrame - spatial.readyFrames
        if (nowMs - spatialPerfStartedAtMs >= 750L && decodedAhead >= SF.SAMPLE_RATE / 4 && processedBehind > 0L) {
            requestSpatialAutoBypass(spatial, input, "processor_underrun", positionFrame)
            return
        }
        val factor = spatial.realtimeFactor
        if (factor >= 0.01 && factor < 3.0) {
            if (spatialSlowSinceMs == 0L) spatialSlowSinceMs = nowMs
            if (nowMs - spatialSlowSinceMs >= 5_000L)
                requestSpatialAutoBypass(spatial, input, "rt_factor_below_3x", positionFrame)
        } else spatialSlowSinceMs = 0L
    }

    private fun requestSpatialAutoBypass(spatial: SpatialSource, input: AudioSource, reason: String, positionFrame: Long) {
        if (!spatialAutoBypassPending.compareAndSet(false, true)) return
        post {
            try {
                if (activeSpatialSource !== spatial || activeSpatialInput !== input || spatialBypassedInput === input ||
                    spatialBypassLocked || !spatialSettings.enabled || !spatialAvailableNow()) return@post
                val timeline = serverPlay.timeline
                if (!timeline.playing || mode != SourceMode.MUSIC) return@post
                val requested = timeline.frameAt(System.nanoTime() + SF.LEAD_NS + 200_000_000L).toLong()
                val frame = requested.coerceIn(0L, maxOf(0L, source.totalFrames - SF.SAMPLE_RATE / 20))
                val previous = serverPlay.source
                val fadeFrames = SF.SAMPLE_RATE / 20
                serverPlay = ServerPlayState(timeline,
                    CrossfadeAudioSource(previous, input, frame, fadeFrames),
                    renderChannel(), serverGain(), activeEqConfig())
                spatialReprocessFrame = frame
                spatialReprocessToken++
                spatialBypassedInput = input
                spatialAutoBypassCount++
                if (spatialAutoBypassCount >= 3) spatialBypassLocked = true
                activeSpatialSource = null
                activeSpatialInput = null
                activeSpatialSignature = ""
                spatialSlowSinceMs = 0L
                closeSpatialLater(spatial)
                telemetry.record("SPATIAL_AUTO_BYPASS", mapOf(
                    "reason" to reason, "rtFactor" to spatial.realtimeFactor,
                    "positionFrame" to positionFrame, "fFrame" to frame,
                    "restarts" to spatial.restarts, "misses" to spatial.misses,
                    "overloadsThisSession" to spatialAutoBypassCount,
                    "lockedUntilRestart" to spatialBypassLocked,
                    "dspVersion" to DSP_VERSION,
                ))
            } finally {
                spatialAutoBypassPending.set(false)
            }
        }
    }

    private fun applySpatialSettings(next: SpatialSettings, finished: Boolean, reason: String = "user") {
        val clean = next.sanitized()
        if (!spatialAvailableNow()) return
        val previousSource = serverPlay.source
        val timeline = serverPlay.timeline
        val now = System.nanoTime()
        val isPlaying = timeline.playing && mode == SourceMode.MUSIC
        val fadeFrames = SF.SAMPLE_RATE / 20
        val requested = if (isPlaying) timeline.frameAt(now + SF.LEAD_NS + 200_000_000L).toLong() else posFrame()
        val f = if (isPlaying) requested.coerceIn(0L, maxOf(0L, source.totalFrames - fadeFrames))
            else requested.coerceIn(0L, source.totalFrames)
        spatialSettings = clean
        val renderStart = if (isPlaying) (f - fadeFrames).coerceAtLeast(0L) else 0L
        val updated = renderSourceFor(source, renderStart)
        val appliedSource = if (isPlaying) CrossfadeAudioSource(previousSource, updated, f, fadeFrames) else updated
        spatialReprocessFrame = f
        spatialReprocessToken++
        serverPlay = ServerPlayState(timeline, appliedSource, renderChannel(), serverGain(), activeEqConfig())
        if (finished) {
            if (soloMode) prefs.edit().putString("soloSpatial:${audio.routeKey()}", clean.toJson().toString()).apply()
            else saveServerParams()
            telemetry.record("SPATIAL_CONFIG", mapOf("profile" to clean.profile, "level" to clean.level.wire,
                "params" to clean.custom?.let { mapOf("gMidDb" to it.gMidDb, "gHighDb" to it.gHighDb,
                    "gAmbientDb" to it.gAmbientDb, "depthDb" to it.depthDb, "reflectionDb" to it.reflectionDb) },
                "preserveLoudness" to clean.preserveLoudness,
                "dspVersion" to DSP_VERSION, "profilesVersion" to SpatialProfiles.VERSION,
                "reason" to reason, "fFrame" to f))
        }
        ui { copy(spatial = clean, spatialAvailable = spatialAvailableNow()) }
        telemetry.record("SPATIAL_REPROCESS", mapOf("reason" to reason, "fFrame" to f,
            "delayMs" to if (isPlaying) (f - posFrame()).coerceAtLeast(0) * 1000.0 / SF.SAMPLE_RATE else 0,
            "tcpResendFrames" to if (currentAudioPath == "tcp" && isPlaying) (source.totalFrames - f).coerceAtLeast(0) else 0))
        publishState()
        if (isPlaying) closeSpatialLater(previousSource)
    }
    private fun activeEqConfig() = if (mode != SourceMode.MUSIC ||
        (phase in setOf(Phase.PAIRED, Phase.RECONNECTING) && !eqAvailableInPair())) EqConfig.neutral() else eqConfig

    private fun eqSettingsJson(settings: EqSettings): JSONObject = JSONObject().apply {
        val clean = settings.sanitized()
        put("enabled", clean.enabled)
        put("gains", org.json.JSONArray().also { array -> clean.gainsDb.forEach { array.put(it.toDouble()) } })
        put("magnet", clean.magnet.name)
        put("preset", clean.preset)
        put("preserveLoudness", clean.preserveLoudness)
        put("bypass", clean.bypass)
    }

    private fun parseEqSettings(json: JSONObject?): EqSettings {
        if (json == null) return EqSettings()
        val values = json.optJSONArray("gains")
        return EqSettings(
            enabled = json.optBoolean("enabled", false),
            gainsDb = List(EqualizerMath.BAND_COUNT) { i -> values?.optDouble(i, 0.0)?.toFloat() ?: 0f },
            magnet = EqMagnet.parse(json.optString("magnet", "SOFT")),
            preset = json.optString("preset", "Ровно").take(40),
            preserveLoudness = json.optBoolean("preserveLoudness", true),
            bypass = json.optBoolean("bypass", false),
            analyzerEnabled = eqSettings.analyzerEnabled,
        ).sanitized()
    }

    private fun eqSettingsForWire() = if (phase in setOf(Phase.PAIRED, Phase.RECONNECTING) && !eqAvailableInPair())
        eqSettings.copy(enabled = false, bypass = false) else eqSettings

    private fun renderChannel() = if (soloMode) Ch.BOTH else serverChannel()

    private fun rebuildPlayState() {
        serverPlay = ServerPlayState(serverPlay.timeline, currentRenderedSource(), renderChannel(), serverGain(), activeEqConfig())
    }

    private fun sendSession(target: ControlLink? = link) {
        target?.send(JSONObject().put("t", "session").put("session", sessionId)
            .put("udpPort", udp.localPort).put("name", myName).put("fileToken", fileTransferToken)
            .put("caps", localCapabilitiesJson()).put("addrs", addressesJson())
            .put("audioPort", if ("tcpAudio" in negotiatedCapabilities) tcpAudioServer.localPort else 0))
    }

    /** Replaces only the UDP audio session; TCP pairing and the playback epoch remain intact. */
    private fun renewAudioSession(reason: String) {
        if (phase != Phase.PAIRED || role != Role.SERVER) return
        val now = SystemClock.elapsedRealtime()
        val sentTotal = sentAudioPackets.get()
        val sentSinceRenew = (sentTotal - sessionSentBaseline).coerceAtLeast(0)
        sessionSentBaseline = sentTotal
        if (sentSinceRenew == 0L) zeroSendRenewStreak++ else {
            zeroSendRenewStreak = 0
            udpNetworkRebindAttempted = false
            udpFailureNoticeShown = false
            audioRenewIntervalMs = 5_000L
        }
        sessionId = rnd.nextInt().let { if (it == 0) 1 else it }
        pointUdp = null
        pointClockReady = false
        fileTransferToken = hex(24)
        abortIncomingFileTransfer("audio session changed")
        lastAudioSessionResetMs = now
        telemetry.record("AUDIO_SESSION_RENEW", mapOf("reason" to reason, "session" to sessionId,
            "sent" to sentTotal, "sentSincePreviousRenew" to sentSinceRenew,
            "zeroSendRenewStreak" to zeroSendRenewStreak, "pointAudioAgeMs" to ui.value.pointDiag.audioAgeMs))
        log("UDP-аудиосессия пересоздана: $reason")
        sendSession()
        sendTimeline()

        if (sentSinceRenew == 0L && zeroSendRenewStreak >= 3) {
            if (!udpNetworkRebindAttempted) {
                udpNetworkRebindAttempted = true
                zeroSendRenewStreak = 0
                val bindResult = bindUdpToControlLink("three_zero_send_renewals")
                telemetry.record("UDP_ROUTE_REPAIR", mapOf("result" to if (bindResult.success) "bound" else "failed",
                    "portChanged" to bindResult.portChanged,
                    "network" to udpBoundNetworkLabel, "localAddress" to udpBoundLocalAddress, "port" to udp.localPort))
                if (bindResult.portChanged) sendSession()
            } else if (!udpFailureNoticeShown) {
                udpFailureNoticeShown = true
                audioRenewIntervalMs = 30_000L
                val routes = availableNetworkRoutes(wifiOnly = true)
                val wifiNetworks = wifiNetworkSummary()
                val attachedTo = if (routes.size > 1) "нескольким сетям Wi‑Fi" else "сети Wi‑Fi"
                val message = "UDP не проходит между телефонами. Телефон подключён к $attachedTo: $wifiNetworks. Проверьте, что оба телефона доступны друг другу в одной сети."
                telemetry.record("UDP_ROUTE_UNAVAILABLE", mapOf("message" to message,
                    "wifiNetworks" to wifiNetworks, "renewIntervalMs" to audioRenewIntervalMs))
                link?.send(JSONObject().put("t", "network-warning").put("message", message))
                toast(message)
            }
        }
    }

    private fun wifiNetworkSummary(): String {
        val descriptions = availableNetworkRoutes(wifiOnly = true).map { route ->
            val addresses = networkAddresses(route.linkProperties).filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
            "${route.interfaceName} (${addresses.joinToString().ifEmpty { "IPv4 неизвестен" }})"
        }
        return descriptions.ifEmpty { listOf("сеть Wi‑Fi не определена") }.joinToString(", ")
    }

    private fun newEpoch(playing: Boolean, frame: Long, anchorNs: Long) {
        if (playing && mode != SourceMode.SYNC_PROBE && phase == Phase.PAIRED && role == Role.SERVER &&
            "tcpAudio" in negotiatedCapabilities && serverTcpAudioSocket?.let { it.isConnected && !it.isClosed } == true) {
            beginTcpAudioPreload(frame)
            return
        }
        cancelTcpAudioPreload()
        commitEpoch(playing, frame, anchorNs, epoch + 1)
    }

    private fun commitEpoch(playing: Boolean, frame: Long, anchorNs: Long, newEpoch: Int) {
        epoch = newEpoch
        preloadEpochCounter = maxOf(preloadEpochCounter, newEpoch)
        val rendered = renderSourceFor(source, frame.coerceAtLeast(0L))
        serverPlay = ServerPlayState(Timeline(epoch, playing, frame, anchorNs), rendered, renderChannel(), serverGain(), activeEqConfig())
        audio.playbackActive = playing
        if (role == Role.POINT) tcpAudioBuffer.activate(epoch)
        sendTimeline()
        publishState()
    }

    private fun beginTcpAudioPreload(frame: Long) {
        tcpAudioPreloadTask?.cancel(false)
        val targetEpoch = maxOf(epoch + 1, preloadEpochCounter + 1).also { preloadEpochCounter = it }
        val itemId = queue.getOrNull(queueIndex)?.id.orEmpty()
        val preload = AudioPreload(targetEpoch, frame.coerceAtLeast(0), renderSourceFor(source, frame.coerceAtLeast(0)), pointChannel, itemId,
            SystemClock.elapsedRealtime())
        tcpAudioPreload = preload
        telemetry.record("START_WAIT", mapOf("epoch" to targetEpoch, "startFrame" to preload.startFrame,
            "track" to itemId.takeIf { it.isNotBlank() }?.let(TelemetryIdentifiers::hash),
            "targetBufferFrames" to TCP_AUDIO_MIN_START_FRAMES, "timeoutMs" to TCP_AUDIO_MAX_WAIT_MS,
            "audioPath" to "tcp", "state" to "waiting"))
        link?.send(JSONObject().put("t", "audio-preload").put("epoch", targetEpoch)
            .put("startFrame", preload.startFrame.toString()).put("trackId", itemId)
            .put("minFrames", TCP_AUDIO_MIN_START_FRAMES))
        tcpAudioPreloadTask = later(TCP_AUDIO_MAX_WAIT_MS) {
            if (tcpAudioPreload?.epoch == targetEpoch) finishTcpAudioPreload(targetEpoch, false, 0)
        }
    }

    private fun finishTcpAudioPreload(targetEpoch: Int, ready: Boolean, bufferedFrames: Int) {
        val preload = tcpAudioPreload?.takeIf { it.epoch == targetEpoch } ?: return
        tcpAudioPreload = null
        tcpAudioPreloadTask?.cancel(false); tcpAudioPreloadTask = null
        val waited = SystemClock.elapsedRealtime() - preload.startedAtMs
        telemetry.record("START_WAIT", mapOf("epoch" to targetEpoch, "startFrame" to preload.startFrame,
            "durationMs" to waited, "ready" to ready, "bufferedFrames" to bufferedFrames,
            "audioPath" to currentAudioPath, "state" to if (ready) "ready" else "timeout"))
        if (ready) setAudioPath("tcp", "preload_ready")
        commitEpoch(true, preload.startFrame, System.nanoTime() + SF.START_DELAY_NS, targetEpoch)
    }

    private fun cancelTcpAudioPreload() {
        tcpAudioPreload = null
        tcpAudioPreloadTask?.cancel(false); tcpAudioPreloadTask = null
    }

    private fun sendTimeline() {
        val tl = serverPlay.timeline
        link?.send(JSONObject().put("t", "timeline").put("epoch", tl.epoch).put("playing", tl.playing)
            .put("anchorFrame", tl.anchorFrame.toString()).put("anchorNs", tl.anchorNs.toString())
            .put("stopFrame", tl.stopFrame.toString()).put("op", activeCommandOp())
            .put("cmdAppliedSrvNs", lastCommandAppliedSrvNs).put("cmdIssuedSrvNs", lastCommandIssuedSrvNs))
    }

    private fun stateJson(): JSONObject {
        val tl = serverPlay.timeline
        return JSONObject().put("t", "state").put("playing", tl.running || ui.value.audioBuffering).put("durFrames", source.totalFrames)
            .put("title", source.title).put("artist", source.artist).put("mode", mode.wire)
            .put("queueIndex", queueIndex).put("queueCount", queue.size).put("trackId", queue.getOrNull(queueIndex)?.id ?: "")
            .put("volL", volL.toDouble()).put("volR", volR.toDouble()).put("master", master.toDouble())
            .put("balance", balance.toDouble())
            .put("pointGain", pointGain().toDouble()).put("gainLaw", 2)
            .put("peerGainLaw", if (peerGainLawReady) 2 else 1)
            .put("levelTrimServerDb", levelTrimServerDb.toDouble()).put("levelTrimPointDb", levelTrimPointDb.toDouble())
            .put("levelMeasureText", levelMeasureText)
            .put("eq", eqSettingsJson(eqSettingsForWire()))
            .put("spatial", spatialSettingsForWire().toJson())
            .put("eqPreampDb", activeEqConfig().preampDb)
            .put("delayCalib", JSONObject().put("state", delayCalib.state).put("atMs", delayCalib.atMs).put("spreadMs", delayCalib.spreadMs))
            .put("levelCalib", JSONObject().put("state", levelCalib.state).put("atMs", levelCalib.atMs).put("db", levelCalib.db))
            .put("pointCh", Ch.name(pointChannel)).put("trimMs", trimNs / 1e6).put("serverName", myName)
            .put("undo", undoText).put("decodeProgress", decodeProgress ?: JSONObject.NULL)
            .put("audioBuffering", ui.value.audioBuffering).put("op", activeCommandOp())
            .put("cmdAppliedSrvNs", lastCommandAppliedSrvNs).put("cmdIssuedSrvNs", lastCommandIssuedSrvNs)
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
        if (role != Role.SERVER) return
        val s = stateJson()
        applyState(s)
        link?.send(s)
        if (soloMode) { if (serverPlay.timeline.playing) ensureSoloService() else scheduleSoloServiceStop() }
    }

    /** Общий для обеих ролей разбор состояния плеера → UI (и параметры рендера на Point). */
    private fun applyState(o: JSONObject) {
        if (role == Role.POINT) observeCommand(o)
        val playing = o.optBoolean("playing")
        val dur = o.optLong("durFrames")
        val vL = o.optDouble("volL", 1.0).toFloat(); val vR = o.optDouble("volR", 1.0).toFloat()
        val m = o.optDouble("master", 0.8).toFloat()
        val b = o.optDouble("balance", 0.5).toFloat().coerceIn(0f, 1f)
        val pCh = Ch.parse(o.optString("pointCh", "R"))
        val trim = o.optDouble("trimMs", 0.0)
        val incomingMode = SourceMode.fromWire(o.optString("mode"))
        val incomingEq = parseEqSettings(o.optJSONObject("eq"))
        val incomingSpatial = SpatialSettings.fromJson(o.optJSONObject("spatial"))
        if (role == Role.POINT) {
            if (incomingEq != eqSettings) {
                eqSettings = incomingEq
                eqConfig = EqualizerMath.config(eqSettings)
            }
            if (spatialSettings != incomingSpatial) {
                spatialSettings = incomingSpatial
                record = record?.copy(spatial = incomingSpatial)
                if (record != null) persistRecord()
            }
            if (o.has("pointGain") && !didAcknowledgeGainLaw) {
                didAcknowledgeGainLaw = true
                link?.send(JSONObject().put("t", "gainAck").put("law", 2))
            }
            val gain = if (incomingMode == SourceMode.SYNC_PROBE) 0.6f else if (o.has("pointGain")) o.optDouble("pointGain").toFloat().coerceIn(0f, 1f)
                else m * (if (pCh == Ch.L) vL else vR) * LegacyBalanceLaw.gain(pCh, b)
            pointParams = PointParams(gain, (trim * 1e6).toLong(), dur,
                if (incomingMode == SourceMode.MUSIC && eqAvailableInPair()) eqConfig else EqConfig.neutral())
        }
        val progress = if (o.isNull("decodeProgress")) null else o.optDouble("decodeProgress").toFloat().coerceIn(0f, 1f)
        ui { copy(playing = playing, durationMs = dur * 1000 / SF.SAMPLE_RATE, title = o.optString("title"),
            artist = o.optString("artist"), mode = incomingMode, audioBuffering = o.optBoolean("audioBuffering"),
            queueIndex = o.optInt("queueIndex", -1), undoText = o.optString("undo", ""),
            volL = vL, volR = vR, master = m, balance = b, pointChannel = pCh, trimMs = trim, decodingProgress = progress,
            levelTrimServerDb = o.optDouble("levelTrimServerDb", 0.0).toFloat(), levelTrimPointDb = o.optDouble("levelTrimPointDb", 0.0).toFloat(),
            delayCalib = o.optJSONObject("delayCalib")?.let { DelayCalib(it.optString("state", "none"), it.optLong("atMs"), it.optDouble("spreadMs")) } ?: DelayCalib(),
            levelCalib = o.optJSONObject("levelCalib")?.let { LevelCalib(it.optString("state", "none"), it.optLong("atMs"), it.optDouble("db")) } ?: LevelCalib(),
            levelMeasureText = o.optString("levelMeasureText", ""),
            eq = if (role == Role.SERVER) eqSettingsForWire() else incomingEq,
            eqAvailable = eqAvailableInPair(),
            spatial = if (role == Role.SERVER) spatialSettingsForWire() else incomingSpatial,
            spatialAvailable = spatialAvailableNow(),
            eqPreampDb = o.optDouble("eqPreampDb", 0.0).toFloat(),
            eqRouteLabel = audio.routeLabel(), eqCurveDb = activeEqConfig().curveDb.map { it.toFloat() },
            gainLaw = if (role == Role.POINT) if (o.has("pointGain")) 2 else 1 else o.optInt("peerGainLaw", 1)) }
        if (playing) requestFocus()
        PlaybackService.refresh(app)
    }

    private fun posFrame(): Long = serverPlay.timeline.positionFrame(System.nanoTime()).coerceIn(0L, source.totalFrames)

    private fun handleCmd(o: JSONObject) {
        val now = System.nanoTime()
        val op = o.optString("op").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        lastCommandOp = op
        lastCommandAppliedSrvNs = now
        lastCommandIssuedSrvNs = o.optLong("issuedSrvNs", now)
        telemetry.record("CMD_APPLIED", mapOf(
            "cmd" to o.optString("a"), "originRole" to o.optString("originRole", "unknown"),
            "appliedSrvNs" to now, "issuedSrvNs" to lastCommandIssuedSrvNs,
        ), op = op)
        when (o.optString("a")) {
            "play" -> cmdPlay()
            "pause" -> cmdPause()
            "toggle" -> if ((serverPlay.timeline.running || ui.value.audioBuffering) && mode != SourceMode.CALIBRATION) cmdPause() else cmdPlay()
            "seek" -> seekFrame(o.optLong("ms") * SF.SAMPLE_RATE / 1000)
            "skip" -> seekFrame(posFrame() + o.optLong("ms") * SF.SAMPLE_RATE / 1000)
            "next" -> selectTrack(navigationIndex() + 1, startPlayback = true)
            "previous" -> selectTrack(navigationIndex() - 1, startPlayback = true)
            "select" -> selectTrack(queue.indexOfFirst { it.id == o.optString("id") }, startPlayback = true)
            "remove" -> {
                val arr = o.optJSONArray("ids") ?: return
                doRemoveTracks((0 until arr.length()).map { arr.optString(it) }.toSet())
            }
            "undoRemove" -> doUndoRemove()
            "vol" -> {
                if (mode == SourceMode.LEVEL_MEASURE) { toast("Во время измерения общая громкость зафиксирована"); return }
                val v = o.optDouble("v", 1.0).toFloat().coerceIn(0f, 1f)
                when (o.optString("ch")) {
                    "L", "R" -> {
                        if (o.optString("ch") == "L") volL = v else volR = v
                        val oldS = if (serverChannel() == Ch.L) volL else volR
                        val oldP = if (pointChannel == Ch.L) volL else volR
                        val migrated = AudioGain.migrateChannelGains(oldS, oldP)
                        levelTrimServerDb = migrated.first; levelTrimPointDb = migrated.second
                    }
                    else -> master = if (o.optInt("law", 1) >= 2) v else AudioGain.migrateMaster(v)
                }
                rebuildPlayState()
                if (o.optBoolean("finished", true)) {
                    if (soloMode) prefs.edit().putFloat("soloMaster", master).apply() else saveServerParams()
                }
                publishState()
            }
            "balance" -> {
                balance = o.optDouble("v", 0.5).toFloat().coerceIn(0f, 1f)
                rebuildPlayState(); if (o.optBoolean("finished", true) && !soloMode) saveServerParams(); publishState()
                if (o.optBoolean("finished", true)) telemetry.record("balance_set", mapOf("balance" to balance.toDouble()))
            }
            "eq" -> {
                if (!eqAvailableInPair()) return
                val previousBypass = eqSettings.bypass
                eqSettings = parseEqSettings(o)
                eqConfig = EqualizerMath.config(eqSettings)
                rebuildPlayState()
                if (o.optBoolean("finished", true)) {
                    if (soloMode) saveSoloEqProfile(activeSoloEqRouteKey) else saveServerParams()
                    val sourceRole = if (soloMode) "solo" else o.optString("originRole", role.name.lowercase()).lowercase()
                    telemetry.record("EQ_CHANGE", mapOf(
                        "preset" to eqSettings.preset, "gains" to eqSettings.gainsDb,
                        "preampDb" to eqConfig.preampDb, "magnet" to eqSettings.magnet.name.lowercase(),
                        "by" to sourceRole, "route" to audio.routeLabel(),
                    ))
                    if (previousBypass != eqSettings.bypass) telemetry.record("EQ_BYPASS", mapOf(
                        "on" to eqSettings.bypass, "loudnessCompDb" to eqConfig.bypassCompDb,
                    ))
                }
                publishState()
            }
            "spatial" -> {
                if (!spatialAvailableNow()) return
                val customJson = o.optJSONObject("custom")
                val custom = customJson?.let { c -> SpatialCustom(
                    c.optDouble("gMidDb", 3.0), c.optDouble("gHighDb", 2.0), c.optDouble("gAmbientDb", 2.0),
                    c.optDouble("depthDb", -2.0), c.optDouble("reflectionDb", -17.0)) }
                val settings = SpatialSettings(o.optBoolean("enabled", false), SpatialLevel.parse(o.optString("level")),
                    o.optString("profile", if (soloMode) "solo_stereo_speaker" else "pair_wide"), custom,
                    o.optBoolean("preserveLoudness", true)).sanitized()
                spatialAbFixedCompensationDb = if (o.has("abFixedCompensationDb") && !o.isNull("abFixedCompensationDb"))
                    o.optDouble("abFixedCompensationDb").coerceIn(-3.0, 3.0) else null
                applySpatialSettings(settings, o.optBoolean("finished", true),
                    if (soloMode) "solo" else o.optString("originRole", "pair"))
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
                trimNs = (ms * 1e6).toLong()
                delayCalib = if (o.optBoolean("reset")) DelayCalib() else DelayCalib("manual", System.currentTimeMillis(), 0.0)
                saveServerParams(); publishState()
            }
            "calibrate" -> startCalibration(apply = true)
            "measure" -> startCalibration(apply = false)
            "syncProbe" -> startSyncProbeInternal()
            "metronome" -> if (o.optBoolean("on")) startTestMode(MetronomeSource()) else if (mode == SourceMode.METRONOME) restoreMusic(false)
            "lrtest" -> startTestMode(ChannelTestSource())
            "levelTrim" -> {
                if (blockCalibrationIfNeeded("level_trim")) return
                setDeviceLevelTrim(o.optString("dev"), o.optDouble("db", 0.0).toFloat())
                rebuildPlayState(); saveServerParams(); publishState()
                telemetry.record("level_trim_set", mapOf("dev" to o.optString("dev"), "db" to o.optDouble("db"), "source" to "manual"))
            }
            "levelOffset" -> {
                if (blockCalibrationIfNeeded("level_ear")) return
                applyLevelOffset(o.optDouble("db", 0.0).toFloat()); rebuildPlayState()
                if (o.optBoolean("finished")) saveServerParams()
                publishState()
            }
            "levelReset" -> {
                if (blockCalibrationIfNeeded("level_reset")) return
                levelTrimServerDb = 0f; levelTrimPointDb = 0f; levelCalib = LevelCalib()
                rebuildPlayState(); saveServerParams(); publishState()
            }
            "levelStart" -> {
                if (calibRunning) toast("Дождитесь окончания калибровки задержки")
                else if (!levelStageReady()) toast(levelGateMessage())
                else { levelMatchStartServerDb = levelTrimServerDb; levelMatchStartPointDb = levelTrimPointDb; startTestMode(LevelMatchSource()) }
            }
            "levelStop" -> finishLevelMatch(o.optBoolean("commit"))
            "centerStart" -> {
                if (calibRunning) toast("Дождитесь окончания калибровки задержки")
                else if (!levelStageReady()) toast(levelGateMessage()) else startTestMode(CenterCheckSource())
            }
            "centerAnswer" -> {
                val answer = o.optString("answer", "unknown")
                telemetry.record("center_check", mapOf("answer" to answer))
                if (answer != "center") toast("Сначала повторите калибровку задержки — даже 0,1 мс разницы сдвигает звук так же, как 1–1,5 дБ. Если после этого всё ещё тянет, выровняйте уровень на слух.")
            }
            "routeChanged" -> resetCalibrationsForRoute()
            "levelMicReference" -> startLevelMicReference(o.optInt("position", 1))
            "levelMeasure" -> startLevelMicMeasurement()
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
        serverPlay = ServerPlayState(Timeline(tl.epoch, true, tl.anchorFrame, tl.anchorNs, stop), currentRenderedSource(), renderChannel(), serverGain(), activeEqConfig())
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

    private fun startTestMode(src: AudioSource, anchorNs: Long = System.nanoTime() + SF.START_DELAY_NS) {
        if (calibRunning) return
        pendingTrackId = null
        ui { copy(loadingTracks = false, loadingTrackId = null) }
        if (mode == SourceMode.MUSIC) { savedFrame = posFrame(); savedPlaying = serverPlay.timeline.running }
        clearProgressiveBufferState()
        cancelScheduledPause()
        mode = src.mode; source = src
        newEpoch(true, 0, anchorNs)
    }

    private fun restoreMusic(resume: Boolean) {
        if (mode == SourceMode.SYNC_PROBE) cancelSyncProbe("playback_interrupted")
        clearProgressiveBufferState()
        mode = SourceMode.MUSIC
        source = if (queueIndex in queue.indices) currentQueueSource ?: music else music
        val now = System.nanoTime()
        newEpoch(resume, savedFrame, if (resume) now + SF.START_DELAY_NS else now)
    }

    private fun cancelSyncProbe(reason: String) {
        if (!syncProbeActive) return
        syncProbeActive = false
        val op = syncProbeOp
        syncProbeOp = null
        pointChannel = syncProbeSavedPointChannel
        if (currentAudioPath != syncProbePreviousAudioPath)
            setAudioPath(syncProbePreviousAudioPath, "sync_probe_cancel", notifyPeer = reason != "pair_disconnected")
        mode = SourceMode.MUSIC
        source = if (queueIndex in queue.indices) currentQueueSource ?: music else music
        ui { copy(syncProbeRunning = false, syncProbeResult = "Замер отменён") }
        telemetry.record("SYNC_PROBE_RESULT", mapOf("valid" to false, "reason" to reason), op = op)
    }

    private var pauseTask: ScheduledFuture<*>? = null
    private fun cancelScheduledPause() { pauseTask?.cancel(false); pauseTask = null }

    private fun navigationIndex(): Int = queue.indexOfFirst { it.id == pendingTrackId }.takeIf { it >= 0 } ?: queueIndex

    private fun selectTrack(index: Int, forcePaused: Boolean = false, startPlayback: Boolean = false, startFrame: Long = 0L) {
        if (calibRunning || (!soloMode && phase !in setOf(Phase.PAIRED, Phase.RECONNECTING)) || index !in queue.indices) return
        val item = queue[index]
        if (queueIndex == index && pendingTrackId == null && currentQueueSource != null) {
            if (startPlayback) cmdPlay()
            return
        }
        pendingShouldPlay = !forcePaused && (startPlayback || serverPlay.timeline.running)
        pendingStartFrame = startFrame.coerceAtLeast(0L)
        clearProgressiveBufferState()
        val generation = ++trackLoadGeneration
        pendingTrackId = item.id
        decodeProgress = null; decodeProgressTrackId = item.id
        ui { copy(loadingTracks = true, loadingTrackId = item.id, decodingProgress = null) }
        queueIo.execute {
            var initialSource: AudioSource? = null
            val cancelled = { generation != trackLoadGeneration || (!soloMode && phase !in setOf(Phase.PAIRED, Phase.RECONNECTING)) || role != Role.SERVER }
            val loaded = loadTrack(item, cancelled,
                onReady = { ready ->
                    initialSource = ready
                    post { activateLoadedTrack(item, generation, ready) }
                },
                onProgress = { progressSource ->
                    val value = progressSource.progress()
                    post {
                        if (role == Role.SERVER && generation == trackLoadGeneration &&
                            (currentQueueSource === progressSource || pendingTrackId == item.id)) {
                            decodeProgress = value
                            ui { copy(decodingProgress = value) }
                            publishState()
                        }
                    }
                })
            post {
                if (role != Role.SERVER || generation != trackLoadGeneration) return@post
                if (loaded != null) {
                    decodedTracks[item.id] = loaded
                    if (initialSource == null) activateLoadedTrack(item, generation, loaded)
                    if (currentQueueSource === loaded) {
                        decodeProgress = null; decodeProgressTrackId = null
                        ui { copy(decodingProgress = null, loadingTracks = false, loadingTrackId = null) }
                        publishState()
                    }
                    Log.i(TAG, "decoded «${item.title}» fully")
                } else if (initialSource == null) {
                    failTrackLoad(item, generation)
                } else if (currentQueueSource === initialSource) {
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
    private fun activateLoadedTrack(item: QueueItem, generation: Long, loaded: AudioSource) {
        // Rows can move while decoding (remove/Undo); the track identity does not.
        val index = queue.indexOfFirst { it.id == item.id }
        if (generation != trackLoadGeneration || pendingTrackId != item.id || index < 0 ||
            role != Role.SERVER || calibRunning) return
        pendingTrackId = null
        clearProgressiveBufferState()
        queueIndex = index
        mode = SourceMode.MUSIC
        spatialBypassedInput = null
        spatialPerfInput = null
        spatialSlowSinceMs = 0L
        source = loaded
        currentQueueSource = loaded
        savedFrame = 0
        decodeProgress = (loaded as? app.soundfield.core.SegmentedPcmSource)?.takeUnless { it.complete }?.progress()
        ui { copy(loadingTracks = false, loadingTrackId = null, queueIndex = queueIndex, decodingProgress = decodeProgress) }
        val now = System.nanoTime()
        cancelScheduledPause()
        newEpoch(pendingShouldPlay, pendingStartFrame.coerceAtMost(loaded.totalFrames), if (pendingShouldPlay) now + SF.START_DELAY_NS else now)
        prefetchAround(index)
    }

    private fun failTrackLoad(item: QueueItem, generation: Long) {
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
        val op = java.util.UUID.randomUUID().toString()
        val trackTag = TelemetryIdentifiers.hash(item.id)
        currentDecodeOp = op
        decodedTracks[item.id]?.let {
            telemetry.record("AUDIO_DECODE_START", mapOf("track" to trackTag, "source" to "cache"), op = op)
            telemetry.record("AUDIO_DECODE_COMPLETE", mapOf("track" to trackTag, "frames" to it.totalFrames,
                "cacheHit" to true, "result" to "ok"), op = op)
            onReady(it)
            currentDecodeOp = null
            return it
        }
        val inputBytes = runCatching { fileSize(item.ref().uri) }.getOrDefault(-1L)
        val mime = runCatching { app.contentResolver.getType(item.ref().uri) }.getOrNull() ?: "unknown"
        val startedAt = SystemClock.elapsedRealtime()
        var firstAudioAt: Long? = null
        telemetry.record("AUDIO_DECODE_START", mapOf("track" to trackTag, "mime" to mime, "bytes" to inputBytes), op = op)
        return try {
            val decoded = AudioFileDecoder.decode(app, item.ref(), cancelled,
                onReady = { ready ->
                    val now = SystemClock.elapsedRealtime()
                    if (firstAudioAt == null) {
                        firstAudioAt = now
                        telemetry.record("AUDIO_DECODE_FIRST_AUDIO", mapOf("track" to trackTag, "elapsedMs" to now - startedAt,
                            "readyFrames" to ready.readyFrames, "estimatedFrames" to ready.totalFrames), op = op)
                    }
                    onReady(ready)
                }, onProgress = { progress ->
                    telemetry.limited("AUDIO_DECODE_PROGRESS",
                        "track=$trackTag readyFrames=${progress.readyFrames} estimatedFrames=${progress.totalFrames}", op = op)
                    onProgress(progress)
                })
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val durationMs = (decoded.totalFrames * 1_000.0 / SF.SAMPLE_RATE).toLong()
            telemetry.record("AUDIO_DECODE_COMPLETE", mapOf(
                "track" to trackTag, "mime" to mime, "bytes" to inputBytes, "elapsedMs" to elapsed,
                "durationMs" to durationMs, "frames" to decoded.totalFrames,
                "firstAudioMs" to (firstAudioAt?.minus(startedAt) ?: -1L), "result" to "ok", "cacheHit" to false,
            ), op = op)
            currentDecodeOp = null
            Log.i(TAG, "decoded «${item.title}» in $elapsed ms")
            decoded
        } catch (t: Throwable) {
            val wasCancelled = cancelled()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val codecDiagnostic = (t as? MediaCodec.CodecException)?.diagnosticInfo
                ?.take(160)?.let { " codecDiagnostic=$it" }.orEmpty()
            telemetry.record(if (wasCancelled) "AUDIO_DECODE_CANCELLED" else "AUDIO_DECODE_FAILED", mapOf(
                "track" to trackTag, "mime" to mime, "bytes" to inputBytes, "elapsedMs" to elapsed,
                "firstAudioMs" to (firstAudioAt?.minus(startedAt) ?: -1L),
                "result" to if (wasCancelled) "cancelled" else "error", "codecDiagnostic" to codecDiagnostic,
            ), op = op, error = t)
            currentDecodeOp = null
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

        if (pendingTrackId in ids || (current != null && current.id in ids)) {
            // Also cancel an already activated progressive decoder before deleting its PCM cache.
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
                if (role != Role.SERVER || (!ui.value.inPair && !soloMode) || calibRunning) { ui { copy(loadingTracks = false) }; return@post }
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
        prefs.getString("audioBrowserRoot", null)?.let { used += it }
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
        if (role != Role.SERVER || (!ui.value.inPair && !soloMode)) return
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
                        Timeline(tl.epoch, true, tl.anchorFrame, tl.anchorNs, scheduled), currentRenderedSource(), renderChannel(), serverGain(), activeEqConfig())
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
            SourceMode.CHANNEL_TEST, SourceMode.CENTER_CHECK -> restoreMusic(savedPlaying)
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
        val op = java.util.UUID.randomUUID().toString()
        calibrationOp = op
        telemetry.record("CALIBRATION_START", mapOf("apply" to apply, "role" to role.name.lowercase()), op = op)
        if (mode == SourceMode.MUSIC) { savedFrame = posFrame(); savedPlaying = serverPlay.timeline.running }
        mode = SourceMode.CALIBRATION; source = CalibrationSource(pointChannel)
        newEpoch(false, 0, System.nanoTime())
        telemetry.record("CALIBRATION_STAGE", mapOf("stage" to "microphone_prepare"), op = op)
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
                        telemetry.record("CALIBRATION_STAGE", mapOf("stage" to "signals"), op = op)
                        broadcastCalib(true, "Звучат тестовые сигналы — тишина, пожалуйста…", "", true, null)
                    }
                }
                post {
                    telemetry.record("CALIBRATION_STAGE", mapOf("stage" to "analysis"), op = op)
                    broadcastCalib(true, "Анализ записи…", "", true, null)
                }
                val res = CalibrationAnalyzer.analyze(rec.samples, rec.sampleRate, rec.startNs, anchor.get(), trimUsed)
                post { finishCalibration(res, apply, trimUsed) }
            } catch (e: Exception) {
                Log.e(TAG, "calibration", e)
                post {
                    calibRunning = false; restoreMusic(savedPlaying)
                    telemetry.record("CALIBRATION_END", mapOf("result" to "error"), op = op, error = e)
                    calibrationOp = null
                    broadcastCalib(false, "", "Ошибка микрофона: ${e.message}", false, null)
                }
            }
        }
    }

    /** Developer-only acoustic sync probe; the ordinary player never exposes this action. */
    private fun startSyncProbeInternal() {
        if (syncProbeActive || role != Role.SERVER || soloMode || phase != Phase.PAIRED || calibRunning) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            telemetry.record("SYNC_PROBE_RESULT", mapOf("valid" to false, "reason" to "microphone_permission"))
            toast("Для замера нужна запись с микрофона Server")
            return
        }
        if (mode == SourceMode.MUSIC) {
            savedFrame = posFrame()
            savedPlaying = serverPlay.timeline.running
        } else {
            restoreMusic(false)
            savedFrame = posFrame()
            savedPlaying = false
        }
        syncProbeActive = true
        syncProbeOp = java.util.UUID.randomUUID().toString()
        syncProbeSavedPointChannel = pointChannel
        syncProbePreviousAudioPath = currentAudioPath
        pointChannel = Ch.R
        if (currentAudioPath != "udp") setAudioPath("udp", "sync_probe")
        clearProgressiveBufferState()
        cancelScheduledPause()
        mode = SourceMode.SYNC_PROBE
        source = SyncProbeSource()
        newEpoch(false, 0, System.nanoTime())
        ui { copy(syncProbeRunning = true, syncProbeResult = "Подготовка записи…") }
        val op = syncProbeOp
        telemetry.record("SYNC_PROBE_START", mapOf("trials" to SyncProbeSource.TRIALS, "role" to "server"), op = op)
        val anchor = AtomicLong(0L)
        io.execute {
            try {
                val recording = mic.record(SyncProbeSource.TRIALS * 1_000L, onStarted = {
                    val startNs = System.nanoTime() + 700_000_000L
                    anchor.set(startNs)
                    post {
                        if (!syncProbeActive) return@post
                        newEpoch(true, 0, startNs)
                        ui { copy(syncProbeResult = "Запись: 120 пар сигналов…") }
                    }
                }, shouldContinue = { syncProbeActive })
                if (!syncProbeActive) return@execute
                val result = SyncProbeAnalyzer.analyze(recording.samples, recording.sampleRate,
                    recording.startNs, anchor.get())
                post { finishSyncProbe(result) }
            } catch (e: Exception) {
                Log.e(TAG, "sync probe", e)
                post { finishSyncProbe(SyncProbeResult(false,
                    if (e is SecurityException) "microphone_permission" else "recording_error")) }
            }
        }
    }

    private fun finishSyncProbe(result: SyncProbeResult) {
        if (!syncProbeActive) return
        syncProbeActive = false
        val op = syncProbeOp
        syncProbeOp = null
        pointChannel = syncProbeSavedPointChannel
        if (syncProbePreviousAudioPath != currentAudioPath) setAudioPath(syncProbePreviousAudioPath, "sync_probe_complete")
        mode = SourceMode.MUSIC
        source = if (queueIndex in queue.indices) currentQueueSource ?: music else music
        restoreMusic(savedPlaying)
        val text = if (!result.valid) {
            if (result.reason == "recording_clipped") "Запись перегружена — уменьшите громкость и повторите замер"
            else "Замер не получился: найдено ${result.trials} пар сигналов"
        } else "Медиана ${"%+.2f".format(result.medianMs)} мс (геометрия и задержки); " +
            "σ ${"%.1f".format(result.sigmaUs)} мкс · p95 ${"%.1f".format(result.p95Us)} мкс · " +
            "max ${"%.1f".format(result.maxUs)} мкс · ppm ${"%+.1f".format(result.ppm)}"
        ui { copy(syncProbeRunning = false, syncProbeResult = text) }
        telemetry.record("SYNC_PROBE_RESULT", mapOf(
            "valid" to result.valid, "reason" to result.reason, "trials" to result.trials,
            "medianMs" to result.medianMs, "sigmaUs" to result.sigmaUs,
            "p95Us" to result.p95Us, "maxUs" to result.maxUs, "ppm" to result.ppm,
        ), op = op)
    }

    private fun finishCalibration(res: CalibrationAnalyzer.Result, apply: Boolean, trimUsed: Long) {
        calibRunning = false
        val op = calibrationOp
        calibrationOp = null
        restoreMusic(savedPlaying)
        if (!res.ok) {
            telemetry.record("CALIBRATION_END", mapOf("result" to "error", "quality" to res.spreadMs,
                "message" to res.message), op = op)
            broadcastCalib(false, "", res.message, false, null); return
        }
        telemetry.record("CALIBRATION_END", mapOf("result" to "ok", "lateMs" to res.lateMs,
            "spreadMs" to res.spreadMs, "apply" to apply), op = op)
        val who = if (res.lateMs >= 0) "опаздывал" else "спешил"
        if (apply) {
            val newTrim = (trimUsed - (res.lateMs * 1e6).toLong())
                .coerceIn(-SF.MAX_TRIM_MS * 1_000_000L, SF.MAX_TRIM_MS * 1_000_000L)
            trimNs = newTrim
            delayCalib = DelayCalib("auto", System.currentTimeMillis(), res.spreadMs)
            saveServerParams(); publishState()
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
        val heartbeatInterval = if (lastHeartbeatAtMs == 0L) 1_000L else (nowMs - lastHeartbeatAtMs).coerceAtLeast(0)
        val stateAfter = mapOf<String, Any?>(
            "phase" to phase.name, "role" to role.name.lowercase(),
            "playing" to if (role == Role.SERVER) serverPlay.timeline.playing else rx.timeline.playing,
            "audioPath" to currentAudioPath, "clockPath" to currentClockPath,
            "linkOpen" to (link?.isOpen == true), "wifiNetworks" to availableNetworkRoutes(wifiOnly = true).size)
        val stateBefore = lastHeartbeatState
        if (stateBefore != null && heartbeatInterval > 5_000L) {
            val frozenMs = (heartbeatInterval - 1_000L).coerceAtLeast(0)
            processFreezeCount++
            processFreezeTotalMs += frozenMs
            prefs.edit().putInt("processFreezeCount", processFreezeCount)
                .putLong("processFreezeTotalMs", processFreezeTotalMs).apply()
            telemetry.record("PROCESS_FROZEN", mapOf("durationMs" to frozenMs,
                "heartbeatIntervalMs" to heartbeatInterval, "expectedIntervalMs" to 1_000,
                "stateBefore" to stateBefore, "stateAfter" to stateAfter))
            ui { copy(freezeCount = processFreezeCount, freezeDurationMs = processFreezeTotalMs) }
            maybeShowStabilitySettings("process_frozen")
        }
        lastHeartbeatState = stateAfter
        lastHeartbeatAtMs = nowMs
        telemetryRateSamples.addLast(heartbeatInterval)
        while (telemetryRateSamples.size > 20) telemetryRateSamples.removeFirst()
        val heartbeatP95 = percentile95(telemetryRateSamples.toList())
        val tickP95 = tickIntervals.takeP95Ms()
        val output = audio.takeWindow(audioFocusHeld)
        val limiterStats = if (role == Role.POINT) pointRenderer.takeLimiterStats() else serverRenderer.takeLimiterStats()
        limiterWindowCount += limiterStats.first
        limiterWindowMaxDb = maxOf(limiterWindowMaxDb, limiterStats.second)
        if (lastLimiterWindowAtMs == 0L) lastLimiterWindowAtMs = nowMs
        else if (nowMs - lastLimiterWindowAtMs >= 10_000L) {
            telemetry.record("LIMITER_ACTIVE", mapOf("count" to limiterWindowCount, "maxReductionDb" to limiterWindowMaxDb))
            limiterWindowCount = 0L; limiterWindowMaxDb = 0.0; lastLimiterWindowAtMs = nowMs
        }
        if (output.recoveryCount > 0) {
            Log.w(TAG, "Recreating audio output after ${output.recoveryCount} failures", output.recoveryError)
            telemetry.record("AUDIO_RECOVERY", mapOf("attempts" to output.recoveryCount), error = output.recoveryError)
        }
        if (udpFirstPacketPending.getAndSet(false)) {
            telemetry.record("UDP_FIRST_PACKET", mapOf("from" to udpFirstPacketFrom.get(),
                "ageAtObservationMs" to (nowMs - udpFirstPacketAtMs.get()).coerceAtLeast(0)))
        }
        val receiveFailureCount = udpReceiveFailures.getAndSet(0)
        if (receiveFailureCount > 0) telemetry.limited("UDP_RECEIVE_FAILED", "count=$receiveFailureCount",
            udpReceiveError.getAndSet(null))
        val sendFailureCount = udpSendFailures.getAndSet(0)
        if (sendFailureCount > 0) telemetry.limited("UDP_SEND_FAILED", "count=$sendFailureCount",
            udpSendError.getAndSet(null))
        val currentUi = ui.value
        val playing = if (role == Role.SERVER) serverPlay.timeline.playing else rx.timeline.playing
        val position = if (role == Role.SERVER) serverPlay.timeline.positionFrame(System.nanoTime()).coerceAtLeast(0)
            else if (clock.ready) rx.timeline.positionFrame(clock.toServer(System.nanoTime())).coerceAtLeast(0) else 0L
        val activeSpatial = if (role == Role.SERVER) activeSpatialSource else null
        if (activeSpatial != null) monitorSpatialOverload(activeSpatial, playing, position, nowMs)
        else {
            spatialPerfInput = null
            spatialPerfStartedAtMs = 0L
            spatialSlowSinceMs = 0L
        }
        if (activeSpatial != null && (lastSpatialPerfAtMs == 0L || nowMs - lastSpatialPerfAtMs >= 10_000L)) {
            val currentPosition = if (playing) position else activeSpatial.readyFrames
            val battery = app.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val batteryCurrentNowUa = battery?.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeUnless { it == Long.MIN_VALUE }
            val batteryCurrentAverageUa = battery?.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                ?.takeUnless { it == Long.MIN_VALUE }
            val batteryCapacityPct = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeUnless { it == Int.MIN_VALUE }
            telemetry.record("SPATIAL_PERF", mapOf(
                "rtFactor" to activeSpatial.realtimeFactor,
                "cacheAheadMs" to ((activeSpatial.readyFrames - currentPosition).coerceAtLeast(0) * 1000.0 / SF.SAMPLE_RATE),
                "restarts" to activeSpatial.restarts,
                "misses" to activeSpatial.misses,
                "limiterDb" to activeSpatial.limiterReductionDb,
                "loudnessCompDb" to activeSpatial.loudnessCompensationDb,
                "sideMidDb" to activeSpatial.sideMidDb,
                "corrMin" to activeSpatial.correlation,
                "xtcApplied" to activeSpatial.xtcApplied,
                "xtcDesignTimedOut" to activeSpatial.xtcDesignTimedOut,
                "batteryCurrentNowUa" to batteryCurrentNowUa,
                "batteryCurrentAverageUa" to batteryCurrentAverageUa,
                "batteryCapacityPct" to batteryCapacityPct,
                "dspVersion" to DSP_VERSION,
            ))
            lastSpatialPerfAtMs = nowMs
        }
        val progressive = if (role == Role.SERVER) serverPlay.source as? SegmentedPcmSource else null
        val readyFrames = progressive?.readyFrames ?: -1L
        val decodeTrack = currentDecodeTrackTag()
        if (decodeTrack != decodeReadyTrackTag || readyFrames != decodeReadyAtLastCheck) {
            decodeReadyTrackTag = decodeTrack
            decodeReadyAtLastCheck = readyFrames
            decodeReadyChangedAtMs = nowMs
        }
        val decodedLead = if (readyFrames >= 0) readyFrames - position else -1L
        val totalFrames = progressive?.totalFrames ?: -1L
        val audioUnderruns = audio.underruns
        val sourceMissing = if (role == Role.SERVER) serverRenderer.windowSourceMissingFrames.getAndSet(0) else 0
        val pointMissing = if (role == Role.POINT) pointRenderer.windowUnderrunFrames.getAndSet(0) else 0
        if (role == Role.SERVER) measurePointChannelSignal()
        val channelSignal = if (role == Role.SERVER) pointChannelSignal.take() else null
        val serverSignalDbfs = channelSignal?.rmsDbfs ?: currentUi.serverDiag.serverChannelRmsDbfs
        val currentSlew = if (role == Role.SERVER) serverRenderer.corrector.slewEvents else pointRenderer.corrector.slewEvents
        val slewDelta = (currentSlew - lastSlewEvents).coerceAtLeast(0)
        lastSlewEvents = currentSlew
        val pairTag = record?.key?.let(TelemetryIdentifiers::hash).orEmpty()
        val trackTag = decodeTrack.orEmpty()
        val udpNow = udpPackets.get()
        val udpRate = (udpNow - previousUdpPackets).coerceAtLeast(0)
        previousUdpPackets = udpNow
        val sentNow = sentAudioPackets.get()
        val sentRate = (sentNow - previousSentAudioPackets).coerceAtLeast(0)
        previousSentAudioPackets = sentNow
        val pointAudioRate = (rx.received - previousPointAudioPackets).coerceAtLeast(0)
        previousPointAudioPackets = rx.received
        val invalidNow = udpInvalid.get()
        val invalidDelta = (invalidNow - previousUdpInvalid).coerceAtLeast(0)
        previousUdpInvalid = invalidNow
        if (invalidDelta > 0) telemetry.limited("UDP_INVALID", "count=$invalidDelta")
        val otherSessionNow = udpOtherSession.get()
        val otherSessionDelta = (otherSessionNow - previousUdpOtherSession).coerceAtLeast(0)
        previousUdpOtherSession = otherSessionNow
        val nacksNow = rx.nacksSent
        val nackDelta = (nacksNow - previousNacksSent).coerceAtLeast(0)
        previousNacksSent = nacksNow

        val localDiag = if (role == Role.SERVER) {
            Diag(
                underruns = audioUnderruns + serverRenderer.underruns.get(), syncErrUs = serverRenderer.corrector.syncErrorUs,
                latencyMs = audio.latencyMs, resent = resent.get(), clockReady = pointClockReady,
                audioAgeMs = currentUi.pointDiag.audioAgeMs, valid = true,
                audioPath = currentAudioPath, clockPath = currentClockPath,
                audioBufferSeconds = currentUi.pointDiag.audioBufferSeconds,
                audioFillFramesPerSec = currentUi.pointDiag.audioFillFramesPerSec,
                pairTag = pairTag, sessionId = sessionId, epoch = serverPlay.timeline.epoch, trackTag = trackTag,
                playbackPhase = phase.name, playing = playing,
                outputRmsDbfs = output.signal.rmsDbfs, outputPeakDbfs = output.signal.peakDbfs,
                outputSilentFrames = sourceMissing, outputFramesPerSecond = output.framesPerSecond,
                outputTimestampValid = output.timestampValid, musicVolume = output.streamVolume,
                musicVolumeMin = output.streamMinVolume, musicVolumeMax = output.streamMaxVolume, musicMuted = output.streamMuted,
                audioRoute = output.route, audioFocus = output.audioFocus, serverChannelRmsDbfs = serverSignalDbfs,
                decodeLeadFrames = decodedLead, decodeReadyFrames = readyFrames, decodePositionFrame = position,
                tickP95Ms = tickP95, heartbeatIntervalMs = heartbeatP95,
                tcpRxAgeMs = link?.let { nowMs - it.lastRx } ?: -1,
                udpPacketsPerSec = udpRate, sentAudioPacketsPerSec = sentRate, udpInvalid = invalidNow,
                udpOtherSession = otherSessionNow, nacksSent = nackDelta,
                offsetMs = 0.0, trimMs = trimNs / 1e6,
                hardJumps = serverRenderer.corrector.hardJumps, slewEvents = slewDelta,
                decodeComplete = progressive?.complete ?: true, decodeTotalFrames = totalFrames,
            )
        } else {
            val recv = rx.received; val lost = rx.lostFinal
            val dR = recv - lastRecv; val dL = lost - lastLost
            lastRecv = recv; lastLost = lost
            val loss = if (dR + dL > 0) dL * 100.0 / (dR + dL) else 0.0
            val tcpPosition = if (clock.ready) rx.timeline.positionFrame(clock.toServer(System.nanoTime()) - pointParams.trimNs)
                .toLong().coerceAtLeast(0) else 0L
            val tcpBufferSeconds = tcpAudioBuffer.bufferedSeconds(rx.timeline.epoch, tcpPosition)
            val fillFrames = tcpAudioFramesSinceMetric.getAndSet(0)
            val metricElapsed = if (lastTcpAudioMetricAtMs == 0L) 1L else (nowMs - lastTcpAudioMetricAtMs).coerceAtLeast(1)
            lastTcpAudioMetricAtMs = nowMs
            tcpAudioFillFramesPerSec = fillFrames * 1_000.0 / metricElapsed
            Diag(
                rttMs = clock.rttAvgMs, rttJitterMs = clock.rttJitterMs, jitterMs = rx.jitterMs, lossPct = loss,
                recovered = rx.recovered, late = rx.late, stale = rx.stale, bufferMs = rx.bufferMs,
                driftPpm = clock.driftPpm, clockUncMs = clock.uncertaintyMs,
                underruns = pointRenderer.underrunEvents.get() + audioUnderruns, syncErrUs = pointRenderer.corrector.syncErrorUs,
                clockModelStepP50Us = clock.modelStepP50Us, clockModelStepP95Us = clock.modelStepP95Us,
                clockModelStepMaxUs = clock.modelStepMaxUs,
                latencyMs = audio.latencyMs, clockReady = clock.ready, resent = currentUi.serverDiag.resent,
                audioAgeMs = lastAudioRxAt.get().let { if (it == 0L) -1 else nowMs - it }, valid = true,
                audioPath = currentAudioPath, clockPath = currentClockPath,
                audioBufferSeconds = if (currentAudioPath == "tcp") tcpBufferSeconds else rx.bufferMs / 1000.0,
                audioFillFramesPerSec = tcpAudioFillFramesPerSec,
                pairTag = pairTag, sessionId = sessionId, epoch = rx.timeline.epoch, trackTag = trackTag,
                playbackPhase = phase.name, playing = playing,
                outputRmsDbfs = output.signal.rmsDbfs, outputPeakDbfs = output.signal.peakDbfs,
                outputSilentFrames = pointMissing, outputFramesPerSecond = output.framesPerSecond,
                outputTimestampValid = output.timestampValid, musicVolume = output.streamVolume,
                musicVolumeMin = output.streamMinVolume, musicVolumeMax = output.streamMaxVolume, musicMuted = output.streamMuted,
                audioRoute = output.route, audioFocus = output.audioFocus, serverChannelRmsDbfs = serverSignalDbfs,
                decodeLeadFrames = currentUi.serverDiag.decodeLeadFrames, decodeReadyFrames = currentUi.serverDiag.decodeReadyFrames,
                decodePositionFrame = currentUi.serverDiag.decodePositionFrame, tickP95Ms = tickP95, heartbeatIntervalMs = heartbeatP95,
                tcpRxAgeMs = link?.let { nowMs - it.lastRx } ?: -1, udpPacketsPerSec = udpRate,
                sentAudioPacketsPerSec = currentUi.serverDiag.sentAudioPacketsPerSec, udpInvalid = invalidNow,
                udpOtherSession = otherSessionNow, nacksSent = nackDelta,
                offsetMs = if (clock.ready) (clock.toServer(System.nanoTime()) - System.nanoTime()) / 1e6 else 0.0,
                trimMs = pointParams.trimNs / 1e6, hardJumps = pointRenderer.corrector.hardJumps, slewEvents = slewDelta,
                decodeComplete = currentUi.serverDiag.decodeComplete, decodeTotalFrames = currentUi.serverDiag.decodeTotalFrames,
            )
        }
        if (role == Role.POINT) {
            val modelSteps = clock.takeModelStepsUs()
            if (modelSteps.isNotEmpty()) telemetry.record("CLOCK_MODEL_STEP", mapOf(
                "count" to modelSteps.size, "absDeltaUs" to modelSteps,
                "p50Us" to clock.modelStepP50Us, "p95Us" to clock.modelStepP95Us,
                "maxUs" to clock.modelStepMaxUs,
            ))
        }
        if (role == Role.SERVER) ui { copy(serverDiag = localDiag) } else ui { copy(pointDiag = localDiag) }

        val serverDiag = if (role == Role.SERVER) localDiag else ui.value.serverDiag
        val pointDiag = if (role == Role.POINT) localDiag else ui.value.pointDiag
        val snapshot = snapshotMetrics(nowMs, serverDiag, pointDiag, channelSignal, output, pointAudioRate, invalidDelta, otherSessionDelta)
        blackBox.add(snapshot)
        val violations = evaluateTelemetryRules(nowMs, serverDiag, pointDiag, localDiag, heartbeatP95)
        val transitions = incidentRules.check(nowMs, violations)
        transitions.forEach { update ->
            val id = "${update.rule.name}-$myId-${update.sinceMs}"
            val eventData = linkedMapOf<String, Any?>(
                "rule" to update.rule.name, "incidentId" to id, "severity" to update.rule.severity,
                "sinceMonoMs" to update.sinceMs, "durMs" to update.durationMs, "values" to update.values,
            )
            if (update.kind == "INCIDENT_START") {
                eventData["snapshot"] = snapshot
                telemetry.record(update.kind, eventData, category = "incident", op = id,
                    severityOverride = update.rule.severity)
                flushBlackBox(id)
            } else {
                if (update.kind == "INCIDENT_END") {
                    eventData["result"] = "ok"
                    eventData["recoveredSnapshot"] = snapshot
                }
                telemetry.record(update.kind, eventData, category = "incident", op = id,
                    severityOverride = update.rule.severity)
            }
        }
        val activeIncidents = incidentRules.active(nowMs).map {
            "${it.rule.name} · ${formatDuration(it.durationMs)}"
        }
        ui { copy(activeIncidents = activeIncidents) }
        if (nowMs - lastTelemetry >= 10_000) {
            lastTelemetry = nowMs
            telemetry.recordCompressed("HEALTH", mapOf("samples" to listOf(snapshot)), field = "samples")
        }

        val activeLink = link
        if (phase != Phase.PAIRED || activeLink == null) return
        if (nowMs - activeLink.lastRx > SF.LINK_TIMEOUT_MS) {
            log("Нет данных ${SF.LINK_TIMEOUT_MS / 1000} с — соединение закрыто")
            activeLink.close("heartbeat timeout")
            return
        }
        activeLink.send(JSONObject().put("t", "hb"))
        if (role == Role.SERVER) {
            activeLink.send(localDiag.toJson("sdiag"))
            val pointAge = ui.value.pointDiag.audioAgeMs
            val audioMissing = serverPlay.timeline.running && nowMs - pairedAtMs > 5_000 &&
                currentAudioPath != "tcp" && (pointUdp == null || pointAge < 0 || pointAge >= 5_000)
            ui { copy(audioMissing = audioMissing) }
            if (audioMissing && nowMs - lastAudioSessionResetMs >= audioRenewIntervalMs) {
                renewAudioSession("Point не получает аудиопакеты")
            } else if (!audioMissing && (udpFailureNoticeShown || udpNetworkRebindAttempted)) {
                udpFailureNoticeShown = false
                udpNetworkRebindAttempted = false
                zeroSendRenewStreak = 0
                audioRenewIntervalMs = 5_000L
            }
        } else {
            val audioMissing = rx.timeline.playing && nowMs - pairedAtMs > 5_000 &&
                (localDiag.audioAgeMs < 0 || localDiag.audioAgeMs >= 5_000)
            ui { copy(audioMissing = audioMissing) }
            activeLink.send(localDiag.toJson("diag"))
        }
    }

    private fun currentDecodeTrackTag(): String? {
        val id = if (role == Role.SERVER) queue.getOrNull(queueIndex)?.id
            else ui.value.queueTracks.getOrNull(ui.value.queueIndex)?.id
        return id?.takeIf { it.isNotBlank() }?.let(TelemetryIdentifiers::hash)
    }

    private fun percentile95(samples: List<Long>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        val index = kotlin.math.ceil(sorted.lastIndex * 0.95).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index].toDouble()
    }

    private fun snapshotMetrics(
        nowMs: Long,
        serverDiag: Diag,
        pointDiag: Diag,
        channelSignal: app.soundfield.diagnostics.SignalWindow?,
        output: AudioOutput.Window,
        pointAudioRate: Long,
        invalidDelta: Long,
        otherSessionDelta: Long,
    ): Map<String, Any?> {
        val state = ui.value
        val track = if (role == Role.SERVER) queue.getOrNull(queueIndex)?.id
            else state.queueTracks.getOrNull(state.queueIndex)?.id
        return linkedMapOf(
            "mono" to nowMs, "srvNs" to if (role == Role.SERVER) System.nanoTime()
                else if (clock.ready) clock.toServer(System.nanoTime()) else null,
            "device" to myId, "role" to role.name.lowercase(), "phase" to phase.name,
            "network" to mapOf(
                "audioPath" to pointDiag.audioPath, "clockPath" to pointDiag.clockPath,
                "rttMs" to pointDiag.rttMs, "rttJitterMs" to pointDiag.rttJitterMs,
                "jitterMs" to pointDiag.jitterMs, "lossPct" to pointDiag.lossPct,
                "nacksSent" to pointDiag.nacksSent, "resent" to serverDiag.resent,
                "late" to pointDiag.late, "stale" to pointDiag.stale,
                "udpPacketsPerSec" to serverDiag.udpPacketsPerSec,
                "audioPacketsSentPerSec" to serverDiag.sentAudioPacketsPerSec,
                "audioPacketsReceivedPerSec" to pointAudioRate,
                "pointPingPacketsSent" to pointPingPacketsSent.get(),
                "pointPingSendErrors" to pointPingSendErrors.get(),
                "udpLocalAddress" to udpBoundLocalAddress, "udpNetwork" to udpBoundNetworkLabel,
                "udpLocalPort" to udp.localPort,
                "udpInvalidDelta" to invalidDelta, "udpOtherSessionDelta" to otherSessionDelta,
                "audioAgeMs" to pointDiag.audioAgeMs, "tcpRxAgeMs" to (link?.let { nowMs - it.lastRx } ?: -1),
            ),
            "sync" to mapOf(
                "offsetMs" to pointDiag.offsetMs, "driftPpm" to pointDiag.driftPpm,
                "uncertaintyMs" to pointDiag.clockUncMs, "syncErrUsServer" to serverDiag.syncErrUs,
                "syncErrUsPoint" to pointDiag.syncErrUs,
                "clockModelStepUs" to mapOf("p50" to pointDiag.clockModelStepP50Us,
                    "p95" to pointDiag.clockModelStepP95Us, "max" to pointDiag.clockModelStepMaxUs),
                "hardJumps" to serverDiag.hardJumps + pointDiag.hardJumps,
                "slewEventsPerSec" to serverDiag.slewEvents + pointDiag.slewEvents, "trimMs" to pointDiag.trimMs,
            ),
            "buffers" to mapOf(
                "jitterBufferMs" to pointDiag.bufferMs, "decodeLeadFrames" to serverDiag.decodeLeadFrames,
                "decodeReadyFrames" to serverDiag.decodeReadyFrames, "decodePositionFrame" to serverDiag.decodePositionFrame,
                "pointAudioBufferSeconds" to pointDiag.audioBufferSeconds,
                "audioFillFramesPerSec" to pointDiag.audioFillFramesPerSec,
                "pointUnderrunFrames" to pointDiag.outputSilentFrames, "serverUnderrunFrames" to serverDiag.outputSilentFrames,
            ),
            "sound" to mapOf(
                "outputRmsDbfs" to output.signal.rmsDbfs, "outputPeakDbfs" to output.signal.peakDbfs,
                "outputFramesPerSec" to output.framesPerSecond, "outputTimestampValid" to output.timestampValid,
                "serverChannelRmsDbfs" to (channelSignal?.rmsDbfs ?: serverDiag.serverChannelRmsDbfs),
                "musicVolume" to output.streamVolume, "musicVolumeMin" to output.streamMinVolume,
                "musicVolumeMax" to output.streamMaxVolume, "musicMuted" to output.streamMuted,
                "route" to output.route, "audioFocus" to output.audioFocus,
            ),
            "playback" to mapOf(
                "session" to sessionId, "epoch" to if (role == Role.SERVER) serverPlay.timeline.epoch else rx.timeline.epoch,
                "playing" to state.playing, "positionFrame" to positionFrameSnapshot(),
                "track" to track?.let(TelemetryIdentifiers::hash), "mode" to state.mode.wire,
            ),
            "loops" to mapOf("tickP95Ms" to serverDiag.tickP95Ms, "heartbeatIntervalP95Ms" to serverDiag.heartbeatIntervalMs),
            "process" to mapOf("freezeCount" to processFreezeCount, "freezeTotalMs" to processFreezeTotalMs),
        )
    }

    private fun positionFrameSnapshot(): Long {
        val now = System.nanoTime()
        return if (role == Role.SERVER) serverPlay.timeline.positionFrame(now).coerceAtLeast(0)
        else if (clock.ready) rx.timeline.positionFrame(clock.toServer(now)).coerceAtLeast(0) else 0
    }

    private fun evaluateTelemetryRules(
        nowMs: Long,
        serverDiag: Diag,
        pointDiag: Diag,
        localDiag: Diag,
        heartbeatP95: Double,
    ): Map<String, Map<String, Any?>> {
        val result = linkedMapOf<String, Map<String, Any?>>()
        val playing = serverDiag.playing || pointDiag.playing
        fun violation(name: String, values: Map<String, Any?>) { result[name] = values }
        peerPathDiagnoses.entries.firstOrNull { it.value.currentVerdict() == PeerPathDiagnosis.Verdict.LAN_PEER_BLOCKED }?.let { (id, _) ->
            violation("LAN_ISOLATION", mapOf("peer" to id, "peerName" to (record?.peerName ?: peerName),
                "candidateResults" to peerPathDiagnoses[id]?.latestCandidates().orEmpty().map { candidate ->
                    mapOf("network" to candidate.networkLabel, "networkId" to candidate.networkId,
                        "remoteAddress" to candidate.remoteHost, "remotePort" to candidate.remotePort,
                        "reason" to candidate.reason.name)
                }))
        }
        if (phase == Phase.RECONNECTING && reconnectSince > 0 && nowMs - reconnectSince >= 60_000L) {
            violation("PEER_UNREACHABLE", mapOf("peer" to peerId, "peerName" to peerName,
                "elapsedMs" to nowMs - reconnectSince, "attemptedRoutes" to reconnectCandidateHistory.toList()))
        }
        if (phase == Phase.PAIRED && playing && (pointDiag.audioAgeMs < 0 || pointDiag.audioAgeMs >= 1_000)) {
            violation("POINT_NO_PACKETS", mapOf("audioAgeMs" to pointDiag.audioAgeMs, "sessionId" to pointDiag.sessionId))
        }
        if (phase == Phase.PAIRED && playing && pointDiag.audioPath == "tcp" && pointDiag.audioBufferSeconds < 3.0) {
            violation("POINT_BUFFER_LOW", mapOf("audioBufferSeconds" to pointDiag.audioBufferSeconds,
                "audioFillFramesPerSec" to pointDiag.audioFillFramesPerSec, "audioPath" to pointDiag.audioPath))
        }
        if (phase == Phase.PAIRED && playing && serverDiag.serverChannelRmsDbfs > -50.0 && pointDiag.outputRmsDbfs < -70.0) {
            violation("POINT_SILENT_OUTPUT", mapOf("serverChannelRmsDbfs" to serverDiag.serverChannelRmsDbfs,
                "pointOutputRmsDbfs" to pointDiag.outputRmsDbfs))
        }
        if (phase == Phase.PAIRED && playing && serverDiag.outputRmsDbfs < -70.0) {
            violation("SERVER_SILENT_OUTPUT", mapOf("serverOutputRmsDbfs" to serverDiag.outputRmsDbfs,
                "serverChannelRmsDbfs" to serverDiag.serverChannelRmsDbfs))
        }
        val stalledDevices = listOf("server" to serverDiag, "point" to pointDiag).filter { (_, diag) ->
            playing && diag.outputTimestampValid && diag.outputFramesPerSecond < SF.SAMPLE_RATE * 0.9
        }.map { (device, diag) -> mapOf("device" to device, "framesPerSec" to diag.outputFramesPerSecond) }
        if (stalledDevices.isNotEmpty()) violation("OUTPUT_STALLED", mapOf("devices" to stalledDevices))
        val muted = listOf("server" to serverDiag, "point" to pointDiag).filter { (_, diag) ->
            playing && (diag.musicMuted || diag.musicVolume == 0)
        }.map { (device, diag) -> mapOf("device" to device, "volume" to diag.musicVolume, "muted" to diag.musicMuted) }
        if (muted.isNotEmpty()) violation("OUTPUT_MUTED", mapOf("devices" to muted))
        val syncErrors = mapOf("serverUs" to serverDiag.syncErrUs, "pointUs" to pointDiag.syncErrUs)
        if (phase == Phase.PAIRED && playing && (abs(serverDiag.syncErrUs) > 2_000.0 || abs(pointDiag.syncErrUs) > 2_000.0)) {
            violation("SYNC_ERROR", syncErrors)
        }
        if (phase == Phase.PAIRED && playing && serverDiag.decodeTotalFrames >= 120L * SF.SAMPLE_RATE &&
            !serverDiag.decodeComplete && serverDiag.decodeLeadFrames in 0 until (2L * SF.SAMPLE_RATE)) {
            violation("DECODE_LEAD_LOW", mapOf("leadFrames" to serverDiag.decodeLeadFrames,
                "thresholdFrames" to 2L * SF.SAMPLE_RATE, "readyFrames" to serverDiag.decodeReadyFrames))
        }
        if ((ui.value.loadingTracks || ui.value.audioBuffering) && nowMs - decodeReadyChangedAtMs >= 10_000) {
            violation("DECODE_STUCK", mapOf("readyFrames" to decodeReadyAtLastCheck,
                "unchangedMs" to nowMs - decodeReadyChangedAtMs, "loading" to ui.value.loadingTracks,
                "buffering" to ui.value.audioBuffering))
        }
        val transferActive = fileTransferActive.get() || incomingFileTransfer != null
        if (transferActive && transferLastProgressAtMs > 0 && nowMs - transferLastProgressAtMs >= 10_000) {
            violation("TRANSFER_STUCK", mapOf("unchangedMs" to nowMs - transferLastProgressAtMs))
        }
        if (phase == Phase.PAIRED && serverDiag.valid && pointDiag.valid) {
            val differences = linkedMapOf<String, Any?>()
            if (serverDiag.pairTag.isNotBlank() && pointDiag.pairTag.isNotBlank() && serverDiag.pairTag != pointDiag.pairTag)
                differences["pairTag"] = listOf(serverDiag.pairTag, pointDiag.pairTag)
            if (serverDiag.sessionId != pointDiag.sessionId) differences["sessionId"] = listOf(serverDiag.sessionId, pointDiag.sessionId)
            if (serverDiag.epoch != pointDiag.epoch) differences["epoch"] = listOf(serverDiag.epoch, pointDiag.epoch)
            if (serverDiag.trackTag.isNotBlank() && pointDiag.trackTag.isNotBlank() && serverDiag.trackTag != pointDiag.trackTag)
                differences["trackTag"] = listOf(serverDiag.trackTag, pointDiag.trackTag)
            if (serverDiag.playbackPhase != pointDiag.playbackPhase) differences["phase"] = listOf(serverDiag.playbackPhase, pointDiag.playbackPhase)
            if (serverDiag.playing != pointDiag.playing) differences["playing"] = listOf(serverDiag.playing, pointDiag.playing)
            if (differences.isNotEmpty()) violation("STATE_MISMATCH", differences)
        }
        if (phase == Phase.PAIRED && (localDiag.tickP95Ms > 20.0 || heartbeatP95 > 1_500.0)) {
            violation("LOOP_LATENCY", mapOf("tickP95Ms" to localDiag.tickP95Ms, "heartbeatP95Ms" to heartbeatP95))
        }
        val tcpAge = link?.let { nowMs - it.lastRx } ?: Long.MAX_VALUE
        if (phase == Phase.PAIRED && tcpAge > 2_000) violation("LINK_SILENT", mapOf("tcpRxAgeMs" to tcpAge))
        return result
    }

    private fun flushBlackBox(id: String) {
        val samples = blackBox.drain()
        telemetry.recordCompressed("BLACKBOX", mapOf("id" to id, "samples" to samples), field = "samples", op = id)
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1_000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    // ================================================================== UDP
    private fun udpLoop() {
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        while (true) {
            val socket = udp
            try {
                pkt.length = buf.size
                socket.receive(pkt)
                val now = System.nanoTime()
                udpPackets.incrementAndGet()
                val m = Wire.decode(pkt.data, pkt.length)
                if (m == null) { udpInvalid.incrementAndGet(); continue }
                if (m.session != sessionId || sessionId == 0) { udpOtherSession.incrementAndGet(); continue }
                val receivedAt = SystemClock.elapsedRealtime()
                if (udpLastRx.getAndSet(receivedAt) == 0L) {
                    udpFirstPacketAtMs.set(receivedAt)
                    udpFirstPacketFrom.set(pkt.socketAddress.toString())
                    udpFirstPacketPending.set(true)
                }
                when (m) {
                    is UdpMsg.Ping -> if (role == Role.SERVER && phase == Phase.PAIRED && !debugStopUdpTraffic) {
                        pointUdp = pkt.socketAddress as InetSocketAddress
                        val b = Wire.pong(sessionId, m.t1, now, System.nanoTime(), m.pingSeq)
                        udp.send(DatagramPacket(b, b.size, pkt.socketAddress))
                    }
                    is UdpMsg.Nack -> if (role == Role.SERVER) resend(m)
                    is UdpMsg.Pong -> if (role == Role.POINT) {
                        lastUdpPongAtMs.set(SystemClock.elapsedRealtime())
                        clock.addPong(m.t1, m.t2, m.t3, now)
                        setClockPath("udp", "udp_pong_received")
                    }
                    is UdpMsg.Audio -> if (role == Role.POINT && phase == Phase.PAIRED) {
                        lastAudioRxAt.set(SystemClock.elapsedRealtime())
                        rx.onAudio(m, now)
                        if (currentAudioPath == "tcp" && rx.bufferMs >= 100.0)
                            setAudioPath("udp", "udp_audio_buffered")
                    }
                }
            } catch (e: Exception) {
                if (socket === udp) {
                    udpReceiveFailures.incrementAndGet(); udpReceiveError.set(e); SystemClock.sleep(10)
                }
            }
        }
    }

    private fun tickLoop() {
        while (true) {
            try {
                if (phase == Phase.PAIRED) {
                    val now = System.nanoTime()
                    val previous = lastTickAtNs.getAndSet(now)
                    if (previous > 0L) tickIntervals.record(now - previous)
                    if (role == Role.SERVER) serverSend() else pointNet()
                    SystemClock.sleep(5)
                } else {
                    lastTickAtNs.set(0L)
                    // Не в паре — нет смысла крутиться каждые 5 мс, спим 200 мс.
                    SystemClock.sleep(200)
                }
            } catch (e: Exception) { udpSendFailures.incrementAndGet(); udpSendError.set(e) }
        }
    }

    private fun measurePointChannelSignal() {
        val st = serverPlay
        val tl = st.timeline
        if (!tl.playing) return
        val start = tl.frameAt(System.nanoTime() + SF.LEAD_NS).toLong().coerceAtLeast(0)
        if (start >= st.source.totalFrames) return
        val count = minOf((SF.SAMPLE_RATE / 10).toLong(), st.source.totalFrames - start,
            st.source.readyFrames - start).coerceAtLeast(0L).toInt()
        if (count <= 0) return
        val channel = Ch.other(st.channel)
        val samples = FloatArray(count) { index ->
            val frame = start + index
            st.source.sample(frame, channel) * tl.envelope(frame)
        }
        pointChannelSignal.add(samples, 0, samples.size)
    }

    /** Число кадров в пакете, начинающемся с [start]: не больше PACKET_FRAMES и только из готовой части источника. */
    private fun packetFrameCount(source: AudioSource, start: Long): Int = Packetizing.frameCount(source, start)

    private fun packet(st: ServerPlayState, tl: Timeline, seq: Int, start: Long, flags: Int,
        count: Int = packetFrameCount(st.source, start)): ByteArray {
        val pCh = Ch.other(st.channel)
        val s = ShortArray(count) { i -> (st.source.sample(start + i, pCh) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }
        return Wire.audio(sessionId, tl.epoch, seq, start, tl.timeOf(start), System.nanoTime(), flags, s, count)
    }

    private fun serverSend() { synchronized(sendLock) {
        if (debugStopUdpTraffic || currentAudioPath == "tcp") return
        val addr = pointUdp ?: return
        val st = serverPlay
        val tl = st.timeline
        if (!tl.playing) return
        if (tl.epoch != sentEpoch) { sentEpoch = tl.epoch; nextFrame = tl.anchorFrame }
        val requestedTarget = minOf(tl.frameAt(System.nanoTime() + SF.LEAD_NS).toLong(), st.source.totalFrames)
        (st.source as? PrefetchableAudioSource)?.requestThrough(requestedTarget)
        val target = minOf(requestedTarget, st.source.readyFrames)
        var k = 0
        while (nextFrame < target && k < 40) {
            // Пакет может оказаться короче PACKET_FRAMES, если источник (обработка, декодер) ещё не готов дальше.
            // Курсор двигаем на фактически отправленное число кадров: иначе хвост пакета терялся бы дырой до 10 мс.
            val count = packetFrameCount(st.source, nextFrame)
            if (count <= 0) break
            val seq = sendSeq++
            val b = packet(st, tl, seq, nextFrame, 0, count)
            val i = seq and 1023
            cacheSeq[i] = seq; cacheEpoch[i] = tl.epoch; cacheStart[i] = nextFrame; cacheCount[i] = count
            udp.send(DatagramPacket(b, b.size, addr))
            sentAudioPackets.incrementAndGet()
            nextFrame += count; k++
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
            if (tl.timeOf(cacheStart[i] + cacheCount[i]) < now) continue   // уже поздно
            val b = packet(st, tl, seq, cacheStart[i], UdpMsg.FLAG_RETRANSMIT, cacheCount[i])
            udp.send(DatagramPacket(b, b.size, addr)); resent.incrementAndGet()
        }
    } }

    private fun pointNet() {
        if (sessionId == 0) return
        val now = System.nanoTime()
        val nowMs = SystemClock.elapsedRealtime()
        if (!debugStopUdpTraffic) {
            val addr = serverUdp
            val interval = if (pingBurst > 0) 50_000_000L else 200_000_000L
            if (addr != null && now - lastPing >= interval) {
                lastPing = now
                if (pingBurst > 0) pingBurst--
                val b = Wire.ping(sessionId, now, pingSeq++)
                try {
                    udp.send(DatagramPacket(b, b.size, addr))
                    pointPingPacketsSent.incrementAndGet()
                } catch (e: Exception) {
                    pointPingSendErrors.incrementAndGet()
                    telemetry.limited("UDP_POINT_PING_SEND_FAILED", "network=$udpBoundNetworkLabel localAddress=$udpBoundLocalAddress target=${addr.address.hostAddress}:${addr.port}", e)
                }
            }
            if (currentAudioPath != "tcp") {
                val nacks = rx.collectNacks(now)
                if (nacks.isNotEmpty() && addr != null) {
                    val b = Wire.nack(sessionId, rx.timeline.epoch, nacks)
                    udp.send(DatagramPacket(b, b.size, addr))
                }
            }
        }
        if ("tcpClock" in negotiatedCapabilities && nowMs - lastUdpPongAtMs.get() >= 1_500L &&
            now - lastTcpClockPingAtNs >= 200_000_000L) {
            lastTcpClockPingAtNs = now
            tcpClockPingSeq++
            link?.send(JSONObject().put("t", "clock-ping").put("seq", tcpClockPingSeq).put("t1", now.toString()))
            setClockPath("tcp", if (debugStopUdpTraffic) "debug_udp_stopped" else "udp_pong_timeout")
        } else if (lastUdpPongAtMs.get() > 0 && nowMs - lastUdpPongAtMs.get() < 1_500L) {
            setClockPath("udp", "udp_pong_received")
        }
    }

    // ================================================================== аудиофокус / прерывания
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        audioFocusHeld = change == AudioManager.AUDIOFOCUS_GAIN || audioFocusHeld
        telemetry.record("AUDIO_FOCUS_CHANGE", mapOf("change" to change, "held" to audioFocusHeld))
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                { audioFocusHeld = false; post { onInterrupted("Звук прерван (звонок или другое приложение) — пара на паузе") } }
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
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = req
            audioFocusHeld = true
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
        audioFocusHeld = false
    }

    /** Прерывание на любом телефоне ставит на паузу всю пару — состояние остаётся согласованным. */
    private fun onInterrupted(reason: String) {
        focusRequest = null
        audioFocusHeld = false
        if (!ui.value.inPair) return
        if (role == Role.SERVER) cmdPause() else link?.send(JSONObject().put("t", "cmd").put("a", "pause"))
        toast(reason)
    }
}
