import SwiftUI

struct H2: View {
    let text: String
    var body: some View { Text(text).font(.system(size: 22, weight: .semibold)).foregroundColor(SFColor.text) }
}

struct H3: View {
    let text: String
    var body: some View {
        Text(text.uppercased()).font(.system(size: 13, weight: .semibold)).kerning(1).foregroundColor(SFColor.dim)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.bottom, 8)
    }
}

struct Subtitle: View {
    let text: String
    var align: TextAlignment = .leading
    @ScaledMetric(relativeTo: .subheadline) var textSize: CGFloat = 14
    var body: some View {
        Text(text).font(.system(size: textSize)).foregroundColor(SFColor.dim).multilineTextAlignment(align)
            .lineSpacing(3).fixedSize(horizontal: false, vertical: true)
    }
}

struct SFCard<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content() }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16).fill(SFColor.card))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(SFColor.cardBorder, lineWidth: 1))
            .padding(.bottom, 16)
    }
}

struct PrimaryButton: View {
    let title: String
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 16, weight: .semibold)).foregroundColor(.white)
                .frame(maxWidth: .infinity).padding(.vertical, 16)
                .background(RoundedRectangle(cornerRadius: 14).fill(SFColor.accent).opacity(enabled ? 1 : 0.4))
                .shadow(color: SFColor.accent1.opacity(0.4), radius: 8, y: 4)
        }
        .disabled(!enabled).buttonStyle(PressStyle()).padding(.bottom, 10)
    }
}

struct SecondaryButton: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                .frame(maxWidth: .infinity).padding(.vertical, 15)
                .background(RoundedRectangle(cornerRadius: 14).fill(SFColor.secondary))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(SFColor.secondaryBorder, lineWidth: 1))
        }
        .buttonStyle(PressStyle()).padding(.bottom, 10)
    }
}

struct PlayerActionButton: View {
    let title: String
    let action: () -> Void
    @ScaledMetric(relativeTo: .footnote) var labelSize: CGFloat = 13
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: labelSize, weight: .semibold)).foregroundColor(SFColor.text)
                .lineLimit(1).minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity).frame(height: 48)
                .background(RoundedRectangle(cornerRadius: 14).fill(SFColor.secondary))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(SFColor.secondaryBorder, lineWidth: 1))
        }
        .buttonStyle(PressStyle())
    }
}

struct TelemetryGlyph: View {
    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            Path { path in
                path.move(to: CGPoint(x: size.width * 0.08, y: size.height * 0.62))
                path.addLine(to: CGPoint(x: size.width * 0.30, y: size.height * 0.62))
                path.addLine(to: CGPoint(x: size.width * 0.44, y: size.height * 0.22))
                path.addLine(to: CGPoint(x: size.width * 0.60, y: size.height * 0.78))
                path.addLine(to: CGPoint(x: size.width * 0.73, y: size.height * 0.45))
                path.addLine(to: CGPoint(x: size.width * 0.92, y: size.height * 0.45))
            }
            .stroke(SFColor.accent1, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
        .frame(width: 23, height: 23)
    }
}

struct GhostButton: View {
    let title: String
    var color: Color = SFColor.accent1
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundColor(color)
                .frame(maxWidth: .infinity).padding(.vertical, 12)
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

struct DangerButton: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundColor(SFColor.bad)
                .frame(maxWidth: .infinity).padding(.vertical, 15)
                .background(RoundedRectangle(cornerRadius: 14).fill(SFColor.bad.opacity(0.2)))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(SFColor.bad.opacity(0.3), lineWidth: 1))
        }
        .buttonStyle(PressStyle()).padding(.bottom, 10)
    }
}

struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1).animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct StatusPill: View {
    let text: String
    let color: Color
    @State private var on = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 8, height: 8).opacity(on ? 1 : 0.4)
            Text(text).font(.system(size: 13, weight: .semibold)).foregroundColor(color)
                .lineLimit(1).truncationMode(.tail).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .padding(.horizontal, 14).padding(.vertical, 7)
        .background(Capsule().fill(color.opacity(0.15)))
        .overlay(Capsule().stroke(color.opacity(0.3), lineWidth: 1))
        .onAppear { withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.9).repeatForever()) { on = true } }
    }
}

struct Badge: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).font(.system(size: 11, weight: .semibold)).foregroundColor(color)
            .padding(.horizontal, 10).padding(.vertical, 4).background(Capsule().fill(color.opacity(0.18)))
    }
}

