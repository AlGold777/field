import Foundation

/// Three reciprocal microphone sweeps. Only one phone plays during each sweep.
final class LevelMeasureSource: AudioSource {
    static let initialSeconds = 0.75
    static let activeSeconds = 3.0
    static let gapSeconds = 0.5
    static let cycleSeconds = 7.0
    static let repeats = 3
    static let captureSeconds = 23.5

    let mode = SourceMode.levelMeasure
    let title = "Эксперимент · измерение микрофонами"
    let artist = "Поочередные измерительные свипы L/R"
    let totalFrames = Int64(Double(SF.sampleRate) * LevelMeasureSource.captureSeconds)
    private let serverChannel: Int
    private let pointChannel: Int
    private let noise = PinkNoise.make(count: SF.sampleRate * 3, lowHz: 500, highHz: 4_000, seed: 0x2468ACE)

    init(serverChannel: Int, pointChannel: Int) {
        self.serverChannel = serverChannel
        self.pointChannel = pointChannel
    }

    func sample(_ frame: Int64, _ ch: Int) -> Float {
        let initial = Int64(Double(SF.sampleRate) * Self.initialSeconds)
        guard frame >= initial && frame < totalFrames else { return 0 }
        let cycle = Int64(Double(SF.sampleRate) * Self.cycleSeconds)
        let active = Int64(Double(SF.sampleRate) * Self.activeSeconds)
        let gap = Int64(Double(SF.sampleRate) * Self.gapSeconds)
        let relative = frame - initial
        let pos = relative % cycle
        if pos < active && ch == serverChannel {
            // Индекс от начала свипа, как у Point: смещение повтора рвало свип щелчком.
            return noise[Int(pos % Int64(noise.count))]
        }
        if pos >= active + gap && pos < active * 2 + gap && ch == pointChannel {
            return noise[Int((pos - active - gap) % Int64(noise.count))]
        }
        return 0
    }
}
