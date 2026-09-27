import SwiftUI

struct WelcomeView: View {
    let ui: UiState
    let engine: Engine
    @State private var renaming = false
    @State private var newName = ""
    @State private var showLog = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(spacing: 8) {
                    Text("🔊").font(.system(size: 56))
                    Text("SoundField").font(.system(size: 30, weight: .bold)).foregroundStyle(SFColor.accent)
                    Subtitle(text: "Объедините два смартфона в стереосистему", align: .center)
                }
                .frame(maxWidth: .infinity).padding(.bottom, 28)

                if let rec = ui.remembered {
                    H3(text: "Знакомая пара")
                    DeviceRow(icon: "🔁", name: rec.peerName,
                              detail: "Этот телефон: \(rec.role.title) · без подтверждения") { engine.resumeRemembered() }
                    if ui.autoResuming { Subtitle(text: "Ищем «\(rec.peerName)» — пара восстановится автоматически…") }
                    HStack {
                        GhostButton(title: "Подключить снова") { engine.resumeRemembered() }
                        GhostButton(title: "Забыть", color: SFColor.dim) { engine.forgetPair() }
                    }
                    .padding(.bottom, 12)
                }

                H3(text: "Телефоны рядом")
                SearchingDots()
                if ui.nearby.isEmpty {
                    SFCard {
                        Text("📱📱").font(.system(size: 32)).frame(maxWidth: .infinity)
                        Subtitle(text: "Откройте SoundField на втором телефоне.\nОба телефона должны быть в одной сети Wi‑Fi (или один раздаёт точку доступа).",
                                 align: .center).frame(maxWidth: .infinity)
                    }
                } else {
                    ForEach(ui.nearby) { p in
                        DeviceRow(icon: p.platform == "ios" ? "🍏" : "🤖", name: p.name,
                                  detail: (p.platform == "ios" ? "iPhone" : "Android") + " · нажмите, чтобы создать пару") {
                            engine.invite(p)
                        }
                    }
                    Subtitle(text: "Этот телефон станет Server (левый канал), второй — Point (правый). Каналы можно поменять позже.")
                }

                GhostButton(title: "Этот телефон: \(ui.myName)  ✎", color: SFColor.dim) {
                    newName = ui.myName; renaming = true
                }
                .padding(.top, 24)
                GhostButton(title: showLog ? "Скрыть журнал подключения" : "Журнал подключения", color: SFColor.dim) {
                    showLog.toggle()
                }
                if showLog {
                    if ui.connLog.isEmpty { Subtitle(text: "Событий нет") }
                    ForEach(Array(ui.connLog.reversed().prefix(30).enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(size: 12)).foregroundColor(SFColor.dim)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 2)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 24)
        }
        .alert("Имя телефона", isPresented: $renaming) {
            TextField("Видно второму телефону", text: $newName)
            Button("Сохранить") { engine.rename(newName) }
            Button("Отмена", role: .cancel) {}
        }
        .alert("Подключение…", isPresented: .constant(ui.phase == .inviting)) {
            Button("Отмена", role: .cancel) { engine.cancelInvite() }
        } message: {
            Text(ui.resuming ? "Восстанавливаем пару с «\(ui.invitingName)»…" : "Ждём ответа от «\(ui.invitingName)».")
        }
        .alert("Создать стереопару?", isPresented: .constant(ui.phase == .incoming)) {
            Button("Принять") { engine.acceptIncoming() }
            Button("Отклонить", role: .cancel) { engine.rejectIncoming() }
        } message: {
            Text("«\(ui.incomingName)» приглашает этот телефон в стереопару. Этот телефон станет Point и будет играть правый канал.")
        }
    }
}
