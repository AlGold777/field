import SwiftUI

struct CalibrationView: View {
    let ui: UiState
    let engine: Engine
    let back: () -> Void
    @State private var trim = 0.0

    var body: some View {
        let c = ui.calib
        let serverName = ui.role == .server ? "этом телефоне" : "«\(ui.peerName)»"
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackBar(title: "Калибровка задержки", back: back)
                Subtitle(text: "Разные модели телефонов выводят звук с разной задержкой. Калибровка измеряет её и выравнивает каналы.")
                    .padding(.bottom, 16)

                SFCard {
                    H3(text: "Автоматически · микрофон")
                    Text("1. Положите телефоны рядом (10–20 см), динамиками вверх.\n2. Громкость — средняя или выше.\n3. Нажмите кнопку и ~8 секунд не шумите.\nЗапись ведётся микрофоном на \(serverName) (Server).")
                        .font(.system(size: 14)).foregroundColor(SFColor.text).lineSpacing(4).padding(.bottom, 8)
                    if c.running {
                        ProgressView().tint(SFColor.accent1).frame(maxWidth: .infinity)
                        Text(c.stage).font(.system(size: 14)).foregroundColor(SFColor.warn)
                    } else {
                        PrimaryButton(title: "🎯  Запустить автокалибровку") { engine.calibrate() }
                        SecondaryButton(title: "📏  Только измерить рассогласование") { engine.measure() }
                    }
                    if c.needsMicPermission && ui.role == .server {
                        SecondaryButton(title: "🎤  Разрешить доступ к микрофону") {
                            MicRecorder.requestPermission { ok in if ok { engine.micPermissionGranted() } }
                        }
                    }
                    if !c.resultText.isEmpty && !c.running {
                        Text(c.resultText).font(.system(size: 14)).foregroundColor(c.resultOk ? SFColor.good : SFColor.warn)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                SFCard {
                    H3(text: "Вручную · на слух")
                    SliderRow(label: "Сдвиг Point", valueText: trimText, value: $trim, color: SFColor.accent1,
                              range: -SF.maxTrimMs...SF.maxTrimMs, step: 0.5) { engine.setTrimMs(trim) }
                    HStack(spacing: 6) {
                        ForEach([-5.0, -1.0, -0.5, 0.5, 1.0, 5.0], id: \.self) { d in
                            Button {
                                trim = min(SF.maxTrimMs, max(-SF.maxTrimMs, trim + d)); engine.setTrimMs(trim)
                            } label: {
                                Text(d > 0 ? "+\(fmt(d))" : fmt(d)).font(.system(size: 12, weight: .semibold)).foregroundColor(SFColor.text)
                                    .frame(maxWidth: .infinity).padding(.vertical, 8)
                                    .background(RoundedRectangle(cornerRadius: 10).stroke(SFColor.secondaryBorder))
                            }
                        }
                    }
                    Toggle(isOn: Binding(get: { ui.mode == .metronome }, set: { engine.metronome($0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Метроном").font(.system(size: 15, weight: .semibold)).foregroundColor(SFColor.text)
                            Subtitle(text: "Щелчки одновременно в обоих каналах. Двигайте сдвиг, пока не услышите один щелчок.")
                        }
                    }
                    .tint(SFColor.accent1).padding(.top, 8)
                    GhostButton(title: "Сбросить сдвиг в 0", color: SFColor.dim) { trim = 0; engine.setTrimMs(0) }
                }

                SFCard {
                    H3(text: "Проверка каналов")
                    Subtitle(text: "Три сигнала слева, затем три справа — убедитесь, что телефоны стоят на своих местах.")
                    SecondaryButton(title: "🔈  Проверить L / R") { engine.channelTest() }.padding(.top, 8)
                }

                SFCard {
                    H3(text: "Текущие значения")
                    DiagRow(label: "Выходная задержка Server", value: ms(ui.serverDiag.latencyMs, ui.serverDiag.valid))
                    DiagRow(label: "Выходная задержка Point", value: ms(ui.pointDiag.latencyMs, ui.pointDiag.valid))
                    DiagRow(label: "Сдвиг Point (калибровка)", value: String(format: "%+.1f мс", ui.trimMs))
                    DiagRow(label: "Акустически измерено L/R",
                            value: ui.calib.lastMeasuredMs.map { String(format: "%+.1f мс", $0) } ?? "—",
                            color: ui.calib.lastMeasuredMs.map { abs($0) < 2 ? SFColor.good : SFColor.warn } ?? SFColor.text)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 24)
        }
        .onAppear { trim = ui.trimMs }
        .onChange(of: ui.trimMs) { trim = $0 }
    }

    private var trimText: String {
        String(format: "%+.1f мс", trim) + (trim > 0 ? " (позже)" : trim < 0 ? " (раньше)" : "")
    }
    private func fmt(_ d: Double) -> String { d == d.rounded() ? String(Int(d)) : String(d) }
    private func ms(_ v: Double, _ valid: Bool) -> String { valid ? String(format: "%.1f мс", v) : "—" }
}
