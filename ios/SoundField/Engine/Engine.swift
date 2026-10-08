import Foundation
import Combine
import Network
import UIKit
import AVFoundation
import UniformTypeIdentifiers

/// Модель для SwiftUI (главный поток).
final class AppModel: ObservableObject {
    @Published var ui = UiState()
}

/// Сердце приложения — порт Android Engine.kt с тем же поведением и протоколом.
///
/// Потоки: q (serial) — всё состояние сессии; sf-udp — приём UDP; sf-tick — каждые 5 мс отправка аудио (Server)
/// или PING/NACK (Point); аудиопоток — AudioOutput → ServerRenderer/PointRenderer.
final class Engine {
    private struct QueueEntry: Codable {
        let id: String
        let fileURL: URL
        let title: String
        let artist: String
    }

    static let shared = Engine()

    let model = AppModel()
    private let q = DispatchQueue(label: "sf.engine", qos: .userInitiated)
    private let queueIo = DispatchQueue(label: "sf.queue.io", qos: .userInitiated)
    private let defaults = UserDefaults.standard

    let myId: String
    private(set) var myName: String
    private var state = UiState()

    // аудио
    let music: PcmSource
    private var queue: [QueueEntry] = []
    private var queueIndex = -1
    private var hasImportedQueue = false
    private var pendingTrackId: String?
    private var pendingShouldPlay = false
    private var trackLoadGeneration = 0
    private var currentQueueSource: AudioSource?
    private var soloMode = false
    private var soloResumeFrame: Int64 = 0
    private var soloHandoff: (frame: Int64, playing: Bool)?
    private var queueRevision = 0
    private var incomingQueueRevision = -1
    private var incomingQueueParts = 0
    private var incomingQueue: [Int: [QueueTrackUi]] = [:]
    private let audio = AudioOutput()
    private let mic = MicRecorder()

    // сеть
    private var discovery: Discovery!
    private let udpBox = Locked<UdpSocket?>(nil)
    private var udp: UdpSocket! { udpBox.value }
    private var pathMonitor: NWPathMonitor?
    private var lastPathSignature: String?

    // сессия (только на q)
    private let phaseBox = Locked<Phase>(.idle)
    private var phase: Phase { get { phaseBox.value } set { phaseBox.value = newValue } }
    private let roleBox = Locked<Role>(.server)
    private var role: Role { get { roleBox.value } set { roleBox.value = newValue } }
    private var link: ControlLink?
    private var pending: ControlLink?
    private var pendingPeer: Peer?
    private var pendingKey = ""
    private var pendingTimeout: DispatchWorkItem?
    private var pendingInvitePort = 0
    private var peerId = "", peerName = "", peerPlatform = ""
    private let sessionBox = Locked<Int32>(0)
    private var sessionId: Int32 { get { sessionBox.value } set { sessionBox.value = newValue } }
    private let lastAudioRxAtNs = Locked<Int64>(0)
    private let sentAudioPackets = Locked<Int64>(0)
    private var pairedAtNs: Int64 = 0
    private var lastAudioSessionResetNs: Int64 = 0
    private var record: PairRecord?
    private var reconnectTask: DispatchWorkItem?
    private var lastBrowseRestart = Date.distantPast
    private var manualResumeDeadline = Date.distantPast
    /// Идёт восстановление сохранённой пары (вручную или автоматически при запуске/возврате в приложение).
    private var manualResumeActive = false
    private var manualResumeAuto = false
    private var resumeAttempt = 0
    private var reconnectSince = Date()
    /// Порт TCP-сервера второго телефона из invite/resume/accept — для прямого переподключения.
    private var peerTcpPortHint = 0
    /// Пара восстанавливается сама, пока её явно не разорвали.
    private var autoResume = UserDefaults.standard.bool(forKey: "sf.autoResume")
    /// До какого момента в состоянии idle тихо пробуем восстановить знакомую пару (nil — не пробуем).
    private var autoResumeUntil: Date?
    private var lastAutoAttempt = Date.distantPast
    private var connLog: [String] = []
    private let logTime: DateFormatter = { let f = DateFormatter(); f.dateFormat = "HH:mm:ss"; f.locale = Locale(identifier: "en_US_POSIX"); return f }()

    // Server
    private let playBox: Locked<ServerPlayState>
    private var epoch: Int32 = 0
    private var mode: SourceMode = .music
    private var source: AudioSource
    private var eqSettings = EqSettings()
    private var spatialSettings = SpatialSettings()
    private var processedSource: AudioSource?
    private var processedInner: AudioSource?
    private var processedSpatial: SpatialSettings?
    private var savedFrame: Int64 = 0
    private var savedPlaying = false
    private var volL: Float = 1, volR: Float = 1, master: Float = 0.96
    private var balance: Float = 0.5
    private var levelTrimServerDb: Float = 0
    private var levelTrimPointDb: Float = 0
    private var delayCalib = DelayCalib()
    private var levelCalib = LevelCalib()
    private var peerGainLawReady = false
    private var didAcknowledgeGainLaw = false
    private var levelMatchStartServerDb: Float = 0
    private var levelMatchStartPointDb: Float = 0
    private var micReferenceDeltaOne: Double?
    private var micReferenceDeltaTwo: Double?
    private var micSensitivityDeltaDb: Double?
    private var levelMeasureText = ""
    private var levelMeasureSavedServerDb: Float = 0
    private var levelMeasureSavedPointDb: Float = 0
    private var levelCaptureWaiters: [String: ([String: Any]) -> Void] = [:]
    private var lastGainCommandNs: [String: UInt64] = [:]
    private var pendingGainCommands: [String: () -> Void] = [:]
    private var scheduledGainCommands: [String: DispatchWorkItem] = [:]
    private let pointChBox = Locked<Int>(Ch.R)
    private var pointChannel: Int { get { pointChBox.value } set { pointChBox.value = newValue } }
    private var trimNs: Int64 = 0
    private let pointUdpBox = Locked<UdpSocket.Addr?>(nil)
    private var serverRenderer: ServerRenderer!
    private var calibRunning = false
    private var pointClockReady = false
    private var resent: Int64 = 0
    private let sendLock = NSLock()
    private var sendSeq: Int32 = 0
    private var sentEpoch: Int32 = -1
    private var nextFrame: Int64 = 0
    private var cacheSeq = [Int32](repeating: -1, count: 1024)
    private var cacheEpoch = [Int32](repeating: 0, count: 1024)
    private var cacheStart = [Int64](repeating: 0, count: 1024)

    // Point
    private let clock = ClockSync()
    private let rx: PointReceiver
    private let paramsBox = Locked(PointParams(gain: 0.8, trimNs: 0, totalFrames: 0))
    private var pointRenderer: PointRenderer!
    private let serverUdpBox = Locked<UdpSocket.Addr?>(nil)
    private var pingSeq: Int32 = 0
    private var pingBurst = 0
    private var lastPing: Int64 = 0
    private var lastRecv: Int64 = 0, lastLost: Int64 = 0

    private init() {
        if let id = defaults.string(forKey: "sf.id") { myId = id } else {
            myId = String(format: "%08x", UInt32.random(in: 0...UInt32.max)); defaults.set(myId, forKey: "sf.id")
        }
        myName = defaults.string(forKey: "sf.name") ?? UIDevice.current.name
        if let d = defaults.data(forKey: "sf.pair") { record = try? JSONDecoder().decode(PairRecord.self, from: d) }
        let url = Bundle.main.url(forResource: "test_track", withExtension: "wav")!
        let pcm = (try? Wav.decode(Data(contentsOf: url))) ?? []
        music = PcmSource(pcm: pcm, title: "Тестовый трек SoundField", artist: "Стерео-демо · L: аккорды и бас · R: мелодия")
        source = music
        playBox = Locked(ServerPlayState(timeline: .none, source: music, channel: Ch.L, gain: 0.8))
        rx = PointReceiver(clock: clock)
        serverRenderer = ServerRenderer { [unowned self] in self.playBox.value }
        pointRenderer = PointRenderer(rx: rx, clock: clock) { [unowned self] in self.paramsBox.value }
    }

