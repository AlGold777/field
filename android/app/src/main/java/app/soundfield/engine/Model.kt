package app.soundfield.engine

import app.soundfield.core.Ch
import app.soundfield.core.Role
import app.soundfield.core.SourceMode
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
)

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
    val master: Float = 0.8f,
    /** Последний известный адрес второго телефона и порт его TCP-сервера — быстрый путь без mDNS. */
    val lastHost: String? = null,
    val lastPort: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("key", key); put("peerId", peerId); put("peerName", peerName); put("peerPlatform", peerPlatform)
        put("myRole", myRole.wire); put("pointCh", Ch.name(pointChannel)); put("trimMs", trimMs)
        put("volL", volL.toDouble()); put("volR", volR.toDouble()); put("master", master.toDouble())
        lastHost?.let { put("lastHost", it) }; put("lastPort", lastPort)
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
                )
            }
        } catch (e: Exception) { null }
    }
}

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
    val driftPpm: Double = 0.0,
    val clockUncMs: Double = 0.0,
    val underruns: Long = 0,
    val syncErrMs: Double = 0.0,
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
    fun toJson(t: String): JSONObject = JSONObject().apply {
        put("t", t); put("rtt", rttMs); put("rttJit", rttJitterMs); put("jitter", jitterMs); put("loss", lossPct)
        put("recovered", recovered); put("late", late); put("stale", stale); put("buffer", bufferMs)
        put("drift", driftPpm); put("unc", clockUncMs); put("underruns", underruns); put("syncErr", syncErrMs)
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
            clockUncMs = o.optDouble("unc"), underruns = o.optLong("underruns"), syncErrMs = o.optDouble("syncErr"),
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

data class UiState(
    val myId: String = "",
    val myName: String = "",
    val phase: Phase = Phase.IDLE,
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
    val master: Float = 0.8f,
    val pointChannel: Int = Ch.R,
    val trimMs: Double = 0.0,
    // калибровка и диагностика
    val calib: CalibUi = CalibUi(),
    val serverDiag: Diag = Diag(),
    val pointDiag: Diag = Diag(),
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
) {
    val myChannel: Int get() = if (role == Role.POINT) pointChannel else Ch.other(pointChannel)
    val inPair: Boolean get() = phase == Phase.PAIRED || phase == Phase.RECONNECTING
}
