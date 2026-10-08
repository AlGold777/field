import Foundation

/// Единственный источник закона пользовательского баланса.
enum BalanceLaw {
    static func gain(channel: Int, balance: Float) -> Float {
        let p = min(1, max(-1, 2 * min(1, max(0, balance)) - 1))
        let a = pow(max(0, 1 - abs(p)), 2)
        if p > 0 && channel == Ch.L { return a }
        if p < 0 && channel == Ch.R { return a }
        return 1
    }

    static func differenceDb(balance: Float) -> Float {
        let p = abs(2 * min(1, max(0, balance)) - 1)
        return p >= 1 ? .infinity : 40 * log10(1 - p)
    }
}

enum AudioGain {
    static func taper(_ value: Float) -> Float {
        let v = min(1, max(0, value))
        return v < 0.005 ? 0 : pow(10, Float(-50 * (1 - Double(v)) / 20))
    }

    static func migrateMaster(_ old: Float) -> Float {
        guard old > 0 else { return 0 }
        return Float(min(1, max(0, 1 + 20 * log10(Double(old)) / 50)))
    }

    static func fromDb(_ db: Float) -> Float { pow(10, min(0, max(-12, db)) / 20) }

    static func migrateChannelGains(serverGain: Float, pointGain: Float) -> (Float, Float) {
        func db(_ value: Float) -> Float { value <= 0 ? -12 : min(0, max(-12, 20 * log10(value))) }
        let s = db(serverGain), p = db(pointGain), peak = max(s, p)
        return (min(0, max(-12, s - peak)), min(0, max(-12, p - peak)))
    }
}


/// Compatibility with servers predating gainLaw 2: their balance uses a linear attenuation.
enum LegacyBalanceLaw {
    static func gain(channel: Int, balance: Float) -> Float {
        let b = min(1,max(0,balance))
        return channel == Ch.L ? min(1,2*(1-b)) : min(1,2*b)
    }
}