    // ================================================================== запуск
    func boot() {
        q.async { [self] in
            refreshPlaylists()
            makeUdp()
            discovery = Discovery(queue: q, myId: myId)
            discovery.onLog = { [weak self] text in self?.log(text) }
            discovery.onPeers = { [weak self] peers in self?.ui { $0.nearby = peers } }
            discovery.onIncoming = { [weak self] c in self?.onIncoming(c) }
            discovery.advertise(name: myName)
            discovery.startBrowse()
            audio.onInterruption = { [weak self] began in
                if began { self?.q.async { self?.onInterrupted("Звук прерван (звонок или другое приложение) — пара на паузе") } }
            }
            audio.onRouteWarning = { [weak self] text in self?.q.async { self?.toast(text) } }
            audio.onRouteChanged = { [weak self] in self?.q.async { self?.handleAudioRouteChanged() } }
            let t = Thread { [weak self] in self?.tickLoop() }
            t.name = "sf-tick"; t.qualityOfService = .userInteractive; t.start()
            schedule(every: 1.0) { [weak self] in self?.heartbeat() }
            schedule(every: 0.1) { [weak self] in self?.serverTick() }
            ui { s in
                s.myId = myId; s.myName = myName; s.remembered = record
                s.durationMs = music.totalFrames * 1000 / Int64(SF.sampleRate)
                s.title = music.title; s.artist = music.artist
                s.queueExpanded = defaults.bool(forKey: "sf.queueExpanded")
            }
            DispatchQueue.main.async { [weak self] in
                let nc = NotificationCenter.default
                nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { _ in
                    self?.q.async { self?.onForeground() }
                }
                nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
                    self?.q.async { self?.onForeground() }
                }
            }
            let mon = NWPathMonitor(requiredInterfaceType: .wifi)
            mon.pathUpdateHandler = { [weak self] path in
                guard let self else { return }
                let sig = "\(path.status)|" + path.availableInterfaces.map { $0.name }.joined(separator: ",")
                let prev = self.lastPathSignature
                self.lastPathSignature = sig
                if prev != nil && prev != sig { self.onNetworkChanged(path.status == .satisfied) }
            }
            mon.start(queue: q)
            pathMonitor = mon
            log("Запуск: UDP \(udpBox.value?.port ?? 0)")
            startSoloPlayback()
            if autoResume && record != nil { startAutoResume() }
        }
    }

    private func makeUdp() {
        udpBox.value?.close()
        guard let s = UdpSocket() else { log("Не удалось открыть UDP-сокет"); udpBox.value = nil; return }
        s.onPacket = { [weak self] p, from, now in self?.onUdp(p, from, now) }
        udpBox.value = s
    }

    /// UDP-сокет мог быть закрыт системой, пока приложение спало. Пересоздаём; Server сообщает Point новый порт.
    private func ensureUdp() {
        guard udpBox.value?.broken ?? true else { return }
        log("UDP-сокет недоступен — пересоздаём")
        makeUdp()
        guard udpBox.value != nil else { return }
        if role == .server, phase == .paired, let l = link {
            sendSession(l)
        }
    }

    private func sendSession(_ target: ControlLink? = nil) {
        let l = target ?? link
        l?.send(["t": "session", "session": Int(sessionId), "udpPort": Int(udp?.port ?? 0), "name": myName, "fileToken": fileToken, "caps": ["fileTransferV1", "eq", "spatialV1"]])
    }

    /// Rebuild the UDP media session without dropping the TCP pair. Point responds with a fresh PING burst.
    private func renewAudioSession(_ reason: String) {
        guard phase == .paired, role == .server else { return }
        let now = MonoClock.nowNs()
        sessionId = Int32.random(in: 1...Int32.max)
        pointUdpBox.value = nil
        pointClockReady = false
        lastAudioSessionResetNs = now
        lastAudioRxAtNs.value = 0
        log("UDP-аудиосессия пересоздана: \(reason); session=\(sessionId), отправлено=\(sentAudioPackets.value), аудио Point=\(state.pointDiag.audioAgeMs)мс")
        sendSession()
        sendTimeline()
    }

    /// Приложение снова на экране: проверяем сокеты, аудио и связь — не ждём таймаутов.
    private func onForeground() {
        discovery.ensureListening()
        ensureUdp()
        if state.inPair || soloMode { DispatchQueue.main.async { self.audio.ensureRunning() } }
        switch phase {
        case .idle:
            if !discovery.browsing { discovery.startBrowse() }
            if autoResume && record != nil && autoResumeUntil == nil { startAutoResume() }
        case .reconnecting:
            if pending == nil { log("Возврат в приложение — переподключаемся сразу"); scheduleReconnect(0) }
        case .paired:
            if let l = link, Date().timeIntervalSince(l.lastRx) > 4 {
                log("Связь молчит \(Int(Date().timeIntervalSince(l.lastRx))) с — переподключаемся"); l.close()
            }
        default: break
        }
    }

    private func onNetworkChanged(_ up: Bool) {
        log(up ? "Сеть Wi‑Fi изменилась" : "Wi‑Fi недоступен")
        guard up else { return }
        discovery.ensureListening()
        ensureUdp()
        if discovery.browsing { lastBrowseRestart = Date(); discovery.restartBrowse() }
        if phase == .reconnecting && pending == nil { scheduleReconnect(0.2) }
    }

    private func log(_ text: String) {
        NSLog("SoundField: \(text)")
        connLog.append("\(logTime.string(from: Date()))  \(text)")
        if connLog.count > 60 { connLog.removeFirst(connLog.count - 60) }
        let snapshot = connLog
        ui { $0.connLog = snapshot }
    }

    /// Тихое восстановление: экран обычный (можно создать новую пару), в фоне раз в 3 с — попытка resume.
    /// Модальный диалог не используется: он блокировал новые приглашения и повторялся при каждом возврате.
    private func startAutoResume() {
        guard autoResume, let rec = record, phase == .idle else { return }
        autoResumeUntil = Date().addingTimeInterval(30)
        lastAutoAttempt = .distantPast; resumeAttempt = 0
        if !discovery.browsing { discovery.startBrowse() }
        log("Фоновое восстановление пары с «\(rec.peerName)»")
        ui { $0.autoResuming = true }
        autoResumeTick()
    }

    private func stopAutoResume() {
        autoResumeUntil = nil
        dropAutoPending()
        ui { $0.autoResuming = false }
    }

    private func autoResumeTick() {
        guard let until = autoResumeUntil else { return }
        guard autoResume, let rec = record, Date() < until else {
            if phase == .idle { log("Фоновое восстановление: «\(record?.peerName ?? "")» не ответил") }
            autoResumeUntil = nil; ui { $0.autoResuming = false }; return
        }
        guard phase == .idle, pending == nil, Date().timeIntervalSince(lastAutoAttempt) >= 3,
              let p = resumeTarget(rec) else { return }
        lastAutoAttempt = Date()
        connectOutgoing(p, tag: "resume-auto", first: resumeJson(rec), timeout: 3)
    }

    private func dropAutoPending() {
        guard let p = pending, p.tag == "resume-auto" else { return }
        pending = nil; p.tag = "dead"; p.close()
    }

    private func setAutoResume(_ on: Bool) {
        guard autoResume != on else { return }
        autoResume = on; defaults.set(on, forKey: "sf.autoResume")
    }

    private var timers: [DispatchSourceTimer] = []
    private func schedule(every sec: Double, _ block: @escaping () -> Void) {
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + sec, repeating: sec)
        t.setEventHandler(handler: block)
        t.resume()
        timers.append(t)
    }

    @discardableResult
    private func later(_ sec: Double, _ block: @escaping () -> Void) -> DispatchWorkItem {
        let w = DispatchWorkItem(block: block)
        q.asyncAfter(deadline: .now() + sec, execute: w)
        return w
    }

    private func ui(_ f: (inout UiState) -> Void) {
        f(&state)
        let s = state
        DispatchQueue.main.async { [model] in if model.ui != s { model.ui = s } }
    }

    private var msgCounter = 0
    private func toast(_ text: String) { msgCounter += 1; let id = msgCounter; ui { $0.message = text; $0.messageId = id } }

    private static func hex(_ bytes: Int) -> String {
        (0..<bytes).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }

    // ================================================================== публичный API (главный поток)
    func invite(_ p: Peer) { q.async { self.doInvite(p) } }
    func cancelInvite() { q.async { self.doCancelPending() } }
    func acceptIncoming() { q.async { self.doAccept() } }
    func rejectIncoming() { q.async { self.doReject() } }
    func resumeRemembered() { q.async { self.doManualResume() } }
    func forgetPair() { q.async { self.record = nil; self.setAutoResume(false); self.stopAutoResume(); self.defaults.removeObject(forKey: "sf.pair"); self.ui { $0.remembered = nil } } }
    func disconnect() { q.async { self.setAutoResume(false); self.goIdle(sendBye: true, reason: "Пара разорвана") } }
    func rename(_ name: String) {
        q.async {
            let n = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
            self.myName = n.isEmpty ? UIDevice.current.name : n
            self.defaults.set(self.myName, forKey: "sf.name")
            self.discovery.advertise(name: self.myName)
            self.ui { $0.myName = self.myName }
            self.link?.send(["t": "name", "name": self.myName])
        }
    }

    func toggleSoloMode() {
        q.async {
            if self.soloMode { self.doManualResume() }
            else if self.phase == .paired || self.phase == .reconnecting {
                self.setAutoResume(false); self.stopAutoResume()
                self.goIdle(sendBye: true, reason: "Solo · локальный плеер")
            }
        }
    }

    private func startSoloPlayback(frame: Int64 = 0, playing: Bool = false) {
        soloMode = true; role = .server; phase = .idle
        mode = .music
        source = queue.indices.contains(queueIndex) ? currentQueueSource ?? music : music
        if defaults.object(forKey: "sf.soloMaster") != nil { master = defaults.float(forKey: "sf.soloMaster") }
        balance = 0.5
        loadSoundProfile()
        audio.source = serverRenderer
        ui { $0.soloMode = true; $0.role = .server; $0.phase = .idle }
        newEpoch(playing, min(max(0, frame), source.totalFrames), MonoClock.nowNs())
        publishQueue()
        DispatchQueue.main.async { self.audio.start() }
    }

    func togglePlay() { cmd("toggle") }
    func play() { cmd("play") }
    func pause() { cmd("pause") }
    func seekMs(_ ms: Int64) { cmd("seek", ["ms": ms]) }
    func skipMs(_ d: Int64) { cmd("skip", ["ms": d]) }
    func swapChannels() { cmd("swap") }
    func setTrimMs(_ ms: Double) { cmd("trim", ["ms": ms, "reset": false]) }
    func resetTrim() { cmd("trim", ["ms": 0, "reset": true]) }
    func calibrate() { cmd("calibrate") }
    func measure() { cmd("measure") }
    func setLevelTrim(_ device: String, _ db: Float) { cmd("levelTrim", ["dev": device, "db": Double(db)]) }
    func setLevelOffsetDb(_ db: Float, finished: Bool = false) {
        sendGainCommand("levelOffset", finished: finished) {
            self.cmd("levelOffset", ["db": Double(db), "finished": finished])
        }
    }
    func resetLevelTrims() { cmd("levelReset") }
    func startLevelMatch() { cmd("levelStart") }
    func stopLevelMatch(commit: Bool) { cmd("levelStop", ["commit": commit]) }
    func startCenterCheck() { cmd("centerStart") }
    func centerCheckAnswer(_ answer: String) { cmd("centerAnswer", ["answer": answer]) }
    func measureLevels() { cmd("levelMeasure") }
    func measureMicReference(_ position: Int) { cmd("levelMicReference", ["position": min(2, max(1, position))]) }
    func metronome(_ on: Bool) { cmd("metronome", ["on": on]) }
    func channelTest() { cmd("lrtest") }

    private func handleAudioRouteChanged() {
        if soloMode { loadSoundProfile(); rebuildPlayState(); publishState(); return }
        guard state.inPair else { return }
        if role == .point { cmd("routeChanged"); return }
        resetCalibrationsForRoute()
    }

    private func resetCalibrationsForRoute() {
        trimNs = 0; delayCalib = DelayCalib(); levelTrimServerDb = 0; levelTrimPointDb = 0; levelCalib = LevelCalib()
        rebuildPlayState(); saveServerParams(); publishState()
        NSLog("SoundField telemetry route_change_reset role=%@", role.rawValue)
        toast("Аудиомаршрут изменился: повторите калибровку задержки и уровня")
    }

    private var levelStageReady: Bool { delayCalib.state != "none" && peerGainLawReady }
    private func blockCalibrationIfNeeded(_ what: String) -> Bool {
        guard !levelStageReady else { return false }
        NSLog("SoundField telemetry calib_gate_blocked what=%@", what)
        toast(levelGateMessage)
        return true
    }
    private var levelGateMessage: String {
        delayCalib.state == "none"
            ? "Сначала выполните калибровку задержки: пока телефоны звучат с разным запаздыванием, звук тянет к одному из них, и уровнем это не исправить"
            : "Обновите второй телефон для калибровки уровня"
    }

    private func normalizeLevelTrims() {
        let peak = max(levelTrimServerDb, levelTrimPointDb)
        levelTrimServerDb = min(0, max(-12, levelTrimServerDb - peak))
        levelTrimPointDb = min(0, max(-12, levelTrimPointDb - peak))
    }

    private func setDeviceLevelTrim(_ device: String, _ db: Float) {
        let value = (min(0, max(-12, db)) * 2).rounded() / 2
        switch device { case "server": levelTrimServerDb = value; case "point": levelTrimPointDb = value; default: return }
        normalizeLevelTrims()
        levelCalib = LevelCalib(state: "manual", atMs: Int64(Date().timeIntervalSince1970 * 1000),
                                db: Double(abs(levelTrimServerDb - levelTrimPointDb)))
    }

    private func applyLevelOffset(_ db: Float) {
        let value = (min(12, max(-12, db)) * 2).rounded() / 2
        if value < 0 {
            if serverChannel == Ch.L { levelTrimServerDb = value; levelTrimPointDb = 0 }
            else { levelTrimPointDb = value; levelTrimServerDb = 0 }
        } else if value > 0 {
            if serverChannel == Ch.R { levelTrimServerDb = -value; levelTrimPointDb = 0 }
            else { levelTrimPointDb = -value; levelTrimServerDb = 0 }
        } else { levelTrimServerDb = 0; levelTrimPointDb = 0 }
    }

    private func stopLevelMatch(_ commit: Bool) {
        guard mode == .level else { return }
        if commit {
            let offset = serverChannel == Ch.L ? levelTrimServerDb - levelTrimPointDb : levelTrimPointDb - levelTrimServerDb
            levelCalib = LevelCalib(state: "ear", atMs: Int64(Date().timeIntervalSince1970 * 1000), db: Double(offset))
            NSLog("SoundField telemetry level_trim_set dev=pair db=%.1f source=ear", offset)
        } else { levelTrimServerDb = levelMatchStartServerDb; levelTrimPointDb = levelMatchStartPointDb }
        saveServerParams(); restoreMusic(savedPlaying); publishState()
    }

    private func levelResultObject(_ result: LevelAnalyzer.Result) -> [String: Any] {
        func boxed(_ values: [Double?]) -> [Any] { values.map { $0.map { $0 as Any } ?? NSNull() } }
        return ["levels": boxed(result.levelsDb), "snr": boxed(result.snrDb), "valid": result.validBands,
                "peak": result.peakDbfs.isFinite ? result.peakDbfs : -120]
    }

    private func readLevelResult(_ o: [String: Any]) -> LevelAnalyzer.Result {
        func values(_ key: String) -> [Double?] {
            (o[key] as? [Any] ?? []).map { ($0 as? NSNumber)?.doubleValue }
        }
        return LevelAnalyzer.Result(levelsDb: values("levels"), snrDb: values("snr"),
                                    validBands: Int(J.i64(o, "valid")), peakDbfs: J.d(o, "peak", .infinity))
    }

    private func analyzeLevelSweeps(_ samples: [Float], sampleRate: Int, recordingStartServerNs: Int64,
                                    anchorServerNs: Int64, pointSpeaker: Bool, trimNsForPoint: Int64 = 0) -> [LevelAnalyzer.Result] {
        (0..<LevelMeasureSource.repeats).map { repeatIndex in
            let offset = LevelMeasureSource.initialSeconds + Double(repeatIndex) * LevelMeasureSource.cycleSeconds +
                (pointSpeaker ? LevelMeasureSource.activeSeconds + LevelMeasureSource.gapSeconds : 0)
            let signalStartNs = anchorServerNs + Int64(offset * 1e9) + (pointSpeaker ? trimNsForPoint : 0)
            let start = Double(signalStartNs - recordingStartServerNs) / 1e9
            return LevelAnalyzer.analyze(samples, sampleRate: sampleRate, start: start,
                duration: LevelMeasureSource.activeSeconds, backgroundStart: start - 0.45, backgroundDuration: 0.4)
        }
    }

    private func receiveLevelCapture(_ o: [String: Any]) {
        let id = J.s(o, "id"), kind = J.s(o, "kind")
        guard !id.isEmpty, role == .point, MicRecorder.permissionGranted else {
            link?.send(["t": "levelCaptureResult", "id": id, "kind": kind,
                        "error": id.isEmpty ? "Некорректный запрос" : "Разрешите микрофон на Point"])
            return
        }
        let duration = J.d(o, "durationMs", kind == "reference" ? 4_200 : LevelMeasureSource.captureSeconds * 1_000) / 1_000
        let anchor = J.i64(o, "anchorSrvNs")
        DispatchQueue.main.async {
            do {
                try AudioOutput.configureMeasurementSession(); self.audio.restart()
            } catch {
                self.q.async { self.link?.send(["t": "levelCaptureResult", "id": id, "kind": kind, "error": error.localizedDescription]) }
                return
            }
            self.mic.record(durationSec: duration, onStarted: {}, completion: { result in
                AudioOutput.configurePlaybackSession(); self.audio.restart()
                switch result {
                case .failure(let error):
                    self.q.async { self.link?.send(["t": "levelCaptureResult", "id": id, "kind": kind, "error": error.localizedDescription]) }
                case .success(let rec):
                    DispatchQueue.global(qos: .userInitiated).async {
                        let serverStart = self.clock.ready ? self.clock.toServer(rec.startNs) : 0
                        let payload: [String: Any]
                        if kind == "reference" {
                            let measured = LevelAnalyzer.analyze(rec.samples, sampleRate: rec.sampleRate, start: 0.2, duration: max(0, duration - 0.4))
                            payload = ["t": "levelCaptureResult", "id": id, "kind": kind,
                                       "startServerNs": serverStart, "result": self.levelResultObject(measured)]
                        } else if anchor <= 0 || serverStart <= 0 {
                            payload = ["t": "levelCaptureResult", "id": id, "kind": kind, "error": "Часы Point не синхронизированы"]
                        } else {
                            let results = self.analyzeLevelSweeps(rec.samples, sampleRate: rec.sampleRate,
                                recordingStartServerNs: serverStart, anchorServerNs: anchor, pointSpeaker: false)
                            payload = ["t": "levelCaptureResult", "id": id, "kind": kind, "startServerNs": serverStart,
                                       "results": results.map(self.levelResultObject)]
                        }
                        self.q.async { self.link?.send(payload) }
                    }
                }
            })
        }
    }

    private func startLevelMicReference(_ position: Int) {
        guard levelStageReady else { toast(levelGateMessage); return }
        guard role == .server, (1...2).contains(position), let activeLink = link else { toast("Сверку микрофонов запускайте на Server"); return }
        guard mode == .music, !calibRunning else { toast("Сначала завершите текущую калибровку или звуковую проверку"); return }
        guard MicRecorder.permissionGranted else { toast("Разрешите доступ к микрофону на этом телефоне"); return }
        guard position != 2 || micReferenceDeltaOne != nil else { toast("Сначала запишите положение 1"); return }
        let id = UUID().uuidString
        let remote = Locked<[String: Any]?>(nil), semaphore = DispatchSemaphore(value: 0)
        levelCaptureWaiters[id] = { value in remote.value = value; semaphore.signal() }
        levelMeasureText = "Сверка микрофонов · положение \(position): запись 4 с. Включите внешний шумовой источник по центру."
        ui { $0.levelMeasureText = self.levelMeasureText }
        if mode == .music { savedFrame = posFrame(); savedPlaying = playBox.value.timeline.playing }
        newEpoch(false, posFrame(), MonoClock.nowNs())
        activeLink.send(["t": "levelCapture", "id": id, "kind": "reference", "durationMs": 4_200])

        DispatchQueue.main.async {
            do { try AudioOutput.configureMeasurementSession(); self.audio.restart() }
            catch {
                self.q.async { self.levelCaptureWaiters.removeValue(forKey: id); self.levelMeasureText = "Сверка микрофонов не удалась: \(error.localizedDescription)"; self.restoreMusic(self.savedPlaying); self.publishState() }
                return
            }
            self.mic.record(durationSec: 4.2, onStarted: {}, completion: { result in
                AudioOutput.configurePlaybackSession(); self.audio.restart()
                switch result {
                case .failure(let error):
                    self.q.async { self.levelCaptureWaiters.removeValue(forKey: id); self.levelMeasureText = "Сверка микрофонов не удалась: \(error.localizedDescription)"; self.restoreMusic(self.savedPlaying); self.publishState() }
                case .success(let rec):
                    DispatchQueue.global(qos: .userInitiated).async {
                        do {
                            let own = LevelAnalyzer.analyze(rec.samples, sampleRate: rec.sampleRate, start: 0.2, duration: 3.8)
                            guard semaphore.wait(timeout: .now() + 8) == .success, let peer = remote.value else { throw NSError(domain: "SoundField.Level", code: 1, userInfo: [NSLocalizedDescriptionKey: "Point не прислал данные микрофона"]) }
                            if let error = peer["error"] as? String { throw NSError(domain: "SoundField.Level", code: 2, userInfo: [NSLocalizedDescriptionKey: error]) }
                            guard let raw = peer["result"] as? [String: Any], let delta = LevelAnalyzer.differenceDb(own, self.readLevelResult(raw)) else {
                                throw NSError(domain: "SoundField.Level", code: 3, userInfo: [NSLocalizedDescriptionKey: "Недостаточно пригодных частотных полос"])
                            }
                            self.q.async {
                                if position == 1 { self.micReferenceDeltaOne = delta } else { self.micReferenceDeltaTwo = delta }
                                let d1 = self.micReferenceDeltaOne, d2 = self.micReferenceDeltaTwo
                                if position == 2 && abs((d1 ?? 0) - delta) <= 3 { self.micSensitivityDeltaDb = ((d1 ?? 0) + delta) / 2 }
                                else if position == 2 { self.micSensitivityDeltaDb = nil }
                                self.levelMeasureText = position == 1
                                    ? String(format: "ΔM: ожидается второе измерение после перестановки телефонов · d1 = %+.1f дБ", delta)
                                    : abs((d1 ?? 0) - delta) > 3
                                        ? "Сверка микрофонов отклонена: |d1 − d2| > 3 дБ. Поставьте внешний источник по центру и повторите."
                                        : String(format: "Сверка микрофонов готова · ΔM = %+.1f дБ · d1 = %+.1f · d2 = %+.1f дБ", self.micSensitivityDeltaDb ?? 0, d1 ?? 0, d2 ?? 0)
                                self.restoreMusic(self.savedPlaying); self.publishState()
                            }
                        } catch {
                            self.q.async { self.levelCaptureWaiters.removeValue(forKey: id); self.levelMeasureText = "Сверка микрофонов не удалась: \(error.localizedDescription)"; self.restoreMusic(self.savedPlaying); self.publishState() }
                        }
                    }
                }
            })
        }
    }

    private func startLevelMicMeasurement() {
        guard levelStageReady else { toast(levelGateMessage); return }
        guard role == .server else { toast("Запустите измерение на Server"); return }
        guard mode == .music, !calibRunning else { toast("Сначала завершите текущую калибровку или звуковую проверку"); return }
        guard let sensitivity = micSensitivityDeltaDb else { toast("Сначала завершите сверку микрофонов в двух положениях"); return }
        guard MicRecorder.permissionGranted else { toast("Разрешите доступ к микрофону на этом телефоне"); return }
        guard master >= 0.5 else { toast("Для измерения поднимите громкость Sound не ниже 50 %"); return }
        guard let activeLink = link else { toast("Point не на связи"); return }
        let id = UUID().uuidString
        let remote = Locked<[String: Any]?>(nil), semaphore = DispatchSemaphore(value: 0)
        levelCaptureWaiters[id] = { value in remote.value = value; semaphore.signal() }
        levelMeasureSavedServerDb = levelTrimServerDb; levelMeasureSavedPointDb = levelTrimPointDb
        levelTrimServerDb = 0; levelTrimPointDb = 0
        levelMeasureText = "Измерение динамиков: 3 взаимных прохода. Не двигайте телефоны."
        ui { $0.levelMeasureText = self.levelMeasureText }; publishState()
        let anchor = MonoClock.nowNs() + 2_000_000_000
        activeLink.send(["t": "levelCapture", "id": id, "kind": "output", "anchorSrvNs": anchor,
                         "durationMs": Int(LevelMeasureSource.captureSeconds * 1_000)])
        startTestMode(LevelMeasureSource(serverChannel: serverChannel, pointChannel: pointChannel), anchorNs: anchor)

        DispatchQueue.main.async {
            do { try AudioOutput.configureMeasurementSession(); self.audio.restart() }
            catch {
                self.q.async { self.levelCaptureWaiters.removeValue(forKey: id); self.failLevelMeasure(error.localizedDescription, id: id) }
                return
            }
            self.mic.record(durationSec: LevelMeasureSource.captureSeconds, onStarted: {}, completion: { result in
                AudioOutput.configurePlaybackSession(); self.audio.restart()
                switch result {
                case .failure(let error): self.q.async { self.levelCaptureWaiters.removeValue(forKey: id); self.failLevelMeasure(error.localizedDescription, id: id) }
                case .success(let rec):
                    DispatchQueue.global(qos: .userInitiated).async {
                        do {
                            let own = self.analyzeLevelSweeps(rec.samples, sampleRate: rec.sampleRate,
                                recordingStartServerNs: rec.startNs, anchorServerNs: anchor, pointSpeaker: true, trimNsForPoint: self.trimNs)
                            guard semaphore.wait(timeout: .now() + 30) == .success, let peer = remote.value else { throw NSError(domain: "SoundField.Level", code: 4, userInfo: [NSLocalizedDescriptionKey: "Point не прислал результаты измерения"]) }
                            if let error = peer["error"] as? String { throw NSError(domain: "SoundField.Level", code: 5, userInfo: [NSLocalizedDescriptionKey: error]) }
                            guard let raw = peer["results"] as? [[String: Any]], raw.count == LevelMeasureSource.repeats else { throw NSError(domain: "SoundField.Level", code: 6, userInfo: [NSLocalizedDescriptionKey: "Неполный результат Point"]) }
                            let remoteResults = raw.map(self.readLevelResult)
                            var deltas: [Double] = []
                            for i in 0..<remoteResults.count {
                                guard remoteResults[i].validBands >= 6, own[i].validBands >= 6,
                                      let difference = LevelAnalyzer.differenceDb(remoteResults[i], own[i]) else {
                                    throw NSError(domain: "SoundField.Level", code: 7, userInfo: [NSLocalizedDescriptionKey: "SNR ниже 15 дБ или менее 6 полос"])
                                }
                                deltas.append(difference + sensitivity)
                            }
                            let spread = (deltas.max() ?? 0) - (deltas.min() ?? 0)
                            let dP = LevelAnalyzer.median(deltas) ?? 0
                            let validBands = (remoteResults + own).map(\.validBands).min() ?? 0
                            let snrValues = (remoteResults + own).flatMap { $0.snrDb.compactMap { $0 } }
                            let snr = LevelAnalyzer.median(snrValues) ?? 0
                            let accepted = spread <= 1 && abs(dP) <= 12
                            self.q.async {
                                self.levelTrimServerDb = self.levelMeasureSavedServerDb; self.levelTrimPointDb = self.levelMeasureSavedPointDb
                                self.restoreMusic(self.savedPlaying)
                                self.levelMeasureText = String(format: "ΔM %+.1f дБ · Δ %+.1f дБ · ΔP %+.1f дБ · разброс %.1f дБ · SNR %.1f дБ · полос %d/10 · %@",
                                    sensitivity, dP - sensitivity, dP, spread, snr, validBands,
                                    accepted ? "результат экспериментальный, не применён" : "качество недостаточно, повторите")
                                NSLog("SoundField telemetry level_measure dM=%.1f d1=%.1f d2=%.1f delta=%.1f dP=%.1f spread=%.1f snr=%.1f bands=%d accepted=%@ applied=false",
                                      sensitivity, self.micReferenceDeltaOne ?? 0, self.micReferenceDeltaTwo ?? 0,
                                      dP - sensitivity, dP, spread, snr, validBands, accepted ? "true" : "false")
                                self.saveServerParams(); self.publishState()
                            }
                        } catch { self.q.async { self.failLevelMeasure(error.localizedDescription, id: id) } }
                    }
                }
            })
        }
    }

    private func failLevelMeasure(_ reason: String, id: String? = nil) {
        if let id { levelCaptureWaiters.removeValue(forKey: id) }
        levelTrimServerDb = levelMeasureSavedServerDb; levelTrimPointDb = levelMeasureSavedPointDb
        restoreMusic(savedPlaying)
        levelMeasureText = "Измерение отклонено: \(reason). Результат не применён."
        NSLog("SoundField telemetry level_measure result=rejected reason=%@ applied=false", reason)
        saveServerParams(); publishState()
    }
    func requestTrackPicker() {
        q.async {
            guard !self.calibRunning else { self.toast("Сначала завершите калибровку"); return }
            if self.role == .server || self.state.fileTransferAvailable { self.ui { $0.filePickerRequest += 1 } }
            else { self.toast("Обновите SoundField на Server для передачи файлов") }
        }
    }
    func addAudioFiles(_ urls: [URL], securityRoot: URL? = nil, deleteAfterImport: [URL] = []) {
        guard !urls.isEmpty else { return }
        q.async {
            if self.role == .point && self.phase == .paired { self.sendAudioFiles(urls, securityRoot: securityRoot); return }
            guard self.role == .server, self.soloMode || self.phase == .paired || self.phase == .reconnecting, !self.calibRunning else {
                self.toast("Добавление музыки сейчас недоступно"); return
            }
            self.ui { $0.loadingTracks = true }
            let rootAccess = securityRoot?.startAccessingSecurityScopedResource() ?? false
            self.queueIo.async {
                defer { if rootAccess { securityRoot?.stopAccessingSecurityScopedResource() }; deleteAfterImport.forEach { try? FileManager.default.removeItem(at: $0) } }
                var prepared: [PreparedAudioFile] = []
                var failures = 0
                for selection in urls {
                    let accessed = selection.startAccessingSecurityScopedResource()
                    defer { if accessed { selection.stopAccessingSecurityScopedResource() } }
                    let candidates = Self.audioURLs(selection)
                    for candidate in candidates {
                        do { prepared.append(try AudioFileDecoder.prepare(candidate)) }
                        catch { failures += 1; NSLog("SoundField: could not import %@: %@", candidate.lastPathComponent, error.localizedDescription) }
                    }
                }
                self.q.async {
                    guard self.role == .server, self.soloMode || self.phase == .paired || self.phase == .reconnecting, !self.calibRunning else {
                        prepared.forEach { try? FileManager.default.removeItem(at: $0.url) }
                        self.ui { $0.loadingTracks = false }
                        return
                    }
                    guard !prepared.isEmpty else {
                        self.ui { $0.loadingTracks = false }
                        self.toast("Не удалось открыть аудиофайлы")
                        return
                    }
                    let firstImport = !self.hasImportedQueue
                    if firstImport {
                        self.queue.removeAll(); self.currentQueueSource = nil; self.queueIndex = -1; self.hasImportedQueue = true
                    }
                    self.queue.append(contentsOf: prepared.map { QueueEntry(id: $0.id, fileURL: $0.url, title: $0.title, artist: $0.artist) })
                    self.ui { $0.queueTracks = self.queue.map { QueueTrackUi(id: $0.id, title: $0.title, artist: $0.artist) } }
                    self.publishQueue()
                    if firstImport {
                        if self.playBox.value.timeline.playing { self.newEpoch(false, self.posFrame(), MonoClock.nowNs()) }
                        self.selectQueueTrack(at: 0, forcePaused: true)
                    } else { self.ui { $0.loadingTracks = false }; self.publishState() }
                    let added = prepared.count
                    self.toast(failures == 0 ? "Добавлено в очередь: \(added) треков" : "Добавлено: \(added), пропущено файлов: \(failures)")
                }
            }
        }
    }
    func nextTrack() { cmd("next") }
    func previousTrack() { cmd("previous") }
    func selectQueueTrack(_ id: String) { cmd("select", ["id": id]) }
    func setQueueExpanded(_ expanded: Bool) {
        q.async { self.defaults.set(expanded, forKey: "sf.queueExpanded"); self.ui { $0.queueExpanded = expanded } }
    }
    func setVolume(_ ch: String, _ v: Float, finished: Bool = true) {
        if ch == "M" { q.async { self.ui { $0.master = v } } }
        sendGainCommand("vol:\(ch)", finished: finished) {
            self.cmd("vol", ["ch": ch, "v": Double(v), "law": 2, "finished": finished])
        }
    }
    func setBalance(_ value: Float, finished: Bool = true) {
        let value = min(1, max(0, value))
        q.async { self.ui { $0.balance = value } }
        sendGainCommand("balance", finished: finished) {
            self.cmd("balance", ["v": Double(value), "finished": finished])
        }
    }

    private func sendGainCommand(_ key: String, finished: Bool, send: @escaping () -> Void) {
        q.async {
            let now = DispatchTime.now().uptimeNanoseconds
            let last = self.lastGainCommandNs[key] ?? 0
            let due = last == 0 ? now : last + 33_333_333
            if finished || now >= due {
                self.scheduledGainCommands.removeValue(forKey: key)?.cancel()
                self.pendingGainCommands.removeValue(forKey: key)
                self.lastGainCommandNs[key] = now
                send()
            } else {
                self.pendingGainCommands[key] = send
                guard self.scheduledGainCommands[key] == nil else { return }
                let item = DispatchWorkItem { [weak self] in
                    guard let self else { return }
                    self.scheduledGainCommands.removeValue(forKey: key)
                    self.lastGainCommandNs[key] = DispatchTime.now().uptimeNanoseconds
                    self.pendingGainCommands.removeValue(forKey: key)?()
                }
                self.scheduledGainCommands[key] = item
                self.q.asyncAfter(deadline: .now() + .nanoseconds(Int(due - now)), execute: item)
            }
        }
    }
    func micPermissionGranted() { q.async { self.ui { $0.calib.needsMicPermission = false; $0.calib.resultText = "" } } }

    private func cmd(_ a: String, _ extra: [String: Any] = [:]) {
        var o: [String: Any] = ["t": "cmd", "a": a]
        for (k, v) in extra { o[k] = v }
        q.async {
            guard self.state.inPair || self.soloMode else { return }
            if self.role == .server { self.handleCmd(o) }
            else if let l = self.link { l.send(o) } else { self.toast("Нет связи с Server") }
        }
    }

    /// Позиция для UI, мс (можно вызывать с главного потока).
    func positionMs() -> Int64 {
        let now = MonoClock.nowNs()
        var frame: Int64, total: Int64
        if role == .server {
            let st = playBox.value; frame = st.timeline.positionFrame(now); total = st.source.totalFrames
        } else {
            let tl = rx.timeline
            frame = clock.ready ? tl.positionFrame(clock.toServer(now)) : tl.anchorFrame
            total = paramsBox.value.totalFrames
        }
        frame = min(max(0, frame), max(0, total))
        return frame * 1000 / Int64(SF.sampleRate)
    }

    // ================================================================== установка пары
    private func inviteJson(_ key: String) -> [String: Any] {
        ["t": "invite", "id": myId, "name": myName, "platform": "ios", "proto": Int(SF.protocolVersion), "key": key,
         "tcpPort": Int(discovery.listenPort), "caps": ["fileTransferV1", "eq", "spatialV1"]]
    }
    private func resumeJson(_ r: PairRecord) -> [String: Any] {
        ["t": "resume", "id": myId, "name": myName, "platform": "ios", "proto": Int(SF.protocolVersion), "key": r.key, "role": r.myRole,
         "tcpPort": Int(discovery.listenPort), "caps": ["fileTransferV1", "eq", "spatialV1"]]
    }
    private func acceptJson(_ key: String, _ role: String) -> [String: Any] {
        ["t": "accept", "id": myId, "name": myName, "platform": "ios", "key": key, "role": role, "tcpPort": Int(discovery.listenPort), "caps": ["fileTransferV1", "eq", "spatialV1"]]
    }

    private func doInvite(_ p: Peer) {
        guard phase == .idle else { return }
        stopAutoResume()
        pendingKey = Self.hex(16)
        phase = .inviting
        ui { $0.phase = .inviting; $0.invitingName = p.name; $0.resuming = false }
        connectOutgoing(p, tag: "invite-out", first: inviteJson(pendingKey), timeout: 30)
    }

    private func doManualResume(auto: Bool = false) {
        if phase == .idle { stopAutoResume() }
        guard let rec = record, phase == .idle, pending == nil else { return }
        phase = .inviting
        manualResumeActive = true; manualResumeAuto = auto; resumeAttempt = 0
        ui { $0.phase = .inviting; $0.invitingName = rec.peerName; $0.resuming = true }
        manualResumeDeadline = Date().addingTimeInterval(auto ? 20 : 15)
        discovery.startBrowse()
        log(auto ? "Автовосстановление пары с «\(rec.peerName)»" : "Подключение к знакомой паре «\(rec.peerName)»")
        tryManualResume()
    }

    private func tryManualResume() {
        guard manualResumeActive, phase == .inviting, pending == nil, let rec = record else { return }
        if Date() > manualResumeDeadline {
            manualResumeActive = false
            phase = .idle; ui { $0.phase = .idle }
            log("«\(rec.peerName)» не ответил")
            toast(manualResumeAuto ? "«\(rec.peerName)» пока не отвечает. Откройте SoundField на втором телефоне — пара восстановится"
                  : "«\(rec.peerName)» не найден. Откройте SoundField на втором телефоне в той же сети Wi‑Fi")
            return
        }
        if let p = resumeTarget(rec) { connectOutgoing(p, tag: "resume-out", first: resumeJson(rec), timeout: 4); return }
        later(0.5) { [weak self] in self?.tryManualResume() }
    }

    /// Куда стучаться при восстановлении: попеременно последний известный адрес (без Bonjour — мгновенно
    /// после разблокировки) и сервис, найденный Bonjour (если IP сменился).
    private func resumeTarget(_ rec: PairRecord) -> Peer? {
        resumeAttempt += 1
        let found = discovery.peer(id: rec.peerId)
        var direct: Peer?
        if let h = rec.lastHost, let p = rec.lastPort, p > 0, let port = NWEndpoint.Port(rawValue: UInt16(truncatingIfNeeded: p)) {
            direct = Peer(id: rec.peerId, name: rec.peerName, platform: rec.peerPlatform,
                          endpoint: .hostPort(host: NWEndpoint.Host(h), port: port))
        }
        return resumeAttempt % 2 == 1 ? (direct ?? found) : (found ?? direct)
    }

    private static func hostString(_ h: NWEndpoint.Host?) -> String? {
        guard let h else { return nil }
        var s: String
        switch h {
        case .ipv4(let a): s = "\(a)"
        case .ipv6(let a): s = "\(a)"
        case .name(let n, _): s = n
        @unknown default: return nil
        }
        if s.hasPrefix("::ffff:"), s.contains(".") {
            s = String(s.dropFirst(7)); if let pct = s.firstIndex(of: "%") { s = String(s[..<pct]) }
        }
        return s
    }

    private func connectOutgoing(_ p: Peer, tag: String, first: [String: Any], timeout: Double) {
        pendingPeer = p
        let l = newLink(NWConnection(to: p.endpoint, using: Discovery.tcpParams()))
        l.tag = tag
        pending = l
        l.start()
        l.send(first)
        pendingTimeout?.cancel()
        pendingTimeout = later(timeout) { [weak self, weak l] in
            guard let self, let l, self.pending === l else { return }
            l.close()
        }
    }

    private func newLink(_ c: NWConnection) -> ControlLink {
        let l = ControlLink(c, queue: q)
        l.onMessage = { [weak self] l, o in self?.onMessage(l, o) }
        l.onClosed = { [weak self] l in self?.onClosed(l) }
        return l
    }

    private func onIncoming(_ c: NWConnection) {
        let l = newLink(c)
        l.tag = "new"
        l.start()
        later(5) { if l.tag == "new" { l.close() } }
    }

    private func doCancelPending() {
        manualResumeActive = false
        if let l = pending { pending = nil; l.tag = "dead"; l.sendAndClose(["t": "cancel"]) }
        if phase == .inviting || phase == .incoming { phase = .idle; ui { $0.phase = .idle } }
    }

    private func doAccept() {
        guard let l = pending, let p = pendingPeer, phase == .incoming else { return }
        l.send(acceptJson(pendingKey, Role.point.rawValue))
        peerTcpPortHint = pendingInvitePort
        becomePaired(l, .point, p.id, p.name, p.platform, pendingKey, resumed: false)
    }

    private func doReject() {
        guard let l = pending else { return }
        pending = nil; l.tag = "dead"
        l.sendAndClose(["t": "reject", "reason": "declined"])
        phase = .idle; ui { $0.phase = .idle }
    }

    // ================================================================== входящие
    private func onMessage(_ l: ControlLink, _ o: [String: Any]) {
        if l === link { onPairedMessage(o) }
        else if l === pending { onPendingMessage(l, o) }
        else if l.tag == "new" { onFirstMessage(l, o) }
    }

    private func dummyPeer(_ id: String, _ name: String, _ platform: String, _ l: ControlLink) -> Peer {
        Peer(id: id, name: name, platform: platform, endpoint: l.conn.endpoint)
    }

    private func onFirstMessage(_ l: ControlLink, _ o: [String: Any]) {
        let pid = J.s(o, "id"), pname = J.s(o, "name", pid), pplat = J.s(o, "platform", "?")
        l.peerCapabilities = Set(o["caps"] as? [String] ?? [])
        switch J.s(o, "t") {
        case "invite":
            if phase == .idle { stopAutoResume() }   // новое приглашение важнее фонового восстановления
            if phase == .idle && pending == nil {
                l.tag = "incoming"; pending = l
                pendingPeer = dummyPeer(pid, pname, pplat, l)
                pendingKey = J.s(o, "key")
                pendingInvitePort = Int(J.i64(o, "tcpPort"))
                log("Приглашение от «\(pname)»")
                phase = .incoming
                ui { $0.phase = .incoming; $0.incomingName = pname }
                pendingTimeout?.cancel()
                pendingTimeout = later(60) { [weak self, weak l] in if let self, let l, self.pending === l { self.doReject() } }
            } else if phase == .inviting && pendingPeer?.id == pid {
                // встречные приглашения: уступает телефон с бо́льшим id
                if myId > pid {
                    if let old = pending { old.tag = "dead"; pending = nil; old.close() }
                    l.tag = "incoming"; pending = l
                    pendingPeer = dummyPeer(pid, pname, pplat, l)
                    pendingKey = J.s(o, "key")
                    pendingInvitePort = Int(J.i64(o, "tcpPort"))
                    phase = .incoming
                    doAccept()
                } else {
                    l.tag = "dead"; l.sendAndClose(["t": "reject", "reason": "crossed"])
                }
            } else {
                l.tag = "dead"; l.sendAndClose(["t": "busy"])
            }
        case "resume":
            guard let rec = record, rec.key == J.s(o, "key"), rec.peerId == pid, phase != .incoming else {
                log("Отклонён resume от «\(pname)»: пара неизвестна")
                l.tag = "dead"; l.sendAndClose(["t": "reject", "reason": "unknown"]); return
            }
            if soloMode && playBox.value.timeline.playing && rec.role == .point && !manualResumeActive {
                l.tag = "dead"; l.sendAndClose(["t": "busy"]); return
            }
            // Встречные попытки восстановления: входящее принимает телефон с бо́льшим id (как для приглашений).
            // Отказываем, только если наше встречное соединение уже установлено (его примет второй телефон).
            // Если наше ещё «висит» (например, старый адрес), принимаем входящее — иначе попытки могли
            // бесконечно отклонять друг друга.
            if let mine = pending, mine.tag == "resume-out" || mine.tag == "resume-auto", myId < pid, mine.ready {
                l.tag = "dead"; l.sendAndClose(["t": "reject", "reason": "crossed"]); return
            }
            if let old = link { link = nil; old.tag = "dead"; old.close() }
            if let p = pending { pending = nil; p.tag = "dead"; p.close() }
            l.send(acceptJson(rec.key, rec.myRole))
            peerTcpPortHint = Int(J.i64(o, "tcpPort"))
            log("Входящее восстановление от «\(pname)»")
            becomePaired(l, rec.role, pid, pname, pplat, rec.key, resumed: true)
        default:
            l.tag = "dead"; l.close()
        }
    }

    private func onPendingMessage(_ l: ControlLink, _ o: [String: Any]) {
        let pid = J.s(o, "id"), pname = J.s(o, "name", pid), pplat = J.s(o, "platform", "?")
        l.peerCapabilities = Set(o["caps"] as? [String] ?? [])
        switch J.s(o, "t") {
        case "accept":
            let hinted = Int(J.i64(o, "tcpPort"))
            peerTcpPortHint = hinted > 0 ? hinted : Self.endpointPort(pendingPeer?.endpoint)
            if l.tag == "invite-out" { becomePaired(l, .server, pid, pname, pplat, pendingKey, resumed: false) }
            else if l.tag == "resume-out" || l.tag == "resume-auto" {
                let their = Role(rawValue: J.s(o, "role")) ?? .point
                becomePaired(l, their == .server ? .point : .server, pid, pname, pplat, J.s(o, "key"), resumed: true)
            }
        case "reject", "busy":
            let reason = J.s(o, "reason"), t = J.s(o, "t")
            let wasAuto = l.tag == "resume-auto"
            pending = nil; l.tag = "dead"; l.close()
            if wasAuto {
                log("Фоновое восстановление: ответ «\(t)\(reason.isEmpty ? "" : ": " + reason)»")
                if reason == "unknown" { setAutoResume(false); stopAutoResume() }   // второй телефон забыл пару
                return
            }
            log("Ответ «\(t)\(reason.isEmpty ? "" : ": " + reason)»")
            if phase == .inviting && reason == "crossed" {
                // ждём встречный accept; если не придёт — пробуем снова
                if manualResumeActive { later(1.5) { [weak self] in self?.tryManualResume() } }
            } else if phase == .inviting && manualResumeActive && reason != "unknown" && t != "busy" {
                later(1) { [weak self] in self?.tryManualResume() }
            } else if phase == .inviting {
                manualResumeActive = false
                if reason == "unknown" { setAutoResume(false) }
                phase = .idle; ui { $0.phase = .idle }
                toast(t == "busy" ? "Телефон уже в другой паре"
                      : reason == "unknown" ? "Второй телефон не помнит эту пару — создайте её заново" : "Подключение отклонено")
            } else if phase == .reconnecting { scheduleReconnect(reason == "crossed" ? 1.5 : 2) }
        case "cancel":
            if l.tag == "incoming" {
                pending = nil; l.tag = "dead"; l.close()
                phase = .idle; ui { $0.phase = .idle }; toast("Приглашение отменено")
            }
        default: break
        }
    }

    private func onClosed(_ l: ControlLink) {
        if l === link { onLinkLost(); return }
        guard l === pending else { return }
        pending = nil
        switch phase {
        case .incoming: phase = .idle; ui { $0.phase = .idle }
        case .inviting:
            if l.tag == "resume-out" { later(0.7) { [weak self] in self?.tryManualResume() } }
            else { phase = .idle; ui { $0.phase = .idle }; toast("Нет ответа от второго телефона") }
        case .reconnecting: scheduleReconnect(reconnectDelay())
        default: break
        }
    }

    private func reconnectDelay() -> Double { Date().timeIntervalSince(reconnectSince) < 60 ? 1 : 3 }

    private static func endpointPort(_ e: NWEndpoint?) -> Int {
        if case .hostPort(_, let p)? = e { return Int(p.rawValue) }
        return 0
    }

    // ================================================================== пара
    private func becomePaired(_ l: ControlLink, _ r: Role, _ pid: String, _ pname: String, _ pplat: String, _ key: String, resumed: Bool) {
        peerCapabilities = l.peerCapabilities
        serverFileToken = ""
        ui { $0.fileTransferAvailable = false }
        if soloMode {
            let frame = mode == .music ? posFrame() : 0
            if r == .point { soloResumeFrame = frame }
            else { soloHandoff = (frame, mode == .music && playBox.value.timeline.playing) }
            soloMode = false
            ui { $0.soloMode = false }
        }
        pendingTimeout?.cancel(); reconnectTask?.cancel()
        if pending === l { pending = nil }
        if let p = pending { p.tag = "dead"; p.close(); pending = nil }
        manualResumeActive = false
        autoResumeUntil = nil
        // Та же пара, новое соединение (после обрыва или когда второй телефон заметил обрыв раньше нас) —
        // музыка и позиция не сбрасываются.
        let continuing = (phase == .reconnecting || phase == .paired) && role == r && peerId == pid
        l.tag = "paired"
        link = l
        role = r; peerId = pid; peerName = pname; peerPlatform = pplat
        phase = .paired
        pairedAtNs = MonoClock.nowNs()
        lastAudioSessionResetNs = pairedAtNs
        lastAudioRxAtNs.value = 0

        let host = Self.hostString(l.remoteHost)
        if var old = record, old.key == key {
            old.peerName = pname; old.peerPlatform = pplat; old.myRole = r.rawValue
            if let host { old.lastHost = host }
            if peerTcpPortHint > 0 { old.lastPort = peerTcpPortHint }
            record = old
        } else {
            record = PairRecord(key: key, peerId: pid, peerName: pname, peerPlatform: pplat, myRole: r.rawValue,
                                lastHost: host, lastPort: peerTcpPortHint > 0 ? peerTcpPortHint : nil)
        }
        peerTcpPortHint = 0
        persistRecord()
        setAutoResume(true)
        log("Пара \(continuing ? "продолжена" : resumed ? "восстановлена" : "создана"): «\(pname)» \(host ?? "?"):\(record?.lastPort ?? 0), роль \(r.rawValue)")
        discovery.stopBrowse()
        peerGainLawReady = false; didAcknowledgeGainLaw = false
        if !continuing {
            micReferenceDeltaOne = nil; micReferenceDeltaTwo = nil; micSensitivityDeltaDb = nil; levelMeasureText = ""
        }

        if r == .server {
            if let rec = record, !continuing {
                pointChannel = Ch.parse(rec.pointCh); trimNs = Int64(rec.trimMs * 1e6)
                volL = Float(rec.volL); volR = Float(rec.volR)
                master = rec.volTaper < 2 ? AudioGain.migrateMaster(Float(rec.master)) : Float(rec.master)
                balance = Float(rec.balance ?? 0.5); levelTrimServerDb = Float(rec.levelTrimServerDb); levelTrimPointDb = Float(rec.levelTrimPointDb)
                if rec.levelTrimVersion < 1 {
                    let legacyServer = Ch.other(pointChannel) == Ch.L ? volL : volR
                    let legacyPoint = pointChannel == Ch.L ? volL : volR
                    let migrated = AudioGain.migrateChannelGains(serverGain: legacyServer, pointGain: legacyPoint)
                    levelTrimServerDb = migrated.0; levelTrimPointDb = migrated.1
                }
                delayCalib = rec.delayCalib.state == "none" && rec.trimMs != 0
                    ? DelayCalib(state: "manual", atMs: Int64(Date().timeIntervalSince1970 * 1000)) : rec.delayCalib
                levelCalib = rec.levelCalib
                var migrated = rec; migrated.master = Double(master); migrated.volTaper = 2
                migrated.levelTrimServerDb = Double(levelTrimServerDb); migrated.levelTrimPointDb = Double(levelTrimPointDb)
                migrated.levelTrimVersion = 1; migrated.delayCalib = delayCalib; record = migrated; persistRecord()
            }
            loadSoundProfile()
            fileToken = Self.hex(24)
            sessionId = Int32.random(in: 1...Int32.max)
            pointUdpBox.value = nil; pointClockReady = false
            if !continuing {
                mode = .music
                source = queue.indices.contains(queueIndex) ? currentQueueSource ?? music : music
                let handoff = soloHandoff; soloHandoff = nil
                newEpoch(handoff?.playing ?? false, handoff?.frame ?? 0, MonoClock.nowNs() + ((handoff?.playing ?? false) ? SF.startDelayNs : 0))
                if queue.indices.contains(queueIndex), currentQueueSource == nil { selectQueueTrack(at: queueIndex, forcePaused: true) }
            } else { rebuildPlayState() }
            audio.source = serverRenderer
            sendSession(l)
            sendTimeline()
            publishState()
            publishQueue()
        } else {
            sessionId = 0; serverUdpBox.value = nil
            clock.reset(); rx.reset()
            audio.source = pointRenderer
        }
        DispatchQueue.main.async { self.audio.start() }
        ui { s in
            s.phase = .paired; s.role = r; s.peerName = pname; s.peerPlatform = pplat; s.linkOk = true; s.audioMissing = false
            s.remembered = record; s.calib = CalibUi(); s.autoResuming = false
        }
        toast(resumed ? "Пара восстановлена" : "Стереопара создана")
    }

    private func onLinkLost() {
        cancelTransfers()
        link = nil
        guard phase == .paired else { return }
        phase = .reconnecting
        pairedAtNs = 0
        ui { $0.phase = .reconnecting; $0.linkOk = false; $0.audioMissing = false }
        log("Связь с «\(peerName)» потеряна")
        reconnectSince = Date(); resumeAttempt = 0
        discovery.startBrowse()
        // Переподключаются оба телефона; встречные попытки разрешаются по id.
        if role == .server {
            pointUdpBox.value = nil
            scheduleReconnect(0.7)
            toast("Нет связи с «\(peerName)». Музыка продолжается, ждём возвращения…")
        } else {
            rx.reset(); serverUdpBox.value = nil; sessionId = 0
            scheduleReconnect(0.2)
            toast("Связь потеряна — переподключаемся…")
        }
    }

    private func scheduleReconnect(_ sec: Double) {
        reconnectTask?.cancel()
        reconnectTask = later(sec) { [weak self] in self?.attemptResume() }
    }

    private func attemptResume() {
        guard phase == .reconnecting, pending == nil, let rec = record else { return }
        if Date().timeIntervalSince(lastBrowseRestart) > 20 { lastBrowseRestart = Date(); discovery.restartBrowse() }
        guard let p = resumeTarget(rec) else { scheduleReconnect(reconnectDelay()); return }
        connectOutgoing(p, tag: "resume-out", first: resumeJson(rec), timeout: 4)
    }

    private func goIdle(sendBye: Bool, reason: String) {
        cancelTransfers()
        let fromServer = role == .server
        let frame = fromServer && mode == .music ? posFrame() : soloResumeFrame
        let playing = fromServer && mode == .music && playBox.value.timeline.playing
        trackLoadGeneration += 1
        reconnectTask?.cancel(); pendingTimeout?.cancel()
        if let l = link { link = nil; l.tag = "dead"; if sendBye { l.sendAndClose(["t": "bye"]) } else { l.close() } }
        if let p = pending { pending = nil; p.tag = "dead"; p.close() }
        phase = .idle
        manualResumeActive = false
        log(reason)
        pendingTrackId = nil
        audio.source = nil
        DispatchQueue.main.async { self.audio.stop() }
        playBox.value = ServerPlayState(timeline: .none, source: music, channel: Ch.L, gain: 0)
        pointUdpBox.value = nil; serverUdpBox.value = nil; sessionId = 0
        pairedAtNs = 0; lastAudioRxAtNs.value = 0
        rx.reset(); clock.reset()
        calibRunning = false
        discovery.startBrowse()
        ui { s in
            s.phase = .idle; s.linkOk = false; s.remembered = record; s.playing = false; s.calib = CalibUi()
            s.serverDiag = Diag(); s.pointDiag = Diag()
            s.loadingTracks = false; s.loadingTrackId = nil; s.audioMissing = false; s.audioBuffering = false
        }
        startSoloPlayback(frame: frame, playing: playing)
        toast(reason)
    }

    private func persistRecord() {
        if let r = record, let d = try? JSONEncoder().encode(r) { defaults.set(d, forKey: "sf.pair") }
    }

    private func saveServerParams() {
        if soloMode { defaults.set(master, forKey: "sf.soloMaster"); return }
        guard var r = record else { return }
        r.pointCh = Ch.name(pointChannel); r.trimMs = Double(trimNs) / 1e6
        r.volL = Double(volL); r.volR = Double(volR); r.master = Double(master); r.balance = Double(balance)
        r.levelTrimServerDb = Double(levelTrimServerDb); r.levelTrimPointDb = Double(levelTrimPointDb)
        r.volTaper = 2; r.levelTrimVersion = 1; r.delayCalib = delayCalib; r.levelCalib = levelCalib
        record = r; persistRecord()
    }

    // ================================================================== сообщения в паре
    private func onPairedMessage(_ o: [String: Any]) {
        switch J.s(o, "t") {
        case "file-ack", "file-error": transferReplies.removeValue(forKey: J.s(o, "id"))?(o)
        case "file-start", "file-chunk", "file-end", "file-cancel": receiveAudio(o)
        case "hb": break
        case "bye": setAutoResume(false); goIdle(sendBye: false, reason: "«\(peerName)» разорвал пару")
        case "name":
            peerName = J.s(o, "name", peerName); ui { $0.peerName = peerName }
            record?.peerName = peerName; persistRecord()
        case "gainAck":
            if role == .server && J.i64(o, "law") >= 2 && !peerGainLawReady { peerGainLawReady = true; publishState() }
        case "levelCapture": if role == .point { receiveLevelCapture(o) }
        case "levelCaptureResult": if role == .server { levelCaptureWaiters.removeValue(forKey: J.s(o, "id"))?(o) }
        case "cmd": if role == .server { handleCmd(o) }
        case "diag":
            if role == .server { let d = Diag.from(o); pointClockReady = d.clockReady; ui { $0.pointDiag = d } }
        case "session":
            guard role == .point, let l = link else { return }
            peerCapabilities = Set(o["caps"] as? [String] ?? [])
            serverFileToken = peerCapabilities.contains("fileTransferV1") ? J.s(o, "fileToken") : ""
            ui { $0.fileTransferAvailable = !serverFileToken.isEmpty }
            let newSession = Int32(truncatingIfNeeded: J.i64(o, "session"))
            if sessionId != newSession {
                sessionId = newSession
                lastAudioRxAtNs.value = 0
                clock.reset(); rx.reset()
            }
            let port = UInt16(truncatingIfNeeded: J.i64(o, "udpPort"))
            if let host = l.remoteHost, let addr = UdpSocket.resolve(host: host, port: port) {
                serverUdpBox.value = addr
                pingBurst = 20
            } else {
                NSLog("SoundField: не удалось определить адрес Server")
            }
        case "state": if role == .point { applyState(o) }
        case "queue": if role == .point { applyQueue(o) }
        case "timeline":
            if role == .point {
                rx.onTimeline(Timeline(epoch: Int32(truncatingIfNeeded: J.i64(o, "epoch")), playing: J.b(o, "playing"),
                                       anchorFrame: J.i64(o, "anchorFrame"), anchorNs: J.i64(o, "anchorNs")))
            }
        case "sdiag": if role == .point { let d = Diag.from(o); ui { $0.serverDiag = d } }
        case "calib": if role == .point { applyCalib(o) }
        default: break
        }
    }

    // ================================================================== Server: шкала и команды
    private var serverChannel: Int { Ch.other(pointChannel) }
    private func balanceGain(_ channel: Int) -> Float { mode == .music ? BalanceLaw.gain(channel: channel, balance: balance) : 1 }
    private var serverGain: Float { soloMode ? AudioGain.taper(master) : AudioGain.taper(master) * AudioGain.fromDb(levelTrimServerDb) * balanceGain(serverChannel) }
    private var pointGain: Float { AudioGain.taper(master) * AudioGain.fromDb(levelTrimPointDb) * balanceGain(pointChannel) }

    func setEq(_ settings: EqSettings, finished: Bool = true) { var o = settings.json; o["finished"] = finished; cmd("eq",o) }
    func setSpatial(_ settings: SpatialSettings, finished: Bool = true) { var o = settings.json; o["finished"] = finished; cmd("spatial",o) }
    private var soundProfileKey: String {
        if !soloMode { return "sf.sound.pair." + (record?.peerId ?? peerName) }
        let route = AVAudioSession.sharedInstance().currentRoute.outputs.map { $0.uid }.sorted().joined(separator: ",")
        return "sf.sound.solo." + route
    }
    private func saveSoundProfile() {
        let value: [String:Any] = ["eq":eqSettings.json,"spatial":spatialSettings.json]
        if let data = try? JSONSerialization.data(withJSONObject:value) { defaults.set(data,forKey:soundProfileKey) }
    }
    private func loadSoundProfile() {
        let value = defaults.data(forKey:soundProfileKey).flatMap { try? JSONSerialization.jsonObject(with:$0) as? [String:Any] } ?? [:]
        eqSettings = EqSettings.read(value["eq"] as? [String:Any] ?? [:])
        spatialSettings = SpatialSettings.read(value["spatial"] as? [String:Any] ?? [:])
        if soloMode { spatialSettings.profile = "solo_stereo_speaker" }
    }
    private func playbackSource() -> AudioSource {
        guard mode == .music && spatialSettings.enabled && (soloMode || peerCapabilities.contains("spatialV1")) else {
            processedSource = nil; processedInner = nil; processedSpatial = nil
            return source
        }
        if processedInner !== source || processedSpatial != spatialSettings {
            processedInner = source; processedSpatial = spatialSettings
            processedSource = StereoSpatialSource(source,settings:spatialSettings)
        }
        return processedSource ?? source
    }

    private func rebuildPlayState() {
        playBox.value = ServerPlayState(timeline: playBox.value.timeline, source: playbackSource(), channel: soloMode ? Ch.BOTH : serverChannel, gain: serverGain)
    }

    private func newEpoch(_ playing: Bool, _ frame: Int64, _ anchorNs: Int64) {
        epoch += 1
        playBox.value = ServerPlayState(timeline: Timeline(epoch: epoch, playing: playing, anchorFrame: frame, anchorNs: anchorNs),
                                        source: playbackSource(), channel: soloMode ? Ch.BOTH : serverChannel, gain: serverGain)
        sendTimeline()
        publishState()
    }

    private func sendTimeline() {
        let tl = playBox.value.timeline
        link?.send(["t": "timeline", "epoch": Int(tl.epoch), "playing": tl.playing,
                    "anchorFrame": String(tl.anchorFrame), "anchorNs": String(tl.anchorNs)])
    }

    private func stateJson() -> [String: Any] {
        let eq = soloMode || peerCapabilities.contains("eq") ? eqSettings : EqSettings()
        let spatial = soloMode || peerCapabilities.contains("spatialV1") ? spatialSettings : SpatialSettings()
        return ["t": "state", "playing": playBox.value.timeline.playing, "durFrames": source.totalFrames,
         "title": source.title, "artist": source.artist, "mode": mode.rawValue,
         "eq": eq.json, "spatial": spatial.json,
         "undo": state.undoText, "queueIndex": queueIndex, "queueCount": queue.count, "trackId": queue.indices.contains(queueIndex) ? queue[queueIndex].id : "",
         "volL": Double(volL), "volR": Double(volR), "master": Double(master), "balance": Double(balance),
         "pointGain": Double(pointGain), "gainLaw": 2, "peerGainLaw": peerGainLawReady ? 2 : 1,
         "levelTrimServerDb": Double(levelTrimServerDb), "levelTrimPointDb": Double(levelTrimPointDb),
         "levelMeasureText": levelMeasureText,
         "delayCalib": ["state": delayCalib.state, "atMs": delayCalib.atMs, "spreadMs": delayCalib.spreadMs],
         "levelCalib": ["state": levelCalib.state, "atMs": levelCalib.atMs, "db": levelCalib.db],
         "pointCh": Ch.name(pointChannel), "trimMs": Double(trimNs) / 1e6, "serverName": myName]
    }

    private func publishQueue() {
        let tracks = queue.map { QueueTrackUi(id: $0.id, title: $0.title, artist: $0.artist) }
        ui { $0.queueTracks = tracks; $0.queueIndex = queueIndex }
        queueRevision += 1
        let parts = max(1, (tracks.count + 99) / 100)
        for part in 0..<parts {
            let items: [[String: String]] = tracks.dropFirst(part * 100).prefix(100).map {
                ["id": $0.id, "title": $0.title, "artist": $0.artist]
            }
            link?.send(["t": "queue", "revision": queueRevision, "part": part, "parts": parts, "items": items])
        }
    }

    private func applyQueue(_ o: [String: Any]) {
        let revision = Int(J.i64(o, "revision", -1)), part = Int(J.i64(o, "part", -1)), parts = Int(J.i64(o, "parts"))
        guard revision >= 0, parts > 0, parts <= 10_000, part >= 0, part < parts,
              let rawItems = o["items"] as? [[String: Any]] else { return }
        if revision != incomingQueueRevision {
            incomingQueueRevision = revision; incomingQueueParts = parts; incomingQueue.removeAll()
        }
        guard incomingQueueParts == parts else { return }
        incomingQueue[part] = rawItems.compactMap { item in
            let id = J.s(item, "id")
            guard !id.isEmpty else { return nil }
            return QueueTrackUi(id: id, title: J.s(item, "title"), artist: J.s(item, "artist"))
        }
        if incomingQueue.count == incomingQueueParts {
            ui { $0.queueTracks = (0..<incomingQueueParts).flatMap { incomingQueue[$0] ?? [] } }
        }
    }

    private func publishState() {
        let s = stateJson()
        applyState(s)
        link?.send(s)
    }

    private func applyState(_ o: [String: Any]) {
        let playing = J.b(o, "playing")
        let dur = J.i64(o, "durFrames")
        let vL = Float(J.d(o, "volL", 1)), vR = Float(J.d(o, "volR", 1)), m = Float(J.d(o, "master", 0.8))
        let b = Float(min(1, max(0, J.d(o, "balance", 0.5))))
        let pCh = Ch.parse(J.s(o, "pointCh", "R"))
        let trim = J.d(o, "trimMs")
        let decodingProgress = (o["decodeProgress"] as? NSNumber)?.doubleValue
        if role == .point {
            if o["pointGain"] != nil && !didAcknowledgeGainLaw {
                didAcknowledgeGainLaw = true; link?.send(["t": "gainAck", "law": 2])
            }
            let gain = o["pointGain"] == nil ? m * (pCh == Ch.L ? vL : vR) * LegacyBalanceLaw.gain(channel: pCh, balance: b)
                : min(1, max(0, Float(J.d(o, "pointGain"))))
            paramsBox.value = PointParams(gain: gain, trimNs: Int64(trim * 1e6), totalFrames: dur)
        }
        let eq = EqSettings.read(o["eq"] as? [String: Any] ?? [:])
        let spatial = SpatialSettings.read(o["spatial"] as? [String: Any] ?? [:])
        let musicMode = J.s(o,"mode") == SourceMode.music.rawValue
        DispatchQueue.main.async { self.audio.setEqualizer(eq, music: musicMode) }
        ui { s in
            s.eq = eq; s.spatial = spatial
            s.eqAvailable = self.soloMode || self.peerCapabilities.contains("eq")
            s.spatialAvailable = musicMode && (self.soloMode || self.peerCapabilities.contains("spatialV1"))
            s.playing = playing; s.durationMs = dur * 1000 / Int64(SF.sampleRate)
            s.audioBuffering = J.b(o, "audioBuffering")
            s.decodingProgress = decodingProgress
            s.title = J.s(o, "title"); s.artist = J.s(o, "artist"); s.mode = SourceMode(rawValue: J.s(o, "mode")) ?? .music
            s.queueIndex = Int(J.i64(o, "queueIndex", -1)); s.undoText = J.s(o, "undo")
            s.volL = vL; s.volR = vR; s.master = m; s.balance = b; s.pointChannel = pCh; s.trimMs = trim
            s.levelTrimServerDb = Float(J.d(o, "levelTrimServerDb")); s.levelTrimPointDb = Float(J.d(o, "levelTrimPointDb"))
            s.levelMeasureText = J.s(o, "levelMeasureText")
            if let c = o["delayCalib"] as? [String: Any] {
                s.delayCalib = DelayCalib(state: J.s(c, "state", "none"), atMs: J.i64(c, "atMs"), spreadMs: J.d(c, "spreadMs"))
            }
            if let c = o["levelCalib"] as? [String: Any] {
                s.levelCalib = LevelCalib(state: J.s(c, "state", "none"), atMs: J.i64(c, "atMs"), db: J.d(c, "db"))
            }
            s.gainLaw = role == .point ? (o["pointGain"] == nil ? 1 : 2) : Int(J.i64(o, "peerGainLaw", 1))
        }
    }

    private func posFrame() -> Int64 {
        min(max(0, playBox.value.timeline.positionFrame(MonoClock.nowNs())), source.totalFrames)
    }

    private func handleCmd(_ o: [String: Any]) {
        let now = MonoClock.nowNs()
        let sr = Int64(SF.sampleRate)
        switch J.s(o, "a") {
        case "play": cmdPlay()
        case "pause": cmdPause()
        case "toggle": if playBox.value.timeline.playing && mode != .calib { cmdPause() } else { cmdPlay() }
        case "seek": seekFrame(J.i64(o, "ms") * sr / 1000)
        case "skip": seekFrame(posFrame() + J.i64(o, "ms") * sr / 1000)
        case "next": selectQueueTrack(at: navigationIndex + 1, startPlayback: true)
        case "previous": selectQueueTrack(at: navigationIndex - 1, startPlayback: true)
        case "select": selectQueueTrack(id: J.s(o, "id"))
        case "remove": removeQueueTracks(Set(o["ids"] as? [String] ?? []))
        case "undoRemove": undoQueueRemoval()
        case "eq":
            guard soloMode || peerCapabilities.contains("eq") else { return }
            eqSettings = EqSettings.read(o); if J.b(o,"finished",true) { saveSoundProfile() }; publishState()
        case "spatial":
            guard mode == .music, soloMode || peerCapabilities.contains("spatialV1") else { return }
            spatialSettings = SpatialSettings.read(o); if J.b(o,"finished",true) { saveSoundProfile() }
            let playing = playBox.value.timeline.playing
            newEpoch(playing,posFrame(), now + (playing ? SF.startDelayNs : 0))
        case "vol":
            if mode == .levelMeasure { toast("Во время измерения общая громкость зафиксирована"); return }
            let v = Float(min(1, max(0, J.d(o, "v", 1))))
            switch J.s(o, "ch") {
            case "L", "R":
                if J.s(o, "ch") == "L" { volL = v } else { volR = v }
                let oldS = serverChannel == Ch.L ? volL : volR, oldP = pointChannel == Ch.L ? volL : volR
                let migrated = AudioGain.migrateChannelGains(serverGain: oldS, pointGain: oldP)
                levelTrimServerDb = migrated.0; levelTrimPointDb = migrated.1
            default: master = J.i64(o, "law", 1) >= 2 ? v : AudioGain.migrateMaster(v)
            }
            rebuildPlayState(); if J.b(o, "finished", true) { saveServerParams() }; publishState()
        case "balance":
            balance = Float(min(1, max(0, J.d(o, "v", 0.5))))
            rebuildPlayState(); if J.b(o, "finished", true) { saveServerParams() }; publishState()
            if J.b(o, "finished", true) { NSLog("SoundField telemetry balance_set balance=%.3f", balance) }
        case "swap":
            guard !calibRunning else { return }
            pointChannel = Ch.other(pointChannel); saveServerParams()
            let tl = playBox.value.timeline
            if tl.playing { newEpoch(true, posFrame(), now + SF.startDelayNs) } else { newEpoch(false, tl.anchorFrame, now) }
            toast("Каналы поменяны: этот телефон — \(Ch.name(serverChannel))")
        case "trim":
            let ms = min(SF.maxTrimMs, max(-SF.maxTrimMs, J.d(o, "ms")))
            trimNs = Int64(ms * 1e6)
            delayCalib = J.b(o, "reset") ? DelayCalib() : DelayCalib(state: "manual", atMs: Int64(Date().timeIntervalSince1970 * 1000))
            saveServerParams(); publishState()
        case "calibrate": startCalibration(apply: true)
        case "measure": startCalibration(apply: false)
        case "metronome":
            if J.b(o, "on") { startTestMode(MetronomeSource()) } else if mode == .metronome { restoreMusic(false) }
        case "lrtest": startTestMode(ChannelTestSource())
        case "levelTrim":
            guard !blockCalibrationIfNeeded("level_trim") else { return }
            let device = J.s(o, "dev"), db = Float(J.d(o, "db"))
            setDeviceLevelTrim(device, db); rebuildPlayState(); saveServerParams(); publishState()
            NSLog("SoundField telemetry level_trim_set dev=%@ db=%.1f source=manual", device, db)
        case "levelOffset":
            guard !blockCalibrationIfNeeded("level_ear") else { return }
            applyLevelOffset(Float(J.d(o, "db"))); rebuildPlayState()
            if J.b(o, "finished") { saveServerParams() }
            publishState()
        case "levelReset":
            guard !blockCalibrationIfNeeded("level_reset") else { return }
            levelTrimServerDb = 0; levelTrimPointDb = 0; levelCalib = LevelCalib()
            rebuildPlayState(); saveServerParams(); publishState()
        case "levelStart":
            guard !calibRunning else { toast("Дождитесь окончания калибровки задержки"); return }
            guard levelStageReady else { toast(levelGateMessage); return }
            levelMatchStartServerDb = levelTrimServerDb; levelMatchStartPointDb = levelTrimPointDb
            startTestMode(LevelMatchSource())
        case "levelStop": stopLevelMatch(J.b(o, "commit"))
        case "centerStart":
            guard !calibRunning else { toast("Дождитесь окончания калибровки задержки"); return }
            guard levelStageReady else { toast(levelGateMessage); return }
            startTestMode(CenterCheckSource())
        case "centerAnswer":
            let answer = J.s(o, "answer", "unknown")
            NSLog("SoundField telemetry center_check answer=%@", answer)
            if answer != "center" { toast("Сначала повторите калибровку задержки — даже 0,1 мс разницы сдвигает звук так же, как 1–1,5 дБ. Если после этого всё ещё тянет, выровняйте уровень на слух.") }
        case "routeChanged": resetCalibrationsForRoute()
        case "levelMicReference": startLevelMicReference(Int(J.i64(o, "position", 1)))
        case "levelMeasure": startLevelMicMeasurement()
        default: break
        }
    }

    private func cmdPlay() {
        guard !calibRunning else { return }
        if pendingTrackId != nil { pendingShouldPlay = true; return }
        if mode != .music { restoreMusic(false) }
        let tl = playBox.value.timeline
        guard !tl.playing else { return }
        let f = tl.anchorFrame >= source.totalFrames ? 0 : tl.anchorFrame
        newEpoch(true, f, MonoClock.nowNs() + SF.startDelayNs)
    }

    private func cmdPause() {
        guard !calibRunning else { return }
        pendingShouldPlay = false
        if mode != .music { restoreMusic(false); return }
        guard playBox.value.timeline.playing else { return }
        newEpoch(false, posFrame(), MonoClock.nowNs())
    }

    private func seekFrame(_ frame: Int64) {
        guard !calibRunning else { return }
        if mode != .music { restoreMusic(false) }
        let f = min(max(0, frame), max(0, source.totalFrames - 1))
        let playing = playBox.value.timeline.playing
        let now = MonoClock.nowNs()
        newEpoch(playing, f, playing ? now + SF.startDelayNs : now)
    }

    private func startTestMode(_ src: AudioSource, anchorNs: Int64? = nil) {
        guard !calibRunning else { return }
        pendingTrackId = nil
        ui { $0.loadingTracks = false; $0.loadingTrackId = nil }
        if mode == .music { savedFrame = posFrame(); savedPlaying = playBox.value.timeline.playing }
        mode = src.mode; source = src
        newEpoch(true, 0, anchorNs ?? MonoClock.nowNs() + SF.startDelayNs)
    }

    private func restoreMusic(_ resume: Bool) {
        mode = .music
        source = queue.indices.contains(queueIndex) ? currentQueueSource ?? music : music
        let now = MonoClock.nowNs()
        newEpoch(resume, savedFrame, resume ? now + SF.startDelayNs : now)
    }

    private func selectQueueTrack(id: String) {
        guard let index = queue.firstIndex(where: { $0.id == id }) else { return }
        selectQueueTrack(at: index, startPlayback: true)
    }

    private var navigationIndex: Int { queue.firstIndex { $0.id == pendingTrackId } ?? queueIndex }

    private func selectQueueTrack(at index: Int, forcePaused: Bool = false, startPlayback: Bool = false) {
        guard !calibRunning, (soloMode || phase == .paired || phase == .reconnecting), queue.indices.contains(index) else { return }
        let item = queue[index]
        if queueIndex == index, pendingTrackId == nil, currentQueueSource != nil {
            if startPlayback { cmdPlay() }
            return
        }
        pendingShouldPlay = !forcePaused && (startPlayback || playBox.value.timeline.playing)
        trackLoadGeneration += 1
        let generation = trackLoadGeneration
        pendingTrackId = item.id
        ui { $0.loadingTracks = true; $0.loadingTrackId = item.id }
        queueIo.async {
            let loaded = try? AudioFileDecoder.decode(PreparedAudioFile(id: item.id, url: item.fileURL, title: item.title, artist: item.artist))
            self.q.async {
                guard self.trackLoadGeneration == generation, self.pendingTrackId == item.id, let index = self.queue.firstIndex(where: { $0.id == item.id }),
                      self.role == .server, !self.calibRunning else { return }
                self.pendingTrackId = nil
                guard let loaded else {
                    self.ui { $0.loadingTracks = false; $0.loadingTrackId = nil }
                    self.toast("Не удалось загрузить «\(item.title)»")
                    return
                }
                self.queueIndex = index; self.mode = .music; self.source = loaded; self.currentQueueSource = loaded; self.savedFrame = 0
                self.ui { $0.loadingTracks = false; $0.loadingTrackId = nil; $0.queueIndex = index }
                let now = MonoClock.nowNs()
                self.newEpoch(self.pendingShouldPlay, 0, self.pendingShouldPlay ? now + SF.startDelayNs : now)
            }
        }
    }

    private func serverTick() {
        guard role == .server, state.inPair || soloMode else { return }
        let tl = playBox.value.timeline
        guard tl.playing, pendingTrackId == nil, tl.positionFrame(MonoClock.nowNs()) >= source.totalFrames else { return }
        switch mode {
        case .music:
            if queueIndex + 1 < queue.count { selectQueueTrack(at: queueIndex + 1) }
            else { newEpoch(false, source.totalFrames, MonoClock.nowNs()) }
        case .lrtest, .center: restoreMusic(savedPlaying)
        default: break
        }
    }

    // ================================================================== калибровка
    private func startCalibration(apply: Bool) {
        guard !calibRunning else { return }
        pendingTrackId = nil
        ui { $0.loadingTracks = false; $0.loadingTrackId = nil }
        guard phase == .paired, pointUdpBox.value != nil else {
            broadcastCalib(false, "", "Point не на связи — калибровка невозможна", false, nil); return
        }
        guard pointClockReady else {
            broadcastCalib(false, "", "Часы ещё синхронизируются. Повторите через пару секунд", false, nil); return
        }
        guard MicRecorder.permissionGranted else {
            broadcastCalib(false, "", "Нужен доступ к микрофону на телефоне «\(myName)» (Server)", false, nil, needsMic: true)
            return
        }
        calibRunning = true
        if mode == .music { savedFrame = posFrame(); savedPlaying = playBox.value.timeline.playing }
        mode = .calib; source = CalibrationSource(pointCh: pointChannel)
        newEpoch(false, 0, MonoClock.nowNs())
        broadcastCalib(true, "Подготовка микрофона…", "", true, nil)
        let trimUsed = trimNs
        let anchor = Locked<Int64>(0)
        DispatchQueue.main.async {
            do {
                try AudioOutput.configureMeasurementSession()
                self.audio.restart()
            } catch {
                self.q.async { self.calibFailed("Не удалось включить микрофон: \(error.localizedDescription)") }
                return
            }
            self.mic.record(durationSec: 8.6, onStarted: {
                self.q.async {
                    let a = MonoClock.nowNs() + 700_000_000
                    anchor.value = a
                    self.newEpoch(true, 0, a)
                    self.broadcastCalib(true, "Звучат тестовые сигналы — тишина, пожалуйста…", "", true, nil)
                }
            }, completion: { result in
                AudioOutput.configurePlaybackSession()
                self.audio.restart()
                switch result {
                case .failure(let e):
                    self.q.async { self.calibFailed("Ошибка микрофона: \(e.localizedDescription)") }
                case .success(let rec):
                    self.q.async { self.broadcastCalib(true, "Анализ записи…", "", true, nil) }
                    DispatchQueue.global(qos: .userInitiated).async {
                        let res = CalibrationAnalyzer.analyze(rec: rec.samples, recSr: rec.sampleRate,
                                                              recStartServerNs: rec.startNs, anchorNs: anchor.value, trimNs: trimUsed)
                        self.q.async { self.finishCalibration(res, apply: apply, trimUsed: trimUsed) }
                    }
                }
            })
        }
    }

    private func calibFailed(_ text: String) {
        calibRunning = false
        restoreMusic(savedPlaying)
        broadcastCalib(false, "", text, false, nil)
    }

    private func finishCalibration(_ res: CalibrationAnalyzer.Result, apply: Bool, trimUsed: Int64) {
        calibRunning = false
        restoreMusic(savedPlaying)
        guard res.ok else { broadcastCalib(false, "", res.message, false, nil); return }
        let who = res.lateMs >= 0 ? "опаздывал" : "спешил"
        if apply {
            let lim = Int64(SF.maxTrimMs * 1e6)
            let newTrim = min(lim, max(-lim, trimUsed - Int64(res.lateMs * 1e6)))
            trimNs = newTrim
            delayCalib = DelayCalib(state: "auto", atMs: Int64(Date().timeIntervalSince1970 * 1000), spreadMs: res.spreadMs)
            saveServerParams(); publishState()
            broadcastCalib(false, "", String(format: "Готово. Point %@ на %.1f мс — компенсировано.\nСдвиг Point: %+.1f мс · точность ±%.1f мс",
                                             who, abs(res.lateMs), Double(newTrim) / 1e6, max(res.spreadMs, 0.1)), true, nil)
        } else {
            broadcastCalib(false, "", String(format: "Фактическое рассогласование L/R: Point %@ на %.1f мс (±%.1f мс)",
                                             who, abs(res.lateMs), max(res.spreadMs, 0.1)), true, res.lateMs)
        }
    }

    private func broadcastCalib(_ running: Bool, _ stage: String, _ result: String, _ ok: Bool, _ measured: Double?, needsMic: Bool = false) {
        var o: [String: Any] = ["t": "calib", "running": running, "stage": stage, "result": result, "ok": ok, "needsMic": needsMic]
        if let m = measured { o["measured"] = m }
        applyCalib(o)
        link?.send(o)
    }

    private func applyCalib(_ o: [String: Any]) {
        let running = J.b(o, "running")
        let measured: Double? = o["measured"] != nil ? J.d(o, "measured") : nil
        ui { s in
            s.calib.running = running
            s.calib.stage = J.s(o, "stage")
            if !running { s.calib.resultText = J.s(o, "result") }
            s.calib.resultOk = J.b(o, "ok", true)
            if let m = measured { s.calib.lastMeasuredMs = m }
            s.calib.needsMicPermission = role == .server && J.b(o, "needsMic")
        }
    }

    // ================================================================== heartbeat + диагностика
    private func heartbeat() {
        ensureUdp()
        autoResumeTick()
        guard let l = link, phase == .paired else { return }
        if Date().timeIntervalSince(l.lastRx) > SF.linkTimeoutSec { log("Нет данных \(Int(SF.linkTimeoutSec)) с — соединение закрыто"); l.close(); return }
        l.send(["t": "hb"])
        if role == .server {
            let d = Diag(underruns: audio.underruns, syncErrMs: serverRenderer.corrector.syncErrorMs,
                         latencyMs: audio.latencyMs, resent: resent, clockReady: true, valid: true)
            ui { $0.serverDiag = d }
            l.send(d.json("sdiag"))
            let now = MonoClock.nowNs()
            let pointAge = state.pointDiag.audioAgeMs
            let audioMissing = playBox.value.timeline.playing && pairedAtNs > 0 && now - pairedAtNs > 5_000_000_000 &&
                (pointUdpBox.value == nil || pointAge < 0 || pointAge >= 5_000)
            ui { $0.audioMissing = audioMissing }
            if audioMissing && now - lastAudioSessionResetNs >= 5_000_000_000 {
                renewAudioSession("Point давно не получает аудио")
            }
        } else {
            let recv = rx.received, lost = rx.lostFinal
            let dR = recv - lastRecv, dL = lost - lastLost
            lastRecv = recv; lastLost = lost
            let loss = dR + dL > 0 ? Double(dL) * 100 / Double(dR + dL) : 0
            let audioAge = lastAudioRxAtNs.value == 0 ? -1 : max(0, (MonoClock.nowNs() - lastAudioRxAtNs.value) / 1_000_000)
            let d = Diag(rttMs: clock.rttAvgMs, rttJitterMs: clock.rttJitterMs, jitterMs: rx.jitterMs, lossPct: loss,
                         recovered: rx.recovered, late: rx.late, stale: rx.stale, bufferMs: rx.bufferMs,
                         driftPpm: clock.driftPpm, clockUncMs: clock.uncertaintyMs,
                         underruns: pointRenderer.underrunEvents + audio.underruns,
                         syncErrMs: pointRenderer.corrector.syncErrorMs, latencyMs: audio.latencyMs,
                         clockReady: clock.ready, audioAgeMs: audioAge, valid: true)
            let now = MonoClock.nowNs()
            ui { $0.pointDiag = d; $0.audioMissing = rx.timeline.playing && pairedAtNs > 0 && now - pairedAtNs > 5_000_000_000 && (audioAge < 0 || audioAge >= 5_000) }
            l.send(d.json("diag"))
        }
    }

    // ================================================================== UDP
    private func onUdp(_ p: UnsafeRawBufferPointer, _ from: UdpSocket.Addr, _ now: Int64) {
        guard let m = Wire.decode(p) else { return }
        let sid = sessionId
        guard sid != 0, m.session == sid else { return }
        switch m {
        case .ping(_, let t1, let seq):
            guard role == .server, phase == .paired else { return }
            pointUdpBox.value = from
            udp?.send(Wire.pong(session: sid, t1: t1, t2: now, t3: MonoClock.nowNs(), pingSeq: seq), to: from)
        case .nack(_, let ep, let seqs):
            if role == .server { resend(epoch: ep, seqs: seqs) }
        case .pong(_, let t1, let t2, let t3, _):
            if role == .point { clock.addPong(t1: t1, t2: t2, t3: t3, t4: now) }
        case .audio(let a):
            if role == .point && phase == .paired {
                lastAudioRxAtNs.value = now
                rx.onAudio(a, arrivalLocalNs: now)
            }
        }
    }

    private func tickLoop() {
        while true {
            if phase == .paired {
                if role == .server { serverSend() } else { pointNet() }
            }
            usleep(5_000)
        }
    }

    private func packet(_ st: ServerPlayState, _ tl: Timeline, seq: Int32, start: Int64, flags: UInt8) -> [UInt8] {
        let count = Int(max(0, min(Int64(SF.packetFrames), st.source.totalFrames - start)))
        let pCh = Ch.other(st.channel)
        var s = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let v = (st.source.sample(start + Int64(i), pCh) * 32767).rounded()
            s[i] = Int16(max(-32768, min(32767, v)))
        }
        return Wire.audio(session: sessionId, epoch: tl.epoch, seq: seq, startFrame: start, playoutNs: tl.timeOf(start),
                          sendNs: MonoClock.nowNs(), flags: flags, samples: s)
    }

    private func serverSend() {
        sendLock.lock(); defer { sendLock.unlock() }
        guard let addr = pointUdpBox.value else { return }
        let st = playBox.value
        let tl = st.timeline
        guard tl.playing else { return }
        if tl.epoch != sentEpoch { sentEpoch = tl.epoch; nextFrame = tl.anchorFrame }
        let target = min(Int64(tl.frameAt(MonoClock.nowNs() + SF.leadNs)), st.source.totalFrames)
        var k = 0
        while nextFrame < target && k < 40 {
            let seq = sendSeq; sendSeq &+= 1
            let b = packet(st, tl, seq: seq, start: nextFrame, flags: 0)
            let i = Int(seq & 1023)
            cacheSeq[i] = seq; cacheEpoch[i] = tl.epoch; cacheStart[i] = nextFrame
            udp?.send(b, to: addr)
            sentAudioPackets.value += 1
            nextFrame += Int64(SF.packetFrames); k += 1
        }
    }

    private func resend(epoch ep: Int32, seqs: [Int32]) {
        sendLock.lock(); defer { sendLock.unlock() }
        guard let addr = pointUdpBox.value else { return }
        let st = playBox.value; let tl = st.timeline
        guard tl.playing, ep == tl.epoch else { return }
        let now = MonoClock.nowNs()
        for seq in seqs {
            let i = Int(seq & 1023)
            guard cacheSeq[i] == seq, cacheEpoch[i] == tl.epoch else { continue }
            if tl.timeOf(cacheStart[i] + Int64(SF.packetFrames)) < now { continue }
            udp?.send(packet(st, tl, seq: seq, start: cacheStart[i], flags: Wire.flagRetransmit), to: addr)
            resent += 1
        }
    }

    private func pointNet() {
        guard let addr = serverUdpBox.value else { return }
        let sid = sessionId
        guard sid != 0 else { return }
        let now = MonoClock.nowNs()
        let interval: Int64 = pingBurst > 0 ? 50_000_000 : 200_000_000
        if now - lastPing >= interval {
            lastPing = now
            if pingBurst > 0 { pingBurst -= 1 }
            udp?.send(Wire.ping(session: sid, t1: now, pingSeq: pingSeq), to: addr)
            pingSeq &+= 1
        }
        let nacks = rx.collectNacks(nowLocal: now)
        if !nacks.isEmpty { udp?.send(Wire.nack(session: sid, epoch: rx.timeline.epoch, seqs: nacks), to: addr) }
    }

    // ================================================================== прерывания
    /// Прерывание (звонок, Siri, другое приложение) на любом телефоне ставит на паузу всю пару.
    private func onInterrupted(_ reason: String) {
        guard state.inPair || soloMode else { return }
        if role == .server { cmdPause() } else { link?.send(["t": "cmd", "a": "pause"]) }
        toast(reason)
    }
    // Queue editing uses track IDs, including commands received from Point.
    func removeTracks(_ ids: Set<String>) { cmd("remove", ["ids": Array(ids)]) }
    func undoRemove() { cmd("undoRemove") }
    private var removedTracks: [(Int, QueueEntry)] = []
    private var undoGeneration = 0
    private func removeQueueTracks(_ ids: Set<String>) {
        guard !calibRunning else { return }
        let removed = queue.enumerated().filter { ids.contains($0.element.id) }.map { ($0.offset, $0.element) }
        guard !removed.isEmpty else { return }
        let current = queue.indices.contains(queueIndex) ? queue[queueIndex].id : nil
        let oldIndex = queueIndex, playing = playBox.value.timeline.playing
        if let pendingTrackId, ids.contains(pendingTrackId) {
            trackLoadGeneration += 1; self.pendingTrackId = nil
            ui { $0.loadingTracks = false; $0.loadingTrackId = nil }
        }
        queue.removeAll { ids.contains($0.id) }; removedTracks = removed
        if let current, !ids.contains(current) { queueIndex = queue.firstIndex { $0.id == current } ?? -1 }
        else {
            currentQueueSource = nil; queueIndex = -1
            if queue.isEmpty { source = music; hasImportedQueue = false; newEpoch(false, 0, MonoClock.nowNs()) }
            else {
                let next = oldIndex - removed.filter { $0.0 < oldIndex }.count
                selectQueueTrack(at: next < queue.count ? max(0, next) : 0, forcePaused: !playing || next >= queue.count, startPlayback: playing && next < queue.count)
            }
        }
        undoGeneration += 1; let generation = undoGeneration
        ui { $0.undoText = "Удалено треков: \(removed.count)" }
        q.asyncAfter(deadline: .now() + 5) {
            if self.undoGeneration == generation { self.removedTracks = []; self.ui { $0.undoText = "" }; self.publishState() }
        }
        publishQueue(); publishState()
    }
    private func undoQueueRemoval() {
        guard !removedTracks.isEmpty else { return }
        let current = queue.indices.contains(queueIndex) ? queue[queueIndex].id : nil
        for (index, item) in removedTracks.sorted(by: { $0.0 < $1.0 }) { queue.insert(item, at: min(index, queue.count)) }
        removedTracks = []; undoGeneration += 1; ui { $0.undoText = "" }
        if let current { queueIndex = queue.firstIndex { $0.id == current } ?? -1 }
        else { hasImportedQueue = true; selectQueueTrack(at: 0, forcePaused: true) }
        publishQueue(); publishState()
    }
    private struct SavedPlaylist: Codable { let id: String; var name: String; var tracks: [QueueEntry] }
    private var playlistsDirectory: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let folder = support.appendingPathComponent("SoundField/Playlists", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
    private func savedPlaylists() -> [SavedPlaylist] {
        let files = (try? FileManager.default.contentsOfDirectory(at: playlistsDirectory, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }.compactMap { url in
            guard let data = try? Data(contentsOf: url) else { return nil }
            return try? JSONDecoder().decode(SavedPlaylist.self, from: data)
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
    private func refreshPlaylists() {
        let list = savedPlaylists().map { PlaylistInfo(id: $0.id, name: $0.name, count: $0.tracks.count) }
        ui { $0.playlists = list }
    }
    func savePlaylist(_ name: String) {
        q.async {
            guard self.role == .server, !self.queue.isEmpty else { return }
            let name = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
            guard !name.isEmpty else { return }
            let playlist = SavedPlaylist(id: UUID().uuidString, name: name, tracks: self.queue)
            do {
                try JSONEncoder().encode(playlist).write(to: self.playlistsDirectory.appendingPathComponent(playlist.id + ".json"), options: .atomic)
                self.refreshPlaylists(); self.toast("Плейлист сохранён")
            } catch { self.toast("Не удалось сохранить плейлист") }
        }
    }
    func openPlaylist(_ id: String, append: Bool) {
        q.async {
            guard self.role == .server, !self.calibRunning, let playlist = self.savedPlaylists().first(where: { $0.id == id }) else { return }
            // A playlist can be appended repeatedly: queue identities remain unique.
            let tracks = playlist.tracks.map { QueueEntry(id: UUID().uuidString, fileURL: $0.fileURL, title: $0.title, artist: $0.artist) }
            self.trackLoadGeneration += 1; self.pendingTrackId = nil
            self.ui { $0.loadingTracks = false; $0.loadingTrackId = nil }
            self.removedTracks = []; self.undoGeneration += 1; self.ui { $0.undoText = "" }
            if append { self.queue.append(contentsOf: tracks) }
            else {
                self.newEpoch(false, 0, MonoClock.nowNs()); self.queue = tracks
                self.queueIndex = -1; self.currentQueueSource = nil
            }
            self.hasImportedQueue = !self.queue.isEmpty
            self.publishQueue()
            if self.queueIndex < 0 && !self.queue.isEmpty { self.selectQueueTrack(at: 0, forcePaused: true) }
            self.publishState()
        }
    }
    func renamePlaylist(_ id: String, name: String) {
        q.async {
            guard var playlist = self.savedPlaylists().first(where: { $0.id == id }) else { return }
            let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
            guard !clean.isEmpty else { return }; playlist.name = clean
            do { try JSONEncoder().encode(playlist).write(to: self.playlistsDirectory.appendingPathComponent(id + ".json"), options: .atomic); self.refreshPlaylists() }
            catch { self.toast("Не удалось переименовать плейлист") }
        }
    }
    func deletePlaylist(_ id: String) {
        q.async {
            guard self.savedPlaylists().contains(where: { $0.id == id }) else { return }
            do { try FileManager.default.removeItem(at: self.playlistsDirectory.appendingPathComponent(id + ".json")); self.refreshPlaylists() }
            catch { self.toast("Не удалось удалить плейлист") }
        }
    }

    private let transferIO = DispatchQueue(label: "sf.file-transfer", qos: .utility)
    private let transferCancelled = Locked(false)
    private var transferActive = false
    private var activeTransferID: String?
    private var transferReplies: [String: ([String: Any]) -> Void] = [:]
    private var fileToken = "", serverFileToken = ""
    private var peerCapabilities = Set<String>()
    private struct IncomingAudio {
        let id: String; let name: String; let size: Int64; let url: URL; let handle: FileHandle
        var received: Int64 = 0
    }
    private var incomingAudio: IncomingAudio?
    private static let audioExtensions = Set(["aac","aif","aiff","alac","amr","flac","m4a","m4b","mp3","oga","ogg","opus","wav","wave","weba","wma"])
    private static func audioURLs(_ selection: URL) -> [URL] {
        guard (try? selection.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { return [selection] }
        let enumerator = FileManager.default.enumerator(at: selection, includingPropertiesForKeys: [.isRegularFileKey, .contentTypeKey], options: [.skipsHiddenFiles, .skipsPackageDescendants])
        return (enumerator?.allObjects as? [URL] ?? []).filter { url in
            guard let value = try? url.resourceValues(forKeys: [.isRegularFileKey, .contentTypeKey]), value.isRegularFile == true else { return false }
            return value.contentType?.conforms(to: .audio) == true || audioExtensions.contains(url.pathExtension.lowercased())
        }.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
    }
    func cancelFileTransfer() { q.async { self.cancelTransfers() } }
    private func cancelTransfers() {
        transferCancelled.value = true
        if let id = activeTransferID { link?.send(["t":"file-cancel", "id":id]) }
        let replies = transferReplies; transferReplies.removeAll()
        replies.values.forEach { $0(["t":"file-error", "message":"Передача отменена"]) }
        if let incoming = incomingAudio { try? incoming.handle.close(); try? FileManager.default.removeItem(at: incoming.url) }
        incomingAudio = nil; ui { $0.fileTransferText = "" }
    }
    private func transferError(_ message: String) -> NSError {
        NSError(domain: "SoundField.Transfer", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
    private func fileReply(_ message: [String: Any], link active: ControlLink, id: String) throws -> [String: Any] {
        let semaphore = DispatchSemaphore(value: 0), box = Locked<[String: Any]?>(nil)
        let sent = q.sync { () -> Bool in
            guard !transferCancelled.value, phase == .paired, link === active, active.isOpen else { return false }
            transferReplies[id] = { box.value = $0; semaphore.signal() }
            active.send(message); return true
        }
        guard sent else { throw transferError("Передача отменена или соединение потеряно") }
        let wait = semaphore.wait(timeout: .now() + 15)
        q.sync { _ = transferReplies.removeValue(forKey: id) }
        guard wait == .success, let reply = box.value else { throw transferError("Server не подтвердил передачу") }
        guard J.s(reply, "t") == "file-ack" else { throw transferError(J.s(reply, "message", "Server отклонил файл")) }
        return reply
    }
    private func sendAudioFiles(_ selections: [URL], securityRoot: URL?) {
        guard !transferActive, phase == .paired, let active = link, !serverFileToken.isEmpty else { toast("Передача недоступна до подключения к Server"); return }
        transferActive = true; transferCancelled.value = false
        let token = serverFileToken, rootAccess = securityRoot?.startAccessingSecurityScopedResource() ?? false
        ui { $0.fileTransferText = "Send…" }
        transferIO.async {
            var opened: [URL] = [], failure: String?
            defer {
                opened.forEach { $0.stopAccessingSecurityScopedResource() }
                if rootAccess { securityRoot?.stopAccessingSecurityScopedResource() }
                self.q.async {
                    self.transferActive = false; self.activeTransferID = nil; self.ui { $0.fileTransferText = "" }
                    self.toast(failure ?? "Файлы отправлены на Server")
                }
            }
            do {
                let files = selections.flatMap { selection -> [URL] in
                    if selection.startAccessingSecurityScopedResource() { opened.append(selection) }
                    return Self.audioURLs(selection)
                }
                let sizes = try files.map { url -> Int64 in
                    guard Self.audioExtensions.contains(url.pathExtension.lowercased()),
                          let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0, size <= 512 * 1024 * 1024 else {
                        throw self.transferError("Файл пуст, не поддерживается или больше 512 МБ")
                    }
                    return Int64(size)
                }
                let total = sizes.reduce(0, +)
                guard !files.isEmpty, total <= 1024 * 1024 * 1024 else { throw self.transferError("Нет аудиофайлов или выбранный объём больше 1 ГБ") }
                var sent: Int64 = 0
                for (index, url) in files.enumerated() {
                    let id = UUID().uuidString, size = sizes[index]
                    self.q.sync { self.activeTransferID = id }
                    let start = try self.fileReply(["t":"file-start", "id":id, "name":url.lastPathComponent, "size":size, "token":token], link: active, id: id)
                    guard J.i64(start, "offset", -1) == 0, let stream = InputStream(url: url) else { throw self.transferError("Не удалось открыть файл") }
                    stream.open(); defer { stream.close() }
                    var offset: Int64 = 0, bytes = [UInt8](repeating: 0, count: 48 * 1024)
                    while true {
                        guard !self.transferCancelled.value else { throw self.transferError("Передача отменена") }
                        let count = stream.read(&bytes, maxLength: bytes.count)
                        guard count >= 0 else { throw stream.streamError ?? self.transferError("Не удалось прочитать файл") }
                        if count == 0 { break }
                        let data = Data(bytes.prefix(count)).base64EncodedString()
                        let reply = try self.fileReply(["t":"file-chunk", "id":id, "offset":offset, "data":data], link: active, id: id)
                        offset += Int64(count); sent += Int64(count)
                        guard J.i64(reply, "offset", -1) == offset else { throw self.transferError("Неверное подтверждение Server") }
                        let percent = Int(sent * 100 / max(1, total))
                        self.q.async { if !self.transferCancelled.value { self.ui { $0.fileTransferText = "Send \(percent)%" } } }
                    }
                    guard offset == size else { throw self.transferError("Файл изменился во время передачи") }
                    let end = try self.fileReply(["t":"file-end", "id":id], link: active, id: id)
                    guard J.b(end, "complete") else { throw self.transferError("Server не сохранил файл") }
                }
            } catch {
                failure = error.localizedDescription
                self.q.sync { if let id = self.activeTransferID { active.send(["t":"file-cancel", "id":id]) } }
            }
        }
    }
    private func receiveAudio(_ o: [String: Any]) {
        let id = J.s(o, "id"), type = J.s(o, "t")
        do {
            switch type {
            case "file-start":
                let size = J.i64(o, "size"), name = URL(fileURLWithPath: J.s(o, "name")).lastPathComponent
                guard role == .server, phase == .paired, !calibRunning, incomingAudio == nil,
                      UUID(uuidString: id) != nil, J.s(o, "token") == fileToken, !fileToken.isEmpty,
                      size > 0, size <= 512 * 1024 * 1024, Self.audioExtensions.contains(URL(fileURLWithPath: name).pathExtension.lowercased()) else {
                    throw transferError("Передача не поддерживается или файл некорректен")
                }
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent("SoundFieldReceive", isDirectory: true)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let url = folder.appendingPathComponent(id + ".part")
                guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw transferError("Не удалось создать файл") }
                incomingAudio = IncomingAudio(id: id, name: name, size: size, url: url, handle: try FileHandle(forWritingTo: url))
                ui { $0.fileTransferText = "Получение от Point · 0%" }
                link?.send(["t":"file-ack", "id":id, "offset":0])
            case "file-chunk":
                guard var incoming = incomingAudio, incoming.id == id, J.i64(o, "offset", -1) == incoming.received,
                      let bytes = Data(base64Encoded: J.s(o, "data")), !bytes.isEmpty, bytes.count <= 48 * 1024,
                      incoming.received + Int64(bytes.count) <= incoming.size else { throw transferError("Нарушен порядок блоков файла") }
                try incoming.handle.write(contentsOf: bytes); incoming.received += Int64(bytes.count); incomingAudio = incoming
                ui { $0.fileTransferText = "Получение от Point · \(incoming.received * 100 / incoming.size)%" }
                link?.send(["t":"file-ack", "id":id, "offset":incoming.received])
            case "file-end":
                guard let incoming = incomingAudio, incoming.id == id, incoming.received == incoming.size else { throw transferError("Файл передан не полностью") }
                try incoming.handle.close()
                let directory = incoming.url.deletingLastPathComponent().appendingPathComponent(incoming.id, isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let target = directory.appendingPathComponent(incoming.name)
                try FileManager.default.moveItem(at: incoming.url, to: target); incomingAudio = nil
                addAudioFiles([target], deleteAfterImport: [target])
                ui { $0.fileTransferText = "" }
                link?.send(["t":"file-ack", "id":id, "offset":incoming.size, "complete":true])
            case "file-cancel":
                if let incoming = incomingAudio, incoming.id == id {
                    try? incoming.handle.close(); try? FileManager.default.removeItem(at: incoming.url); incomingAudio = nil
                    ui { $0.fileTransferText = "" }
                }
            default: break
            }
        } catch {
            if let incoming = incomingAudio, incoming.id == id {
                try? incoming.handle.close(); try? FileManager.default.removeItem(at: incoming.url); incomingAudio = nil
                ui { $0.fileTransferText = "" }
            }
            link?.send(["t":"file-error", "id":id, "message":error.localizedDescription])
        }
    }

}
