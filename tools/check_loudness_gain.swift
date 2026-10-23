// Автономная проверка формулы нормализации громкости A3.1: ios/SoundField/Core/LoudnessGain.swift ↔ tools/loudness_gain_vectors.json.
// Читает все векторы из JSON (null = NaN), сверяет результат с допуском 1e-9.
//
// Запуск из корня репозитория (компилятор требует, чтобы файл с top-level кодом назывался main.swift):
//   mkdir -p "$TMPDIR/sf_loudness" && cp tools/check_loudness_gain.swift "$TMPDIR/sf_loudness/main.swift"
//   swiftc -o "$TMPDIR/sf_loudness/check" ios/SoundField/Core/LoudnessGain.swift "$TMPDIR/sf_loudness/main.swift"
//   "$TMPDIR/sf_loudness/check"
// Путь к JSON можно передать первым аргументом; по умолчанию — tools/loudness_gain_vectors.json.
// Вывод: «ALL PASSED» и код 0, либо список провалов и код 1.
import Foundation

let defaultPath = "tools/loudness_gain_vectors.json"
let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : defaultPath

guard let data = FileManager.default.contents(atPath: path),
      let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let vectors = root["vectors"] as? [[String: Any]], !vectors.isEmpty
else {
    print("FAILED: не удалось прочитать векторы из \(path)")
    exit(1)
}

/// JSON-число → Double; null или отсутствие → NaN.
func value(_ v: Any?) -> Double {
    if let d = v as? Double { return d }
    if let n = v as? NSNumber, !(v is Bool) { return n.doubleValue }
    return Double.nan
}

var passed = 0
var failures: [String] = []
let tolerance = 1e-9

for (i, v) in vectors.enumerated() {
    let lufs = value(v["lufs"])
    let tp = value(v["tp"])
    let expected = value(v["gain_db"])
    if expected.isNaN {
        failures.append("вектор \(i): нет gain_db")
        continue
    }
    let got = LoudnessGain.db(lufs: lufs, truePeakDbtp: tp)
    if abs(got - expected) <= tolerance {
        passed += 1
    } else {
        failures.append("вектор \(i): lufs=\(lufs) tp=\(tp) ожидалось \(expected), получено \(got)")
    }
}

if failures.isEmpty {
    print("ALL PASSED (\(passed) векторов)")
    exit(0)
}
print("FAILED \(failures.count) из \(vectors.count):")
for f in failures { print("  - \(f)") }
exit(1)
