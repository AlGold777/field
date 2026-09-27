import Foundation
import Network

/// Управляющий контур: TCP (NWConnection), кадры [u32 длина BE][UTF-8 JSON] — как на Android.
/// Колбэки вызываются на очереди Engine.
final class ControlLink {
    let conn: NWConnection
    private let queue: DispatchQueue
    var onMessage: ((ControlLink, [String: Any]) -> Void)?
    var onClosed: ((ControlLink) -> Void)?
    var tag = ""
    private(set) var lastRx = Date()
    /// TCP-соединение установлено (для исходящих — после .ready).
    private(set) var ready = false
    private var closed = false

    init(_ conn: NWConnection, queue: DispatchQueue) {
        self.conn = conn
        self.queue = queue
    }

    func start() {
        conn.stateUpdateHandler = { [weak self] st in
            guard let self else { return }
            switch st {
            case .ready: self.ready = true
            case .failed, .cancelled: self.close()
            case .waiting(let e): NSLog("SoundField link waiting: \(e)"); self.close()
            default: break
            }
        }
        conn.start(queue: queue)
        readHeader()
    }

    var isOpen: Bool { !closed }

    /// IP-адрес собеседника (для UDP). Для исходящих Bonjour-соединений доступен после .ready.
    var remoteHost: NWEndpoint.Host? {
        if case .hostPort(let h, _) = conn.currentPath?.remoteEndpoint ?? conn.endpoint { return h }
        if case .hostPort(let h, _) = conn.endpoint { return h }
        return nil
    }

    func send(_ o: [String: Any]) {
        guard !closed, let body = try? JSONSerialization.data(withJSONObject: o) else { return }
        var len = UInt32(body.count).bigEndian
        var frame = Data(bytes: &len, count: 4)
        frame.append(body)
        conn.send(content: frame, completion: .contentProcessed { [weak self] err in
            if err != nil { self?.close() }
        })
    }

    func sendAndClose(_ o: [String: Any]) {
        send(o)
        queue.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.close() }
    }

    private func readHeader() {
        conn.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] data, _, done, err in
            guard let self, !self.closed else { return }
            guard err == nil, let d = data, d.count == 4 else { if done || err != nil || data == nil { self.close() }; return }
            let len = d.withUnsafeBytes { Int(UInt32(bigEndian: $0.loadUnaligned(as: UInt32.self))) }
            guard len > 0 && len <= 1_000_000 else { self.close(); return }
            self.readBody(len)
        }
    }

    private func readBody(_ len: Int) {
        conn.receive(minimumIncompleteLength: len, maximumLength: len) { [weak self] data, _, _, err in
            guard let self, !self.closed else { return }
            guard err == nil, let d = data, d.count == len else { self.close(); return }
            self.lastRx = Date()
            if let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] { self.onMessage?(self, o) }
            self.readHeader()
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        conn.cancel()
        onClosed?(self)
    }
}
