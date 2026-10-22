// Автономная проверка загрузчика профилей A2: profiles/devices.json ↔ ios/SoundField/Core/DeviceProfiles.swift.
// Парсинг, поиск (без учёта регистра, synthetic), generic-запас, отбраковка битых данных, границы диапазонов.
//
// Запуск из корня репозитория (компилятор требует, чтобы файл с top-level кодом назывался main.swift):
//   mkdir -p "$TMPDIR/sf_profiles" && cp tools/check_device_profiles.swift "$TMPDIR/sf_profiles/main.swift"
//   swiftc -o "$TMPDIR/sf_profiles/check" ios/SoundField/Core/DeviceProfiles.swift "$TMPDIR/sf_profiles/main.swift"
//   "$TMPDIR/sf_profiles/check"
// Путь к JSON можно передать первым аргументом; по умолчанию — profiles/devices.json.
// Вывод: «ALL PASSED» и код 0, либо список провалов и код 1.
import Foundation

// Якорь для Bundle(for:): у CLI-бинарника нет devices.json, загрузчик обязан вернуть fallback.
final class BundleAnchor {}

var passed = 0
var failures: [String] = []

func expect(_ condition: Bool, _ name: String) {
    if condition { passed += 1 } else { failures.append(name) }
}

// MARK: - Построитель JSON для негативных и граничных случаев

func section(_ type: String, _ f0: Double, _ q: Double, gain: Double? = nil) -> [String: Any] {
    var s: [String: Any] = ["type": type, "f0_hz": f0, "q": q]
    if let g = gain { s["gain_db"] = g }
    return s
}

func profileBody() -> [String: Any] {
    [
        "synthetic": false,
        "preamp_db": -2.0,
        "f_low_hz": 200.0,
        "sections": [
            section("highpass", 200, 0.7071),
            section("peak", 2500, 1.2, gain: -4),
            section("highshelf", 9000, 0.7071, gain: 2),
        ],
        "allpass_vs_partner": ["other_model": [["f0_hz": 1800.0, "q": 0.6]]],
    ]
}

func genericBody() -> [String: Any] {
    [
        "synthetic": false,
        "preamp_db": 0.0,
        "f_low_hz": 250.0,
        "sections": [section("highpass", 250, 0.5412)],
        "allpass_vs_partner": [String: Any](),
    ]
}

func modelBody(key: String = "test_model", match: [String] = ["Vendor/Model/dev"]) -> [String: Any] {
    var m = profileBody()
    m["key"] = key
    m["match"] = match
    return m
}

/// Модель с одним изменением относительно базовой.
func model(_ mutate: (inout [String: Any]) -> Void) -> [String: Any] {
    var m = modelBody()
    mutate(&m)
    return m
}

func tableData(models: [[String: Any]], classes: [String: Any]? = nil,
               version: Any = "0.1.0", schema: Any = 1) -> Data {
    let table: [String: Any] = [
        "profiles_version": version,
        "schema": schema,
        "classes": classes ?? ["generic_phone_speaker": genericBody()],
        "models": models,
    ]
    return try! JSONSerialization.data(withJSONObject: table)
}

func expectRejected(_ data: Data, _ name: String) {
    expect(ProfileTable.parse(data) == nil, "must reject: \(name)")
}

func expectAccepted(_ data: Data, _ name: String) {
    expect(ProfileTable.parse(data) != nil, "must accept: \(name)")
}

// MARK: - Реальный файл

let path = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "profiles/devices.json"
guard let realData = FileManager.default.contents(atPath: path) else {
    print("FAIL: cannot read \(path)")
    exit(1)
}
guard let table = ProfileTable.parse(realData) else {
    print("FAIL: \(path) rejected by ProfileTable.parse")
    exit(1)
}

