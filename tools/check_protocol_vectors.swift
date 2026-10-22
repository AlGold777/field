// Сверка золотых векторов протокола (tools/protocol_vectors.json) с iOS-реализацией Wire.swift.
// Те же векторы проверяет Android-тест android/coretest/CoreSimulation.kt.
//
// Запуск из корня репозитория (компилятор требует, чтобы файл с top-level кодом назывался main.swift):
//   mkdir -p /tmp/sf_vectors && cp tools/check_protocol_vectors.swift /tmp/sf_vectors/main.swift
//   swiftc -o /tmp/sf_vectors/check ios/SoundField/Core/Constants.swift ios/SoundField/Core/PairCrypto.swift ios/SoundField/Core/Wire.swift /tmp/sf_vectors/main.swift
//   /tmp/sf_vectors/check
// Путь к JSON можно передать первым аргументом; по умолчанию — tools/protocol_vectors.json.
import Foundation

func hexString(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

func bytesFrom(hex: String) -> [UInt8] {
    var out: [UInt8] = []
    var i = hex.startIndex
    while i < hex.endIndex {
        let j = hex.index(i, offsetBy: 2)
        out.append(UInt8(hex[i..<j], radix: 16)!)
        i = j
    }
    return out
}

func jnum(_ v: [String: Any], _ k: String) -> Int64 { (v[k] as! NSNumber).int64Value }
func jstr(_ v: [String: Any], _ k: String) -> String { v[k] as! String }
func jlist(_ s: String) -> [Int] { s.isEmpty ? [] : s.split(separator: ",").map { Int($0)! } }

func encode(_ v: [String: Any]) -> [UInt8] {
    let key = bytesFrom(hex: jstr(v, "key"))
    let session = Int32(truncatingIfNeeded: jnum(v, "session"))
    switch jstr(v, "type") {
    case "ping":
        return Wire.ping(session: session, t1: jnum(v, "t1"), pingSeq: Int32(truncatingIfNeeded: jnum(v, "pingSeq")), key: key)
    case "pong":
        return Wire.pong(session: session, t1: jnum(v, "t1"), t2: jnum(v, "t2"), t3: jnum(v, "t3"),
                         pingSeq: Int32(truncatingIfNeeded: jnum(v, "pingSeq")), key: key)
    case "audio":
        return Wire.audio(session: session, epoch: Int32(truncatingIfNeeded: jnum(v, "epoch")),
                          seq: Int32(truncatingIfNeeded: jnum(v, "seq")), startFrame: jnum(v, "startFrame"),
                          playoutNs: jnum(v, "playoutNs"), sendNs: jnum(v, "sendNs"),
                          flags: UInt8(truncatingIfNeeded: jnum(v, "flags")),
                          samples: jlist(jstr(v, "samples")).map { Int32($0) }, key: key)
    case "nack":
        return Wire.nack(session: session, epoch: Int32(truncatingIfNeeded: jnum(v, "epoch")),
                         seqs: jlist(jstr(v, "seqs")).map { Int32($0) }, key: key)
    default:
        fatalError("неизвестный тип вектора")
    }
}

func decodedMatches(_ m: UdpMsg?, _ v: [String: Any]) -> Bool {
    guard let m = m else { return false }
    let session = Int32(truncatingIfNeeded: jnum(v, "session"))
    switch m {
    case .ping(let s, let t1, let p):
        return s == session && t1 == jnum(v, "t1") && p == Int32(truncatingIfNeeded: jnum(v, "pingSeq"))
    case .pong(let s, let t1, let t2, let t3, let p):
        return s == session && t1 == jnum(v, "t1") && t2 == jnum(v, "t2") && t3 == jnum(v, "t3")
            && p == Int32(truncatingIfNeeded: jnum(v, "pingSeq"))
    case .audio(let a):
        return a.session == session && a.epoch == Int32(truncatingIfNeeded: jnum(v, "epoch"))
            && a.seq == Int32(truncatingIfNeeded: jnum(v, "seq")) && a.startFrame == jnum(v, "startFrame")
            && a.playoutNs == jnum(v, "playoutNs") && a.sendNs == jnum(v, "sendNs")
            && Int64(a.flags) == jnum(v, "flags") && a.samples.map { Int($0) } == jlist(jstr(v, "samples"))
    case .nack(let s, let e, let seqs):
        return s == session && e == Int32(truncatingIfNeeded: jnum(v, "epoch"))
            && seqs.map { Int($0) } == jlist(jstr(v, "seqs"))
    }
}

let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "tools/protocol_vectors.json"
let vectors = try! JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: path))) as! [[String: Any]]
var failures = 0
func check(_ name: String, _ ok: Bool) {
    print((ok ? "  OK   " : "  FAIL ") + name)
    if !ok { failures += 1 }
}

