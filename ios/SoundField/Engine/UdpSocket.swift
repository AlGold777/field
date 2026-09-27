import Foundation
import Network

/// UDP медиаконтура на BSD-сокете (dual-stack IPv6/IPv4) — один сокет и для Server, и для Point,
/// как DatagramSocket на Android. Приём — в отдельном потоке высокого приоритета.
final class UdpSocket {
    struct Addr { var storage = sockaddr_storage(); var len: socklen_t = 0 }

    private let fd: Int32
    let port: UInt16
    /// Сокет «сломан» (iOS закрыла его, пока приложение спало) — Engine создаёт новый.
    private let brokenBox = Locked(false)
    var broken: Bool { brokenBox.value }
    private let closedBox = Locked(false)
    var onPacket: ((UnsafeRawBufferPointer, Addr, Int64) -> Void)?

    init?() {
        let socketFD = socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFD >= 0 else { return nil }
        fd = socketFD
        var off: Int32 = 0
        setsockopt(socketFD, IPPROTO_IPV6, IPV6_V6ONLY, &off, socklen_t(MemoryLayout<Int32>.size))
        var tos: Int32 = 0xB8 // DSCP EF — приоритетный трафик (если сеть учитывает)
        setsockopt(socketFD, IPPROTO_IPV6, IPV6_TCLASS, &tos, socklen_t(MemoryLayout<Int32>.size))
        var addr = sockaddr_in6()
        addr.sin6_family = sa_family_t(AF_INET6)
        addr.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size)
        addr.sin6_addr = in6addr_any
        addr.sin6_port = 0
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socketFD, $0, socklen_t(MemoryLayout<sockaddr_in6>.size)) }
        }
        guard ok == 0 else { Darwin.close(socketFD); return nil }
        var bound = sockaddr_in6(); var len = socklen_t(MemoryLayout<sockaddr_in6>.size)
        _ = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socketFD, $0, &len) }
        }
        port = UInt16(bigEndian: bound.sin6_port)
        let t = Thread { [weak self] in self?.loop() }
        t.name = "sf-udp"
        t.qualityOfService = .userInteractive
        t.start()
    }

    private func loop() {
        var buf = [UInt8](repeating: 0, count: 4096)
        var errors = 0
        while !closedBox.value {
            var storage = sockaddr_storage()
            var len = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let n = buf.withUnsafeMutableBytes { b -> Int in
                withUnsafeMutablePointer(to: &storage) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, b.baseAddress, b.count, 0, $0, &len) }
                }
            }
            let now = MonoClock.nowNs()
            if n < 0 {
                let e = errno
                if e == EINTR || e == EAGAIN { continue }
                errors += 1
                if e == EBADF || e == ENOTSOCK || e == ENOTCONN || errors > 100 {
                    NSLog("SoundField udp: сокет недоступен (errno \(e))")
                    brokenBox.value = true
                    return
                }
                usleep(10_000); continue
            }
            errors = 0
            if n == 0 { continue }
            let from = Addr(storage: storage, len: len)
            buf.withUnsafeBytes { b in onPacket?(UnsafeRawBufferPointer(rebasing: b[0..<n]), from, now) }
        }
    }

    func close() {
        guard !closedBox.value else { return }
        closedBox.value = true
        brokenBox.value = true
        Darwin.shutdown(fd, SHUT_RDWR)
        Darwin.close(fd)
    }

    func send(_ bytes: [UInt8], to a: Addr) {
        var st = a.storage
        _ = bytes.withUnsafeBytes { b in
            withUnsafePointer(to: &st) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sendto(fd, b.baseAddress, b.count, 0, $0, a.len) }
            }
        }
    }

    /// Адрес из NWEndpoint.Host + порт (IPv4 → IPv4-mapped IPv6 для dual-stack сокета).
    static func resolve(host: NWEndpoint.Host, port: UInt16) -> Addr? {
        var hostStr: String
        switch host {
        case .ipv4(let a): hostStr = "\(a)"
        case .ipv6(let a):
            hostStr = "\(a)"
            if let iface = a.interface, !hostStr.contains("%") { hostStr += "%\(iface.name)" }
        case .name(let n, _): hostStr = n
        @unknown default: return nil
        }
        if let pct = hostStr.firstIndex(of: "%"), hostStr.hasPrefix("::ffff:") { hostStr = String(hostStr[..<pct]) }
        var hints = addrinfo()
        hints.ai_family = AF_INET6
        hints.ai_socktype = SOCK_DGRAM
        hints.ai_flags = AI_V4MAPPED | AI_ALL
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(hostStr, String(port), &hints, &res) == 0, let ai = res else { return nil }
        defer { freeaddrinfo(res) }
        var out = Addr()
        memcpy(&out.storage, ai.pointee.ai_addr, Int(ai.pointee.ai_addrlen))
        out.len = ai.pointee.ai_addrlen
        return out
    }
}
