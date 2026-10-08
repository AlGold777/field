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
    var master: Double = 0.96
    var balance: Double? = nil
    var levelTrimServerDb: Double = 0
    var levelTrimPointDb: Double = 0
    var volTaper: Int = 2
    var levelTrimVersion: Int = 1
    var delayCalib = DelayCalib()
    var levelCalib = LevelCalib()
    var levelMeasureText = ""
    /// Последний известный адрес второго телефона и порт его TCP-сервера — быстрый путь без Bonjour.
    /// Optional: старые сохранённые пары без этих полей продолжают читаться.
    var lastHost: String? = nil
    var lastPort: Int? = nil
    var role: Role { Role(rawValue: myRole) ?? .point }

    init(key: String, peerId: String, peerName: String, peerPlatform: String, myRole: String,
         pointCh: String = "R", trimMs: Double = 0, volL: Double = 1, volR: Double = 1,
         master: Double = 0.96, balance: Double? = nil, levelTrimServerDb: Double = 0,
         levelTrimPointDb: Double = 0, volTaper: Int = 2, levelTrimVersion: Int = 1,
         delayCalib: DelayCalib = DelayCalib(), levelCalib: LevelCalib = LevelCalib(),
         lastHost: String? = nil, lastPort: Int? = nil) {
        self.key = key; self.peerId = peerId; self.peerName = peerName; self.peerPlatform = peerPlatform
        self.myRole = myRole; self.pointCh = pointCh; self.trimMs = trimMs; self.volL = volL; self.volR = volR
        self.master = master; self.balance = balance; self.levelTrimServerDb = levelTrimServerDb
        self.levelTrimPointDb = levelTrimPointDb; self.volTaper = volTaper; self.levelTrimVersion = levelTrimVersion
        self.delayCalib = delayCalib; self.levelCalib = levelCalib; self.lastHost = lastHost; self.lastPort = lastPort
    }

    enum CodingKeys: String, CodingKey {
        case key, peerId, peerName, peerPlatform, myRole, pointCh, trimMs, volL, volR, master, balance
        case lastHost, lastPort, levelTrimServerDb, levelTrimPointDb, volTaper, levelTrimVersion, delayCalib, levelCalib
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        key = try c.decode(String.self, forKey: .key); peerId = try c.decode(String.self, forKey: .peerId)
        peerName = try c.decode(String.self, forKey: .peerName); peerPlatform = try c.decodeIfPresent(String.self, forKey: .peerPlatform) ?? "ios"
        myRole = try c.decode(String.self, forKey: .myRole); pointCh = try c.decodeIfPresent(String.self, forKey: .pointCh) ?? "R"
        trimMs = try c.decodeIfPresent(Double.self, forKey: .trimMs) ?? 0
        volL = try c.decodeIfPresent(Double.self, forKey: .volL) ?? 1; volR = try c.decodeIfPresent(Double.self, forKey: .volR) ?? 1
        master = try c.decodeIfPresent(Double.self, forKey: .master) ?? 0.8; balance = try c.decodeIfPresent(Double.self, forKey: .balance)
        lastHost = try c.decodeIfPresent(String.self, forKey: .lastHost); lastPort = try c.decodeIfPresent(Int.self, forKey: .lastPort)
        levelTrimServerDb = try c.decodeIfPresent(Double.self, forKey: .levelTrimServerDb) ?? 0
        levelTrimPointDb = try c.decodeIfPresent(Double.self, forKey: .levelTrimPointDb) ?? 0
        volTaper = try c.decodeIfPresent(Int.self, forKey: .volTaper) ?? 0
        levelTrimVersion = try c.decodeIfPresent(Int.self, forKey: .levelTrimVersion) ?? 0
        delayCalib = try c.decodeIfPresent(DelayCalib.self, forKey: .delayCalib) ?? DelayCalib()
        levelCalib = try c.decodeIfPresent(LevelCalib.self, forKey: .levelCalib) ?? LevelCalib()
    }
}

struct DelayCalib: Codable, Equatable {
    var state = "none"
    var atMs: Int64 = 0
    var spreadMs = 0.0
}

struct LevelCalib: Codable, Equatable {
    var state = "none"
    var atMs: Int64 = 0
    var db = 0.0
}

struct Diag: Equatable {
    var rttMs = 0.0, rttJitterMs = 0.0, jitterMs = 0.0, lossPct = 0.0
    var recovered: Int64 = 0, late: Int64 = 0, stale: Int64 = 0
    var bufferMs = 0.0, driftPpm = 0.0, clockUncMs = 0.0
    var underruns: Int64 = 0
    var syncErrMs = 0.0, latencyMs = 0.0
    var resent: Int64 = 0
    var clockReady = false
    /// Milliseconds since Point accepted its last valid audio packet; -1 means no packets yet.
    var audioAgeMs: Int64 = -1
    var valid = false

    func json(_ t: String) -> [String: Any] {
        ["t": t, "rtt": rttMs, "rttJit": rttJitterMs, "jitter": jitterMs, "loss": lossPct, "recovered": recovered,
         "late": late, "stale": stale, "buffer": bufferMs, "drift": driftPpm, "unc": clockUncMs, "underruns": underruns,
         "syncErr": syncErrMs, "latency": latencyMs, "resent": resent, "clockReady": clockReady, "audioAge": audioAgeMs]
    }

    static func from(_ o: [String: Any]) -> Diag {
        Diag(rttMs: J.d(o, "rtt"), rttJitterMs: J.d(o, "rttJit"), jitterMs: J.d(o, "jitter"), lossPct: J.d(o, "loss"),
             recovered: J.i64(o, "recovered"), late: J.i64(o, "late"), stale: J.i64(o, "stale"), bufferMs: J.d(o, "buffer"),
             driftPpm: J.d(o, "drift"), clockUncMs: J.d(o, "unc"), underruns: J.i64(o, "underruns"),
             syncErrMs: J.d(o, "syncErr"), latencyMs: J.d(o, "latency"), resent: J.i64(o, "resent"),
             clockReady: J.b(o, "clockReady"), audioAgeMs: J.i64(o, "audioAge", -1), valid: true)
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

struct PlaylistInfo: Identifiable, Equatable { let id: String; let name: String; let count: Int }

struct UiState: Equatable {
    var myId = ""
    var myName = ""
    var phase: Phase = .idle
    var soloMode = true
    var role: Role = .server
    var peerName = ""
    var peerPlatform = ""
    var nearby: [Peer] = []
    var remembered: PairRecord? = nil
    var invitingName = ""
    var resuming = false          // восстановление знакомой пары, а не новое приглашение
    var autoResuming = false      // фоновое восстановление знакомой пары (без диалога)
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
    var decodingProgress: Double? = nil
    var audioMissing = false
    var fileTransferAvailable = false
    var fileTransferText = ""
    var audioBuffering = false
    var queueExpanded = false
    var undoText = ""
    var playlists: [PlaylistInfo] = []
    var filePickerRequest = 0
    var mode: SourceMode = .music
    var volL: Float = 1
    var volR: Float = 1
    var master: Float = 0.96
    var balance: Float = 0.5
    var levelTrimServerDb: Float = 0
    var levelTrimPointDb: Float = 0
    var delayCalib = DelayCalib()
    var levelCalib = LevelCalib()
    var gainLaw = 2
    var pointChannel = Ch.R
    var trimMs = 0.0
    var calib = CalibUi()
    var serverDiag = Diag()
    var pointDiag = Diag()
    var message = ""
    var messageId = 0
    var connLog: [String] = []    // журнал подключения для «Диагностики»

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
