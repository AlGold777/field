import Foundation

/// Бинарный UDP-протокол медиаконтура (big-endian), байт-в-байт как Android core/Wire.kt.
///
/// Заголовок (8 байт): 'S' 'F' | version u8 | type u8 | sessionId i32
///  AUDIO (1): epoch i32 | seq i32 | startFrame i64 | playoutNs i64 | sendNs i64 | count u16 | flags u8 | 0 | count × i16
///  PING  (2): t1 i64 | pingSeq i32
///  PONG  (3): t1 i64 | t2 i64 | t3 i64 | pingSeq i32
///  NACK  (4): epoch i32 | n u16 | n × seq i32
/// Версия 2: в конце каждого пакета — усечённый HMAC-SHA256 (tagBytes = 8) от всех предыдущих байт, ключ — udpKey соединения.
struct AudioPacket {
    var session: Int32
    var epoch: Int32
    var seq: Int32
    var startFrame: Int64
    var playoutNs: Int64
    var sendNs: Int64
    var flags: UInt8
    var samples: [Int16]
    var isRetransmit: Bool { flags & Wire.flagRetransmit != 0 }
}

enum UdpMsg {
    case audio(AudioPacket)
    case ping(session: Int32, t1: Int64, pingSeq: Int32)
    case pong(session: Int32, t1: Int64, t2: Int64, t3: Int64, pingSeq: Int32)
    case nack(session: Int32, epoch: Int32, seqs: [Int32])

    var session: Int32 {
        switch self {
        case .audio(let a): return a.session
        case .ping(let s, _, _): return s
        case .pong(let s, _, _, _, _): return s
        case .nack(let s, _, _): return s
        }
    }
}

enum Wire {
    static let flagRetransmit: UInt8 = 1
    static let tAudio: UInt8 = 1, tPing: UInt8 = 2, tPong: UInt8 = 3, tNack: UInt8 = 4
    static let header = 8
    static let audioHeader = header + 36
    static let maxNack = 64
    static let tag = PairCrypto.tagBytes

    private struct W {
        var b: [UInt8] = []
        init(capacity: Int) { b.reserveCapacity(capacity) }
        mutating func u8(_ v: UInt8) { b.append(v) }
        mutating func i16(_ v: Int16) { let u = UInt16(bitPattern: v); b.append(UInt8(u >> 8)); b.append(UInt8(u & 0xFF)) }
        mutating func u16(_ v: UInt16) { b.append(UInt8(v >> 8)); b.append(UInt8(v & 0xFF)) }
        mutating func i32(_ v: Int32) { let u = UInt32(bitPattern: v); for s in stride(from: 24, through: 0, by: -8) { b.append(UInt8((u >> UInt32(s)) & 0xFF)) } }
        mutating func i64(_ v: Int64) { let u = UInt64(bitPattern: v); for s in stride(from: 56, through: 0, by: -8) { b.append(UInt8((u >> UInt64(s)) & 0xFF)) } }
        mutating func head(_ type: UInt8, _ session: Int32) { u8(0x53); u8(0x46); u8(SF.protocolVersion); u8(type); i32(session) }
    }

    private struct R {
        let p: UnsafeRawBufferPointer
        var i = 0
        init(_ p: UnsafeRawBufferPointer) { self.p = p }
        var remaining: Int { p.count - i }
        mutating func u8() -> UInt8 { let v = p[i]; i += 1; return v }
        mutating func u16() -> UInt16 { let v = UInt16(p[i]) << 8 | UInt16(p[i + 1]); i += 2; return v }
        mutating func i16() -> Int16 { Int16(bitPattern: u16()) }
        mutating func i32() -> Int32 {
            var u: UInt32 = 0; for k in 0..<4 { u = u << 8 | UInt32(p[i + k]) }; i += 4; return Int32(bitPattern: u)
        }
        mutating func i64() -> Int64 {
            var u: UInt64 = 0; for k in 0..<8 { u = u << 8 | UInt64(p[i + k]) }; i += 8; return Int64(bitPattern: u)
        }
    }

