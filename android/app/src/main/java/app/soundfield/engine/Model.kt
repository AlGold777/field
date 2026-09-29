package app.soundfield.engine

import app.soundfield.core.Ch
import app.soundfield.core.Role
import app.soundfield.core.SourceMode
import app.soundfield.core.EqMagnet
import app.soundfield.core.EqSettings
import app.soundfield.core.EqPreset
import app.soundfield.core.SpatialSettings
import org.json.JSONObject
import java.net.InetAddress

/** Телефон, найденный в локальной сети. */
data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val host: InetAddress?,
    val port: Int,
    val supportsRinging: Boolean = false,
    /** All resolved mDNS addresses; `host` remains the compatibility/preferred address. */
    val hosts: List<InetAddress> = listOfNotNull(host),
    val addresses: List<PeerAddress> = emptyList(),
    val lastSuccessfulHost: String? = null,
    val historyHosts: List<String> = emptyList(),
)

data class PeerAddress(val ip: String, val iface: String, val networkId: String) {
    fun toJson(): JSONObject = JSONObject().put("ip", ip).put("iface", iface).put("networkId", networkId)
}

enum class Phase { IDLE, INVITING, INCOMING, PAIRED, RECONNECTING }

/** Сохранённая пара — для быстрого переподключения без подтверждения. */
data class PairRecord(
    val key: String,
    val peerId: String,
    val peerName: String,
    val peerPlatform: String,
    val myRole: Role,
    val pointChannel: Int = Ch.R,
    val trimMs: Double = 0.0,
    val volL: Float = 1f,
    val volR: Float = 1f,
    val master: Float = 0.96f,
    /** Последний известный адрес второго телефона и порт его TCP-сервера — быстрый путь без mDNS. */
    val lastHost: String? = null,
    val lastPort: Int = 0,
    val peerAddresses: List<PeerAddress> = emptyList(),
    val lastSuccessfulHost: String? = lastHost,
    val balance: Float = 0.5f,
    val levelTrimServerDb: Float = 0f,
    val levelTrimPointDb: Float = 0f,
    /** 0 означает запись до миграции громкостной кривой. */
    val volTaper: Int = 2,
    /** 0 означает, что старые volL/volR ещё нужно перенести в калибровочные уровни. */
    val levelTrimVersion: Int = 1,
    val delayCalib: DelayCalib = DelayCalib(),
    val levelCalib: LevelCalib = LevelCalib(),
    val lastSuccessfulNetworkId: String? = null,
    val eq: EqSettings = EqSettings(),
    val spatial: SpatialSettings = SpatialSettings(),
) {
    fun toJson(): String = JSONObject().apply {
        put("key", key); put("peerId", peerId); put("peerName", peerName); put("peerPlatform", peerPlatform)
        put("myRole", myRole.wire); put("pointCh", Ch.name(pointChannel)); put("trimMs", trimMs)
        put("volL", volL.toDouble()); put("volR", volR.toDouble()); put("master", master.toDouble())
        put("balance", balance.toDouble())
        put("levelTrimServerDb", levelTrimServerDb.toDouble()); put("levelTrimPointDb", levelTrimPointDb.toDouble())
        put("volTaper", volTaper); put("levelTrimVersion", levelTrimVersion)
        put("delayCalib", JSONObject().put("state", delayCalib.state).put("atMs", delayCalib.atMs).put("spreadMs", delayCalib.spreadMs))
        put("levelCalib", JSONObject().put("state", levelCalib.state).put("atMs", levelCalib.atMs).put("db", levelCalib.db))
        lastHost?.let { put("lastHost", it) }; put("lastPort", lastPort)
        lastSuccessfulHost?.let { put("lastSuccessfulHost", it) }
        lastSuccessfulNetworkId?.let { put("lastSuccessfulNetworkId", it) }
        put("peerAddresses", org.json.JSONArray().also { array -> peerAddresses.forEach { array.put(it.toJson()) } })
        put("eq", JSONObject().apply {
            put("enabled", eq.enabled)
            put("gains", org.json.JSONArray(eq.sanitized().gainsDb))
            put("magnet", eq.magnet.name)
            put("preset", eq.preset)
            put("preserveLoudness", eq.preserveLoudness)
            put("bypass", eq.bypass)
        })
        put("spatial", spatial.toJson())
    }.toString()

    companion object {
        fun fromJson(s: String?): PairRecord? = try {
            if (s == null) null else JSONObject(s).let {
                PairRecord(
                    it.getString("key"), it.getString("peerId"), it.getString("peerName"),
                    it.optString("peerPlatform", "android"), Role.fromWire(it.getString("myRole")),
                    Ch.parse(it.optString("pointCh", "R")), it.optDouble("trimMs", 0.0),
                    it.optDouble("volL", 1.0).toFloat(), it.optDouble("volR", 1.0).toFloat(),
                    it.optDouble("master", 0.8).toFloat(),
                    it.optString("lastHost", "").ifEmpty { null }, it.optInt("lastPort", 0),
                    it.optJSONArray("peerAddresses")?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            array.optJSONObject(index)?.let { address -> PeerAddress(address.optString("ip"),
                                address.optString("iface"), address.optString("networkId")) }
                                ?.takeIf { address -> address.ip.isNotBlank() }
                        }
                    }.orEmpty(), it.optString("lastSuccessfulHost", "").ifEmpty { null },
                    it.optDouble("balance", 0.5).toFloat(),
                    it.optDouble("levelTrimServerDb", 0.0).toFloat(),
                    it.optDouble("levelTrimPointDb", 0.0).toFloat(),
                    it.optInt("volTaper", 0), it.optInt("levelTrimVersion", 0),
                    it.optJSONObject("delayCalib")?.let { c -> DelayCalib(c.optString("state", "none"), c.optLong("atMs"), c.optDouble("spreadMs")) } ?: DelayCalib(),
                    it.optJSONObject("levelCalib")?.let { c -> LevelCalib(c.optString("state", "none"), c.optLong("atMs"), c.optDouble("db")) } ?: LevelCalib(),
                    it.optString("lastSuccessfulNetworkId", "").ifEmpty { null },
                    it.optJSONObject("eq")?.let { eqObject ->
                        val gains = eqObject.optJSONArray("gains")
                        EqSettings(
                            enabled = eqObject.optBoolean("enabled", false),
                            gainsDb = List(10) { index -> gains?.optDouble(index, 0.0)?.toFloat() ?: 0f },
                            magnet = EqMagnet.parse(eqObject.optString("magnet", "SOFT")),
                            preset = eqObject.optString("preset", "Ровно"),
                            preserveLoudness = eqObject.optBoolean("preserveLoudness", true),
                            bypass = eqObject.optBoolean("bypass", false),
                        ).sanitized()
                    } ?: EqSettings(),
                    SpatialSettings.fromJson(it.optJSONObject("spatial")),
                )
            }
        } catch (e: Exception) { null }
    }
}

