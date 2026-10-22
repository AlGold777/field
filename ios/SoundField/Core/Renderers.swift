import Foundation

/// Снимок состояния для аудиопотока Server (меняется целиком).
final class ServerPlayState {
    let timeline: Timeline
    let source: AudioSource
    let channel: Int
    let gain: Float
    init(timeline: Timeline, source: AudioSource, channel: Int, gain: Float) {
        self.timeline = timeline; self.source = source; self.channel = channel; self.gain = gain
    }
}

/// Fade-out при паузе: спад от последнего уровня проигрывания до нуля за ≈5 мс. Без аллокаций.
struct PauseFade {
    private static let frames = SF.sampleRate / 200
    private var level: Float = 0
    private var left = 0
    /// Вызывать после буфера с воспроизведением, до применения громкости.
    mutating func remember(_ out: UnsafeMutablePointer<Float>, _ n: Int) {
        guard n > 0 else { return }
        level = out[n - 1]; left = Self.frames
    }
    /// Вызывать для буфера паузы (out уже обнулён).
    mutating func apply(_ out: UnsafeMutablePointer<Float>, _ n: Int) {
        var i = 0
        while left > 0 && i < n {
            out[i] = level * Float(left) / Float(Self.frames)
            left -= 1; i += 1
        }
    }
}

/// Рендер на Server: канал Server из текущего источника по общей шкале (часы Server — эталон).
final class ServerRenderer: RenderSource {
    let corrector = PlayoutCorrector()
    private let ramp = GainRamp()
    private var fade = PauseFade()
    private let state: () -> ServerPlayState
    init(state: @escaping () -> ServerPlayState) { self.state = state }

    func render(_ out: UnsafeMutablePointer<Float>, _ n: Int, _ presentLocalNs: Int64) {
        let st = state()
        let tl = st.timeline
        guard tl.playing else {
            out.update(repeating: 0, count: n); fade.apply(out, n); corrector.reset(); ramp.apply(out, n, st.gain); return
        }
        corrector.plan(ideal: tl.frameAt(presentLocalNs), n: n)
        let src = st.source
        for i in 0..<n {
            let f = corrector.frame(i, n)
            out[i] = f < tl.anchorFrame ? 0 : st.channel == Ch.BOTH ? (src.sample(f, Ch.L) + src.sample(f, Ch.R)) * 0.5 : src.sample(f, st.channel)
        }
        fade.remember(out, n)
        ramp.apply(out, n, st.gain)
    }
    private let rightRamp = GainRamp()
    private var rightFade = PauseFade()
    func renderStereo(_ left: UnsafeMutablePointer<Float>, _ right: UnsafeMutablePointer<Float>, _ n: Int, _ presentLocalNs: Int64) {
        let st = state(), tl = st.timeline
        guard tl.playing else {
            left.update(repeating: 0, count: n); right.update(repeating: 0, count: n)
            fade.apply(left, n); rightFade.apply(right, n)
            corrector.reset(); ramp.apply(left, n, st.gain); rightRamp.apply(right, n, st.gain); return
        }
        corrector.plan(ideal: tl.frameAt(presentLocalNs), n: n)
        for i in 0..<n {
            let f = corrector.frame(i, n)
            left[i] = f < tl.anchorFrame ? 0 : st.source.sample(f, st.channel == Ch.BOTH ? Ch.L : st.channel)
            right[i] = f < tl.anchorFrame ? 0 : st.source.sample(f, st.channel == Ch.BOTH ? Ch.R : st.channel)
        }
        fade.remember(left, n); rightFade.remember(right, n)
        ramp.apply(left, n, st.gain); rightRamp.apply(right, n, st.gain)
    }
}

struct PointParams { var gain: Float; var trimNs: Int64; var totalFrames: Int64 }

/// Рендер на Point: кадры из jitter buffer; идеальный кадр — по часам Server минус калибровочный сдвиг.
final class PointRenderer: RenderSource {
    let corrector = PlayoutCorrector()
    private let ramp = GainRamp()
    private let rx: PointReceiver
    private let clock: ClockSync
    private let params: () -> PointParams
    private(set) var underrunFrames: Int64 = 0
    private(set) var underrunEvents: Int64 = 0
    private var inUnderrun = false

    init(rx: PointReceiver, clock: ClockSync, params: @escaping () -> PointParams) {
        self.rx = rx; self.clock = clock; self.params = params
    }

    func render(_ out: UnsafeMutablePointer<Float>, _ n: Int, _ presentLocalNs: Int64) {
        let tl = rx.timeline
        let p = params()
        guard tl.playing, clock.ready else {
            out.update(repeating: 0, count: n); corrector.reset(); ramp.apply(out, n, p.gain); return
        }
        let serverNs = clock.toServer(presentLocalNs) - p.trimNs
        corrector.plan(ideal: tl.frameAt(serverNs), n: n)
        var missing = 0
        for i in 0..<n {
            let f = corrector.frame(i, n)
            if let s = rx.read(f) { out[i] = s } else {
                out[i] = 0
                if f >= tl.anchorFrame && (p.totalFrames <= 0 || f < p.totalFrames) { missing += 1 }
            }
        }
        if missing > 0 {
            underrunFrames += Int64(missing)
            if !inUnderrun { underrunEvents += 1; inUnderrun = true }
        } else { inUnderrun = false }
        rx.lastRenderedFrame = corrector.frame(n - 1, n)
        ramp.apply(out, n, p.gain)
    }
}
