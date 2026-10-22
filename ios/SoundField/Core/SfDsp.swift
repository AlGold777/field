import Foundation

/// Обёртка над C-ядром sfdsp (dsp/include/sfdsp.h).
enum SfDsp {
    static var version: String { String(cString: sfdsp_version()) }
    static var abiVersion: Int { Int(sfdsp_abi_version()) }

    /// Ресемплер ядра (A1.2): полифазный sinc, окно Кайзера, ≥ 120 dB подавления зеркал, ≤ ±0,01 dB до 20 кГц.
    /// Интерлив [channels] float → интерлив [channels] float на частоте [to]. Нулевой результат — неверные параметры.
    static func resample(_ input: [Float], channels: Int, from: Int, to: Int) -> [Float] {
        if from == to { return input }
        let framesIn = input.count / channels
        guard framesIn > 0, let src = sf_src_create(Int32(channels), Int32(from), Int32(to)) else { return [] }
        defer { sf_src_destroy(src) }
        let cap = Int(sf_src_max_output(src, Int64(framesIn))) + Int(sf_src_max_output(src, 0))
        var out = [Float](repeating: 0, count: cap * channels)
        let produced: Int = input.withUnsafeBufferPointer { inp in
            out.withUnsafeMutableBufferPointer { o in
                let n = sf_src_process(src, inp.baseAddress, Int64(framesIn), o.baseAddress, Int64(cap))
                guard n >= 0 else { return -1 }
                let f = sf_src_flush(src, o.baseAddress! + Int(n) * channels, Int64(cap) - n)
                return f < 0 ? -1 : Int(n + f)
            }
        }
        guard produced >= 0 else { return [] }
        out.removeLast(out.count - produced * channels)
        return out
    }
}

/// Потоковый ресемплер ядра для импорта: подавайте блоки интерлива, в конце — finish(). Один экземпляр — один поток.
final class SfSampleRateConverter {
    private var handle: OpaquePointer?
    private let channels: Int
    let passthrough: Bool

    init?(channels: Int, from: Int, to: Int) {
        self.channels = channels
        passthrough = from == to
        if !passthrough {
            handle = sf_src_create(Int32(channels), Int32(from), Int32(to))
            if handle == nil { return nil }
        }
    }

    deinit { if let h = handle { sf_src_destroy(h) } }

    /// Интерлив [channels] float → интерлив на выходной частоте (может быть короче/длиннее входа).
    func process(_ input: [Float]) -> [Float] {
        guard let h = handle else { return input }
        let framesIn = input.count / channels
        guard framesIn > 0 else { return [] }
        let cap = Int(sf_src_max_output(h, Int64(framesIn)))
        var out = [Float](repeating: 0, count: cap * channels)
        let n: Int = input.withUnsafeBufferPointer { inp in
            out.withUnsafeMutableBufferPointer { o in Int(sf_src_process(h, inp.baseAddress, Int64(framesIn), o.baseAddress, Int64(cap))) }
        }
        out.removeLast(out.count - max(0, n) * channels)
        return out
    }

    /// Конец входа: дописывает хвост (за концом — тишина).
    func finish() -> [Float] {
        guard let h = handle else { return [] }
        let cap = Int(sf_src_max_output(h, 0))
        var out = [Float](repeating: 0, count: cap * channels)
        let n: Int = out.withUnsafeMutableBufferPointer { o in Int(sf_src_flush(h, o.baseAddress, Int64(cap))) }
        out.removeLast(out.count - max(0, n) * channels)
        return out
    }
}

/// Кодировщик интерлива стерео float → 24 бит little-endian (6 байт на кадр) с TPDF-дизерингом, независимый генератор на канал.
/// Состояние генераторов сохраняется между вызовами: кодируйте поток блоками одним экземпляром.
final class Pcm24StereoEncoder {
    private let dl: OpaquePointer?
    private let dr: OpaquePointer?

    init(seed: UInt64) {
        dl = sf_dither_create(seed &* 2 &+ 1)
        dr = sf_dither_create(seed &* 2 &+ 2)
    }

    deinit { if let d = dl { sf_dither_destroy(d) }; if let d = dr { sf_dither_destroy(d) } }

    func encode(_ interleaved: [Float]) -> [UInt8] {
        let frames = interleaved.count / 2
        var out = [UInt8](repeating: 0, count: frames * 6)
        guard frames > 0 else { return out }
        interleaved.withUnsafeBufferPointer { inp in
            out.withUnsafeMutableBufferPointer { o in
                sf_pcm24_encode(dl, inp.baseAddress, 2, Int32(frames), o.baseAddress, 6, 0)
                sf_pcm24_encode(dr, inp.baseAddress! + 1, 2, Int32(frames), o.baseAddress! + 3, 6, 0)
            }
        }
        return out
    }
}

/// PCM 24 бит со знаком (A1.1). Квантование — только с TPDF-дизерингом ядра (независимый генератор на канал).
enum Pcm24 {
    static let scale: Float = 8_388_608
    static let bytes = 3

    /// Сетевое квантование: float → знаковые 24 бит (Int32) с TPDF; одинаковый seed даёт одинаковый пакет (перепосылка).
    static func quantize(_ values: [Float], seed: UInt64) -> [Int32] {
        let n = values.count
        var packed = [UInt8](repeating: 0, count: n * 3)
        guard n > 0, let d = sf_dither_create(seed) else { return [Int32](repeating: 0, count: n) }
        defer { sf_dither_destroy(d) }
        values.withUnsafeBufferPointer { inp in
            packed.withUnsafeMutableBufferPointer { o in sf_pcm24_encode(d, inp.baseAddress, 1, Int32(n), o.baseAddress, 3, 0) }
        }
        return (0..<n).map { i in
            var v = Int32(packed[i * 3]) | Int32(packed[i * 3 + 1]) << 8 | Int32(packed[i * 3 + 2]) << 16
            if v & 0x800000 != 0 { v -= 0x1000000 }
            return v
        }
    }

    /// Отсчёт из 24-бит little-endian буфера: [index] — номер отсчёта (3 байта на отсчёт).
    @inline(__always) static func sampleLe(_ b: UnsafeBufferPointer<UInt8>, _ index: Int) -> Float {
        let o = index * 3
        var v = Int32(b[o]) | Int32(b[o + 1]) << 8 | Int32(b[o + 2]) << 16
        if v & 0x800000 != 0 { v -= 0x1000000 }
        return Float(v) / scale
    }
}

/// Одноходовой анализатор трека при импорте (true-peak 4×; громкость BS.1770 добавится в A3.1 в тот же проход).
final class SfTrackMeter {
    private let handle: OpaquePointer?
    init() { handle = sf_meter_create(2) }
    deinit { if let h = handle { sf_meter_destroy(h) } }

    /// Стерео интерлив float (после ресемплера, до квантования).
    func process(_ interleaved: [Float]) {
        guard let h = handle, interleaved.count >= 2 else { return }
        interleaved.withUnsafeBufferPointer { sf_meter_process(h, $0.baseAddress, Int64($0.count / 2)) }
    }

    /// Максимальный true-peak с начала потока, dBTP (−200 — тишина).
    var truePeakDbtp: Double { handle.map { sf_meter_true_peak_dbtp($0) } ?? -200 }

    /// Статическое усиление трека, линейное: min(0, −1,55 − tp) dB (запас 0,05 dB под потолок предлимитера −1,5 dBTP).
    static func staticGain(truePeakDbtp tp: Double) -> Float {
        let db = min(0.0, -1.55 - tp)
        return Float(pow(10.0, db / 20.0))
    }
}