data class DelayCalib(val state: String = "none", val atMs: Long = 0, val spreadMs: Double = 0.0)
data class LevelCalib(val state: String = "none", val atMs: Long = 0, val db: Double = 0.0)

/** Технические показатели одной стороны пары. */
data class Diag(
    val rttMs: Double = 0.0,
    val rttJitterMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val lossPct: Double = 0.0,
    val recovered: Long = 0,
    val late: Long = 0,
    val stale: Long = 0,
    val bufferMs: Double = 0.0,
    val audioPath: String = "udp",
    val clockPath: String = "udp",
    val audioBufferSeconds: Double = 0.0,
    val audioFillFramesPerSec: Double = 0.0,
    val driftPpm: Double = 0.0,
    val clockUncMs: Double = 0.0,
    val underruns: Long = 0,
    val syncErrUs: Double = 0.0,
    val clockModelStepP50Us: Double = 0.0,
    val clockModelStepP95Us: Double = 0.0,
    val clockModelStepMaxUs: Double = 0.0,
    val latencyMs: Double = 0.0,
    val resent: Long = 0,
    val clockReady: Boolean = false,
    /** Сколько мс назад Point принял последний audio-пакет; −1 означает, что аудио ещё не приходило. */
    val audioAgeMs: Long = -1,
    val valid: Boolean = false,
    val pairTag: String = "",
    val sessionId: Int = 0,
    val epoch: Int = 0,
    val trackTag: String = "",
    val playbackPhase: String = "",
    val playing: Boolean = false,
    val outputRmsDbfs: Double = -120.0,
    val outputPeakDbfs: Double = -120.0,
    val outputSilentFrames: Long = 0,
    val outputFramesPerSecond: Double = 0.0,
    val outputTimestampValid: Boolean = false,
    val musicVolume: Int = -1,
    val musicVolumeMin: Int = 0,
    val musicVolumeMax: Int = 0,
    val musicMuted: Boolean = false,
    val audioRoute: String = "unknown",
    val audioFocus: Boolean = false,
    val serverChannelRmsDbfs: Double = -120.0,
    val decodeLeadFrames: Long = -1,
    val decodeReadyFrames: Long = -1,
    val decodePositionFrame: Long = -1,
    val tickP95Ms: Double = 0.0,
    val heartbeatIntervalMs: Double = 0.0,
    val tcpRxAgeMs: Long = -1,
    val udpPacketsPerSec: Long = 0,
    val sentAudioPacketsPerSec: Long = 0,
    val udpInvalid: Long = 0,
    val udpOtherSession: Long = 0,
    val nacksSent: Long = 0,
    val offsetMs: Double = 0.0,
    val trimMs: Double = 0.0,
    val hardJumps: Int = 0,
    val slewEvents: Long = 0,
    val decodeComplete: Boolean = true,
    val decodeTotalFrames: Long = -1,
) {
    val syncErrMs: Double get() = syncErrUs / 1_000.0

    fun toJson(t: String): JSONObject = JSONObject().apply {
        put("t", t); put("rtt", rttMs); put("rttJit", rttJitterMs); put("jitter", jitterMs); put("loss", lossPct)
        put("recovered", recovered); put("late", late); put("stale", stale); put("buffer", bufferMs)
        put("audioPath", audioPath); put("clockPath", clockPath)
        put("audioBufferSeconds", audioBufferSeconds); put("audioFillFramesPerSec", audioFillFramesPerSec)
        put("drift", driftPpm); put("unc", clockUncMs); put("underruns", underruns)
        put("syncErrUs", syncErrUs); put("syncErr", syncErrMs)
        put("clockModelStepP50Us", clockModelStepP50Us); put("clockModelStepP95Us", clockModelStepP95Us)
        put("clockModelStepMaxUs", clockModelStepMaxUs)
        put("latency", latencyMs); put("resent", resent); put("clockReady", clockReady); put("audioAge", audioAgeMs)
        put("pairTag", pairTag); put("sessionId", sessionId); put("epoch", epoch); put("trackTag", trackTag)
        put("playbackPhase", playbackPhase); put("playing", playing)
        put("outputRmsDbfs", outputRmsDbfs); put("outputPeakDbfs", outputPeakDbfs)
        put("outputSilentFrames", outputSilentFrames); put("outputFps", outputFramesPerSecond)
        put("outputTimestampValid", outputTimestampValid); put("musicVolume", musicVolume)
        put("musicVolumeMin", musicVolumeMin); put("musicVolumeMax", musicVolumeMax); put("musicMuted", musicMuted)
        put("audioRoute", audioRoute); put("audioFocus", audioFocus); put("serverChannelRmsDbfs", serverChannelRmsDbfs)
        put("decodeLeadFrames", decodeLeadFrames); put("decodeReadyFrames", decodeReadyFrames)
        put("decodePositionFrame", decodePositionFrame); put("tickP95Ms", tickP95Ms)
        put("heartbeatIntervalMs", heartbeatIntervalMs); put("tcpRxAgeMs", tcpRxAgeMs)
        put("udpPacketsPerSec", udpPacketsPerSec); put("sentAudioPacketsPerSec", sentAudioPacketsPerSec)
        put("udpInvalid", udpInvalid); put("udpOtherSession", udpOtherSession); put("nacksSent", nacksSent)
        put("offsetMs", offsetMs); put("trimMs", trimMs); put("hardJumps", hardJumps); put("slewEvents", slewEvents)
        put("decodeComplete", decodeComplete)
        put("decodeTotalFrames", decodeTotalFrames)
    }

    companion object {
        fun fromJson(o: JSONObject) = Diag(
            rttMs = o.optDouble("rtt"), rttJitterMs = o.optDouble("rttJit"), jitterMs = o.optDouble("jitter"),
            lossPct = o.optDouble("loss"), recovered = o.optLong("recovered"), late = o.optLong("late"),
            stale = o.optLong("stale"), bufferMs = o.optDouble("buffer"), driftPpm = o.optDouble("drift"),
            audioPath = o.optString("audioPath", "udp"), clockPath = o.optString("clockPath", "udp"),
            audioBufferSeconds = o.optDouble("audioBufferSeconds"),
            audioFillFramesPerSec = o.optDouble("audioFillFramesPerSec"),
            clockUncMs = o.optDouble("unc"), underruns = o.optLong("underruns"),
            syncErrUs = if (o.has("syncErrUs")) o.optDouble("syncErrUs") else o.optDouble("syncErr") * 1_000.0,
            clockModelStepP50Us = o.optDouble("clockModelStepP50Us"),
            clockModelStepP95Us = o.optDouble("clockModelStepP95Us"),
            clockModelStepMaxUs = o.optDouble("clockModelStepMaxUs"),
            latencyMs = o.optDouble("latency"), resent = o.optLong("resent"), clockReady = o.optBoolean("clockReady"),
            audioAgeMs = o.optLong("audioAge", -1), valid = true,
            pairTag = o.optString("pairTag"), sessionId = o.optInt("sessionId"), epoch = o.optInt("epoch"),
            trackTag = o.optString("trackTag"), playbackPhase = o.optString("playbackPhase"), playing = o.optBoolean("playing"),
            outputRmsDbfs = o.optDouble("outputRmsDbfs", -120.0), outputPeakDbfs = o.optDouble("outputPeakDbfs", -120.0),
            outputSilentFrames = o.optLong("outputSilentFrames"), outputFramesPerSecond = o.optDouble("outputFps"),
            outputTimestampValid = o.optBoolean("outputTimestampValid"), musicVolume = o.optInt("musicVolume", -1),
            musicVolumeMin = o.optInt("musicVolumeMin"), musicVolumeMax = o.optInt("musicVolumeMax"), musicMuted = o.optBoolean("musicMuted"),
            audioRoute = o.optString("audioRoute", "unknown"), audioFocus = o.optBoolean("audioFocus"),
            serverChannelRmsDbfs = o.optDouble("serverChannelRmsDbfs", -120.0), decodeLeadFrames = o.optLong("decodeLeadFrames", -1),
            decodeReadyFrames = o.optLong("decodeReadyFrames", -1), decodePositionFrame = o.optLong("decodePositionFrame", -1),
            tickP95Ms = o.optDouble("tickP95Ms"), heartbeatIntervalMs = o.optDouble("heartbeatIntervalMs"),
            tcpRxAgeMs = o.optLong("tcpRxAgeMs", -1), udpPacketsPerSec = o.optLong("udpPacketsPerSec"),
            sentAudioPacketsPerSec = o.optLong("sentAudioPacketsPerSec"), udpInvalid = o.optLong("udpInvalid"),
            udpOtherSession = o.optLong("udpOtherSession"), nacksSent = o.optLong("nacksSent"),
            offsetMs = o.optDouble("offsetMs"), trimMs = o.optDouble("trimMs"), hardJumps = o.optInt("hardJumps"),
            slewEvents = o.optLong("slewEvents"),
            decodeComplete = o.optBoolean("decodeComplete", true),
            decodeTotalFrames = o.optLong("decodeTotalFrames", -1),
        )
    }
}

