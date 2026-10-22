// Загрузчик профилей устройств (A2). Контракт: docs/a2-profile-spec.md. Данные: profiles/devices.json (ресурс приложения).
// Только Foundation, без UIKit. Любое нарушение правил спецификации → ProfileTable.parse возвращает nil;
// вызывающий код берёт ProfileTable.fallback (только generic_phone_speaker).
// Проверка: tools/check_device_profiles.swift (см. шапку файла).
import Foundation

enum SectionType: String, Equatable {
    case highpass, lowshelf, highshelf, peak
}

/// Секция корректора: биквад по RBJ Audio EQ Cookbook, min-phase, TDF-II.
struct Section: Equatable {
    let type: SectionType
    let f0Hz: Double
    let q: Double
    /// Есть у peak/lowshelf/highshelf; у highpass всегда nil.
    let gainDb: Double?
}

/// Секция allpass 2-го порядка (фазовая коррекция относительно партнёрской модели).
struct AllpassSection: Equatable {
    let f0Hz: Double
    let q: Double
}

struct Profile: Equatable {
    let key: String
    /// Строки MANUFACTURER/MODEL/DEVICE (Android) или hw.machine (iOS). Пусто у generic.
    let match: [String]
    /// true — тестовый профиль: в релизной сборке не участвует в поиске.
    let synthetic: Bool
    let preampDb: Double
    let fLowHz: Double
    let sections: [Section]
    let allpassVsPartner: [String: [AllpassSection]]
}

struct ProfileTable: Equatable {
    static let genericKey = "generic_phone_speaker"

    /// Запасной профиль, зашитый в код: совпадает с classes.generic_phone_speaker из devices.json.
    static let builtinGeneric = Profile(
        key: genericKey,
        match: [],
        synthetic: false,
        preampDb: 0,
        fLowHz: 250,
        sections: [Section(type: .highpass, f0Hz: 250, q: 0.5412, gainDb: nil)],
        allpassVsPartner: [:]
    )

    /// Таблица, когда devices.json отсутствует или не прошёл проверку.
    static let fallback = ProfileTable(profilesVersion: "builtin", schema: 1, generic: builtinGeneric, models: [])

    let profilesVersion: String
    let schema: Int
    let generic: Profile
    let models: [Profile]

    /// Разбор и полная проверка devices.json. nil — при любом нарушении схемы или диапазонов.
    static func parse(_ data: Data) -> ProfileTable? {
        guard let raw = try? JSONDecoder().decode(RawTable.self, from: data) else { return nil }
        return try? raw.validated()
    }

    /// Поиск по hw.machine (iOS) или строке MANUFACTURER/MODEL/DEVICE; без учёта регистра.
    /// Synthetic-модели видны только при includeSynthetic = true (отладка/тесты). Иначе — generic.
    func lookup(hwMachine: String, includeSynthetic: Bool = false) -> Profile {
        let needle = hwMachine.lowercased()
        for profile in models where includeSynthetic || !profile.synthetic {
            if profile.match.contains(where: { $0.lowercased() == needle }) {
                return profile
            }
        }
        return generic
    }

    /// Секции allpass для пары профиля с партнёрской моделью; пусто, если пары нет.
    func allpass(for profile: Profile, partnerKey: String) -> [AllpassSection] {
        profile.allpassVsPartner[partnerKey] ?? []
    }
}

enum DeviceProfileLoader {
    static let resourceName = "devices"

