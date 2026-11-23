import Foundation
import AVFoundation

struct PreparedAudioFile {
    let id: String
    let url: URL
    let title: String
    let artist: String
    /// Исходный файл (до копирования в хранилище приложения); нужен для списка недавних.
    var source: URL? = nil
}

enum AudioFileDecoder {
    static func prepare(_ source: URL) throws -> PreparedAudioFile {
        let manager = FileManager.default
        let support = try manager.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let folder = support.appendingPathComponent("SoundField/AudioQueue", isDirectory: true)
        try manager.createDirectory(at: folder, withIntermediateDirectories: true)
        let id = UUID().uuidString
        let ext = source.pathExtension.isEmpty ? "audio" : source.pathExtension
        let destination = folder.appendingPathComponent(id).appendingPathExtension(ext)
        try manager.copyItem(at: source, to: destination)

        let metadata = AVURLAsset(url: source).commonMetadata
        let title = metadata.first { $0.commonKey == .commonKeyTitle }?.stringValue
            ?? source.deletingPathExtension().lastPathComponent
        let artist = metadata.first { $0.commonKey == .commonKeyArtist }?.stringValue ?? ""
        return PreparedAudioFile(id: id, url: destination, title: title, artist: artist, source: source)
    }

    static func decode(_ item: PreparedAudioFile) throws -> PcmSource {
        let file = try AVAudioFile(forReading: item.url)
        let format = file.processingFormat
        guard format.sampleRate > 0, file.length > 0 else {
            throw NSError(domain: "SoundField.Audio", code: 1, userInfo: [NSLocalizedDescriptionKey: "В файле нет аудиоданных"])
        }
        // Частота источника → 48 кГц ядром (SfSampleRateConverter); при совпадении частот конвертер не нужен.
        let inputRate = Int(format.sampleRate.rounded())
        var converter: SfSampleRateConverter?
        if inputRate != SF.sampleRate {
            guard let c = SfSampleRateConverter(channels: 2, from: inputRate, to: SF.sampleRate) else {
                throw NSError(domain: "SoundField.Audio", code: 2, userInfo: [NSLocalizedDescriptionKey: "Формат аудио не поддерживается"])
            }
            converter = c
        }
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 32_768) else {
            throw NSError(domain: "SoundField.Audio", code: 3, userInfo: [NSLocalizedDescriptionKey: "Не удалось подготовить аудио"])
        }
        let encoder = Pcm24StereoEncoder(seed: 0x5F24)
        var pcm24: [UInt8] = []
        let meter = SfTrackMeter()
        let channelCount = Int(format.channelCount)
        while true {
            try file.read(into: buffer, frameCount: 32_768)
            let n = Int(buffer.frameLength)
            if n == 0 { break }
            guard let ch = buffer.floatChannelData else {
                throw NSError(domain: "SoundField.Audio", code: 5, userInfo: [NSLocalizedDescriptionKey: "Кодек не вернул аудиоданные"])
            }
            // Интерлив стерео: моно дублируется в L и R; при >2 каналах берём первые два.
            let left = ch[0], right = channelCount > 1 ? ch[1] : ch[0]
            var block = [Float](repeating: 0, count: n * 2)
            for f in 0..<n {
                block[f * 2] = left[f]
                block[f * 2 + 1] = right[f]
            }
            if let c = converter {
                let out = c.process(block)
                meter.process(out)
                pcm24 += encoder.encode(out)
            } else {
                meter.process(block)
                pcm24 += encoder.encode(block)
            }
        }
        if let c = converter {
            let tail = c.finish()
            meter.process(tail)
            pcm24 += encoder.encode(tail)
        }
        guard !pcm24.isEmpty else {
            throw NSError(domain: "SoundField.Audio", code: 5, userInfo: [NSLocalizedDescriptionKey: "Кодек не вернул аудиоданные"])
        }
        let tp = meter.truePeakDbtp
        let lufs = meter.loudnessLufs
        return PcmSource(pcm24: pcm24, title: item.title, artist: item.artist,
                         staticGain: SfTrackMeter.staticGain(lufs: lufs, truePeakDbtp: tp), truePeakDbtp: tp, loudnessLufs: lufs)
    }
}

