import Foundation

/// Источник со случайным доступом по номеру кадра (48 кГц). ch: 0 = L, 1 = R.
protocol AudioSource: AnyObject {
    var mode: SourceMode { get }
    var title: String { get }
    var artist: String { get }
    var totalFrames: Int64 { get }
    func sample(_ frame: Int64, _ ch: Int) -> Float
}

final class PcmSource: AudioSource {
    let mode = SourceMode.music
    let title: String
    let artist: String
    let totalFrames: Int64
    private let pcm: [Int16]
    init(pcm: [Int16], title: String, artist: String) {
        self.pcm = pcm; self.title = title; self.artist = artist; totalFrames = Int64(pcm.count / 2)
    }
    func sample(_ frame: Int64, _ ch: Int) -> Float {
        guard frame >= 0 && frame < totalFrames else { return 0 }
        return Float(pcm[Int(frame * 2) + ch]) / 32768
    }
}

enum Chirp {
    static let lengthS = 1024.0 / 48_000.0
    static let fLow = 1_500.0
    static let fHigh = 7_500.0
    /// Линейный чирп с окном Ханна; up — F_LOW→F_HIGH.
    static func make(up: Bool, sampleRate: Int) -> [Float] {
        let n = Int(lengthS * Double(sampleRate))
        let f0 = up ? fLow : fHigh, f1 = up ? fHigh : fLow
        let T = Double(n) / Double(sampleRate)
        return (0..<n).map { i in
            let t = Double(i) / Double(sampleRate)
            let phase = 2 * Double.pi * (f0 * t + (f1 - f0) * t * t / (2 * T))
            let w = 0.5 - 0.5 * cos(2 * Double.pi * Double(i) / Double(n - 1))
            return Float(sin(phase) * w)
        }
    }
}

/// Калибровка: канал Point — чирп вверх в 0.5+k с; канал Server — чирп вниз в 1.0+k с (k = 0…5).
final class CalibrationSource: AudioSource {
    static let count = 6
    static let pointFirstS = 0.5
    static let serverFirstS = 1.0
    static let durationS = 6.8
    let mode = SourceMode.calib
    let title = "Калибровка задержки"
    let artist = "SoundField"
    let totalFrames = Int64(Double(SF.sampleRate) * CalibrationSource.durationS)
    private let pointCh: Int
    private let up = Chirp.make(up: true, sampleRate: SF.sampleRate)
    private let down = Chirp.make(up: false, sampleRate: SF.sampleRate)
    init(pointCh: Int) { self.pointCh = pointCh }

    func sample(_ frame: Int64, _ ch: Int) -> Float {
        let isPoint = ch == pointCh
        let base = Int64((isPoint ? Self.pointFirstS : Self.serverFirstS) * Double(SF.sampleRate))
        guard frame >= base else { return 0 }
        let sr = Int64(SF.sampleRate)
        let k = (frame - base) / sr
        let off = Int((frame - base) % sr)
        let tpl = isPoint ? up : down
        return (k < Self.count && off < tpl.count) ? tpl[off] * 0.8 : 0
    }
}

/// Метроном для ручной подстройки: щелчок в обоих каналах каждые 0.5 с.
final class MetronomeSource: AudioSource {
    let mode = SourceMode.metronome
    let title = "Метроном"
    let artist = "Сдвигайте ползунок, пока щелчки не сольются в один"
    let totalFrames = Int64(SF.sampleRate) * 600
    private let period = Int64(SF.sampleRate / 2)
    private let click: [Float] = (0..<480).map { i in
        let t = Double(i) / Double(SF.sampleRate)
        return Float(sin(2 * Double.pi * 2_000 * t) * exp(-t / 0.002) * 0.9)
    }
    func sample(_ frame: Int64, _ ch: Int) -> Float {
        guard frame >= 0 else { return 0 }
        let off = Int(frame % period)
        return off < click.count ? click[off] : 0
    }
}

/// Три сигнала слева (880 Гц), затем три справа (1320 Гц).
final class ChannelTestSource: AudioSource {
    let mode = SourceMode.lrtest
    let title = "Проверка каналов"
    let artist = "Сначала левый, затем правый"
    let totalFrames = Int64(Double(SF.sampleRate) * 3.8)
    func sample(_ frame: Int64, _ ch: Int) -> Float {
        let t = Double(frame) / Double(SF.sampleRate)
        let starts = ch == Ch.L ? [0.3, 0.8, 1.3] : [2.1, 2.6, 3.1]
        let f = ch == Ch.L ? 880.0 : 1_320.0
        for s in starts {
            let dt = t - s
            if dt >= 0 && dt <= 0.25 {
                let env = min(1, dt / 0.01) * min(1, (0.25 - dt) / 0.03)
                return Float(sin(2 * Double.pi * f * dt) * env * 0.6)
            }
        }
        return 0
    }
}

/// Минимальный WAV-парсер (PCM 16 бит, моно/стерео) → 48 кГц стерео int16.
enum Wav {
    enum WavError: Error { case format }

    static func decode(_ data: Data) throws -> [Int16] {
        let b = [UInt8](data)
        func u32(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 | Int(b[o + 2]) << 16 | Int(b[o + 3]) << 24 }
        func u16(_ o: Int) -> Int { Int(b[o]) | Int(b[o + 1]) << 8 }
        func i16(_ o: Int) -> Int16 { Int16(bitPattern: UInt16(u16(o))) }
        guard b.count > 44, String(bytes: b[0..<4], encoding: .ascii) == "RIFF",
              String(bytes: b[8..<12], encoding: .ascii) == "WAVE" else { throw WavError.format }
        var pos = 12
        var channels = 0, rate = 0, bits = 0, fmt = 0, dataOff = -1, dataLen = 0
        while pos + 8 <= b.count {
            let id = String(bytes: b[pos..<pos + 4], encoding: .ascii) ?? ""
            let len = u32(pos + 4); let body = pos + 8
            if id == "fmt " { fmt = u16(body); channels = u16(body + 2); rate = u32(body + 4); bits = u16(body + 14) }
            if id == "data" { dataOff = body; dataLen = min(len, b.count - body) }
            if dataOff >= 0 && channels > 0 { break }
            pos = body + len + (len & 1)
        }
        guard fmt == 1, bits == 16, (1...2).contains(channels), dataOff >= 0 else { throw WavError.format }
        let frames = dataLen / (2 * channels)
        var st = [Int16](repeating: 0, count: frames * 2)
        for f in 0..<frames {
            let l = i16(dataOff + f * channels * 2)
            let r = channels == 2 ? i16(dataOff + (f * channels + 1) * 2) : l
            st[f * 2] = l; st[f * 2 + 1] = r
        }
        if rate != SF.sampleRate { st = resample(st, from: rate, to: SF.sampleRate) }
        return st
    }

    private static func resample(_ src: [Int16], from: Int, to: Int) -> [Int16] {
        let inFrames = src.count / 2
        let outFrames = inFrames * to / from
        var out = [Int16](repeating: 0, count: outFrames * 2)
        for i in 0..<outFrames {
            let x = Double(i) * Double(from) / Double(to)
            let i0 = min(Int(x), inFrames - 1), i1 = min(i0 + 1, inFrames - 1)
            let fr = x - Double(i0)
            for c in 0..<2 {
                out[i * 2 + c] = Int16(Double(src[i0 * 2 + c]) * (1 - fr) + Double(src[i1 * 2 + c]) * fr)
            }
        }
        return out
    }
}