data class CalibUi(
    val running: Boolean = false,
    val stage: String = "",
    val resultText: String = "",
    val resultOk: Boolean = true,
    val lastMeasuredMs: Double? = null,     // фактическое рассогласование L/R (акустика), + = Point позже
    val needsMicPermission: Boolean = false,
)

data class QueueTrackUi(val id: String, val title: String, val artist: String) {
    /** «Название - Исполнитель» (или только название, если исполнитель неизвестен). */
    val label: String get() = trackLabel(title, artist)
}

fun trackLabel(title: String, artist: String): String =
    if (artist.isBlank()) title else "$title - $artist"

/** Сохранённый плейлист (хранится внутри приложения, в filesDir/playlists). */
data class PlaylistInfo(val id: String, val name: String, val count: Int, val savedAt: Long)

data class SpatialDeveloperSnapshot(
    val realtimeFactor: Double,
    val cacheAheadMs: Double,
    val restarts: Long,
    val misses: Long,
    val sideMidDb: Double,
    val correlation: Double,
    val limiterDb: Double,
    val loudnessCompDb: Double,
)

data class UiState(
    val myId: String = "",
    val myName: String = "",
    val phase: Phase = Phase.IDLE,
    /** Локальный плеер без подключённой стереопары. */
    val soloMode: Boolean = false,
    val role: Role = Role.SERVER,
    val peerName: String = "",
    val peerPlatform: String = "",
    val nearby: List<Peer> = emptyList(),
    val remembered: PairRecord? = null,
    val invitingName: String = "",
    /** true — идёт восстановление знакомой пары (без подтверждения), false — новое приглашение. */
    val resuming: Boolean = false,
    /** Фоновое (без диалога) восстановление знакомой пары после запуска/возврата в приложение. */
    val autoResuming: Boolean = false,
    /** Второй телефон подтвердил, что показал приглашение (сообщение ringing). */
    val inviteRinging: Boolean = false,
    val incomingName: String = "",
    val linkOk: Boolean = false,
    // плеер
    val playing: Boolean = false,
    val durationMs: Long = 0,
    val title: String = "",
    val artist: String = "",
    val queueIndex: Int = -1,
    val queueTracks: List<QueueTrackUi> = emptyList(),
    /** Сохранённые плейлисты (только на Server). */
    val playlists: List<PlaylistInfo> = emptyList(),
    /** Текст полосы «Удалено … · Вернуть»; пусто — полосы нет. Приходит и на Point. */
    val undoText: String = "",
    val loadingTracks: Boolean = false,
    val loadingTrackId: String? = null,
    /** Процент подготовки выбранного длинного трека после того, как доступен стартовый сегмент. */
    val decodingProgress: Float? = null,
    /** Передача/приём файлов между двумя Android-телефонами. */
    val fileTransferText: String = "",
    /** Пара подключена, но Point давно не получает аудиопакеты. */
    val audioMissing: Boolean = false,
    /** Воспроизведение автоматически ждёт, пока декодер подготовит следующий сегмент. */
    val audioBuffering: Boolean = false,
    val queueExpanded: Boolean = false,
    val filePickerRequest: Int = 0,
    val mode: SourceMode = SourceMode.MUSIC,
    val volL: Float = 1f,
    val volR: Float = 1f,
    val master: Float = 0.96f,
    val balance: Float = 0.5f,
    val levelTrimServerDb: Float = 0f,
    val levelTrimPointDb: Float = 0f,
    val delayCalib: DelayCalib = DelayCalib(),
    val levelCalib: LevelCalib = LevelCalib(),
    val levelMeasureText: String = "",
    val gainLaw: Int = 2,
    val pointChannel: Int = Ch.R,
    val trimMs: Double = 0.0,
    // калибровка и диагностика
    val calib: CalibUi = CalibUi(),
    val serverDiag: Diag = Diag(),
    val pointDiag: Diag = Diag(),
    val syncProbeRunning: Boolean = false,
    val syncProbeResult: String = "",
    // уведомления
    val message: String = "",
    val messageId: Int = 0,
    /** Журнал событий подключения (новые — в конце), показывается в «Диагностике». */
    val connLog: List<String> = emptyList(),
    val telemetrySaving: Boolean = false,
    val telemetryFile: String? = null,
    val activeIncidents: List<String> = emptyList(),
    val debugUdpAudioStopped: Boolean = false,
    val debugPointOutputSilenced: Boolean = false,
    val multiWifiWarning: String = "",
    val pointAudioBufferSeconds: Double = 0.0,
    val audioPath: String = "udp",
    val clockPath: String = "udp",
    val eq: EqSettings = EqSettings(),
    val eqAvailable: Boolean = false,
    val eqExpanded: Boolean = false,
    val eqPreampDb: Float = 0f,
    val eqRouteLabel: String = "Динамик телефона",
    val eqSpectrumDb: List<Float> = emptyList(),
    val customEqPresets: List<EqPreset> = emptyList(),
    val eqCurveDb: List<Float> = List(256) { 0f },
    val spatial: SpatialSettings = SpatialSettings(),
    val spatialAvailable: Boolean = false,
    val freezeCount: Int = 0,
    val freezeDurationMs: Long = 0,
    val showStabilitySettings: Boolean = false,
    val showLanBlocked: Boolean = false,
    val lanBlockedPeerName: String = "",
    val lanBlockedDetails: String = "",
) {
    val myChannel: Int get() = if (role == Role.POINT) pointChannel else Ch.other(pointChannel)
    val inPair: Boolean get() = phase == Phase.PAIRED || phase == Phase.RECONNECTING
}
