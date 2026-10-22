import Foundation

// Защита UDP-контура от повтора подлинных пакетов (HMAC сам повтор не отсекает).
// Зеркало android core/ReplayWindow.kt. Оба класса сбрасываются при смене udpKey (новое управляющее соединение).

/// Server: PING принимается, только если pingSeq строго больше последнего принятого.
final class PingReplayGate {
    private let lock = NSLock()
    private var last: Int32 = 0
    private var any = false

    func accept(_ pingSeq: Int32) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if any && pingSeq <= last { return false }
        any = true; last = pingSeq
        return true
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        any = false; last = 0
    }
}

/// Point: PONG принимается один раз и только для pingSeq, отправленного в этой сессии (окно последних size).
final class PongWindow {
    private let lock = NSLock()
    private let size: Int
    private var outstanding: [Int32] = []

    init(size: Int = 64) { self.size = size }

    func sent(_ pingSeq: Int32) {
        lock.lock(); defer { lock.unlock() }
        outstanding.append(pingSeq)
        while outstanding.count > size { outstanding.removeFirst() }
    }

    /// Удаляет первое вхождение pingSeq; true, если оно было в окне.
    func accept(_ pingSeq: Int32) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let i = outstanding.firstIndex(of: pingSeq) else { return false }
        outstanding.remove(at: i)
        return true
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        outstanding.removeAll()
    }
}
