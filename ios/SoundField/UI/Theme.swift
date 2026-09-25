import SwiftUI

/// Палитра прототипа «Хороший минимализм».
enum SFColor {
    static let bgTop = Color(hex: 0x16213E)
    static let bgBottom = Color(hex: 0x0F3460)
    static let accent1 = Color(hex: 0x667EEA)
    static let accent2 = Color(hex: 0x764BA2)
    static let text = Color(hex: 0xE2E8F0)
    static let dim = Color(hex: 0x94A3B8)
    static let card = Color.white.opacity(0.05)
    static let cardBorder = Color.white.opacity(0.10)
    static let secondary = Color.white.opacity(0.10)
    static let secondaryBorder = Color.white.opacity(0.20)
    static let left = Color(hex: 0x3B82F6)
    static let leftText = Color(hex: 0x60A5FA)
    static let right = Color(hex: 0xEF4444)
    static let rightText = Color(hex: 0xF87171)
    static let good = Color(hex: 0x4ADE80)
    static let warn = Color(hex: 0xFBBF24)
    static let bad = Color(hex: 0xEF4444)
    static let master = Color(hex: 0xC084FC)

    static let background = LinearGradient(colors: [bgTop, bgBottom], startPoint: .top, endPoint: .bottom)
    static let accent = LinearGradient(colors: [accent1, accent2], startPoint: .topLeading, endPoint: .bottomTrailing)

    static func channel(_ ch: Int) -> Color { ch == Ch.L ? left : right }
    static func channelText(_ ch: Int) -> Color { ch == Ch.L ? leftText : rightText }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

func fmtTime(_ ms: Int64) -> String {
    let s = max(0, ms / 1000)
    return String(format: "%d:%02d", s / 60, s % 60)
}
