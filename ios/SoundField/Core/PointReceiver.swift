import Foundation

/// Приёмная сторона Point: jitter buffer по абсолютному номеру кадра, учёт seq, NACK, статистика.
/// Порт core/PointReceiver.kt. Запись — сетевой поток (под lock), чтение — аудиопоток без блокировок
/// (каждая ячейка хранит номер кадра; несовпадение тега = тишина).
final class PointReceiver {
    private let cap: Int
    private let mask: Int64
    private let samples: UnsafeMutablePointer<Float>
    private let tags: UnsafeMutablePointer<Int64>
    private let clock: ClockSync
    private let lock = NSLock()

    private let tlBox = Locked(Timeline.none)
    var timeline: Timeline { tlBox.value }
    /// Пишется аудиопотоком.
    var lastRenderedFrame: Int64 = .min
    private(set) var maxFrame: Int64 = -1

    // Статистика пишется под lock (сетевой поток), а UI читает её без блокировки. Аналог @Volatile в Kotlin:
    // чтение допустимо, так как Int64/Double выровнены и на arm64 читаются целым словом. Возможна лишь устаревшая копия.
    private(set) var received: Int64 = 0
    private(set) var lostFinal: Int64 = 0
    private(set) var recovered: Int64 = 0
    private(set) var stale: Int64 = 0
    private(set) var late: Int64 = 0
    private(set) var jitterMs = 0.0
    private(set) var nacksSent: Int64 = 0

    private var expectedSeq: Int32 = -1
    private var seqInit = false   // expectedSeq уже задан (seq может быть отрицательным после переполнения Int32)
    private struct Pending { var firstSeen: Int64; var lastNack: Int64 = 0; var tries = 0 }
    private var pending: [Int32: Pending] = [:]
    private var lastTransit: Int64 = .min

    init(clock: ClockSync, capacityPow2: Int = 17) {
        self.clock = clock
        cap = 1 << capacityPow2
        mask = Int64(cap - 1)
        samples = .allocate(capacity: cap); samples.initialize(repeating: 0, count: cap)
        tags = .allocate(capacity: cap); tags.initialize(repeating: -1, count: cap)
    }
    deinit { samples.deallocate(); tags.deallocate() }

    var bufferMs: Double {
        let tl = timeline
        guard tl.playing, maxFrame >= 0 else { return 0 }
        let lr = lastRenderedFrame
        let cur = lr == .min ? tl.anchorFrame : lr
        return Double(max(0, maxFrame - cur)) * 1000 / Double(SF.sampleRate)
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        tlBox.value = .none
        clearBuffer(); expectedSeq = -1; seqInit = false; pending.removeAll(); lastTransit = .min
    }

    private func clearBuffer() {
        tags.update(repeating: -1, count: cap)
        maxFrame = -1
        lastRenderedFrame = .min
    }

    func onTimeline(_ tl: Timeline) {
        lock.lock(); defer { lock.unlock() }
        let cur = tlBox.value
        if tl.epoch < cur.epoch { return }
        if tl.epoch > cur.epoch { clearBuffer(); expectedSeq = -1; seqInit = false; pending.removeAll(); lastTransit = .min }
        tlBox.value = tl
    }

    func onAudio(_ p: AudioPacket, arrivalLocalNs: Int64) {
        lock.lock(); defer { lock.unlock() }
        let cur = tlBox.value
        if p.epoch < cur.epoch { stale += 1; return }
        if p.epoch > cur.epoch {
            clearBuffer(); expectedSeq = -1; seqInit = false; pending.removeAll(); lastTransit = .min
            tlBox.value = Timeline(epoch: p.epoch, playing: true, anchorFrame: p.startFrame, anchorNs: p.playoutNs)
        } else if !cur.playing { stale += 1; return }

        clock.addOneWay(sendServerNs: p.sendNs, arrivalLocal: arrivalLocalNs)

        if p.isRetransmit {
            if pending.removeValue(forKey: p.seq) != nil { recovered += 1 }
        } else {
            if !seqInit { expectedSeq = p.seq; seqInit = true }
            // разность по модулю 2^32: >= 0 — пакет впереди ожидаемого
            let diff = p.seq &- expectedSeq
            var duplicate = false
            if diff >= 0 {
                let gap = Int64(diff)
                if gap > 256 {
                    // Огромный провал в приёме (> 2.5 с звука) — не забиваем очередь безнадёжными NACK
                    lostFinal += gap
                } else {
                    var s = expectedSeq
                    while s != p.seq {
                        // очередь полна: вытесняем самый старый, потерю учитываем (как в Kotlin)
                        if pending.count >= 512, let oldest = pending.min(by: { $0.value.firstSeen < $1.value.firstSeen }) {
                            pending.removeValue(forKey: oldest.key)
                            lostFinal += 1
                        }
                        pending[s] = Pending(firstSeen: arrivalLocalNs)
                        s &+= 1
                    }
                }
                expectedSeq = p.seq &+ 1
            } else if pending.removeValue(forKey: p.seq) != nil {
                recovered += 1
            } else {
                duplicate = true // уже обработанный seq
            }
            if !duplicate { received += 1 }
            if clock.ready {
                let transit = clock.toServer(arrivalLocalNs) - p.sendNs
                if lastTransit != .min {
                    let d = Double(abs(transit - lastTransit)) / 1e6
                    jitterMs += (d - jitterMs) / 16
                }
                lastTransit = transit
            }
        }

        let count = p.samples.count
        let lr = lastRenderedFrame
        if lr != .min && p.startFrame + Int64(count) <= lr { late += 1; return }

        p.samples.withUnsafeBufferPointer { src in
            for i in 0..<count {
                let f = p.startFrame + Int64(i)
                let idx = Int(f & mask)
                samples[idx] = Float(src[i]) / 32768
                tags[idx] = f
            }
        }
        let end = p.startFrame + Int64(count) - 1
        if end > maxFrame { maxFrame = end }
    }

    func collectNacks(nowLocal: Int64) -> [Int32] {
        lock.lock(); defer { lock.unlock() }
        guard !pending.isEmpty else { return [] }
        var out: [Int32] = []
        for (seq, var pd) in pending {
            let age = nowLocal - pd.firstSeen
            if age > 350_000_000 { pending.removeValue(forKey: seq); lostFinal += 1; continue }
            if pd.tries < 4 && age >= 12_000_000 && nowLocal - pd.lastNack >= 40_000_000 {
                pd.lastNack = nowLocal; pd.tries += 1; pending[seq] = pd
                out.append(seq)
                if out.count >= Wire.maxNack { break }
            }
        }
        if !out.isEmpty { nacksSent += 1 }
        return out
    }

    /// nil — кадра нет в буфере.
    @inline(__always) func read(_ frame: Int64) -> Float? {
        let idx = Int(frame & mask)
        let s = samples[idx]
        return tags[idx] == frame ? s : nil
    }
}
