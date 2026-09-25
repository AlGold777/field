import SwiftUI

struct RootView: View {
    @ObservedObject var model: AppModel
    let engine: Engine
    @State private var sub: Sub = .none
    @State private var toastText = ""
    @State private var toastVisible = false

    enum Sub { case none, calibration, diagnostics }

    var body: some View {
        let ui = model.ui
        ZStack(alignment: .bottom) {
            SFColor.background.ignoresSafeArea()
            Group {
                if !ui.inPair {
                    WelcomeView(ui: ui, engine: engine)
                } else if sub == .calibration {
                    CalibrationView(ui: ui, engine: engine) { sub = .none }
                } else if sub == .diagnostics {
                    DiagnosticsView(ui: ui) { sub = .none }
                } else {
                    PlayerView(ui: ui, engine: engine,
                               openCalibration: { sub = .calibration }, openDiagnostics: { sub = .diagnostics })
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
        .onChange(of: ui.inPair) { inPair in if !inPair { sub = .none } }
    }
}
