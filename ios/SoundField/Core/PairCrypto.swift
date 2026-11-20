import Foundation
import CryptoKit

/// Криптография протокола v2, байт-в-байт как Android core/PairCrypto.kt: HMAC-SHA256 и X25519 (CryptoKit).
enum PairCrypto {
    static let tagBytes = 8

    static func random(_ n: Int) -> [UInt8] { (0..<n).map { _ in UInt8.random(in: 0...255) } }

    static func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
    static func unhex(_ s: String) -> [UInt8]? {
        let c = Array(s.utf8)
        guard c.count % 2 == 0 else { return nil }
        var out: [UInt8] = []; out.reserveCapacity(c.count / 2)
        var i = 0
        while i < c.count {
            guard let h = nibble(c[i]), let l = nibble(c[i + 1]) else { return nil }
            out.append(h << 4 | l); i += 2
        }
        return out
    }
    private static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case 48...57: return c - 48
        case 97...102: return c - 87
        case 65...70: return c - 55
        default: return nil
        }
    }

    static func hmac(_ key: [UInt8], _ data: ArraySlice<UInt8>) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: Array(data), using: SymmetricKey(data: key)))
    }

    /// MAC над частями, каждая с префиксом длины u16 BE.
    static func mac(_ key: [UInt8], _ parts: [[UInt8]]) -> [UInt8] {
        var d: [UInt8] = []
        // truncatingIfNeeded — как ByteArrayOutputStream.write на Android: часть ≥ 64 КБ из сети
        // (длинный id в invite/accept) раньше роняла приложение на UInt8(...).
        for p in parts {
            d.append(UInt8(truncatingIfNeeded: p.count >> 8)); d.append(UInt8(truncatingIfNeeded: p.count))
            d.append(contentsOf: p)
        }
        return hmac(key, d[...])
    }
    static func mac(_ key: [UInt8], _ label: String, _ parts: [[UInt8]]) -> [UInt8] { mac(key, [Array(label.utf8)] + parts) }

    static func equal(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        guard a.count == b.count else { return false }
        var d: UInt8 = 0
        for i in 0..<a.count { d |= a[i] ^ b[i] }
        return d == 0
    }

    struct Ephemeral {
        let priv: [UInt8]
        let pub: [UInt8]
    }
    static func ephemeral() -> Ephemeral {
        let k = Curve25519.KeyAgreement.PrivateKey()
        return Ephemeral(priv: Array(k.rawRepresentation), pub: Array(k.publicKey.rawRepresentation))
    }
    static func publicKey(priv: [UInt8]) -> [UInt8]? {
        (try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv)).map { Array($0.publicKey.rawRepresentation) }
    }
    static func sharedSecret(priv: [UInt8], theirPub: [UInt8]) -> [UInt8]? {
        guard let k = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv),
              let p = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: theirPub),
              let s = try? k.sharedSecretFromKeyAgreement(with: p) else { return nil }
        let out = s.withUnsafeBytes { Array($0) }
        return out.allSatisfy({ $0 == 0 }) ? nil : out
    }

    /// Ключ пары (32 байта) из X25519 с привязкой к обоим открытым ключам.
    static func pairKey(priv: [UInt8], theirPub: [UInt8], inviterPub: [UInt8], acceptorPub: [UInt8]) -> [UInt8]? {
        guard let shared = sharedSecret(priv: priv, theirPub: theirPub) else { return nil }
        return mac(shared, "sf-pair-v2", [inviterPub, acceptorPub])
    }

    /// Ключ UDP-контура: свой для каждого управляющего соединения.
    static func udpKey(_ pairKey: [UInt8], _ a: [UInt8], _ b: [UInt8]) -> [UInt8] { mac(pairKey, "sf-udp-v2", [a, b]) }

    static func proof(_ pairKey: [UInt8], _ label: String, _ ctx: [[UInt8]]) -> [UInt8] { mac(pairKey, label, ctx) }
}
