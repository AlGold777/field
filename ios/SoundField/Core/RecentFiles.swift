import Foundation

/// Недавно добавленный или открытый файл. Путь хранится относительно выбранной папки с музыкой:
/// абсолютный путь меняется при обновлении приложения, закладка папки — нет.
struct RecentFile: Codable, Equatable {
    let path: String
    let title: String
    let artist: String
    let usedAt: Double
}

/// Последние файлы (новые первыми, без повторов, не более `limit`). «Добавил» и «открыл» поднимают файл наверх.
struct RecentFilesStore {
    static let key = "sf.recentFiles"
    static let limit = 40
    private let defaults: UserDefaults
    private let now: () -> Double

    init(defaults: UserDefaults = .standard, now: @escaping () -> Double = { Date().timeIntervalSince1970 }) {
        self.defaults = defaults; self.now = now
    }

    func load() -> [RecentFile] {
        guard let data = defaults.data(forKey: Self.key), let list = try? JSONDecoder().decode([RecentFile].self, from: data) else { return [] }
        return list.sorted { $0.usedAt > $1.usedAt }
    }

    /// Первый элемент остаётся самым новым. Пустое название не затирает известное.
    func touch(_ items: [(path: String, title: String, artist: String)]) {
        guard !items.isEmpty else { return }
        let old = load()
        let known = Dictionary(old.map { ($0.path, $0) }, uniquingKeysWith: { a, _ in a })
        var seen = Set<String>()
        let t = now()
        var fresh: [RecentFile] = []
        for item in items where seen.insert(item.path).inserted {
            let prev = known[item.path]
            fresh.append(RecentFile(path: item.path,
                                    title: item.title.isEmpty ? (prev?.title ?? "") : item.title,
                                    artist: item.artist.isEmpty ? (prev?.artist ?? "") : item.artist,
                                    usedAt: t - Double(fresh.count) * 0.001))
        }
        save(Array((fresh + old.filter { !seen.contains($0.path) }).prefix(Self.limit)))
    }

    func remove(_ paths: Set<String>) {
        let old = load()
        let kept = old.filter { !paths.contains($0.path) }
        if kept.count != old.count { save(kept) }
    }

    private func save(_ list: [RecentFile]) {
        if let data = try? JSONEncoder().encode(list) { defaults.set(data, forKey: Self.key) }
    }

    /// Путь файла относительно папки `root`; nil, если файл лежит не внутри неё.
    static func relativePath(of file: URL, in root: URL) -> String? {
        let r = root.standardizedFileURL.path, f = file.standardizedFileURL.path
        guard f.hasPrefix(r + "/") else { return nil }
        return String(f.dropFirst(r.count + 1))
    }
}