struct SpeakerCircle: View {
    let label: String
    let ch: Int
    let playing: Bool
    let caption: String
    let sub: String
    let highlight: Bool
    let action: () -> Void
    @State private var pulse = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        VStack(spacing: 6) {
            Button(action: action) {
                Text(label).font(.system(size: 26, weight: .bold)).foregroundColor(SFColor.channelText(ch))
                    .frame(width: 84, height: 84)
                    .background(Circle().fill(SFColor.channel(ch).opacity(0.2)))
                    .overlay(Circle().stroke(SFColor.channel(ch).opacity(playing && !pulse ? 0.55 : 1),
                                             lineWidth: highlight ? 3 : 2))
            }
            .onAppear { updatePulse() }
            .onChange(of: playing) { _ in updatePulse() }
            Text(caption).font(.system(size: 13, weight: .semibold)).foregroundColor(SFColor.text).lineLimit(1)
            Text(sub.isEmpty ? " " : sub).font(.system(size: 11)).foregroundColor(sub.isEmpty ? .clear : SFColor.dim).lineLimit(1)
        }
        .frame(width: 130)
    }

    /// Пульсация только во время воспроизведения и без «Уменьшения движения».
    private func updatePulse() {
        guard playing && !reduceMotion else {
            withAnimation(.easeOut(duration: 0.2)) { pulse = false }
            return
        }
        withAnimation(.easeInOut(duration: 0.52).repeatForever()) { pulse = true }
    }
}

struct DeviceRow: View {
    let icon: String
    let name: String
    let detail: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Text(icon).font(.system(size: 20)).frame(width: 48, height: 48)
                    .background(RoundedRectangle(cornerRadius: 12).fill(SFColor.accent))
                VStack(alignment: .leading, spacing: 4) {
                    Text(name).font(.system(size: 15, weight: .semibold)).foregroundColor(SFColor.text).lineLimit(1)
                    Text(detail).font(.system(size: 12)).foregroundColor(SFColor.dim)
                }
                Spacer()
                Text("→").foregroundColor(SFColor.dim)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 14).fill(SFColor.card))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(SFColor.cardBorder, lineWidth: 1))
        }
        .buttonStyle(PressStyle()).padding(.bottom, 10)
    }
}

struct SliderRow: View {
    let label: String
    let valueText: String
    @Binding var value: Double
    let color: Color
    var range: ClosedRange<Double> = 0...1
    var step: Double? = nil
    var enabled = true
    var onEditingChanged: (Bool) -> Void = { _ in }
    var onEditingEnded: () -> Void = {}
    var body: some View {
        VStack(spacing: 2) {
            HStack {
                Text(label).font(.system(size: 14)).foregroundColor(SFColor.dim)
                Spacer()
                Text(valueText).font(.system(size: 14, weight: .semibold)).foregroundColor(color)
            }
            if let step {
                Slider(value: $value, in: range, step: step, onEditingChanged: { onEditingChanged($0); if !$0 { onEditingEnded() } })
                    .tint(color).disabled(!enabled)
            } else {
                Slider(value: $value, in: range, onEditingChanged: { onEditingChanged($0); if !$0 { onEditingEnded() } })
                    .tint(color).disabled(!enabled)
            }
        }
        .padding(.bottom, 6)
    }
}

struct DiagRow: View {
    let label: String
    let value: String
    var color: Color = SFColor.text
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(label).font(.system(size: 14)).foregroundColor(SFColor.dim)
                Spacer()
                Text(value).font(.system(size: 14, weight: .semibold)).foregroundColor(color)
            }
            .padding(.vertical, 7)
            Rectangle().fill(Color.white.opacity(0.05)).frame(height: 1)
        }
    }
}

struct SearchingDots: View {
    @State private var on = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        HStack(spacing: 10) {
            ForEach(0..<3, id: \.self) { i in
                Circle().fill(SFColor.accent1).frame(width: 10, height: 10).opacity(on ? 1 : 0.2)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.6).repeatForever().delay(Double(i) * 0.2), value: on)
            }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 14)
        .onAppear { on = true }
    }
}

struct BackBar: View {
    let title: String
    let back: () -> Void
    var body: some View {
        HStack(spacing: 14) {
            Button(action: back) {
                Text("←").font(.system(size: 20)).foregroundColor(.white).frame(width: 40, height: 40)
                    .background(RoundedRectangle(cornerRadius: 12).fill(SFColor.secondary))
            }
            .accessibilityLabel("Назад")
            H2(text: title)
            Spacer()
        }
        .padding(.bottom, 12)
    }
}
