import SwiftUI

struct WelcomeView: View {
    let ui: UiState
    let engine: Engine
    @State private var showLog = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
          ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(spacing: 8) {
                    Text("🔊").font(.system(size: 56))
                    Text("SoundField").font(.system(size: 30, weight: .bold)).foregroundStyle(SFColor.accent)
                }
                .frame(maxWidth: .infinity).padding(.bottom, 28)

                if let rec = ui.remembered {
                    HStack {
                        H3(text: "Знакомая пара")
                        Spacer()
                        GhostButton(title: "Забыть", color: SFColor.dim) { engine.forgetPair() }
                            .fixedSize(horizontal: true, vertical: false)
                    }
                    Button { engine.resumeRemembered() } label: {
                        SFCard {
                            H2(text: rec.peerName)
                            Text("Подключить").font(.system(size: 13, weight: .semibold)).foregroundColor(SFColor.accent1)
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.bottom, 12)
                }

                SearchingDots()
                H3(text: "Телефоны рядом")
                if ui.nearby.isEmpty {
                    SFCard {
                        Text("📱📱").font(.system(size: 32)).frame(maxWidth: .infinity)
                        Subtitle(text: "Откройте SoundField на другом телефоне.",
                                 align: .center).frame(maxWidth: .infinity)
                    }
                } else {
                    ForEach(ui.nearby) { p in
                        DeviceRow(icon: p.platform == "ios" ? "🍏" : "🤖", name: p.name,
                                  detail: (p.platform == "ios" ? "iPhone" : "Android") + " · нажмите, чтобы создать пару") {
                            engine.invite(p)
                        }
                    }
                }

                GhostButton(title: showLog ? "Скрыть журнал подключения" : "Журнал подключения", color: SFColor.dim) {
                    showLog.toggle()
                }
                .padding(.top, 24)
                if showLog {
                    if ui.connLog.isEmpty { Subtitle(text: "Событий нет") }
                    ForEach(Array(ui.connLog.reversed().prefix(30).enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(size: 12)).foregroundColor(SFColor.dim)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 2)
                    }
                }
            }
            .padding(.horizontal, 20).padding(.top, 36).padding(.bottom, 24)
          }
          if let rec = ui.remembered {
              Badge(text: rec.role.title.uppercased(), color: SFColor.master)
                  .padding(.top, 12).padding(.trailing, 20)
          }
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
