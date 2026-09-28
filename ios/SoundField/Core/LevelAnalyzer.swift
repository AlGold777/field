import Foundation

/// Shared one-third-octave Welch analysis used by the microphone experiment.
enum LevelAnalyzer {
    static let centersHz: [Double] = [500, 630, 800, 1_000, 1_250, 1_600, 2_000, 2_500, 3_150, 4_000]
    private static let fftSize = 8_192
    private static let hop = fftSize / 2

    struct Result {
        let levelsDb: [Double?]
        let snrDb: [Double?]
        let validBands: Int
        let peakDbfs: Double
    }

    static func analyze(_ samples: [Float], sampleRate: Int, start: Double, duration: Double,
                        backgroundStart: Double? = nil, backgroundDuration: Double = 0) -> Result {
        precondition(sampleRate > 0)
        let signal = spectrum(samples, sampleRate: sampleRate, start: start, duration: duration)
        let background = backgroundStart.map { spectrum(samples, sampleRate: sampleRate, start: $0, duration: backgroundDuration, trimEdges: false) }
        let snr = centersHz.indices.map { i -> Double? in
            guard let s = signal.levels[i] else { return nil }
            if let background { guard let b = background.levels[i] else { return nil }; return s - b }
            return 99
        }
        let valid = signal.peakDbfs < -1
            ? snr.filter { ($0 ?? -Double.infinity) >= 15 }.count : 0
        return Result(levelsDb: signal.levels, snrDb: snr, validBands: valid, peakDbfs: signal.peakDbfs)
    }

    static func median<S: Sequence>(_ values: S) -> Double? where S.Element == Double {
        let sorted = values.filter(\.isFinite).sorted()
        guard !sorted.isEmpty else { return nil }
        let n = sorted.count
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    static func differenceDb(_ a: Result, _ b: Result) -> Double? {
        guard a.validBands >= 6, b.validBands >= 6 else { return nil }
        let shared = centersHz.indices.compactMap { i -> Double? in
            guard let la = a.levelsDb[i], let lb = b.levelsDb[i],
                  (a.snrDb[i] ?? 0) >= 15, (b.snrDb[i] ?? 0) >= 15 else { return nil }
            return la - lb
        }
        return shared.count >= 6 ? median(shared) : nil
    }

    private struct Spectrum { let levels: [Double?]; let peakDbfs: Double }

    private static func spectrum(_ samples: [Float], sampleRate: Int, start: Double, duration: Double, trimEdges: Bool = true) -> Spectrum {
        guard !samples.isEmpty, duration > 0 else { return Spectrum(levels: Array(repeating: nil, count: centersHz.count), peakDbfs: .infinity) }
        let from = min(samples.count, max(0, Int(start * Double(sampleRate))))
        let until = min(samples.count, max(from, Int((start + duration) * Double(sampleRate))))
        let peak = (from..<until).reduce(0.0) { max($0, abs(Double(samples[$1]))) }
        let peakDb = peak <= 1e-12 ? -Double.infinity : 20 * log10(peak)
        let edge = trimEdges ? Int(0.3 * Double(sampleRate)) : 0
        let first = min(until, from + edge)
        let last = max(first, until - edge)
        guard last - first >= fftSize else { return Spectrum(levels: Array(repeating: nil, count: centersHz.count), peakDbfs: peakDb) }

        var accum = Array(repeating: 0.0, count: centersHz.count)
        var windows = 0
        var offset = first
        let windowSum = (0..<fftSize).reduce(0.0) { $0 + 0.5 - 0.5 * cos(2 * Double.pi * Double($1) / Double(fftSize - 1)) }
        while offset + fftSize <= last {
            var re = Array(repeating: 0.0, count: fftSize)
            var im = Array(repeating: 0.0, count: fftSize)
            for i in 0..<fftSize {
                let w = 0.5 - 0.5 * cos(2 * Double.pi * Double(i) / Double(fftSize - 1))
                re[i] = Double(samples[offset + i]) * w
            }
            fft(&re, &im)
            for band in centersHz.indices {
                let scale = pow(2.0, 1.0 / 6.0)
                let low = centersHz[band] / scale, high = centersHz[band] * scale
                let loBin = max(1, Int(ceil(low * Double(fftSize) / Double(sampleRate))))
                let hiBin = min(fftSize / 2, Int(high * Double(fftSize) / Double(sampleRate)))
                if loBin <= hiBin {
                    var power = 0.0
                    for k in loBin...hiBin { power += re[k] * re[k] + im[k] * im[k] }
                    accum[band] += 2 * power / (windowSum * windowSum)
                }
            }
            windows += 1; offset += hop
        }
        let levels = centersHz.indices.map { i -> Double? in
            guard windows > 0, accum[i] > 1e-24 else { return nil }
            return 10 * log10(accum[i] / Double(windows))
        }
        return Spectrum(levels: levels, peakDbfs: peakDb)
    }

    private static func fft(_ re: inout [Double], _ im: inout [Double]) {
        let n = re.count
        var j = 0
        for i in 1..<n {
            var bit = n >> 1
            while j & bit != 0 { j ^= bit; bit >>= 1 }
            j ^= bit
            if i < j { re.swapAt(i, j); im.swapAt(i, j) }
        }
        var len = 2
        while len <= n {
            let angle = -2 * Double.pi / Double(len)
            let wr0 = cos(angle), wi0 = sin(angle)
            var start = 0
            while start < n {
                var wr = 1.0, wi = 0.0
                for k in 0..<(len / 2) {
                    let even = start + k, odd = even + len / 2
                    let tr = wr * re[odd] - wi * im[odd]
                    let ti = wr * im[odd] + wi * re[odd]
                    re[odd] = re[even] - tr; im[odd] = im[even] - ti
                    re[even] += tr; im[even] += ti
                    let nextWr = wr * wr0 - wi * wi0
                    wi = wr * wi0 + wi * wr0; wr = nextWr
                }
                start += len
            }
            len <<= 1
        }
    }
}