let packetTypes: Set<String> = ["ping", "pong", "audio", "nack"]
check("vectors count = 10", vectors.count == 10)
for v in vectors {
    let name = jstr(v, "name")
    switch jstr(v, "type") {
    case "x25519":
        let aPriv = bytesFrom(hex: jstr(v, "privA")), bPriv = bytesFrom(hex: jstr(v, "privB"))
        let aPub = bytesFrom(hex: jstr(v, "pubA")), bPub = bytesFrom(hex: jstr(v, "pubB"))
        check("x25519 \(name) public A", PairCrypto.publicKey(priv: aPriv) == aPub)
        check("x25519 \(name) public B", PairCrypto.publicKey(priv: bPriv) == bPub)
        check("x25519 \(name) shared A", PairCrypto.sharedSecret(priv: aPriv, theirPub: bPub).map(hexString) == jstr(v, "shared"))
        check("x25519 \(name) shared B", PairCrypto.sharedSecret(priv: bPriv, theirPub: aPub).map(hexString) == jstr(v, "shared"))
    case "kdf":
        let aPub = bytesFrom(hex: jstr(v, "pubA")), bPub = bytesFrom(hex: jstr(v, "pubB"))
        let pk = PairCrypto.mac(bytesFrom(hex: jstr(v, "shared")), "sf-pair-v2", [aPub, bPub])
        check("kdf \(name) pairKey", hexString(pk) == jstr(v, "pairKey"))
        check("kdf \(name) udpKey", hexString(PairCrypto.udpKey(pk, bytesFrom(hex: jstr(v, "nonceA")), bytesFrom(hex: jstr(v, "nonceB")))) == jstr(v, "udpKey"))
        check("kdf \(name) acceptProof", hexString(PairCrypto.proof(pk, "sf-accept",
            [Array(jstr(v, "idA").utf8), Array(jstr(v, "idB").utf8), aPub, bPub])) == jstr(v, "acceptProof"))
    default:
        precondition(packetTypes.contains(jstr(v, "type")))
        let golden = jstr(v, "hex")
        let key = bytesFrom(hex: jstr(v, "key"))
        check("encode \(name) = golden hex", hexString(encode(v)) == golden)
        let bytes = bytesFrom(hex: golden)
        let msg = bytes.withUnsafeBytes { Wire.decode($0, key: key) }
        check("decode \(name) = source fields", decodedMatches(msg, v))
        // подделанный байт (epoch/payload) и неверный ключ отвергаются
        var forged = bytes; forged[forged.count / 2] ^= 0x01
        check("forged \(name) rejected", forged.withUnsafeBytes { Wire.decode($0, key: key) } == nil)
        check("wrong key \(name) rejected", bytes.withUnsafeBytes { Wire.decode($0, key: [UInt8](repeating: 1, count: 32)) } == nil)
        check("untagged \(name) rejected", Array(bytes.dropLast(Wire.tag)).withUnsafeBytes { Wire.decode($0, key: key) } == nil)
    }
}
// (a) пакет версии 2 с верным тегом отвергается; та же форма с версией 3 принимается
// (b) AUDIO с count = 385 и верным тегом отвергается
// (c) длина максимального AUDIO-пакета 1204 байта и не больше 1232 (без IP-фрагментации)
if let ping = vectors.first(where: { jstr($0, "name") == "ping" }),
   let full = vectors.first(where: { jstr($0, "name") == "audio_full_384" }) {
    let key = bytesFrom(hex: jstr(ping, "key"))
    func retag(_ body: [UInt8]) -> [UInt8] { body + PairCrypto.hmac(key, body[...]).prefix(Wire.tag) }
    var v2 = Array(bytesFrom(hex: jstr(ping, "hex")).dropLast(Wire.tag))
    v2[2] = 2
    check("(a) version 2 packet with valid tag rejected", retag(v2).withUnsafeBytes { Wire.decode($0, key: key) } == nil)
    var v3 = v2; v3[2] = 3
    check("(a) version 3 control with same body accepted", retag(v3).withUnsafeBytes { Wire.decode($0, key: key) } != nil)
    var big = Array(bytesFrom(hex: jstr(full, "hex")).dropLast(Wire.tag)) + [0, 0, 0]
    big[40] = 0x01; big[41] = 0x81 // count = 385 (u16 на смещении 40)
    check("(b) audio count 385 with valid tag rejected", retag(big).withUnsafeBytes { Wire.decode($0, key: key) } == nil)
    let fullBytes = bytesFrom(hex: jstr(full, "hex"))
    check("(c) audio_full_384 is 1204 bytes and <= 1232", fullBytes.count == 1204 && fullBytes.count <= 1232)
} else {
    check("audio_full_384 and ping vectors present", false)
}
print(failures == 0 ? "ALL PASSED" : "FAILURES: \(failures)")
exit(failures == 0 ? 0 : 1)
