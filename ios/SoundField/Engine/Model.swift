import Foundation
import Network

/// Телефон, найденный в сети (Bonjour).
struct Peer: Identifiable, Equatable {
    let id: String
    let name: String
    let platform: String
    let endpoint: NWEndpoint
    static func == (a: Peer, b: Peer) -> Bool { a.id == b.id && a.name == b.name }
}

enum Phase { case idle, inviting, incoming, paired, reconnecting }

/// Сохранённая пара (формат JSON совместим по смыслу с Android PairRecord).
struct PairRecord: Codable, Equatable {
    var key: String
    var peerId: String
    var peerName: String
    var peerPlatform: String
    var myRole: String            // "server" | "point"
    var pointCh: String = "R"
    var trimMs: Double = 0
    var volL: Double = 1
    var volR: Double = 1
    var master: Double = 0.8
    var role: Role { Role(rawValue: myRole) ?? .point }
}

struct Diag: Equatable {
    var rttMs = 0.0, rttJitterMs = 0.0, jitterMs = 0.0, lossPct = 0.0
    var recovered: Int64 = 0, late: Int64 = 0, stale: Int64 = 0
    var bufferMs = 0.0, driftPpm = 0.0, clockUncMs = 0.0
    var underruns: Int64 = 0
    var syncErrMs = 0.0, latencyMs = 0.0
    var resent: Int64 = 0
    var clockReady = false
    var valid = false

    func json(_ t: String) -> [String: Any] {
        ["t": t, "rtt": rttMs, "rttJit": rttJitterMs, "jitter": jitterMs, "loss": lossPct, "recovered": recovered,
         "late": late, "stale": stale, "buffer": bufferMs, "drift": driftPpm, "unc": clockUncMs, "underruns": underruns,
         "syncErr": syncErrMs, "latency": latencyMs, "resent": resent, "clockReady": clockReady]
    }

    static func from(_ o: [String: Any]) -> Diag {
        Diag(rttMs: J.d(o, "rtt"), rttJitterMs: J.d(o, "rttJit"), jitterMs: J.d(o, "jitter"), lossPct: J.d(o, "loss"),
             recovered: J.i64(o, "recovered"), late: J.i64(o, "late"), stale: J.i64(o, "stale"), bufferMs: J.d(o, "buffer"),
             driftPpm: J.d(o, "drift"), clockUncMs: J.d(o, "unc"), underruns: J.i64(o, "underruns"),
             syncErrMs: J.d(o, "syncErr"), latencyMs: J.d(o, "latency"), resent: J.i64(o, "resent"),
             clockReady: J.b(o, "clockReady"), valid: true)
    }
}

struct CalibUi: Equatable {
    var running = false
    var stage = ""
    var resultText = ""
    var resultOk = true
    var lastMeasuredMs: Double? = nil
    var needsMicPermission = false
}

struct QueueTrackUi: Identifiable, Equatable {
    let id: String
    let title: String
    let artist: String
}

struct UiState: Equatable {
    var myId = ""
    var myName = ""
    var phase: Phase = .idle
    var role: Role = .server
    var peerName = ""
    var peerPlatform = ""
    var nearby: [Peer] = []
    var remembered: PairRecord? = nil
    var invitingName = ""
    var incomingName = ""
    var linkOk = false
    var playing = false
    var durationMs: Int64 = 0
    var title = ""
    var artist = ""
    var queueIndex = -1
    var queueTracks: [QueueTrackUi] = []
    var loadingTracks = false
    var loadingTrackId: String? = nil
    var queueExpanded = false
    var filePickerRequest = 0
    var mode: SourceMode = .music
    var volL: Float = 1
    var volR: Float = 1
    var master: Float = 0.8
    var pointChannel = Ch.R
    var trimMs = 0.0
    var calib = CalibUi()
    var serverDiag = Diag()
    var pointDiag = Diag()
    var message = ""
    var messageId = 0

    var myChannel: Int { role == .point ? pointChannel : Ch.other(pointChannel) }
    var inPair: Bool { phase == .paired || phase == .reconnecting }
}

/// Помощники чтения JSONSerialization (числа приходят как NSNumber).
enum J {
    static func d(_ o: [String: Any], _ k: String, _ def: Double = 0) -> Double { (o[k] as? NSNumber)?.doubleValue ?? def }
    static func i64(_ o: [String: Any], _ k: String, _ def: Int64 = 0) -> Int64 {
        if let n = o[k] as? NSNumber { return n.int64Value }
        if let s = o[k] as? String, let v = Int64(s) { return v }
        return def
    }
    static func b(_ o: [String: Any], _ k: String, _ def: Bool = false) -> Bool { (o[k] as? NSNumber)?.boolValue ?? def }
    static func s(_ o: [String: Any], _ k: String, _ def: String = "") -> String { o[k] as? String ?? def }
}
