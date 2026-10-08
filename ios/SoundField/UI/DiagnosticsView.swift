import SwiftUI

struct DiagnosticsView: View {
    let ui: UiState
    let back: () -> Void

    private func c(_ v: Double, _ good: Double, _ warn: Double) -> Color {
        v <= good ? SFColor.good : v <= warn ? SFColor.warn : SFColor.bad
    }

    private var balanceDescription: String {
        let p = 2 * ui.balance - 1
        if abs(p) < 0.001 { return "Центр" }
        if abs(p) >= 0.995 { return p < 0 ? "Только L" : "Только R" }
        return String(format: "%@ +%.1f дБ", p < 0 ? "L" : "R", -BalanceLaw.differenceDb(balance: ui.balance))
    }

    var body: some View {
        let p = ui.pointDiag, s = ui.serverDiag
        let est: Double? = (p.valid && s.valid) ? abs(p.syncErrMs - s.syncErrMs) + p.clockUncMs : nil
        let serverCh = Ch.other(ui.pointChannel)
        let leftLevel = serverCh == Ch.L ? ui.levelTrimServerDb : ui.levelTrimPointDb
        let rightLevel = serverCh == Ch.R ? ui.levelTrimServerDb : ui.levelTrimPointDb
        let levelSource = ["ear": "на слух", "mic": "микрофон", "manual": "вручную"][ui.levelCalib.state] ?? "не настроено"
        let balanceText = balanceDescription
        let delayState = ui.delayCalib.state == "auto"
            ? String(format: "авто, разброс %.1f мс", ui.delayCalib.spreadMs)
            : ui.delayCalib.state == "manual" ? "вручную" : "не откалибрована"
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackBar(title: "Диагностика", back: back)
                Subtitle(text: "Обновляется каждую секунду. Этот телефон — \(ui.role.title).").padding(.bottom, 16)

                SFCard {
                    H3(text: "Рассогласование L/R")
                    DiagRow(label: "Оценка по часам", value: est.map { String(format: "±%.2f мс", $0) } ?? "—",
                            color: est.map { c($0, 2, 5) } ?? SFColor.text)
                    DiagRow(label: "Акустически измерено",
                            value: ui.calib.lastMeasuredMs.map { String(format: "%+.1f мс", $0) } ?? "не измерялось",
                            color: ui.calib.lastMeasuredMs.map { c(abs($0), 2, 5) } ?? SFColor.dim)
                    DiagRow(label: "Задержка", value: String(format: "trim %+.1f мс · %@", ui.trimMs, delayState))
                    DiagRow(label: "Уровень", value: String(format: "L-тел %+.1f дБ, R-тел %+.1f дБ · %@", leftLevel, rightLevel, levelSource))
                    DiagRow(label: "Баланс", value: balanceText)
                }

                SFCard {
                    H3(text: "Сеть (Point ← Server)")
                    if !p.valid { DiagRow(label: "Нет данных", value: "—") } else {
                        DiagRow(label: "RTT (среднее)", value: String(format: "%.1f мс", p.rttMs), color: c(p.rttMs, 15, 50))
                        DiagRow(label: "Разброс RTT", value: String(format: "%.1f мс", p.rttJitterMs), color: c(p.rttJitterMs, 5, 20))
                        DiagRow(label: "Jitter (RFC 3550)", value: String(format: "%.1f мс", p.jitterMs), color: c(p.jitterMs, 5, 20))
                        DiagRow(label: "Потери пакетов", value: String(format: "%.1f %%", p.lossPct), color: c(p.lossPct, 0.5, 3))
                        DiagRow(label: "Восстановлено NACK", value: "\(p.recovered)")
                        DiagRow(label: "Опоздавшие / устаревшие", value: "\(p.late) / \(p.stale)", color: p.late > 0 ? SFColor.warn : SFColor.text)
                        DiagRow(label: "Перепослано Server", value: "\(s.resent)")
                    }
                }

                SFCard {
                    H3(text: "Буфер и часы")
                    if !p.valid { DiagRow(label: "Нет данных", value: "—") } else {
                        DiagRow(label: "Размер буфера Point", value: String(format: "%.0f мс", p.bufferMs),
                                color: p.bufferMs < 60 ? SFColor.warn : SFColor.good)
                        DiagRow(label: "Clock drift", value: String(format: "%+.1f ppm", p.driftPpm))
                        DiagRow(label: "Точность часов", value: String(format: "±%.2f мс", p.clockUncMs), color: c(p.clockUncMs, 1, 3))
                        DiagRow(label: "Часы синхронизированы", value: p.clockReady ? "да" : "нет",
                                color: p.clockReady ? SFColor.good : SFColor.warn)
                    }
                }

                SFCard {
                    H3(text: "Аудиовыход")
                    let age = ui.pointDiag.audioAgeMs
                    DiagRow(label: "Аудиопакеты на Point",
                            value: age < 0 ? "не поступают" : "\(age) мс назад",
                            color: age < 0 ? SFColor.bad : age < 2_000 ? SFColor.good : age < 5_000 ? SFColor.warn : SFColor.bad)
                    DiagRow(label: "Ошибка воспроизведения Server", value: s.valid ? String(format: "%+.2f мс", s.syncErrMs) : "—")
                    DiagRow(label: "Ошибка воспроизведения Point", value: p.valid ? String(format: "%+.2f мс", p.syncErrMs) : "—")
                    DiagRow(label: "Underrun Server", value: s.valid ? "\(s.underruns)" : "—", color: s.underruns > 0 ? SFColor.warn : SFColor.text)
                    DiagRow(label: "Underrun Point", value: p.valid ? "\(p.underruns)" : "—", color: p.underruns > 0 ? SFColor.warn : SFColor.text)
                    DiagRow(label: "Выходная задержка Server", value: s.valid ? String(format: "%.1f мс", s.latencyMs) : "—")
                    DiagRow(label: "Выходная задержка Point", value: p.valid ? String(format: "%.1f мс", p.latencyMs) : "—")
                }

                SFCard {
                    H3(text: "Журнал подключения")
                    if ui.connLog.isEmpty { DiagRow(label: "Событий нет", value: "—") }
                    ForEach(Array(ui.connLog.reversed().prefix(30).enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(size: 12)).foregroundColor(SFColor.dim)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 3)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 24)
        }
    }
}
