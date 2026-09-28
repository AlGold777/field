import Foundation
import os

/// Общие константы. ОБЯЗАНЫ совпадать с Android (core/Constants.kt) — иначе смешанная пара не соберётся.
enum SF {
    static let protocolVersion: UInt8 = 1
    static let sampleRate = 48_000
    static let packetFrames = 480                  // 10 мс PCM в одном UDP-пакете
    static let leadNs: Int64 = 400_000_000         // Server отправляет аудио на 400 мс вперёд
    static let startDelayNs: Int64 = 300_000_000   // play/seek стартуют через 300 мс
    static let serviceType = "_soundfield._tcp"
    static let servicePrefix = "SF-"
    static let heartbeatSec = 1.0
    // 8 с без данных = разрыв. При выключенном экране Wi‑Fi экономит энергию и TCP может «замирать» на 1–3 с.
    static let linkTimeoutSec = 8.0
    static let maxTrimMs = 250.0
    static let hardResyncFrames = 960.0            // >20 мс — жёсткий прыжок
    static let slewThresholdFrames = 6.0           // >0.125 мс — плавная подстройка по 1 сэмплу
}

enum Role: String { case server, point
    var title: String { self == .server ? "Server" : "Point" }
}

/// Канал: 0 = левый, 1 = правый.
enum Ch {
    static let L = 0
    static let R = 1
    static func name(_ c: Int) -> String { c == L ? "L" : "R" }
    static func parse(_ s: String?) -> Int { s == "L" ? L : R }
    static func other(_ c: Int) -> Int { 1 - c }
}

enum SourceMode: String { case music, calib, metronome, lrtest, level = "level", center = "center", levelMeasure }

/// Монотонные часы в наносекундах (mach_absolute_time) — те же, что в AudioTimeStamp.mHostTime.
enum MonoClock {
    private static let tb: mach_timebase_info_data_t = {
        var t = mach_timebase_info_data_t(); mach_timebase_info(&t); return t
    }()
    static func nowNs() -> Int64 { ns(fromHost: mach_absolute_time()) }
    static func ns(fromHost h: UInt64) -> Int64 { Int64(h) * Int64(tb.numer) / Int64(tb.denom) }
    static func host(fromNs ns: Int64) -> UInt64 { UInt64(ns * Int64(tb.denom) / Int64(tb.numer)) }
}

/// Минимальная блокировка для обмена снимками с аудиопотоком.
final class Locked<T> {
    private var v: T
    private let l: UnsafeMutablePointer<os_unfair_lock>
    init(_ v: T) {
        self.v = v
        l = .allocate(capacity: 1)
        l.initialize(to: os_unfair_lock())
    }
    deinit { l.deinitialize(count: 1); l.deallocate() }
    var value: T {
        get { os_unfair_lock_lock(l); defer { os_unfair_lock_unlock(l) }; return v }
        set { os_unfair_lock_lock(l); v = newValue; os_unfair_lock_unlock(l) }
    }
}
