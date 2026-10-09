import Foundation

/// Акустическая калибровка (порт core/Calibration.kt, алгоритм идентичен).
///   late_k = (arrivalPoint_k − arrivalServer_k) − (expectedPoint_k − expectedServer_k)
/// Абсолютное время записи и входная задержка микрофона сокращаются. trim_new = trim_old − median(late).
enum CalibrationAnalyzer {
    struct Result {
        let ok: Bool
        let lateMs: Double
        let spreadMs: Double
        let pairs: Int
        let snrDb: Double
        let message: String
    }

    static func analyze(rec: [Float], recSr: Int, recStartServerNs: Int64, anchorNs: Int64, trimNs: Int64) -> Result {
        guard rec.count >= recSr else { return Result(ok: false, lateMs: 0, spreadMs: 0, pairs: 0, snrDb: 0, message: "Запись слишком короткая") }
        let (corrUp, corrDown) = xcorrAbsBoth(rec, Chirp.make(up: true, sampleRate: recSr), Chirp.make(up: false, sampleRate: recSr))
        func expected(_ serverNs: Int64) -> Double { Double(serverNs - recStartServerNs) * Double(recSr) / 1e9 }

        var lates: [Double] = []
        var snrSum = 0.0, snrN = 0
        for k in 0..<CalibrationSource.count {
            let eP = anchorNs + trimNs + Int64((CalibrationSource.pointFirstS + Double(k)) * 1e9)
            let eS = anchorNs + Int64((CalibrationSource.serverFirstS + Double(k)) * 1e9)
            guard let pP = findPeak(corrUp, expected(eP), recSr), let pS = findPeak(corrDown, expected(eS), recSr) else { continue }
            let late = (pP.pos - pS.pos) / Double(recSr) - Double(eP - eS) / 1e9
            lates.append(late * 1000)
            snrSum += (pP.snrDb + pS.snrDb) / 2; snrN += 1
        }
        guard lates.count >= 3 else {
            return Result(ok: false, lateMs: 0, spreadMs: 0, pairs: lates.count, snrDb: snrN > 0 ? snrSum / Double(snrN) : 0,
                          message: "Сигнал не распознан (\(lates.count)/\(CalibrationSource.count)). Положите телефоны рядом, увеличьте громкость и повторите в тишине.")
        }
        let med = median(lates)
        let mad = median(lates.map { abs($0 - med) })
        let ok = mad < 3
        return Result(ok: ok, lateMs: med, spreadMs: mad, pairs: lates.count, snrDb: snrSum / Double(snrN),
                      message: ok ? "Измерено по \(lates.count) парам сигналов"
                                  : String(format: "Большой разброс измерений (±%.1f мс) — повторите в более тихом месте", mad))
    }

    private struct Peak { let pos: Double; let snrDb: Double }

    private static func findPeak(_ c: [Float], _ expected: Double, _ sr: Int) -> Peak? {
        let from = max(1, Int(expected - 0.30 * Double(sr)))
        let to = min(c.count - 2, Int(expected + 0.45 * Double(sr)))
        guard to - from >= sr / 20 else { return nil }
        var argmax = from; var maxV: Float = 0
        for i in from...to where c[i] > maxV { maxV = c[i]; argmax = i }
        // первый локальный максимум, превышающий 50% глобального максимума окна (а не самый сильный путь)
        var best = argmax
        for i in from...to where c[i] >= 0.5 * maxV && c[i] >= c[i - 1] && c[i] > c[i + 1] { best = i; break }
        var window = Array(c[from...to]); window.sort()
        let noise = max(Double(window[window.count / 2]), 1e-9)
        let ratio = Double(c[best]) / noise
        guard ratio >= 8 else { return nil }
        let y0 = Double(c[best - 1]), y1 = Double(c[best]), y2 = Double(c[best + 1])
        let den = y0 - 2 * y1 + y2
        let delta = abs(den) > 1e-12 ? 0.5 * (y0 - y2) / den : 0
        return Peak(pos: Double(best) + min(0.5, max(-0.5, delta)), snrDb: 20 * log10(ratio))
    }

    private static func median(_ v: [Double]) -> Double {
        let s = v.sorted(); let m = s.count / 2
        return s.count % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2
    }

    /// |взаимная корреляция| с двумя шаблонами. БПФ записи считается один раз.
    static func xcorrAbsBoth(_ x: [Float], _ tplA: [Float], _ tplB: [Float]) -> (up: [Float], down: [Float]) {
        var n = 1
        while n < x.count + max(tplA.count, tplB.count) { n <<= 1 }
        var xr0 = [Double](repeating: 0, count: n), xi0 = [Double](repeating: 0, count: n)
        for i in 0..<x.count { xr0[i] = Double(x[i]) }
        Fft.transform(&xr0, &xi0, inverse: false)
        return (corrWith(xr0, xi0, tplA, x.count), corrWith(xr0, xi0, tplB, x.count))
    }

    private static func corrWith(_ xr0: [Double], _ xi0: [Double], _ tpl: [Float], _ len: Int) -> [Float] {
        let n = xr0.count
        var xr = xr0, xi = xi0
        var tr = [Double](repeating: 0, count: n), ti = [Double](repeating: 0, count: n)
        for i in 0..<tpl.count { tr[i] = Double(tpl[i]) }
        Fft.transform(&tr, &ti, inverse: false)
        for i in 0..<n {
            let re = xr[i] * tr[i] + xi[i] * ti[i]
            let im = xi[i] * tr[i] - xr[i] * ti[i]
            xr[i] = re; xi[i] = im
        }
        Fft.transform(&xr, &xi, inverse: true)
        return (0..<len).map { Float(abs(xr[$0])) }
    }
}

/// Итеративное БПФ radix-2 (in-place).
enum Fft {
    static func transform(_ re: inout [Double], _ im: inout [Double], inverse: Bool) {
        let n = re.count
        re.withUnsafeMutableBufferPointer { r in
            im.withUnsafeMutableBufferPointer { m in
                var j = 0
                for i in 1..<n {
                    var bit = n >> 1
                    while j & bit != 0 { j ^= bit; bit >>= 1 }
                    j ^= bit
                    if i < j { r.swapAt(i, j); m.swapAt(i, j) }
                }
                var len = 2
                while len <= n {
                    let ang = 2 * Double.pi / Double(len) * (inverse ? 1 : -1)
                    let wr = cos(ang), wi = sin(ang)
                    let half = len / 2
                    var i = 0
                    while i < n {
                        var cr = 1.0, ci = 0.0
                        for k in 0..<half {
                            let a = i + k, b = a + half
                            let ar = r[b] * cr - m[b] * ci
                            let ai = r[b] * ci + m[b] * cr
                            r[b] = r[a] - ar; m[b] = m[a] - ai
                            r[a] += ar; m[a] += ai
                            let ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                        }
                        i += len
                    }
                    len <<= 1
                }
                if inverse { let fn = Double(n); for i in 0..<n { r[i] /= fn; m[i] /= fn } }
            }
        }
    }
}
