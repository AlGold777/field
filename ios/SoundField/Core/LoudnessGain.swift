// Нормализация громкости трека (A3.1): чистая формула без C-зависимостей.
// Источник правил: tools/loudness_gain_vectors.json (поле doc). Проверка: tools/check_loudness_gain.swift.
// NaN — отсутствие значения (null в JSON). Значения в dB.
import Foundation

enum LoudnessGain {
    /// Целевая интегральная громкость, LUFS.
    static let targetLufs = -16.0
    /// Потолок усиления, dB.
    static let maxGainDb = 12.0
    /// Запас под true-peak предлимитера, dBTP (потолок −1,5 dBTP с запасом 0,05 dB).
    static let truePeakCeilingDbtp = -1.55
    /// Допустимый перевес усиления над запасом по true-peak, dB.
    static let headroomSlackDb = 3.0

    /// Усиление в dB по интегральной громкости lufs и true-peak truePeakDbtp.
    /// lufs NaN или ≤ −100 → min(0, −1,55 − tp) (старый путь). tp NaN при известной lufs → min(g, 0). tp NaN и lufs неизвестна → 0.
    static func db(lufs: Double, truePeakDbtp tp: Double) -> Double {
        let legacy = min(0.0, truePeakCeilingDbtp - tp)
        if lufs.isNaN || lufs <= -100 {
            return tp.isNaN ? 0.0 : legacy
        }
        var g = targetLufs - lufs
        if !tp.isNaN {
            g = min(g, (truePeakCeilingDbtp - tp) + headroomSlackDb)
        } else {
            g = min(g, 0.0)
        }
        return min(g, maxGainDb)
    }
}