// Парсинг
expect(table.profilesVersion == "0.1.0", "profiles_version parsed")
expect(table.schema == 1, "schema parsed")
expect(table.generic.key == ProfileTable.genericKey, "generic key")
expect(table.generic == ProfileTable.builtinGeneric, "parsed generic equals builtin fallback generic")
expect(table.models.count == 2, "two models in devices.json")
let synth = table.models.first(where: { $0.key == "synthetic_test_a" }) ?? table.generic
expect(synth.key == "synthetic_test_a", "model key")
let synthB = table.models.first(where: { $0.key == "synthetic_test_b" }) ?? table.generic
expect(synthB.key == "synthetic_test_b" && synthB.sections.count == 2 && synthB.preampDb == -2.0 && synthB.fLowHz == 220.0,
       "second synthetic model fields")
expect(table.lookup(hwMachine: "TEST/SYNTH_B/synth_b", includeSynthetic: true).key == "synthetic_test_b",
       "second synthetic model found by lookup")
expect(synth.synthetic, "model synthetic flag")
expect(synth.preampDb == -3.0, "model preamp")
expect(synth.fLowHz == 180.0, "model f_low")
expect(synth.sections.count == 3, "model section count")
expect(synth.sections.count == 3 && synth.sections[1] == Section(type: .peak, f0Hz: 2500, q: 1.2, gainDb: -4),
       "peak section fields")
expect(synth.sections.count == 3 && synth.sections[2].gainDb == 2.0, "highshelf gain")

// Allpass
expect(table.allpass(for: synth, partnerKey: "synthetic_test_b") == [AllpassSection(f0Hz: 1800, q: 0.6)],
       "allpass for declared partner")
expect(table.allpass(for: synth, partnerKey: "unknown_partner").isEmpty, "allpass for unknown partner is empty")
expect(table.allpass(for: table.generic, partnerKey: "synthetic_test_b").isEmpty, "generic has no allpass")

// Поиск
expect(table.lookup(hwMachine: "TEST/SYNTH_A/synth_a", includeSynthetic: true).key == "synthetic_test_a",
       "exact match with synthetic included")
expect(table.lookup(hwMachine: "test/synth_a/SYNTH_A", includeSynthetic: true).key == "synthetic_test_a",
       "match is case-insensitive")
expect(table.lookup(hwMachine: "TEST/SYNTH_A/synth_a") == table.generic,
       "synthetic ignored by default (release build)")
expect(table.lookup(hwMachine: "TEST/SYNTH_A/synth_a", includeSynthetic: false) == table.generic,
       "synthetic ignored when includeSynthetic is false")
expect(table.lookup(hwMachine: "TEST/SYNTH_A") == table.generic, "partial match is not a match")
expect(table.lookup(hwMachine: "iPhone15,2", includeSynthetic: true) == table.generic, "unknown model gives generic")
expect(table.lookup(hwMachine: "") == table.generic, "empty hw gives generic")

// Generic-запас
expect(ProfileTable.fallback.lookup(hwMachine: "TEST/SYNTH_A/synth_a", includeSynthetic: true) == ProfileTable.builtinGeneric,
       "fallback table returns builtin generic")
expect(ProfileTable.fallback.generic.sections.count == 1, "builtin generic has one highpass")
expect(DeviceProfileLoader.loadBundled(Bundle(for: BundleAnchor.self)) == ProfileTable.fallback,
       "loader without bundled resource returns fallback")
let hw = DeviceProfileLoader.hwMachine()
expect(!hw.isEmpty, "uname hw.machine is non-empty")
print("hw.machine: \(hw)")

// Битые данные: любое нарушение → nil
let base = modelBody()
expect(ProfileTable.parse(Data()) == nil, "empty data rejected")
expect(ProfileTable.parse(Data("not json".utf8)) == nil, "invalid JSON rejected")
expect(ProfileTable.parse(Data("[]".utf8)) == nil, "top-level array rejected")
expectAccepted(tableData(models: [base]), "baseline synthetic table accepted")

expectRejected(tableData(models: [base], schema: 2), "schema 2")
expectRejected(tableData(models: [base], schema: "1"), "schema as string")
expectRejected(tableData(models: [base], version: "1.0"), "profiles_version two parts")
expectRejected(tableData(models: [base], version: "0.1.x"), "profiles_version with letter")
expectRejected(tableData(models: [base], classes: ["other": genericBody()]), "missing generic_phone_speaker")
expectRejected(tableData(models: [base], classes: ["generic_phone_speaker": { var g = genericBody(); g["synthetic"] = true; return g }()]),
               "generic marked synthetic")
