import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct PlayerView: View {
    let ui: UiState
    let engine: Engine
    let openCalibration: () -> Void
    let openDiagnostics: () -> Void

    @State private var pos: Int64 = 0
    @State private var dragging: Double? = nil
    @State private var confirmDisconnect = false
    @State private var master = 0.8
    @State private var balance = 0.5
    @State private var balanceDragging = false
    @State private var balanceWasInCenter = true
    @AppStorage("sf.queueExpanded") private var queueExpanded = false
    @State private var showingAudioSourceDialog = false
    @State private var showingAudioFiles = false
    @State private var showingAudioFolder = false
    private let timer = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Button { confirmDisconnect = true } label: {
                        if ui.phase == .reconnecting && ui.role == .point { StatusPill(text: "Переподключение…", color: SFColor.warn) }
                        else if ui.phase == .reconnecting { StatusPill(text: "Ждём «\(ui.peerName)»", color: SFColor.warn) }
                        else { StatusPill(text: "Стереопара активна", color: SFColor.good) }
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    Badge(text: ui.role == .server ? "SERVER" : "POINT", color: SFColor.master)
                }

                stage

                syncIndicator.padding(.bottom, 20)

                if ui.mode != .music {
                    SFCard {
                        Text(ui.mode == .metronome ? "🥁 Метроном для ручной подстройки"
                             : ui.mode == .lrtest ? "🔈 Проверка каналов: сначала L, потом R"
                             : ui.mode == .level ? "Уровень: L и R звучат по очереди"
                             : ui.mode == .center ? "Проверка центра"
                             : ui.mode == .levelMeasure ? "Экспериментальное измерение микрофонами" : "🎯 Идёт калибровка")
                            .font(.system(size: 15, weight: .semibold)).foregroundColor(SFColor.warn)
                    }
                }

                Button { engine.requestTrackPicker() } label: {
                    Text(ui.title).font(.system(size: 20, weight: .bold)).foregroundColor(SFColor.text)
                        .multilineTextAlignment(.center).frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .highPriorityGesture(
                    DragGesture(minimumDistance: 20)
                        .onEnded { value in
                            let dx = value.translation.width
                            guard abs(dx) >= 48, abs(dx) > abs(value.translation.height) else { return }
                            if dx < 0 { engine.nextTrack() } else { engine.previousTrack() }
                        }
                )
                Subtitle(text: ui.artist, align: .center).frame(maxWidth: .infinity).padding(.top, 4).padding(.bottom, 12)
                Text(trackStatusText)
                    .font(.system(size: 12)).foregroundColor(SFColor.dim).multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity).padding(.bottom, 8)
                if ui.audioMissing {
                    Text("Нет аудио на Point · восстанавливаем соединение")
                        .font(.system(size: 12)).foregroundColor(SFColor.warn).multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity).padding(.bottom, 8)
                }

                let dur = Double(max(1, ui.durationMs))
                Slider(value: Binding(get: { dragging ?? min(1, Double(pos) / dur) }, set: { dragging = $0 }),
                       in: 0...1, onEditingChanged: { editing in
                           if !editing, let d = dragging { engine.seekMs(Int64(d * dur)); dragging = nil }
                       })
                    .tint(SFColor.accent1)
                HStack {
                    Text(fmtTime(dragging.map { Int64($0 * dur) } ?? pos))
                    Spacer()
                    Text(fmtTime(ui.durationMs))
                }
                .font(.system(size: 12)).foregroundColor(SFColor.dim)

                HStack {
                    Spacer()
                    roundIcon("gobackward.10") { engine.skipMs(-10_000) }
                    Spacer()
                    Button { engine.togglePlay() } label: {
                        Image(systemName: ui.playing ? "pause.fill" : "play.fill").font(.system(size: 30)).foregroundColor(.white)
                            .frame(width: 76, height: 76).background(Circle().fill(SFColor.accent))
                    }
                    .buttonStyle(PressStyle())
                    Spacer()
                    roundIcon("goforward.10") { engine.skipMs(10_000) }
                    Spacer()
                }
                .padding(.vertical, 18)

                queueSection

                SFCard {
                    Text("Sound").font(.system(size: 13, weight: .semibold)).kerning(1)
                        .foregroundColor(SFColor.dim).padding(.bottom, 8)
                    SliderRow(label: "Громкость", valueText: "\(Int(master * 100))%", value: $master, color: SFColor.master,
                              enabled: ui.mode != .levelMeasure,
                              onEditingEnded: { engine.setVolume("M", Float(master), finished: true) })
                        .onChange(of: master) { if abs($0 - Double(ui.master)) > 0.004 { engine.setVolume("M", Float($0), finished: false) } }
                    HStack {
                        Text("Баланс между телефонами").font(.system(size: 14)).foregroundColor(SFColor.dim)
                        Spacer()
                        Button {
                            balance = 0.5; balanceWasInCenter = true
                            engine.setBalance(0.5, finished: true)
                        } label: {
                            Text(balanceLabel(balance)).font(.system(size: 14, weight: .semibold)).foregroundColor(SFColor.accent1)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Сбросить баланс в центр")
                    }
                    HStack(spacing: 8) {
                        Text("L").font(.system(size: 13, weight: .semibold)).foregroundColor(SFColor.leftText)
                        ZStack {
                            Slider(value: Binding(get: { balance }, set: { raw in
                                let centered = abs(raw - 0.5) < 0.015
                                if centered && !balanceWasInCenter {
                                    let haptic = UISelectionFeedbackGenerator(); haptic.prepare(); haptic.selectionChanged()
                                }
                                balanceWasInCenter = centered
                                let value = centered ? 0.5 : raw
                                balance = value
                                if abs(value - Double(ui.balance)) > 0.004 { engine.setBalance(Float(value), finished: false) }
                            }), in: 0...1, onEditingChanged: { editing in
                                balanceDragging = editing
                                if !editing { engine.setBalance(Float(balance), finished: true) }
                            })
                            .tint(SFColor.accent1)
                            GeometryReader { geometry in
                                Rectangle().fill(SFColor.dim.opacity(0.8)).frame(width: 2, height: 10)
                                    .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
                            }
                            .allowsHitTesting(false)
                        }
                        .accessibilityElement()
                        .accessibilityLabel("Баланс между телефонами")
                        .accessibilityValue("Баланс: \(balanceLabel(balance))")
                        Text("R").font(.system(size: 13, weight: .semibold)).foregroundColor(SFColor.rightText)
                    }
                }

                HStack(spacing: 8) {
                    PlayerActionButton(title: "Калибровка", action: openCalibration)
                    PlayerActionButton(title: "Диагностика", action: openDiagnostics)
                    ShareLink(item: telemetrySnapshot) {
                        TelemetryGlyph().frame(width: 23, height: 23)
                            .frame(width: 48, height: 48)
                            .background(RoundedRectangle(cornerRadius: 14).fill(SFColor.secondary))
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(SFColor.secondaryBorder, lineWidth: 1))
                    }
                    .buttonStyle(PressStyle())
                    .accessibilityLabel("Экспорт телеметрии")
                }
                .padding(.bottom, 12)
            }
            .padding(.horizontal, 20).padding(.vertical, 24)
        }
        .onReceive(timer) { _ in pos = engine.positionMs() }
        .onAppear { syncVolumes(); engine.setQueueExpanded(queueExpanded) }
        .onChange(of: ui.master) { _ in syncVolumes() }
        .onChange(of: ui.balance) { value in
            if !balanceDragging { balance = Double(value); balanceWasInCenter = abs(Double(value) - 0.5) < 0.015 }
            syncVolumes()
        }
        .onChange(of: ui.filePickerRequest) { request in
            if request > 0 { showingAudioSourceDialog = true }
        }
        .onChange(of: queueExpanded) { expanded in engine.setQueueExpanded(expanded) }
        .confirmationDialog("Добавить музыку", isPresented: $showingAudioSourceDialog, titleVisibility: .visible) {
            Button("Выбрать файлы") { showingAudioFiles = true }
            Button("Выбрать папку") { showingAudioFolder = true }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Файлы из папки будут добавлены в очередь по имени.")
        }
        .fileImporter(isPresented: $showingAudioFiles, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { engine.addAudioFiles(urls) }
        }
        .fileImporter(isPresented: $showingAudioFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { engine.addAudioFiles([url]) }
        }
        .alert("Вы хотите разорвать пару?", isPresented: $confirmDisconnect) {
            Button("Да", role: .destructive) { engine.disconnect() }
            Button("Нет", role: .cancel) {}
        }
    }

    private func syncVolumes() {
        if abs(master - Double(ui.master)) > 0.005 { master = Double(ui.master) }
        if !balanceDragging && abs(balance - Double(ui.balance)) > 0.005 {
            balance = Double(ui.balance); balanceWasInCenter = abs(balance - 0.5) < 0.015
        }
    }

    private func balanceLabel(_ value: Double) -> String {
        let p = 2 * min(1, max(0, value)) - 1
        if abs(p) < 0.001 { return "Центр" }
        if abs(p) >= 0.995 { return p < 0 ? "Только L" : "Только R" }
        let db = -Double(BalanceLaw.differenceDb(balance: Float(value)))
        let rounded = (db * 2).rounded() / 2
        return String(format: "%@ +%.1f дБ", p < 0 ? "L" : "R", rounded)
    }

    private var telemetrySnapshot: String {
        let phase: String
        switch ui.phase {
        case .idle: phase = "idle"
        case .inviting: phase = "connecting"
        case .incoming: phase = "incoming"
        case .paired: phase = "paired"
        case .reconnecting: phase = "reconnecting"
        }
        func summary(_ label: String, _ diag: Diag) -> String {
            "\(label): valid=\(diag.valid), RTT=\(String(format: "%.1f", diag.rttMs)) ms, " +
                "jitter=\(String(format: "%.1f", diag.jitterMs)) ms, loss=\(String(format: "%.2f", diag.lossPct))%, " +
                "buffer=\(String(format: "%.1f", diag.bufferMs)) ms, clock uncertainty=\(String(format: "%.1f", diag.clockUncMs)) ms, " +
                "underruns=\(diag.underruns)"
        }
        return [
            "SoundField telemetry snapshot",
            "Created: \(ISO8601DateFormatter().string(from: Date()))",
            "Pair state: \(phase)",
            "Role: \(ui.role.rawValue)",
            "Balance: \(String(format: "%.2f", balance))",
            summary("Server", ui.serverDiag),
            summary("Point", ui.pointDiag),
        ].joined(separator: "\n")
    }

    private var trackStatusText: String {
        if ui.loadingTracks { return "Подготовка аудиофайла…" }
        if ui.audioBuffering { return "Буферизация аудио…" }
        if let progress = ui.decodingProgress { return "Подготовка аудиофайла… \(Int(progress * 100))%" }
        if ui.role == .point { return "Добавление файлов с Point доступно только при паре Android ↔ Android" }
        return "Нажмите на название, чтобы добавить FLAC, WAV или MP3"
    }

    private var queueSection: some View {
        VStack(spacing: 0) {
            Button { queueExpanded.toggle() } label: {
                HStack {
                    Text("Очередь · \(ui.queueTracks.count) треков")
                        .font(.system(size: 13, weight: .semibold)).tracking(1).foregroundColor(SFColor.dim)
                    Spacer()
                    Image(systemName: queueExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 14, weight: .semibold)).foregroundColor(SFColor.text)
                }
                .contentShape(Rectangle()).padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            if queueExpanded {
                if ui.queueTracks.isEmpty {
                    Text("Список появится после добавления музыки на Server")
                        .font(.system(size: 14)).foregroundColor(SFColor.dim).frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)
                } else {
                    ScrollView(.vertical) {
                        VStack(spacing: 2) {
                            ForEach(ui.queueTracks.indices, id: \.self) { index in
                                let track = ui.queueTracks[index]
                                let selected = index == ui.queueIndex || track.id == ui.loadingTrackId
                                Button { engine.selectQueueTrack(track.id) } label: {
                                    HStack(spacing: 10) {
                                        Text("\(index + 1).").font(.system(size: 13)).foregroundColor(SFColor.dim).frame(width: 28, alignment: .leading)
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(track.title).font(.system(size: 14, weight: selected ? .semibold : .regular))
                                                .foregroundColor(selected ? SFColor.accent1 : SFColor.text).lineLimit(1)
                                            if !track.artist.isEmpty { Text(track.artist).font(.system(size: 12)).foregroundColor(SFColor.dim).lineLimit(1) }
                                        }
                                        Spacer(minLength: 4)
                                        if selected {
                                            if track.id == ui.loadingTrackId { ProgressView().tint(SFColor.accent1) }
                                            else { Image(systemName: "music.note").foregroundColor(SFColor.accent1) }
                                        }
                                    }
                                    .padding(.horizontal, 8).padding(.vertical, 9)
                                    .background(selected ? SFColor.accent1.opacity(0.16) : .clear)
                                    .clipShape(RoundedRectangle(cornerRadius: 9))
                                }
                                .buttonStyle(.plain).disabled(ui.loadingTracks)
                            }
                        }
                    }
                    .frame(maxHeight: 260).padding(.top, 6)
                }
            }
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(SFColor.card, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(SFColor.cardBorder, lineWidth: 1))
        .padding(.bottom, 16)
    }

    private var stage: some View {
        let serverCh = Ch.other(ui.pointChannel)
        let leftIsMe = ui.myChannel == Ch.L
        func role(_ ch: Int) -> String { ch == serverCh ? "Server" : "Point" }
        return HStack {
            SpeakerCircle(label: "L", ch: Ch.L, playing: ui.playing, caption: leftIsMe ? "Этот телефон" : ui.peerName,
                          sub: role(Ch.L), highlight: leftIsMe) { engine.swapChannels() }
            Button { engine.swapChannels() } label: {
                Rectangle().fill(SFColor.accent).frame(width: 40, height: 2)
                    .frame(width: 48, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .offset(y: -20)
            .accessibilityLabel("Поменять каналы L и R")
            SpeakerCircle(label: "R", ch: Ch.R, playing: ui.playing, caption: leftIsMe ? ui.peerName : "Этот телефон",
                          sub: role(Ch.R), highlight: !leftIsMe) { engine.swapChannels() }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 28)
    }

    private var syncIndicator: some View {
        let p = ui.pointDiag, s = ui.serverDiag
        let est: Double? = (p.valid && s.valid) ? abs(p.syncErrMs - s.syncErrMs) + p.clockUncMs : nil
        let text = est.map { String(format: "⚡ L/R: ±%.1f мс • RTT %.0f мс • буфер %.0f мс", $0, p.rttMs, p.bufferMs) }
            ?? "⚡ Синхронизация часов…"
        return Button(action: openDiagnostics) {
            Text(text).font(.system(size: 13)).foregroundColor((est ?? 99) < 2 ? SFColor.good : SFColor.warn)
                .frame(maxWidth: .infinity).padding(.vertical, 10)
                .background(Capsule().fill(SFColor.card))
        }
    }

    private func roundIcon(_ name: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name).font(.system(size: 20)).foregroundColor(.white)
                .frame(width: 54, height: 54).background(Circle().fill(SFColor.secondary))
        }
        .buttonStyle(PressStyle())
    }
}
