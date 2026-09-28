import SwiftUI

struct CalibrationView: View {
    let ui: UiState
    let engine: Engine
    let back: () -> Void
    @State private var trim = 0.0
    @State private var levelOffset = 0.0
    @State private var levelDragging = false

    var body: some View {
        let c = ui.calib
        let serverCh = Ch.other(ui.pointChannel)
        let levelReady = ui.delayCalib.state != "none" && ui.gainLaw >= 2 && !ui.calib.running
        let gateText = ui.delayCalib.state == "none"
            ? "Сначала выполните калибровку задержки: пока телефоны звучат с разным запаздыванием, звук тянет к одному из них, и уровнем это не исправить"
            : "Обновите второй телефон для калибровки уровня"
        let offset = serverCh == Ch.L ? Double(ui.levelTrimServerDb - ui.levelTrimPointDb)
            : Double(ui.levelTrimPointDb - ui.levelTrimServerDb)

        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackBar(title: "Калибровка") {
                    if ui.mode == .level { engine.stopLevelMatch(commit: false) }
                    back()
                }
                Subtitle(text: "Выполняйте шаги по порядку: задержка → уровень → проверка центра. Ошибка задержки смещает образ сильнее, чем небольшая разница громкости.")
                    .padding(.bottom, 16)

                SFCard {
                    H3(text: "Шаг 1 · Задержка")
                    DiagRow(label: "Состояние", value: delayStatus(ui.delayCalib.state, ui.delayCalib.spreadMs))
                    Text("Положите телефоны рядом (10–20 см), динамиками вверх. Во время автокалибровки не шумите.")
                        .font(.system(size: 14)).foregroundColor(SFColor.text).lineSpacing(4)
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
                    SliderRow(label: "Ручной сдвиг Point", valueText: trimText, value: $trim, color: SFColor.accent1,
                              range: -SF.maxTrimMs...SF.maxTrimMs, step: 0.5,
                              onEditingEnded: { engine.setTrimMs(trim) })
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
                            Subtitle(text: "Щелчки звучат одновременно в обоих каналах. Двигайте сдвиг, пока они не сольются.")
                        }
                    }
                    .tint(SFColor.accent1).padding(.top, 8)
                    GhostButton(title: "Сбросить сдвиг в 0", color: SFColor.dim) { trim = 0; engine.resetTrim() }
                }

                SFCard {
                    H3(text: "Шаг 2 · Уровень телефонов")
                    Text("Выровняйте громкость устройств. Уровень хранится за телефоном, а баланс L/R остаётся отдельной пользовательской настройкой.")
                        .font(.system(size: 14)).foregroundColor(SFColor.text).lineSpacing(4)
                    if !levelReady { Text(gateText).font(.system(size: 13)).foregroundColor(SFColor.warn).fixedSize(horizontal: false, vertical: true) }
                    if ui.mode == .level {
                        Text("Сигнал L и R звучит по очереди").font(.system(size: 14, weight: .semibold)).foregroundColor(SFColor.accent1)
                        SliderRow(label: "L громче  ·  R громче", valueText: String(format: "%+.1f дБ", levelOffset), value: $levelOffset,
                                  color: SFColor.accent1, range: -12...12, step: 0.5,
                                  onEditingChanged: { levelDragging = $0 },
                                  onEditingEnded: { engine.setLevelOffsetDb(Float(levelOffset), finished: true) })
                            .onChange(of: levelOffset) { value in
                                if levelDragging && abs(value - offset) > 0.004 { engine.setLevelOffsetDb(Float(value), finished: false) }
                            }
                        Subtitle(text: "Сядьте на одинаковом расстоянии от телефонов (±10 см). Двигайте ползунок, пока обе стороны не станут одинаково громкими.")
                        HStack(spacing: 8) {
                            Button("Готово") { engine.stopLevelMatch(commit: true) }.buttonStyle(.borderedProminent).tint(SFColor.accent1)
                            Button("Отмена") { engine.stopLevelMatch(commit: false) }.buttonStyle(.bordered)
                        }
                    } else {
                        PrimaryButton(title: "Выровнять уровень на слух", enabled: levelReady) { engine.startLevelMatch() }
                    }
                    levelRow(name: "\(serverDeviceName(ui)) (\(Ch.name(serverCh))) · Server", device: "server",
                             db: ui.levelTrimServerDb, enabled: levelReady)
                    levelRow(name: "\(pointDeviceName(ui)) (\(Ch.name(ui.pointChannel))) · Point", device: "point",
                             db: ui.levelTrimPointDb, enabled: levelReady)
                    GhostButton(title: "Сбросить уровни", color: SFColor.dim, enabled: levelReady) { engine.resetLevelTrims() }
                }

                SFCard {
                    H3(text: "Шаг 3 · Проверка центра")
                    if !levelReady { Text(gateText).font(.system(size: 13)).foregroundColor(SFColor.warn).fixedSize(horizontal: false, vertical: true) }
                    Text("Сядьте на одинаковом расстоянии от телефонов. Розовый шум должен звучать из точки посередине.")
                        .font(.system(size: 14)).foregroundColor(SFColor.text).lineSpacing(4)
                    PrimaryButton(title: "Проверка центра", enabled: levelReady) { engine.startCenterCheck() }
                    HStack(spacing: 6) {
                        answerButton("В центре ✓", enabled: levelReady) { engine.centerCheckAnswer("center") }
                        answerButton("Тянет влево", enabled: levelReady) { engine.centerCheckAnswer("left") }
                        answerButton("Тянет вправо", enabled: levelReady) { engine.centerCheckAnswer("right") }
                    }
                    .padding(.bottom, 8)
                }

                SFCard {
                    H3(text: "Эксперимент · измерение микрофонами")
                    Text("Разрешение нужно на обоих телефонах. Сверка: телефоны рядом, внешний шумовой источник по центру в 30–50 см; выполните запись, поменяйте телефоны местами и запишите ещё раз. Затем верните их в рабочие позиции.")
                        .font(.system(size: 13)).foregroundColor(SFColor.text).lineSpacing(3)
                    SecondaryButton(title: "Разрешить микрофон на этом телефоне") {
                        MicRecorder.requestPermission { granted in if granted { engine.micPermissionGranted() } }
                    }
                    if ui.role == .server {
                        SecondaryButton(title: "Сверить микрофоны · положение 1") { engine.measureMicReference(1) }
                            .disabled(!levelReady)
                        SecondaryButton(title: "Сверить микрофоны · положение 2") { engine.measureMicReference(2) }
                            .disabled(!levelReady || !ui.levelMeasureText.contains("d1 ="))
                        Subtitle(text: "После сверки: поставьте телефоны на места для прослушивания. Измерение длится около 24 с.")
                        PrimaryButton(title: "Измерить микрофонами (эксперимент)",
                                      enabled: levelReady && ui.levelMeasureText.contains("Сверка микрофонов готова")) {
                            engine.measureLevels()
                        }
                        Subtitle(text: "Результат показывается для диагностики и автоматически не применяется.")
                    } else {
                        Subtitle(text: "После выдачи разрешения запустите оба измерения с телефона Server.")
                    }
                    if !ui.levelMeasureText.isEmpty {
                        Text(ui.levelMeasureText).font(.system(size: 13)).foregroundColor(SFColor.accent1)
                            .fixedSize(horizontal: false, vertical: true).padding(.top, 6)
                    }
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
                    DiagRow(label: "Сдвиг Point", value: String(format: "%+.1f мс", ui.trimMs))
                    DiagRow(label: "Уровень Server", value: String(format: "%+.1f дБ", ui.levelTrimServerDb))
                    DiagRow(label: "Уровень Point", value: String(format: "%+.1f дБ", ui.levelTrimPointDb))
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 24)
        }
        .onAppear { trim = ui.trimMs; levelOffset = offset }
        .onChange(of: ui.trimMs) { trim = $0 }
        .onChange(of: ui.levelTrimServerDb) { _ in if !levelDragging { levelOffset = offset } }
        .onChange(of: ui.levelTrimPointDb) { _ in if !levelDragging { levelOffset = offset } }
    }

    private var trimText: String {
        String(format: "%+.1f мс", trim) + (trim > 0 ? " (позже)" : trim < 0 ? " (раньше)" : "")
    }

    private func levelRow(name: String, device: String, db: Float, enabled: Bool) -> some View {
        HStack(spacing: 4) {
            Text(name).font(.system(size: 12)).foregroundColor(SFColor.text).lineLimit(1).minimumScaleFactor(0.75)
            Spacer(minLength: 2)
            Text(String(format: "%+.1f дБ", db)).font(.system(size: 12, weight: .semibold)).foregroundColor(SFColor.accent1)
            Button("−0.5") { engine.setLevelTrim(device, db - 0.5) }.disabled(!enabled || db <= -12)
            Button("+0.5") { engine.setLevelTrim(device, db + 0.5) }.disabled(!enabled || db >= 0)
        }
        .buttonStyle(.bordered).padding(.vertical, 3)
    }

    private func answerButton(_ title: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 12, weight: .semibold)).foregroundColor(SFColor.text)
                .frame(maxWidth: .infinity).padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 10).fill(SFColor.secondary))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(SFColor.secondaryBorder))
        }
        .buttonStyle(PressStyle()).disabled(!enabled)
    }

    private func serverDeviceName(_ ui: UiState) -> String { ui.role == .server ? ui.myName : ui.peerName }
    private func pointDeviceName(_ ui: UiState) -> String { ui.role == .point ? ui.myName : ui.peerName }
    private func delayStatus(_ state: String, _ spreadMs: Double) -> String {
        switch state {
        case "auto": return String(format: "Авто, разброс %.1f мс", spreadMs)
        case "manual": return "Вручную"
        default: return "Не откалибрована"
        }
    }
    private func fmt(_ d: Double) -> String { d == d.rounded() ? String(Int(d)) : String(d) }
    private func ms(_ v: Double, _ valid: Bool) -> String { valid ? String(format: "%.1f мс", v) : "—" }
}
