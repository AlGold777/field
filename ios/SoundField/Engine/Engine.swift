import Foundation
import Network
import UIKit
import AVFoundation

/// Модель для SwiftUI (главный поток).
final class AppModel: ObservableObject {
    @Published var ui = UiState()
}

/// Сердце приложения — порт Android Engine.kt с тем же поведением и протоколом.
///
/// Потоки: q (serial) — всё состояние сессии; sf-udp — приём UDP; sf-tick — каждые 5 мс отправка аудио (Server)
/// или PING/NACK (Point); аудиопоток — AudioOutput → ServerRenderer/PointRenderer.
final class Engine {
    static let shared = Engine()

    let model = AppModel()
    private let q = DispatchQueue(label: "sf.engine", qos: .userInitiated)
    private let defaults = UserDefaults.standard

    let myId: String
    private(set) var myName: String
    private var state = UiState()

    // аудио
    let music: PcmSource
    private let audio = AudioOutput()
    private let mic = MicRecorder()

    // сеть
    private var discovery: Discovery!
    private var udp: UdpSocket!

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
    private var peerId = "", peerName = "", peerPlatform = ""
    private let sessionBox = Locked<Int32>(0)
    private var sessionId: Int32 { get { sessionBox.value } set { sessionBox.value = newValue } }
    private var record: PairRecord?
    private var reconnectTask: DispatchWorkItem?
    private var lastBrowseRestart = Date.distantPast
    private var manualResumeDeadline = Date.distantPast

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
            udp = UdpSocket()
            udp.onPacket = { [weak self] p, from, now in self?.onUdp(p, from, now) }
            discovery = Discovery(queue: q, myId: myId)
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
            }
        }
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
    func forgetPair() { q.async { self.record = nil; self.defaults.removeObject(forKey: "sf.pair"); self.ui { $0.remembered = nil } } }
    func disconnect() { q.async { self.goIdle(sendBye: true, reason: "Пара разорвана") } }
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
        ["t": "invite", "id": myId, "name": myName, "platform": "ios", "proto": Int(SF.protocolVersion), "key": key]
    }
    private func resumeJson(_ r: PairRecord) -> [String: Any] {
        ["t": "resume", "id": myId, "name": myName, "platform": "ios", "proto": Int(SF.protocolVersion), "key": r.key, "role": r.myRole]
    }

    private func doInvite(_ p: Peer) {
        guard phase == .idle else { return }
        pendingKey = Self.hex(16)
        phase = .inviting
        ui { $0.phase = .inviting; $0.invitingName = p.name }
        connectOutgoing(p, tag: "invite-out", first: inviteJson(pendingKey), timeout: 30)
    }

    private func doManualResume() {
        guard let rec = record, phase == .idle else { return }
        phase = .inviting
        ui { $0.phase = .inviting; $0.invitingName = rec.peerName }
        manualResumeDeadline = Date().addingTimeInterval(8)
        discovery.startBrowse()
        tryManualResume()
    }

    private func tryManualResume() {
        guard phase == .inviting, pending == nil, let rec = record else { return }
        if let p = discovery.peer(id: rec.peerId) { connectOutgoing(p, tag: "resume-out", first: resumeJson(rec), timeout: 4); return }
        if Date() > manualResumeDeadline {
            phase = .idle; ui { $0.phase = .idle }
            toast("«\(rec.peerName)» не найден. Откройте SoundField на втором телефоне в той же сети Wi‑Fi")
            return
        }
        later(0.5) { [weak self] in self?.tryManualResume() }
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
        if let l = pending { pending = nil; l.tag = "dead"; l.sendAndClose(["t": "cancel"]) }
        if phase == .inviting || phase == .incoming { phase = .idle; ui { $0.phase = .idle } }
    }

    private func doAccept() {
        guard let l = pending, let p = pendingPeer, phase == .incoming else { return }
        l.send(["t": "accept", "id": myId, "name": myName, "platform": "ios", "key": pendingKey, "role": Role.point.rawValue])
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
            if phase == .idle && pending == nil {
                l.tag = "incoming"; pending = l
                pendingPeer = dummyPeer(pid, pname, pplat, l)
                pendingKey = J.s(o, "key")
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
                l.tag = "dead"; l.sendAndClose(["t": "reject", "reason": "unknown"]); return
            }
            if let old = link { link = nil; old.tag = "dead"; old.close() }
            if let p = pending { pending = nil; p.tag = "dead"; p.close() }
            l.send(["t": "accept", "id": myId, "name": myName, "platform": "ios", "key": rec.key, "role": rec.myRole])
            becomePaired(l, rec.role, pid, pname, pplat, rec.key, resumed: true)
        default:
            l.tag = "dead"; l.close()
        }
    }

    private func onPendingMessage(_ l: ControlLink, _ o: [String: Any]) {
        let pid = J.s(o, "id"), pname = J.s(o, "name", pid), pplat = J.s(o, "platform", "?")
        switch J.s(o, "t") {
        case "accept":
            if l.tag == "invite-out" { becomePaired(l, .server, pid, pname, pplat, pendingKey, resumed: false) }
            else if l.tag == "resume-out" {
                let their = Role(rawValue: J.s(o, "role")) ?? .point
                becomePaired(l, their == .server ? .point : .server, pid, pname, pplat, J.s(o, "key"), resumed: true)
            }
        case "reject", "busy":
            let reason = J.s(o, "reason")
            pending = nil; l.tag = "dead"; l.close()
            if phase == .inviting && reason == "crossed" { /* ждём встречный accept */ }
            else if phase == .inviting {
                phase = .idle; ui { $0.phase = .idle }
                toast(J.s(o, "t") == "busy" ? "Телефон уже в другой паре" : "Подключение отклонено")
            } else if phase == .reconnecting { scheduleReconnect(2) }
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
        case .reconnecting: scheduleReconnect(1.5)
        default: break
        }
    }

    // ================================================================== пара
    private func becomePaired(_ l: ControlLink, _ r: Role, _ pid: String, _ pname: String, _ pplat: String, _ key: String, resumed: Bool) {
        pendingTimeout?.cancel(); reconnectTask?.cancel()
        if pending === l { pending = nil }
        if let p = pending { p.tag = "dead"; p.close(); pending = nil }
        let continuing = phase == .reconnecting && role == r && peerId == pid
        l.tag = "paired"
        link = l
        role = r; peerId = pid; peerName = pname; peerPlatform = pplat
        phase = .paired

        if var old = record, old.key == key {
            old.peerName = pname; old.peerPlatform = pplat; old.myRole = r.rawValue; record = old
        } else {
            record = PairRecord(key: key, peerId: pid, peerName: pname, peerPlatform: pplat, myRole: r.rawValue)
        }
        persistRecord()
        discovery.stopBrowse()

        if r == .server {
            if let rec = record, !continuing {
                pointChannel = Ch.parse(rec.pointCh); trimNs = Int64(rec.trimMs * 1e6)
                volL = Float(rec.volL); volR = Float(rec.volR); master = Float(rec.master)
            }
            sessionId = Int32.random(in: 1...Int32.max)
            pointUdpBox.value = nil; pointClockReady = false
            if !continuing { mode = .music; source = music; newEpoch(false, 0, MonoClock.nowNs()) } else { rebuildPlayState() }
            audio.source = serverRenderer
            l.send(["t": "session", "session": Int(sessionId), "udpPort": Int(udp.port), "name": myName])
            sendTimeline()
            publishState()
        } else {
            sessionId = 0; serverUdpBox.value = nil
            clock.reset(); rx.reset()
            audio.source = pointRenderer
        }
        DispatchQueue.main.async { self.audio.start() }
        ui { s in
            s.phase = .paired; s.role = r; s.peerName = pname; s.peerPlatform = pplat; s.linkOk = true
            s.remembered = record; s.calib = CalibUi()
        }
        toast(resumed ? "Пара восстановлена" : "Стереопара создана")
    }

    private func onLinkLost() {
        link = nil
        guard phase == .paired else { return }
        phase = .reconnecting
        ui { $0.phase = .reconnecting; $0.linkOk = false }
        if role == .server {
            pointUdpBox.value = nil
            toast("Нет связи с «\(peerName)». Музыка продолжается, ждём возвращения…")
        } else {
            rx.reset(); serverUdpBox.value = nil; sessionId = 0
            discovery.startBrowse()
            scheduleReconnect(0.3)
            toast("Связь потеряна — переподключаемся…")
        }
    }

    private func scheduleReconnect(_ sec: Double) {
        reconnectTask?.cancel()
        reconnectTask = later(sec) { [weak self] in self?.attemptResume() }
    }

    private func attemptResume() {
        guard phase == .reconnecting, role == .point, pending == nil, let rec = record else { return }
        if Date().timeIntervalSince(lastBrowseRestart) > 10 { lastBrowseRestart = Date(); discovery.restartBrowse() }
        guard let p = discovery.peer(id: rec.peerId) else { scheduleReconnect(1); return }
        connectOutgoing(p, tag: "resume-out", first: resumeJson(rec), timeout: 4)
    }

    private func goIdle(sendBye: Bool, reason: String) {
        reconnectTask?.cancel(); pendingTimeout?.cancel()
        if let l = link { link = nil; l.tag = "dead"; if sendBye { l.sendAndClose(["t": "bye"]) } else { l.close() } }
        if let p = pending { pending = nil; p.tag = "dead"; p.close() }
        phase = .idle
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
        case "bye": goIdle(sendBye: false, reason: "«\(peerName)» разорвал пару")
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
         "volL": Double(volL), "volR": Double(volR), "master": Double(master),
         "pointCh": Ch.name(pointChannel), "trimMs": Double(trimNs) / 1e6, "serverName": myName]
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
        if mode == .music { savedFrame = posFrame(); savedPlaying = playBox.value.timeline.playing }
        mode = src.mode; source = src
        newEpoch(true, 0, MonoClock.nowNs() + SF.startDelayNs)
    }

    private func restoreMusic(_ resume: Bool) {
        mode = .music; source = music
        let now = MonoClock.nowNs()
        newEpoch(resume, savedFrame, resume ? now + SF.startDelayNs : now)
    }

    private func serverTick() {
        guard role == .server, state.inPair else { return }
        let tl = playBox.value.timeline
        guard tl.playing, tl.positionFrame(MonoClock.nowNs()) >= source.totalFrames else { return }
        switch mode {
        case .music: newEpoch(false, 0, MonoClock.nowNs())
        case .lrtest: restoreMusic(savedPlaying)
        default: break
        }
    }

    // ================================================================== калибровка
    private func startCalibration(apply: Bool) {
        guard !calibRunning else { return }
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
        guard let l = link, phase == .paired else { return }
        if Date().timeIntervalSince(l.lastRx) > SF.linkTimeoutSec { NSLog("SoundField heartbeat timeout"); l.close(); return }
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
            udp.send(Wire.pong(session: sid, t1: t1, t2: now, t3: MonoClock.nowNs(), pingSeq: seq), to: from)
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
            udp.send(b, to: addr)
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
            udp.send(packet(st, tl, seq: seq, start: cacheStart[i], flags: Wire.flagRetransmit), to: addr)
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
            udp.send(Wire.ping(session: sid, t1: now, pingSeq: pingSeq), to: addr)
            pingSeq &+= 1
        }
        let nacks = rx.collectNacks(nowLocal: now)
        if !nacks.isEmpty { udp.send(Wire.nack(session: sid, epoch: rx.timeline.epoch, seqs: nacks), to: addr) }
    }

    // ================================================================== прерывания
    /// Прерывание (звонок, Siri, другое приложение) на любом телефоне ставит на паузу всю пару.
    private func onInterrupted(_ reason: String) {
        guard state.inPair else { return }
        if role == .server { cmdPause() } else { link?.send(["t": "cmd", "a": "pause"]) }
        toast(reason)
    }
}
