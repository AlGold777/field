import Foundation
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
    private struct QueueEntry {
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
    private var currentQueueSource: AudioSource?
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
    private var savedFrame: Int64 = 0
    private var savedPlaying = false
    private var volL: Float = 1, volR: Float = 1, master: Float = 0.8
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
            if let support = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                          appropriateFor: nil, create: true) {
                let folder = support.appendingPathComponent("SoundField/AudioQueue", isDirectory: true)
                if let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) {
                    files.forEach { try? FileManager.default.removeItem(at: $0) }
                }
            }
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
        guard let u = udpBox.value else { return }
        if role == .server, phase == .paired, let l = link {
            l.send(["t": "session", "session": Int(sessionId), "udpPort": Int(u.port), "name": myName])
        }
    }

    /// Приложение снова на экране: проверяем сокеты, аудио и связь — не ждём таймаутов.
    private func onForeground() {
        discovery.ensureListening()
        ensureUdp()
        if state.inPair { DispatchQueue.main.async { self.audio.ensureRunning() } }
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

    func togglePlay() { cmd("toggle") }
    func play() { cmd("play") }
    func pause() { cmd("pause") }
    func seekMs(_ ms: Int64) { cmd("seek", ["ms": ms]) }
    func skipMs(_ d: Int64) { cmd("skip", ["ms": d]) }
    func swapChannels() { cmd("swap") }
    func setTrimMs(_ ms: Double) { cmd("trim", ["ms": ms]) }
    func calibrate() { cmd("calibrate") }
    func measure() { cmd("measure") }
    func metronome(_ on: Bool) { cmd("metronome", ["on": on]) }
    func channelTest() { cmd("lrtest") }
    func requestTrackPicker() {
        q.async {
            guard !self.calibRunning else { self.toast("Сначала завершите калибровку"); return }
            if self.role == .server { self.ui { $0.filePickerRequest += 1 } }
            else { self.toast("Добавьте файлы на телефоне Server") }
        }
    }
    func addAudioFiles(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        q.async {
            guard self.role == .server, self.phase == .paired || self.phase == .reconnecting, !self.calibRunning else {
                self.toast("Добавление музыки сейчас недоступно"); return
            }
            self.ui { $0.loadingTracks = true }
            self.queueIo.async {
                var prepared: [PreparedAudioFile] = []
                var failures = 0
                for selection in urls {
                    let accessed = selection.startAccessingSecurityScopedResource()
                    defer { if accessed { selection.stopAccessingSecurityScopedResource() } }
                    let values = try? selection.resourceValues(forKeys: [.isDirectoryKey])
                    let candidates: [URL]
                    if values?.isDirectory == true {
                        let enumerator = FileManager.default.enumerator(at: selection,
                            includingPropertiesForKeys: [.isRegularFileKey, .contentTypeKey],
                            options: [.skipsHiddenFiles, .skipsPackageDescendants])
                        candidates = (enumerator?.allObjects as? [URL] ?? []).filter { url in
                            let resource = try? url.resourceValues(forKeys: [.isRegularFileKey, .contentTypeKey])
                            return resource?.isRegularFile == true && (resource?.contentType?.conforms(to: .audio) == true ||
                                ["aac", "aif", "aiff", "alac", "amr", "flac", "m4a", "m4b", "mp3", "oga", "ogg", "opus", "wav", "weba", "wma"].contains(url.pathExtension.lowercased()))
                        }.sorted { $0.path.localizedStandardCompare($1.path) == .orderedAscending }
                    } else { candidates = [selection] }
                    for candidate in candidates {
                        do { prepared.append(try AudioFileDecoder.prepare(candidate)) }
                        catch { failures += 1; NSLog("SoundField: could not import %@: %@", candidate.lastPathComponent, error.localizedDescription) }
                    }
                }
                self.q.async {
                    guard self.role == .server, self.phase == .paired || self.phase == .reconnecting, !self.calibRunning else {
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
                        self.queue.forEach { try? FileManager.default.removeItem(at: $0.fileURL) }
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
    func selectQueueTrack(_ id: String) { cmd("select", ["id": id]) }
    func setQueueExpanded(_ expanded: Bool) {
        q.async { self.defaults.set(expanded, forKey: "sf.queueExpanded"); self.ui { $0.queueExpanded = expanded } }
    }
    func setVolume(_ ch: String, _ v: Float) {
        q.async { self.ui { s in switch ch { case "L": s.volL = v; case "R": s.volR = v; default: s.master = v } } }
        cmd("vol", ["ch": ch, "v": Double(v)])
    }
    func micPermissionGranted() { q.async { self.ui { $0.calib.needsMicPermission = false; $0.calib.resultText = "" } } }

    private func cmd(_ a: String, _ extra: [String: Any] = [:]) {
        var o: [String: Any] = ["t": "cmd", "a": a]
        for (k, v) in extra { o[k] = v }
        q.async {
            guard self.state.inPair else { return }
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
         "tcpPort": Int(discovery.listenPort)]
    }
    private func resumeJson(_ r: PairRecord) -> [String: Any] {
        ["t": "resume", "id": myId, "name": myName, "platform": "ios", "proto": Int(SF.protocolVersion), "key": r.key, "role": r.myRole,
         "tcpPort": Int(discovery.listenPort)]
    }
    private func acceptJson(_ key: String, _ role: String) -> [String: Any] {
        ["t": "accept", "id": myId, "name": myName, "platform": "ios", "key": key, "role": role, "tcpPort": Int(discovery.listenPort)]
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

        if r == .server {
            if let rec = record, !continuing {
                pointChannel = Ch.parse(rec.pointCh); trimNs = Int64(rec.trimMs * 1e6)
                volL = Float(rec.volL); volR = Float(rec.volR); master = Float(rec.master)
            }
            sessionId = Int32.random(in: 1...Int32.max)
            pointUdpBox.value = nil; pointClockReady = false
            if !continuing {
                mode = .music
                source = queue.indices.contains(queueIndex) ? currentQueueSource ?? music : music
                newEpoch(false, 0, MonoClock.nowNs())
                if queue.indices.contains(queueIndex), currentQueueSource == nil { selectQueueTrack(at: queueIndex, forcePaused: true) }
            } else { rebuildPlayState() }
            audio.source = serverRenderer
            l.send(["t": "session", "session": Int(sessionId), "udpPort": Int(udp?.port ?? 0), "name": myName])
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
            s.phase = .paired; s.role = r; s.peerName = pname; s.peerPlatform = pplat; s.linkOk = true
            s.remembered = record; s.calib = CalibUi(); s.autoResuming = false
        }
        toast(resumed ? "Пара восстановлена" : "Стереопара создана")
    }

    private func onLinkLost() {
        link = nil
        guard phase == .paired else { return }
        phase = .reconnecting
        ui { $0.phase = .reconnecting; $0.linkOk = false }
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
        rx.reset(); clock.reset()
        calibRunning = false
        discovery.startBrowse()
        ui { s in
            s.phase = .idle; s.linkOk = false; s.remembered = record; s.playing = false; s.calib = CalibUi()
            s.serverDiag = Diag(); s.pointDiag = Diag()
            s.loadingTracks = false; s.loadingTrackId = nil
        }
        toast(reason)
    }

    private func persistRecord() {
        if let r = record, let d = try? JSONEncoder().encode(r) { defaults.set(d, forKey: "sf.pair") }
    }

    private func saveServerParams() {
        guard var r = record else { return }
        r.pointCh = Ch.name(pointChannel); r.trimMs = Double(trimNs) / 1e6
        r.volL = Double(volL); r.volR = Double(volR); r.master = Double(master)
        record = r; persistRecord()
    }

    // ================================================================== сообщения в паре
    private func onPairedMessage(_ o: [String: Any]) {
        switch J.s(o, "t") {
        case "hb": break
        case "bye": setAutoResume(false); goIdle(sendBye: false, reason: "«\(peerName)» разорвал пару")
        case "name":
            peerName = J.s(o, "name", peerName); ui { $0.peerName = peerName }
            record?.peerName = peerName; persistRecord()
        case "cmd": if role == .server { handleCmd(o) }
        case "diag":
            if role == .server { let d = Diag.from(o); pointClockReady = d.clockReady; ui { $0.pointDiag = d } }
        case "session":
            guard role == .point, let l = link else { return }
            sessionId = Int32(truncatingIfNeeded: J.i64(o, "session"))
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
    private var serverGain: Float { master * (serverChannel == Ch.L ? volL : volR) }

    private func rebuildPlayState() {
        playBox.value = ServerPlayState(timeline: playBox.value.timeline, source: source, channel: serverChannel, gain: serverGain)
    }

    private func newEpoch(_ playing: Bool, _ frame: Int64, _ anchorNs: Int64) {
        epoch += 1
        playBox.value = ServerPlayState(timeline: Timeline(epoch: epoch, playing: playing, anchorFrame: frame, anchorNs: anchorNs),
                                        source: source, channel: serverChannel, gain: serverGain)
        sendTimeline()
        publishState()
    }

    private func sendTimeline() {
        let tl = playBox.value.timeline
        link?.send(["t": "timeline", "epoch": Int(tl.epoch), "playing": tl.playing,
                    "anchorFrame": String(tl.anchorFrame), "anchorNs": String(tl.anchorNs)])
    }

    private func stateJson() -> [String: Any] {
        ["t": "state", "playing": playBox.value.timeline.playing, "durFrames": source.totalFrames,
         "title": source.title, "artist": source.artist, "mode": mode.rawValue,
         "queueIndex": queueIndex, "queueCount": queue.count, "trackId": queue.indices.contains(queueIndex) ? queue[queueIndex].id : "",
         "volL": Double(volL), "volR": Double(volR), "master": Double(master),
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
        let pCh = Ch.parse(J.s(o, "pointCh", "R"))
        let trim = J.d(o, "trimMs")
        if role == .point {
            paramsBox.value = PointParams(gain: m * (pCh == Ch.L ? vL : vR), trimNs: Int64(trim * 1e6), totalFrames: dur)
        }
        ui { s in
            s.playing = playing; s.durationMs = dur * 1000 / Int64(SF.sampleRate)
            s.title = J.s(o, "title"); s.artist = J.s(o, "artist"); s.mode = SourceMode(rawValue: J.s(o, "mode")) ?? .music
            s.queueIndex = Int(J.i64(o, "queueIndex", -1))
            s.volL = vL; s.volR = vR; s.master = m; s.pointChannel = pCh; s.trimMs = trim
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
        case "next": selectQueueTrack(at: queueIndex + 1)
        case "previous": selectQueueTrack(at: max(0, queueIndex - 1))
        case "select": selectQueueTrack(id: J.s(o, "id"))
        case "vol":
            let v = Float(min(1, max(0, J.d(o, "v", 1))))
            switch J.s(o, "ch") { case "L": volL = v; case "R": volR = v; default: master = v }
            rebuildPlayState(); saveServerParams(); publishState()
        case "swap":
            guard !calibRunning else { return }
            pointChannel = Ch.other(pointChannel); saveServerParams()
            let tl = playBox.value.timeline
            if tl.playing { newEpoch(true, posFrame(), now + SF.startDelayNs) } else { newEpoch(false, tl.anchorFrame, now) }
            toast("Каналы поменяны: этот телефон — \(Ch.name(serverChannel))")
        case "trim":
            let ms = min(SF.maxTrimMs, max(-SF.maxTrimMs, J.d(o, "ms")))
            trimNs = Int64(ms * 1e6); saveServerParams(); publishState()
        case "calibrate": startCalibration(apply: true)
        case "measure": startCalibration(apply: false)
        case "metronome":
            if J.b(o, "on") { startTestMode(MetronomeSource()) } else if mode == .metronome { restoreMusic(false) }
        case "lrtest": startTestMode(ChannelTestSource())
        default: break
        }
    }

    private func cmdPlay() {
        guard !calibRunning else { return }
        if mode != .music { restoreMusic(false) }
        let tl = playBox.value.timeline
        guard !tl.playing else { return }
        let f = tl.anchorFrame >= source.totalFrames ? 0 : tl.anchorFrame
        newEpoch(true, f, MonoClock.nowNs() + SF.startDelayNs)
    }

    private func cmdPause() {
        guard !calibRunning else { return }
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

    private func startTestMode(_ src: AudioSource) {
        guard !calibRunning else { return }
        pendingTrackId = nil
        ui { $0.loadingTracks = false; $0.loadingTrackId = nil }
        if mode == .music { savedFrame = posFrame(); savedPlaying = playBox.value.timeline.playing }
        mode = src.mode; source = src
        newEpoch(true, 0, MonoClock.nowNs() + SF.startDelayNs)
    }

    private func restoreMusic(_ resume: Bool) {
        mode = .music
        source = queue.indices.contains(queueIndex) ? currentQueueSource ?? music : music
        let now = MonoClock.nowNs()
        newEpoch(resume, savedFrame, resume ? now + SF.startDelayNs : now)
    }

    private func selectQueueTrack(id: String) {
        guard let index = queue.firstIndex(where: { $0.id == id }) else { return }
        selectQueueTrack(at: index)
    }

    private func selectQueueTrack(at index: Int, forcePaused: Bool = false) {
        guard !calibRunning, (phase == .paired || phase == .reconnecting), queue.indices.contains(index) else { return }
        let item = queue[index]
        guard queueIndex != index || pendingTrackId != nil else { return }
        let shouldPlay = !forcePaused && playBox.value.timeline.playing
        pendingTrackId = item.id
        ui { $0.loadingTracks = true; $0.loadingTrackId = item.id }
        queueIo.async {
            let loaded = try? AudioFileDecoder.decode(PreparedAudioFile(id: item.id, url: item.fileURL, title: item.title, artist: item.artist))
            self.q.async {
                guard self.pendingTrackId == item.id, self.queue.indices.contains(index), self.queue[index].id == item.id,
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
                self.newEpoch(shouldPlay, 0, shouldPlay ? now + SF.startDelayNs : now)
            }
        }
    }

    private func serverTick() {
        guard role == .server, state.inPair else { return }
        let tl = playBox.value.timeline
        guard tl.playing, pendingTrackId == nil, tl.positionFrame(MonoClock.nowNs()) >= source.totalFrames else { return }
        switch mode {
        case .music:
            if queueIndex + 1 < queue.count { selectQueueTrack(at: queueIndex + 1) }
            else { newEpoch(false, source.totalFrames, MonoClock.nowNs()) }
        case .lrtest: restoreMusic(savedPlaying)
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
            trimNs = newTrim; saveServerParams(); publishState()
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
        } else {
            let recv = rx.received, lost = rx.lostFinal
            let dR = recv - lastRecv, dL = lost - lastLost
            lastRecv = recv; lastLost = lost
            let loss = dR + dL > 0 ? Double(dL) * 100 / Double(dR + dL) : 0
            let d = Diag(rttMs: clock.rttAvgMs, rttJitterMs: clock.rttJitterMs, jitterMs: rx.jitterMs, lossPct: loss,
                         recovered: rx.recovered, late: rx.late, stale: rx.stale, bufferMs: rx.bufferMs,
                         driftPpm: clock.driftPpm, clockUncMs: clock.uncertaintyMs,
                         underruns: pointRenderer.underrunEvents + audio.underruns,
                         syncErrMs: pointRenderer.corrector.syncErrorMs, latencyMs: audio.latencyMs,
                         clockReady: clock.ready, valid: true)
            ui { $0.pointDiag = d }
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
            if role == .point && phase == .paired { rx.onAudio(a, arrivalLocalNs: now) }
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
        guard state.inPair else { return }
        if role == .server { cmdPause() } else { link?.send(["t": "cmd", "a": "pause"]) }
        toast(reason)
    }
}
