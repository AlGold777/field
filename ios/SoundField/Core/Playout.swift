import Foundation

/// Эпоха общей временной шкалы: кадр F звучит в anchorNs + (F − anchorFrame)/SR (часы Server).
struct Timeline {
    var epoch: Int32
    var playing: Bool
    var anchorFrame: Int64
    var anchorNs: Int64

    static let none = Timeline(epoch: -1, playing: false, anchorFrame: 0, anchorNs: 0)

    func frameAt(_ serverNs: Int64) -> Double {
        Double(anchorFrame) + Double(serverNs - anchorNs) * Double(SF.sampleRate) / 1e9
    }
    func timeOf(_ frame: Int64) -> Int64 {
        anchorNs + Int64(Double(frame - anchorFrame) * 1e9 / Double(SF.sampleRate))
    }
    func positionFrame(_ serverNs: Int64) -> Int64 {
        playing ? max(anchorFrame, Int64(frameAt(serverNs))) : anchorFrame
    }
}

/// Держит позицию чтения рядом с «идеальной»: пропуск/повтор одного сэмпла при малой ошибке,
/// прыжок при ошибке > 20 мс. Только аудиопоток. (Порт core/Playout.kt.)
final class PlayoutCorrector {
    private var readPos = Int64.min
    private var filt = 0.0
    private(set) var start: Int64 = 0
    private(set) var adjust = 0
    private(set) var syncErrorMs = 0.0
    private(set) var hardJumps = 0

    func reset() { readPos = Int64.min; filt = 0 }

    func plan(ideal: Double, n: Int) {
        if readPos == Int64.min || abs(ideal - Double(readPos)) > SF.hardResyncFrames {
            if readPos != Int64.min { hardJumps += 1 }
            readPos = Int64((ideal + 0.5).rounded(.down))
            filt = 0
        }
        let err = ideal - Double(readPos)
        filt += 0.1 * (err - filt)
        if filt > SF.slewThresholdFrames { adjust = 1 }
        else if filt < -SF.slewThresholdFrames { adjust = -1 }
        else { adjust = 0 }
        start = readPos
        readPos += Int64(n + adjust)
        filt -= Double(adjust)
        syncErrorMs = filt * 1000 / Double(SF.sampleRate)
    }

    @inline(__always) func frame(_ i: Int, _ n: Int) -> Int64 {
        let half = n / 2
        if adjust > 0 && i >= half { return start + Int64(i) + 1 }
        if adjust < 0 && i > half { return start + Int64(i) - 1 }
        return start + Int64(i)
    }
}

final class GainRamp {
    private var current: Float = 1
    func apply(_ out: UnsafeMutablePointer<Float>, _ n: Int, _ target: Float) {
        guard n > 0 else { return }
        let k = Float(1 - exp(-1 / (0.015 * Double(SF.sampleRate))))
        var g = current
        for i in 0..<n { g += (target - g) * k; out[i] *= g }
        current = g
    }
}

/// Заполнить n сэмплов; первый прозвучит в presentLocalNs (локальные монотонные часы).
protocol RenderSource: AnyObject {
    func render(_ out: UnsafeMutablePointer<Float>, _ n: Int, _ presentLocalNs: Int64)
}