/// Источник со случайным доступом по номеру кадра (48 кГц). ch: 0 = L, 1 = R.
protocol AudioSource: AnyObject {
    var mode: SourceMode { get }
    var title: String { get }
    var artist: String { get }
    var totalFrames: Int64 { get }
    func sample(_ frame: Int64, _ ch: Int) -> Float
}

/// Музыкальный источник: 24-битный кэш little-endian, интерлив L/R, 6 байт на кадр.
final class PcmSource: AudioSource {
    let mode = SourceMode.music
    let title: String
    let artist: String
    let totalFrames: Int64
    /// Статическое усиление по true-peak (линейное); кэш остаётся без усиления.
    let staticGain: Float
    /// Измеренный true-peak трека, dBTP (для диагностики; −200 — не измерялся).
    let truePeakDbtp: Double
    /// Измеренная интегральная громкость BS.1770-4, LUFS (для диагностики; −200 — нет данных).
    let loudnessLufs: Double
    private let pcm: [UInt8]
    init(pcm24: [UInt8], title: String, artist: String, staticGain: Float = 1, truePeakDbtp: Double = -200, loudnessLufs: Double = -200) {
        self.pcm = pcm24; self.title = title; self.artist = artist; totalFrames = Int64(pcm24.count / 6)
        self.staticGain = staticGain; self.truePeakDbtp = truePeakDbtp; self.loudnessLufs = loudnessLufs
    }
    convenience init(interleavedFloat: [Float], title: String, artist: String) {
        let meter = SfTrackMeter()
        meter.process(interleavedFloat)
        let tp = meter.truePeakDbtp
        let lufs = meter.loudnessLufs
        self.init(pcm24: Pcm24StereoEncoder(seed: 0x5F24).encode(interleavedFloat), title: title, artist: artist,
                  staticGain: SfTrackMeter.staticGain(lufs: lufs, truePeakDbtp: tp), truePeakDbtp: tp, loudnessLufs: lufs)
    }
    func sample(_ frame: Int64, _ ch: Int) -> Float {
        guard frame >= 0 && frame < totalFrames else { return 0 }
        return pcm.withUnsafeBufferPointer { Pcm24.sampleLe($0, Int(frame) * 2 + ch) } * staticGain
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
    // Вынесено из sample(): литерал массива создавался бы на каждый сэмпл.
    private static let leftStarts = [0.3, 0.8, 1.3]
    private static let rightStarts = [2.1, 2.6, 3.1]
    func sample(_ frame: Int64, _ ch: Int) -> Float {
        let t = Double(frame) / Double(SF.sampleRate)
        let starts = ch == Ch.L ? Self.leftStarts : Self.rightStarts
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

/// Fixed-seed pink noise shaped into a frequency band and normalized to −20 dBFS RMS, capped at −8 dBFS peak.
enum PinkNoise {
    static func make(count: Int, lowHz: Double, highHz: Double, seed: UInt32 = 0x13579BDF) -> [Float] {
        var state = seed
        var b0: Float = 0, b1: Float = 0, b2: Float = 0, b3: Float = 0, b4: Float = 0, b5: Float = 0, b6: Float = 0
        let aLow = Float(1 - exp(-2 * Double.pi * lowHz / Double(SF.sampleRate)))
        let aHigh = Float(1 - exp(-2 * Double.pi * highHz / Double(SF.sampleRate)))
        var low: Float = 0, high: Float = 0
        var out = [Float](repeating: 0, count: count)
        var power = 0.0, peak: Float = 0
        for i in out.indices {
            state ^= state << 13; state ^= state >> 17; state ^= state << 5
            let white = Float(Double(state) / Double(UInt32.max) * 2 - 1)
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            let pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11
            b6 = white * 0.115926
            high += aHigh * (pink - high); low += aLow * (pink - low)
            let sample = high - low
            out[i] = sample; power += Double(sample) * Double(sample); peak = max(peak, abs(sample))
        }
        guard !out.isEmpty else { return out }
        let rms = max(sqrt(power / Double(out.count)), 1e-9)
        let scale = min(0.1 / rms, 0.39810717 / max(Double(peak), 1e-9))
        for i in out.indices { out[i] = Float(Double(out[i]) * scale) }
        return out
    }
}

final class CenterCheckSource: AudioSource {
    let mode = SourceMode.center
    let title = "Проверка центра"
    let artist = "Коррелированный розовый шум · оба телефона"
    let totalFrames = Int64(SF.sampleRate) * 15 / 2
    private let noise = PinkNoise.make(count: SF.sampleRate * 2, lowHz: 200, highHz: 8_000)
    func sample(_ frame: Int64, _ ch: Int) -> Float {
        guard frame >= 0 else { return 0 }
        let pos = frame % (Int64(SF.sampleRate) * 5 / 2)
        return pos < Int64(SF.sampleRate) * 2 ? noise[Int(pos % Int64(noise.count))] : 0
    }
}

final class LevelMatchSource: AudioSource {
    let mode = SourceMode.level
    let title = "Выравнивание уровня на слух"
    let artist = "Сигнал L и R звучит по очереди"
    let totalFrames = Int64.max
    private let tone = PinkNoise.make(count: SF.sampleRate * 3 / 2, lowHz: 500, highHz: 4_000)
    private let active = Int64(SF.sampleRate) * 3 / 2
    private let pause = Int64(SF.sampleRate) / 4
    private let fadeFrames = Int64(SF.sampleRate) * 20 / 1_000
    private var cycle: Int64 { active * 2 + pause * 2 }

    private func sampleAt(_ frame: Int64) -> Float {
        let envelope = min(1, min(Double(frame) / Double(fadeFrames), Double(active - 1 - frame) / Double(fadeFrames)))
        return tone[Int(frame % Int64(tone.count))] * Float(max(0, envelope))
    }

    func sample(_ frame: Int64, _ ch: Int) -> Float {
        guard frame >= 0 else { return 0 }
        let pos = frame % cycle
        if pos < active && ch == Ch.L { return sampleAt(pos) }
        if pos >= active + pause && pos < active * 2 + pause && ch == Ch.R {
            return sampleAt(pos - active - pause)
        }
        return 0
    }
}

/// Минимальный WAV-парсер (PCM 8/16/24/32 бит или float32, моно/стерео) → 48 кГц стерео интерлив [Float].
enum Wav {
    enum WavError: Error { case format }

    static func decode(_ data: Data) throws -> [Float] {
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
            if id == "fmt " {
                // Обрезанный fmt-чанк — невалидный файл, а не выход за границы массива.
                guard len >= 16, body + 16 <= b.count else { throw WavError.format }
                fmt = u16(body); channels = u16(body + 2); rate = u32(body + 4); bits = u16(body + 14)
                // WAVE_FORMAT_EXTENSIBLE: реальный формат — первые 2 байта под-GUID (смещение 24 в fmt).
                if fmt == 0xFFFE {
                    guard len >= 40, body + 26 <= b.count else { throw WavError.format }
                    fmt = u16(body + 24)
                }
            }
            if id == "data" { dataOff = body; dataLen = min(len, b.count - body) }
            if dataOff >= 0 && channels > 0 { break }
            pos = body + len + (len & 1)
        }
        let isPcm = fmt == 1 && [8, 16, 24, 32].contains(bits)
        let isFloat = fmt == 3 && bits == 32
        guard isPcm || isFloat, (1...2).contains(channels), dataOff >= 0 else { throw WavError.format }
        let bytesPerSample = bits / 8
        let frames = dataLen / (bytesPerSample * channels)
        // Масштаб к [-1, 1): 8 бит — беззнаковый, 16/24/32 — знаковые, float32 — как есть.
        func sampleAt(_ o: Int) -> Float {
            switch (fmt, bits) {
            case (3, _): return Float(bitPattern: UInt32(truncatingIfNeeded: u32(o)))
            case (_, 8): return (Float(b[o]) - 128) / 128
            case (_, 16): return Float(i16(o)) / 32768
            case (_, 24):
                var v = Int32(b[o]) | Int32(b[o + 1]) << 8 | Int32(b[o + 2]) << 16
                if v & 0x800000 != 0 { v -= 0x1000000 }
                return Float(v) / 8_388_608
            default: return Float(Int32(truncatingIfNeeded: u32(o))) / 2_147_483_648
            }
        }
        var st = [Float](repeating: 0, count: frames * 2)
        for f in 0..<frames {
            let base = dataOff + f * channels * bytesPerSample
            let l = sampleAt(base)
            let r = channels == 2 ? sampleAt(base + bytesPerSample) : l
            st[f * 2] = l; st[f * 2 + 1] = r
        }
        if rate != SF.sampleRate { st = SfDsp.resample(st, channels: 2, from: rate, to: SF.sampleRate) }
        return st
    }
}


/// iOS M/S spatial processor. Stateless frame access gives identical results to UDP resend and local render.
/// It does not replace Android's STFT/XTC implementation; both use the same control protocol.
final class StereoSpatialSource: AudioSource {
    private let inner: AudioSource
    private let width: Float, air: Float, ambient: Float, depth: Float, reflection: Float, scale: Float
    private let taps: [(Int64, Int64)]
    var mode: SourceMode { inner.mode }
    var title: String { inner.title }
    var artist: String { inner.artist }
    var totalFrames: Int64 { inner.totalFrames }
    init(_ inner: AudioSource, settings: SpatialSettings) {
        self.inner = inner
        let strength = settings.level == "strong" ? 1.4 : 0.6
        width = Float(pow(10,settings.width * strength/20))
        air = Float(pow(10,settings.air * strength/20))
        ambient = Float(pow(10,settings.ambient * strength/20)-1)
        depth = Float(pow(10,settings.depth * strength/20))
        reflection = Float(pow(10,settings.reflection/20)*strength)
        let near = settings.profile == "pair_near"
        taps = near ? [(240,360),(432,576),(672,552)] : [(346,504),(643,869),(1008,754)]
        let bound = max(1,Float(pow(10,settings.width * strength/20)) + 2 * (air-1) + ambient + 3 * reflection)
        // Headroom is maintained for every parameter combination; optional loudness mode reduces attenuation.
        scale = settings.preserveLoudness ? 1 / bound : 0.7 / bound
    }
    private func mid(_ f: Int64) -> Float { (inner.sample(f,Ch.L)+inner.sample(f,Ch.R))*0.5 }
    private func side(_ f: Int64) -> Float { (inner.sample(f,Ch.L)-inner.sample(f,Ch.R))*0.5 }
    func sample(_ frame: Int64, _ ch: Int) -> Float {
        guard frame >= 0 && frame < totalFrames else { return 0 }
        let sign: Float = ch == Ch.L ? 1 : -1
        let currentSide = side(frame)
        // Short symmetric low pass splits side information into body and air without phase offset.
        let body = (side(frame-6) + 2*currentSide + side(frame+6))*0.25
        let widened = currentSide*width + (currentSide-body)*(air-1) + side(frame-528)*ambient
        var value = mid(frame)*depth + sign*widened
        for tap in taps { value += mid(frame - (ch == Ch.L ? tap.0 : tap.1))*reflection }
        return min(1,max(-1,value*scale))
    }
}
