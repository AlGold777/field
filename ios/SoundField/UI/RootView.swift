import SwiftUI

struct RootView: View {
    @ObservedObject var model: AppModel
    let engine: Engine
    @State private var sub: Sub = .none
    @State private var toastText = ""
    @State private var toastVisible = false

    enum Sub { case none, pairing, calibration, diagnostics }

    var body: some View {
        let ui = model.ui
        ZStack(alignment: .bottom) {
            SFColor.background.ignoresSafeArea()
            Group {
                if sub == .pairing {
                    WelcomeView(ui: ui, engine: engine)
                } else if sub == .calibration {
                    CalibrationView(ui: ui, engine: engine) { sub = .none }
                } else if sub == .diagnostics {
                    DiagnosticsView(ui: ui) { sub = .none }
                } else {
                    PlayerView(ui: ui, engine: engine,
                               openCalibration: { sub = .calibration }, openDiagnostics: { sub = .diagnostics },
                               openPairing: { sub = .pairing })
                }
            }
            if toastVisible {
                Text(toastText).font(.system(size: 14, weight: .medium)).foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .background(Capsule().fill(Color.black.opacity(0.75)))
                    .padding(.horizontal, 20).padding(.bottom, 30)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .alert(ui.phase == .incoming ? "Создать стереопару?" : "Подключение…",
               isPresented: .constant(ui.phase == .incoming || ui.phase == .inviting)) {
            if ui.phase == .incoming {
                Button("Принять") { engine.acceptIncoming() }
                Button("Отклонить", role: .cancel) { engine.rejectIncoming() }
            } else { Button("Отмена", role: .cancel) { engine.cancelInvite() } }
        } message: {
            Text(ui.phase == .incoming ? "«\(ui.incomingName)» приглашает этот телефон в стереопару." : "Соединяемся с «\(ui.invitingName)»…")
        }
        .preferredColorScheme(.dark)
        .onChange(of: ui.messageId) { _ in
            guard !ui.message.isEmpty else { return }
            toastText = ui.message
            withAnimation { toastVisible = true }
            let shown = ui.messageId
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                if model.ui.messageId == shown { withAnimation { toastVisible = false } }
            }
        }
        .onChange(of: ui.inPair) { inPair in if (inPair && sub == .pairing) || (!inPair && sub == .calibration) { sub = .none } }
    }
}
