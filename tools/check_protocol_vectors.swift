// Сверка золотых векторов протокола (tools/protocol_vectors.json) с iOS-реализацией Wire.swift.
// Те же векторы проверяет Android-тест android/coretest/CoreSimulation.kt.
//
// Запуск из корня репозитория (компилятор требует, чтобы файл с top-level кодом назывался main.swift):
//   mkdir -p /tmp/sf_vectors && cp tools/check_protocol_vectors.swift /tmp/sf_vectors/main.swift
//   swiftc -o /tmp/sf_vectors/check ios/SoundField/Core/Constants.swift ios/SoundField/Core/Wire.swift /tmp/sf_vectors/main.swift
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
    let session = Int32(truncatingIfNeeded: jnum(v, "session"))
    switch jstr(v, "type") {
    case "ping":
        return Wire.ping(session: session, t1: jnum(v, "t1"), pingSeq: Int32(truncatingIfNeeded: jnum(v, "pingSeq")))
    case "pong":
        return Wire.pong(session: session, t1: jnum(v, "t1"), t2: jnum(v, "t2"), t3: jnum(v, "t3"),
                         pingSeq: Int32(truncatingIfNeeded: jnum(v, "pingSeq")))
    case "audio":
        return Wire.audio(session: session, epoch: Int32(truncatingIfNeeded: jnum(v, "epoch")),
                          seq: Int32(truncatingIfNeeded: jnum(v, "seq")), startFrame: jnum(v, "startFrame"),
                          playoutNs: jnum(v, "playoutNs"), sendNs: jnum(v, "sendNs"),
                          flags: UInt8(truncatingIfNeeded: jnum(v, "flags")),
                          samples: jlist(jstr(v, "samples")).map { Int16($0) })
    case "nack":
        return Wire.nack(session: session, epoch: Int32(truncatingIfNeeded: jnum(v, "epoch")),
                         seqs: jlist(jstr(v, "seqs")).map { Int32($0) })
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

check("vectors count = 7", vectors.count == 7)
for v in vectors {
    let name = jstr(v, "name")
    let golden = jstr(v, "hex")
    check("encode \(name) = golden hex", hexString(encode(v)) == golden)
    let bytes = bytesFrom(hex: golden)
    let msg = bytes.withUnsafeBytes { Wire.decode($0) }
    check("decode \(name) = source fields", decodedMatches(msg, v))
}
print(failures == 0 ? "ALL PASSED" : "FAILURES: \(failures)")
exit(failures == 0 ? 0 : 1)