expectRejected(tableData(models: [base], classes: ["generic_phone_speaker": { var g = genericBody(); g["preamp_db"] = 1.0; return g }()]),
               "generic preamp above zero")

expectRejected(tableData(models: [model { $0["key"] = "Bad" }]), "key uppercase")
expectRejected(tableData(models: [model { $0["key"] = "" }]), "key empty")
expectRejected(tableData(models: [model { $0["key"] = "a-b" }]), "key with dash")
expectRejected(tableData(models: [modelBody(), modelBody()]), "duplicate key")
expectRejected(tableData(models: [model { $0["match"] = [String]() }]), "empty match array")
expectRejected(tableData(models: [model { $0["match"] = [""] }]), "empty match string")
expectRejected(tableData(models: [model { $0["match"] = [1] }]), "non-string match element")
expectRejected(tableData(models: [model { $0["synthetic"] = "yes" }]), "synthetic not bool")
expectRejected(tableData(models: [model { $0["preamp_db"] = 1.0 }]), "preamp above zero")
expectRejected(tableData(models: [model { $0["preamp_db"] = nil }]), "preamp missing")
expectAccepted(tableData(models: [model { $0["preamp_db"] = 0.0 }]), "preamp exactly zero")

expectRejected(tableData(models: [model { $0["f_low_hz"] = 59.9 }]), "f_low below 60")
expectRejected(tableData(models: [model { $0["f_low_hz"] = 600.1 }]), "f_low above 600")
expectAccepted(tableData(models: [model { $0["f_low_hz"] = 60.0 }]), "f_low at 60")
expectAccepted(tableData(models: [model { $0["f_low_hz"] = 600.0 }]), "f_low at 600")

let sec17 = (0..<17).map { _ in section("peak", 1000, 1, gain: 0) }
let sec16 = Array(sec17.prefix(16))
expectRejected(tableData(models: [model { $0["sections"] = sec17 }]), "17 sections")
expectAccepted(tableData(models: [model { $0["sections"] = sec16 }]), "16 sections")

expectRejected(tableData(models: [model { $0["sections"] = [section("notch", 1000, 1, gain: 0)] }]), "unknown section type")
expectRejected(tableData(models: [model { $0["sections"] = [["f0_hz": 1000.0, "q": 1.0]] }]), "section type missing")
expectRejected(tableData(models: [model { $0["sections"] = [section("highpass", 19.9, 1)] }]), "f0 below 20")
expectAccepted(tableData(models: [model { $0["sections"] = [section("highpass", 20, 1)] }]), "f0 at 20")
expectRejected(tableData(models: [model { $0["sections"] = [section("highpass", 20000.1, 1)] }]), "f0 above 20000")
expectAccepted(tableData(models: [model { $0["sections"] = [section("highpass", 20000, 1)] }]), "f0 at 20000")
expectRejected(tableData(models: [model { $0["sections"] = [section("highpass", 1000, 0.09)] }]), "q below 0.1")
expectAccepted(tableData(models: [model { $0["sections"] = [section("highpass", 1000, 0.1)] }]), "q at 0.1")
expectRejected(tableData(models: [model { $0["sections"] = [section("highpass", 1000, 6.01)] }]), "q above 6")
expectAccepted(tableData(models: [model { $0["sections"] = [section("highpass", 1000, 6)] }]), "q at 6")

expectRejected(tableData(models: [model { $0["sections"] = [section("peak", 1000, 1)] }]), "peak without gain")
expectRejected(tableData(models: [model { $0["sections"] = [section("lowshelf", 300, 1)] }]), "lowshelf without gain")
expectRejected(tableData(models: [model { $0["sections"] = [section("peak", 1000, 1, gain: -15.1)] }]), "gain below -15")
expectAccepted(tableData(models: [model { $0["sections"] = [section("peak", 1000, 1, gain: -15)] }]), "gain at -15")
expectRejected(tableData(models: [model { $0["sections"] = [section("peak", 1000, 1, gain: 6.1)] }]), "gain above 6")
expectAccepted(tableData(models: [model { $0["sections"] = [section("peak", 1000, 1, gain: 6)] }]), "gain at 6")
expectRejected(tableData(models: [model { $0["sections"] = [section("highshelf", 9000, 1, gain: 4)] }]),
               "gain above +3 above 8 kHz")
