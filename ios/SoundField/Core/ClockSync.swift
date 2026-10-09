import Foundation

/// Оценка соответствия часов Point → часы Server (порт core/ClockSync.kt, алгоритм идентичен).
///
///   fwd = t2 − t1 (Point→Server, PING) = dFwd + offset
///   bwd = t4 − t3 (Server→Point, PONG и каждый аудиопакет) = dBwd − offset
///   offset ≈ (min fwd − min bwd) / 2
/// Минимумы по корзинам 2 с; по корзинам — взвешенная регрессия offset(t) = a + b·(t − ref), b — дрейф.
final class ClockSync {
    struct Model { var ref: Int64 = 0; var a: Double = 0; var b: Double = 0; var ready = false }

    private final class Bucket {
        let start: Int64
        var minFwd = Int64.max, minBwd = Int64.max
        var nFwd = 0, nBwd = 0
        init(_ s: Int64) { start = s }
        var usable: Bool { nFwd >= 3 && nBwd >= 3 }
        var offset: Double { Double(minFwd - minBwd) / 2 }
        var quality: Double { Double(minFwd) + Double(minBwd) }
        var mid: Int64 { start + ClockSync.bucketNs / 2 }
    }

    static let bucketNs: Int64 = 2_000_000_000
    static let maxBuckets = 40
    static let maxDrift = 300e-6

    private let modelBox = Locked(Model())
    var model: Model { modelBox.value }
    private var buckets: [Bucket] = []
    private var rtts: [Double] = []
    private let lock = NSLock()

    private(set) var rttMinMs = 0.0
    private(set) var rttAvgMs = 0.0
    private(set) var rttJitterMs = 0.0
    private(set) var driftPpm = 0.0
    private(set) var uncertaintyMs = 0.0

    var ready: Bool { modelBox.value.ready }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        buckets.removeAll(); rtts.removeAll()
        modelBox.value = Model()
    }

    private func bucketFor(_ local: Int64) -> Bucket {
        if let last = buckets.last {
            if local >= last.start && local < last.start + Self.bucketNs { return last }
            if local < last.start {
                return buckets.last(where: { local >= $0.start && local < $0.start + Self.bucketNs }) ?? last
            }
            let b = Bucket(last.start + ((local - last.start) / Self.bucketNs) * Self.bucketNs)
            buckets.append(b)
            if buckets.count > Self.maxBuckets { buckets.removeFirst(buckets.count - Self.maxBuckets) }
            return b
        }
        let b = Bucket(local); buckets.append(b); return b
    }

    func addPong(t1: Int64, t2: Int64, t3: Int64, t4: Int64) {
        let rtt = (t4 - t1) - (t3 - t2)
        guard rtt >= 0, rtt <= 2_000_000_000 else { return }
        lock.lock(); defer { lock.unlock() }
        let bf = bucketFor(t1); bf.minFwd = min(bf.minFwd, t2 - t1); bf.nFwd += 1
        let bb = bucketFor(t4); bb.minBwd = min(bb.minBwd, t4 - t3); bb.nBwd += 1
        rtts.append(Double(rtt) / 1e6); if rtts.count > 100 { rtts.removeFirst() }
        rttMinMs = rtts.min() ?? 0
        rttAvgMs = rtts.reduce(0, +) / Double(rtts.count)
        let avg = rttAvgMs
        rttJitterMs = (rtts.reduce(0) { $0 + ($1 - avg) * ($1 - avg) } / Double(rtts.count)).squareRoot()
        recompute()
    }

    /// Односторонний замер Server→Point по аудиопакету.
    func addOneWay(sendServerNs: Int64, arrivalLocal: Int64) {
        lock.lock(); defer { lock.unlock() }
        guard !buckets.isEmpty else { return }
        let b = bucketFor(arrivalLocal)
        let d = arrivalLocal - sendServerNs
        b.nBwd += 1
        if d < b.minBwd { b.minBwd = d; recompute() }
    }

    private func recompute() {
        let ok = buckets.filter { $0.usable }
        guard let first = ok.first, let last = ok.last else { return }
        let qMin = ok.map { $0.quality }.min()!
        let span = Double(last.mid - first.mid) / 1e9
        var a: Double, b: Double, ref: Int64
        if ok.count >= 5 && span >= 12 {
            ref = last.mid
            var sw = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
            let base = last.offset
            for k in ok {
                let q = (k.quality - qMin) / 1e6 + 0.3
                let w = 1.0 / (q * q)
                let x = Double(k.mid - ref), y = k.offset - base
                sw += w; sx += w * x; sy += w * y; sxx += w * x * x; sxy += w * x * y
            }
            let den = sw * sxx - sx * sx
            b = abs(den) > 1e-12 ? (sw * sxy - sx * sy) / den : 0
            b = min(max(b, -Self.maxDrift), Self.maxDrift)
            a = base + (sy - b * sx) / sw
            var r2 = 0.0
            for k in ok { let e = k.offset - (a + b * Double(k.mid - ref)); r2 += e * e }
            uncertaintyMs = (r2 / Double(ok.count)).squareRoot() / 1e6 + (qMin / 1e6) * 0.05
        } else {
            let recent = ok.suffix(3)
            let best = recent.min(by: { $0.quality < $1.quality })!
            b = modelBox.value.b
            ref = best.mid
            a = best.offset
            uncertaintyMs = (best.quality - qMin) / 2e6 + (qMin / 1e6) * 0.1
        }
        driftPpm = b * 1e6
        modelBox.value = Model(ref: ref, a: a, b: b, ready: true)
    }

    /// Локальное монотонное время Point → время Server.
    func toServer(_ localNs: Int64) -> Int64 {
        let m = modelBox.value
        return localNs + Int64(m.a + m.b * Double(localNs - m.ref))
    }
}