    /// Читает devices.json из бандла приложения. Нет файла или нарушены правила → ProfileTable.fallback.
    static func loadBundled(_ bundle: Bundle = .main) -> ProfileTable {
        guard let url = bundle.url(forResource: resourceName, withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let table = ProfileTable.parse(data) else {
            return .fallback
        }
        return table
    }

    /// hw.machine через uname(), например «iPhone15,2». Пустая строка, если uname не сработал.
    static func hwMachine() -> String {
        var info = utsname()
        guard uname(&info) == 0 else { return "" }
        return withUnsafeBytes(of: info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
    }
}

// MARK: - Разбор JSON (Codable) и проверка правил спецификации

private struct RawSection: Decodable {
    let type: String
    let f0Hz: Double
    let q: Double
    let gainDb: Double?

    enum CodingKeys: String, CodingKey {
        case type, q
        case f0Hz = "f0_hz"
        case gainDb = "gain_db"
    }
}

private struct RawAllpass: Decodable {
    let f0Hz: Double
    let q: Double

    enum CodingKeys: String, CodingKey {
        case q
        case f0Hz = "f0_hz"
    }
}

private struct RawProfile: Decodable {
    let synthetic: Bool?
    let preampDb: Double
    let fLowHz: Double
    let sections: [RawSection]
    let allpassVsPartner: [String: [RawAllpass]]?

    enum CodingKeys: String, CodingKey {
        case synthetic, sections
        case preampDb = "preamp_db"
        case fLowHz = "f_low_hz"
        case allpassVsPartner = "allpass_vs_partner"
    }
}

/// Элемент models[]: поля key/match плюс поля профиля в том же объекте.
private struct RawModel: Decodable {
    let key: String
    let match: [String]
    let body: RawProfile

    enum CodingKeys: String, CodingKey {
        case key, match
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        key = try container.decode(String.self, forKey: .key)
        match = try container.decode([String].self, forKey: .match)
        body = try RawProfile(from: decoder)
    }
}

private struct RawTable: Decodable {
    let profilesVersion: String
    let schema: Int
    let classes: [String: RawProfile]
    let models: [RawModel]

    enum CodingKeys: String, CodingKey {
        case schema, classes, models
        case profilesVersion = "profiles_version"
    }
}

private struct SpecViolation: Error {}

private func require(_ condition: Bool) throws {
    if !condition { throw SpecViolation() }
}

private func inRange(_ value: Double, _ low: Double, _ high: Double) -> Bool {
    value >= low && value <= high
}

/// [a-z0-9_]+
private func isValidKey(_ text: String) -> Bool {
    !text.isEmpty && text.allSatisfy { ($0 >= "a" && $0 <= "z") || ($0 >= "0" && $0 <= "9") || $0 == "_" }
}

/// MAJOR.MINOR.PATCH, только цифры.
private func isVersion(_ text: String) -> Bool {
    let parts = text.split(separator: ".", omittingEmptySubsequences: false)
    return parts.count == 3 && parts.allSatisfy { part in
        !part.isEmpty && part.allSatisfy { $0 >= "0" && $0 <= "9" }
    }
}

private func makeSection(_ raw: RawSection) throws -> Section {
    guard let type = SectionType(rawValue: raw.type) else { throw SpecViolation() }
    try require(inRange(raw.f0Hz, 20, 20000))
    try require(inRange(raw.q, 0.1, 6))
    switch type {
    case .highpass:
        return Section(type: type, f0Hz: raw.f0Hz, q: raw.q, gainDb: nil)
    case .peak, .lowshelf, .highshelf:
        guard let gain = raw.gainDb else { throw SpecViolation() }
        try require(inRange(gain, -15, 6))
        if raw.f0Hz > 8000 {
            try require(gain <= 3)
        }
        return Section(type: type, f0Hz: raw.f0Hz, q: raw.q, gainDb: gain)
    }
}

private func makeProfile(key: String, match: [String], body: RawProfile) throws -> Profile {
    let synthetic = body.synthetic ?? false
    try require(body.preampDb <= 0)
    try require(inRange(body.fLowHz, 60, 600))
    try require(body.sections.count <= 16)
    let sections = try body.sections.map { try makeSection($0) }

    var allpass: [String: [AllpassSection]] = [:]
    for (partner, list) in body.allpassVsPartner ?? [:] {
        try require(isValidKey(partner))
        try require(list.count <= 4)
        var converted: [AllpassSection] = []
        for item in list {
            try require(inRange(item.f0Hz, 20, 20000))
            try require(inRange(item.q, 0.1, 6))
            converted.append(AllpassSection(f0Hz: item.f0Hz, q: item.q))
        }
        allpass[partner] = converted
    }

    return Profile(key: key, match: match, synthetic: synthetic, preampDb: body.preampDb,
                   fLowHz: body.fLowHz, sections: sections, allpassVsPartner: allpass)
}

private extension RawTable {
    func validated() throws -> ProfileTable {
        try require(isVersion(profilesVersion))
        try require(schema == 1)

        var generic: Profile?
        for (key, body) in classes {
            try require(isValidKey(key))
            let profile = try makeProfile(key: key, match: [], body: body)
            if key == ProfileTable.genericKey {
                try require(!profile.synthetic)
                generic = profile
            }
        }
        guard let genericProfile = generic else { throw SpecViolation() }

        var keys = Set<String>()
        var matches = Set<String>()
        var profiles: [Profile] = []
        for model in self.models {
            try require(isValidKey(model.key))
            try require(!keys.contains(model.key))
            keys.insert(model.key)
            try require(!model.match.isEmpty)
            for pattern in model.match {
                try require(!pattern.isEmpty)
                try require(matches.insert(pattern.lowercased()).inserted)
            }
            profiles.append(try makeProfile(key: model.key, match: model.match, body: model.body))
        }

        return ProfileTable(profilesVersion: profilesVersion, schema: schema,
                            generic: genericProfile, models: profiles)
    }
}
