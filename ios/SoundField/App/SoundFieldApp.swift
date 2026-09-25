import SwiftUI

@main
struct SoundFieldApp: App {
    private let engine = Engine.shared

    init() {
        engine.boot()   // сеть и Bonjour поднимаются сразу — второй телефон увидит этот без лишних действий
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: engine.model, engine: engine)
        }
    }
}
