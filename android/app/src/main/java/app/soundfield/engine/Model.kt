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
) {
    fun toJson(): String = JSONObject().apply {
        put("key", key); put("peerId", peerId); put("peerName", peerName); put("peerPlatform", peerPlatform)
        put("myRole", myRole.wire); put("pointCh", Ch.name(pointChannel)); put("trimMs", trimMs)
        put("volL", volL.toDouble()); put("volR", volR.toDouble()); put("master", master.toDouble())
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
    val valid: Boolean = false,
) {
    fun toJson(t: String): JSONObject = JSONObject().apply {
        put("t", t); put("rtt", rttMs); put("rttJit", rttJitterMs); put("jitter", jitterMs); put("loss", lossPct)
        put("recovered", recovered); put("late", late); put("stale", stale); put("buffer", bufferMs)
        put("drift", driftPpm); put("unc", clockUncMs); put("underruns", underruns); put("syncErr", syncErrMs)
        put("latency", latencyMs); put("resent", resent); put("clockReady", clockReady)
    }

    companion object {
        fun fromJson(o: JSONObject) = Diag(
            o.optDouble("rtt"), o.optDouble("rttJit"), o.optDouble("jitter"), o.optDouble("loss"),
            o.optLong("recovered"), o.optLong("late"), o.optLong("stale"), o.optDouble("buffer"),
            o.optDouble("drift"), o.optDouble("unc"), o.optLong("underruns"), o.optDouble("syncErr"),
            o.optDouble("latency"), o.optLong("resent"), o.optBoolean("clockReady"), true,
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
    val incomingName: String = "",
    val linkOk: Boolean = false,
    // плеер
    val playing: Boolean = false,
    val durationMs: Long = 0,
    val title: String = "",
    val artist: String = "",
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
) {
    val myChannel: Int get() = if (role == Role.POINT) pointChannel else Ch.other(pointChannel)
    val inPair: Boolean get() = phase == Phase.PAIRED || phase == Phase.RECONNECTING
}