    private static func seal(_ body: [UInt8], _ key: [UInt8]) -> [UInt8] {
        body + PairCrypto.hmac(key, body[...]).prefix(tag)
    }

    static func audio(session: Int32, epoch: Int32, seq: Int32, startFrame: Int64, playoutNs: Int64, sendNs: Int64,
                      flags: UInt8, samples: [Int16], key: [UInt8]) -> [UInt8] {
        var w = W(capacity: audioHeader + samples.count * 2)
        w.head(tAudio, session)
        w.i32(epoch); w.i32(seq); w.i64(startFrame); w.i64(playoutNs); w.i64(sendNs)
        w.u16(UInt16(samples.count)); w.u8(flags); w.u8(0)
        for s in samples { w.i16(s) }
        return seal(w.b, key)
    }

    static func ping(session: Int32, t1: Int64, pingSeq: Int32, key: [UInt8]) -> [UInt8] {
        var w = W(capacity: 20); w.head(tPing, session); w.i64(t1); w.i32(pingSeq); return seal(w.b, key)
    }

    static func pong(session: Int32, t1: Int64, t2: Int64, t3: Int64, pingSeq: Int32, key: [UInt8]) -> [UInt8] {
        var w = W(capacity: 36); w.head(tPong, session); w.i64(t1); w.i64(t2); w.i64(t3); w.i32(pingSeq); return seal(w.b, key)
    }

    static func nack(session: Int32, epoch: Int32, seqs: [Int32], key: [UInt8]) -> [UInt8] {
        let n = min(seqs.count, maxNack)
        var w = W(capacity: header + 6 + n * 4)
        w.head(tNack, session); w.i32(epoch); w.u16(UInt16(n))
        for k in 0..<n { w.i32(seqs[k]) }
        return seal(w.b, key)
    }

    /// nil — чужой, битый пакет или пакет с неверным тегом.
    static func decode(_ full: UnsafeRawBufferPointer, key: [UInt8]) -> UdpMsg? {
        guard full.count >= header + tag, full[0] == 0x53, full[1] == 0x46, full[2] == SF.protocolVersion else { return nil }
        let bodyCount = full.count - tag
        let expect = PairCrypto.hmac(key, Array(full[0..<bodyCount])[...])
        var diff: UInt8 = 0
        for k in 0..<tag { diff |= expect[k] ^ full[bodyCount + k] }
        guard diff == 0 else { return nil }
        let p = UnsafeRawBufferPointer(rebasing: full[0..<bodyCount])
        var r = R(p)
        guard r.u8() == 0x53, r.u8() == 0x46, r.u8() == SF.protocolVersion else { return nil }
        let type = r.u8()
        let session = r.i32()
        switch type {
        case tAudio:
            guard p.count >= audioHeader else { return nil }
            let epoch = r.i32(), seq = r.i32(), start = r.i64(), playout = r.i64(), send = r.i64()
            let count = Int(r.u16()); let flags = r.u8(); _ = r.u8()
            guard p.count >= audioHeader + count * 2 else { return nil }
            var s = [Int16](repeating: 0, count: count)
            for k in 0..<count { s[k] = r.i16() }
            return .audio(AudioPacket(session: session, epoch: epoch, seq: seq, startFrame: start, playoutNs: playout,
                                      sendNs: send, flags: flags, samples: s))
        case tPing:
            guard r.remaining >= 12 else { return nil }
            return .ping(session: session, t1: r.i64(), pingSeq: r.i32())
        case tPong:
            guard r.remaining >= 28 else { return nil }
            return .pong(session: session, t1: r.i64(), t2: r.i64(), t3: r.i64(), pingSeq: r.i32())
        case tNack:
            guard r.remaining >= 6 else { return nil }
            let epoch = r.i32(); let n = Int(r.u16())
            guard n <= maxNack, r.remaining >= n * 4 else { return nil }
            var seqs = [Int32](); seqs.reserveCapacity(n)
            for _ in 0..<n { seqs.append(r.i32()) }
            return .nack(session: session, epoch: epoch, seqs: seqs)
        default:
            return nil
        }
    }
}