expectAccepted(tableData(models: [model { $0["sections"] = [section("highshelf", 9000, 1, gain: 3)] }]),
               "gain +3 above 8 kHz")
expectAccepted(tableData(models: [model { $0["sections"] = [section("peak", 8000, 1, gain: 6)] }]),
               "gain +6 exactly at 8 kHz allowed")
expectRejected(tableData(models: [model { $0["sections"] = [section("peak", 8000.5, 1, gain: 4)] }]),
               "gain +4 just above 8 kHz")

expectRejected(tableData(models: [model { $0["allpass_vs_partner"] = ["other": Array(repeating: ["f0_hz": 1000.0, "q": 1.0], count: 5)] }]),
               "5 allpass sections for one partner")
expectAccepted(tableData(models: [model { $0["allpass_vs_partner"] = ["other": Array(repeating: ["f0_hz": 1000.0, "q": 1.0], count: 4)] }]),
               "4 allpass sections for one partner")
expectRejected(tableData(models: [model { $0["allpass_vs_partner"] = ["other": [["f0_hz": 1000.0, "q": 7.0]]] }]),
               "allpass q above 6")
expectRejected(tableData(models: [model { $0["allpass_vs_partner"] = ["other": [["f0_hz": 10.0, "q": 1.0]]] }]),
               "allpass f0 below 20")
expectRejected(tableData(models: [model { $0["allpass_vs_partner"] = ["Bad Key": [["f0_hz": 1000.0, "q": 1.0]]] }]),
               "allpass partner key format")

// Граничная модель: все крайние значения в допустимых пределах
let boundary = model {
    $0["f_low_hz"] = 600.0
    $0["preamp_db"] = 0.0
    $0["sections"] = [
        section("highpass", 20, 0.1),
        section("peak", 8000, 6, gain: 6),
        section("peak", 20000, 6, gain: 3),
        section("lowshelf", 20000, 0.1, gain: -15),
    ]
    $0["allpass_vs_partner"] = ["other": Array(repeating: ["f0_hz": 20000.0, "q": 6.0], count: 4)]
}
expectAccepted(tableData(models: [boundary]), "boundary values accepted")
if let parsed = ProfileTable.parse(tableData(models: [boundary])) {
    expect(parsed.models.first?.sections.count == 4, "boundary sections kept")
    expect(parsed.models.first?.allpassVsPartner["other"]?.count == 4, "four allpass sections kept")
}

// Synthetic: в релизном поиске скрыт, с includeSynthetic виден; обычная модель видна всегда
let mixed = tableData(models: [
    model { $0["key"] = "real_model"; $0["match"] = ["Real/Phone/dev"]; $0["synthetic"] = false },
    model { $0["key"] = "fake_model"; $0["match"] = ["Fake/Phone/dev"]; $0["synthetic"] = true },
])
if let parsed = ProfileTable.parse(mixed) {
    expect(parsed.lookup(hwMachine: "Real/Phone/dev").key == "real_model", "real model found in release search")
    expect(parsed.lookup(hwMachine: "Fake/Phone/dev").key == ProfileTable.genericKey, "synthetic hidden in release search")
    expect(parsed.lookup(hwMachine: "fake/phone/DEV", includeSynthetic: true).key == "fake_model",
           "synthetic found in debug search, case-insensitive")
} else {
    expect(false, "mixed table with synthetic and real models accepted")
}

// Итог
if failures.isEmpty {
    print("passed checks: \(passed)")
    print("ALL PASSED")
    exit(0)
} else {
    for name in failures { print("FAIL: \(name)") }
    print("passed checks: \(passed), failed: \(failures.count)")
    exit(1)
}
