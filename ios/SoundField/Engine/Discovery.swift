import Foundation
import Network

/// TCP-сервер управляющего контура + Bonjour-реклама + поиск соседей.
/// Имя сервиса "SF-<id>" (ASCII), имя/платформа/id — в TXT (n, p, id) — совместимо с Android NSD.
final class Discovery {
    private let queue: DispatchQueue
    private let myId: String
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var currentName = ""
    private var usePreferredPort = true
    /// Порт TCP-сервера. Сохраняется между запусками, чтобы второй телефон мог подключиться напрямую по старому адресу.
    private(set) var listenPort: UInt16 = UInt16(UserDefaults.standard.integer(forKey: "sf.tcpPort"))
    var onLog: ((String) -> Void)?
    private(set) var peers: [Peer] = []
    var onPeers: (([Peer]) -> Void)?
    var onIncoming: ((NWConnection) -> Void)?

    init(queue: DispatchQueue, myId: String) {
        self.queue = queue
        self.myId = myId
    }

    static func tcpParams() -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 2
        let p = NWParameters(tls: nil, tcp: tcp)
        // Без peer-to-peer (AWDL): Android его не поддерживает, а iOS при включённом P2P может выбрать
        // AWDL-адрес и «висеть» на разрешении/подключении. Связь идёт только через общую сеть Wi‑Fi.
        p.includePeerToPeer = false
        return p
    }

    func advertise(name: String) {
        currentName = name
        var txt = NWTXTRecord()
        txt["n"] = String(name.prefix(60))
        txt["p"] = "ios"
        txt["id"] = myId
        let service = NWListener.Service(name: SF.servicePrefix + myId, type: SF.serviceType, domain: nil, txtRecord: txt)
        if let l = listener {
            l.service = service   // обновить TXT (переименование) без пересоздания
            return
        }
        do {
            let l: NWListener
            if usePreferredPort, listenPort != 0, let port = NWEndpoint.Port(rawValue: listenPort) {
                l = try NWListener(using: Self.tcpParams(), on: port)
            } else {
                l = try NWListener(using: Self.tcpParams())
            }
            l.service = service
            l.newConnectionHandler = { [weak self] c in self?.onIncoming?(c) }
            l.stateUpdateHandler = { [weak self, weak l] st in
                guard let self, let l, self.listener === l else { return }
                switch st {
                case .ready:
                    if let p = l.port?.rawValue {
                        self.listenPort = p
                        UserDefaults.standard.set(Int(p), forKey: "sf.tcpPort")
                        self.onLog?("TCP-сервер готов, порт \(p)")
                    }
                case .failed(let e):
                    // Порт занят или сокет «умер» после сна — пересоздаём (со свободным портом).
                    self.onLog?("TCP-сервер: ошибка \(e) — перезапуск")
                    self.usePreferredPort = false
                    l.cancel()
                    self.listener = nil
                    self.queue.asyncAfter(deadline: .now() + 1) { [weak self] in
                        guard let self, self.listener == nil else { return }
                        self.advertise(name: self.currentName)
                    }
                default: break
                }
            }
            l.start(queue: queue)
            listener = l
        } catch {
            NSLog("SoundField listener error: \(error)")
            usePreferredPort = false
            queue.asyncAfter(deadline: .now() + 1) { [weak self] in
                guard let self, self.listener == nil else { return }
                self.advertise(name: self.currentName)
            }
        }
    }

    /// После сна iOS может закрыть сокеты приостановленного приложения: слушатель остаётся объектом,
    /// но соединения не принимает. На возврате в приложение пересоздаём его, если он не в состоянии .ready.
    func ensureListening() {
        guard let l = listener else { advertise(name: currentName); return }
        switch l.state {
        case .failed, .cancelled: break
        default: return
        }
        onLog?("TCP-сервер не готов (\(l.state)) — перезапуск")
        l.cancel(); listener = nil
        advertise(name: currentName)
    }

    var browsing: Bool { browser != nil }

    func startBrowse() {
        guard browser == nil else { return }
        let params = NWParameters()
        params.includePeerToPeer = false
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: SF.serviceType, domain: nil), using: params)
        b.browseResultsChangedHandler = { [weak self] results, _ in self?.update(results) }
        b.stateUpdateHandler = { [weak self] st in
            if case .failed(let e) = st {
                NSLog("SoundField browser failed: \(e)")
                self?.browser = nil
                self?.queue.asyncAfter(deadline: .now() + 2) { self?.startBrowse() }
            }
        }
        b.start(queue: queue)
        browser = b
    }

    func stopBrowse() {
        browser?.cancel(); browser = nil
    }

    func restartBrowse() { stopBrowse(); startBrowse() }

    func peer(id: String) -> Peer? { peers.first { $0.id == id } }

    private func update(_ results: Set<NWBrowser.Result>) {
        var list: [Peer] = []
        for r in results {
            guard case .service(let name, _, _, _) = r.endpoint, name.hasPrefix(SF.servicePrefix) else { continue }
            var id = String(name.dropFirst(SF.servicePrefix.count))
            var display = id, platform = "?"
            if case .bonjour(let txt) = r.metadata {
                if let v = txt["id"] { id = v }
                if let v = txt["n"] { display = v }
                if let v = txt["p"] { platform = v }
            }
            if id == myId { continue }
            list.append(Peer(id: id, name: display, platform: platform, endpoint: r.endpoint))
        }
        list.sort { $0.name < $1.name }
        peers = list
        onPeers?(list)
    }
}
