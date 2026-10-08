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
    @State private var showingAudioImporter = false
    @State private var audioBrowserRoot: URL? = AudioBrowserView.restoredRoot()
    @State private var showingAudioBrowser = false
    private let timer = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Button { confirmDisconnect = true } label: {
                        if ui.phase == .reconnecting { StatusPill(text: "Reconnecting…", color: SFColor.warn) }
                        else { StatusPill(text: "Active", color: SFColor.good) }
                    }
                    .buttonStyle(.plain)
                    .frame(width: 104, height: 36)
                    Spacer()
                    Badge(text: ui.role == .server ? "Server" : "Point", color: SFColor.master)
                        .frame(width: 76, height: 32)
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

                trackStatusSlot

                Group {
                    Text(ui.title).font(.system(size: 20, weight: .bold)).foregroundColor(SFColor.text)
                        .lineLimit(1).truncationMode(.tail).minimumScaleFactor(0.8)
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
                Text(ui.artist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Unknown" : ui.artist)
                    .font(.system(size: 14)).foregroundColor(SFColor.dim).lineLimit(1).truncationMode(.tail)
                    .multilineTextAlignment(.center).frame(maxWidth: .infinity)
                    .padding(.top, 4).padding(.bottom, 8)

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
                    roundIcon("backward.end.fill") { engine.previousTrack() }.accessibilityLabel("Предыдущий трек")
                    Spacer()
                    Button { engine.togglePlay() } label: {
                        Image(systemName: ui.playing ? "pause.fill" : "play.fill").font(.system(size: 30)).foregroundColor(.white)
                            .frame(width: 76, height: 76).background(Circle().fill(SFColor.accent))
                    }
                    .buttonStyle(PressStyle())
                    Spacer()
                    roundIcon("forward.end.fill") { engine.nextTrack() }.accessibilityLabel("Следующий трек")
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
            if request > 0 {
                if audioBrowserRoot != nil { showingAudioBrowser = true }
                else { showingAudioImporter = true }
            }
        }
        .onChange(of: queueExpanded) { expanded in engine.setQueueExpanded(expanded) }
        .fileImporter(isPresented: $showingAudioImporter, allowedContentTypes: [.folder], allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let root = urls.first {
                let access = root.startAccessingSecurityScopedResource()
                if let data = try? root.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil, relativeTo: nil) {
                    UserDefaults.standard.set(data, forKey: "sf.audioBrowserRoot")
                }
                if access { root.stopAccessingSecurityScopedResource() }
                audioBrowserRoot = root
                DispatchQueue.main.async { showingAudioBrowser = true }
            }
        }
        .sheet(isPresented: $showingAudioBrowser) {
            if let root = audioBrowserRoot {
                AudioBrowserView(root: root, onChangeRoot: {
                    DispatchQueue.main.async { showingAudioImporter = true }
                }, onImport: { engine.addAudioFiles($0, securityRoot: root) })
            }
        }
        .alert("Disconnect \((ui.peerName.isEmpty ? "Phone" : String(ui.peerName.prefix(7))))?", isPresented: $confirmDisconnect) {
            Button("No", role: .cancel) {}
            Button("Yes", role: .destructive) { engine.disconnect() }
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
        return ""
    }

    private var trackStatusSlot: some View {
        Text(trackStatusText)
            .font(.system(size: 12)).foregroundColor(SFColor.dim)
            .lineLimit(1).truncationMode(.tail)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).frame(height: 28)
            .accessibilityHidden(trackStatusText.isEmpty)
    }

    private var queueSection: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button { engine.requestTrackPicker() } label: {
                    Image(systemName: "text.badge.plus").frame(width: 40, height: 40)
                }.accessibilityLabel("Добавить музыку")
            }
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
        let text = est.map { String(format: "⚡ L/R: ±%.1f мс : RTT %.0f мс : буф. %.0f мс", $0, p.rttMs, p.bufferMs) }
            ?? "⚡ Синхронизация часов…"
        return Button(action: openDiagnostics) {
            Text(text).font(.system(size: 13)).foregroundColor((est ?? 99) < 2 ? SFColor.good : SFColor.warn)
                .lineLimit(1).truncationMode(.tail).minimumScaleFactor(0.72)
                .frame(maxWidth: .infinity).frame(height: 36)
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

/// A single folder/file browser. No selected files means importing the folder being viewed.
private struct AudioBrowserView: View {
    let root: URL
    let onChangeRoot: () -> Void
    let onImport: ([URL]) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var path: [URL] = []
    @State private var entries: [Entry] = []
    @State private var selected = Set<URL>()
    @State private var loading = false
    @State private var error: String?
    @State private var scoped = false
    private var current: URL { path.last ?? root }
    private struct Entry: Identifiable {
        let url: URL
        let directory: Bool
        var id: URL { url }
    }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack {
                    Button("‹ Назад") { selected.removeAll(); path.removeLast() }.disabled(path.isEmpty)
                    Spacer()
                    Text(current.lastPathComponent).lineLimit(1)
                }.padding()
                if loading { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
                else if let error { Text(error).foregroundColor(SFColor.warn).padding().frame(maxHeight: .infinity) }
                else {
                    List(entries) { entry in
                        Button {
                            if entry.directory { selected.removeAll(); path.append(entry.url) }
                            else if selected.contains(entry.url) { selected.remove(entry.url) }
                            else { selected.insert(entry.url) }
                        } label: {
                            HStack {
                                Image(systemName: entry.directory ? "folder" : selected.contains(entry.url) ? "checkmark.circle.fill" : "circle")
                                    .foregroundColor(SFColor.accent1).frame(width: 26)
                                Text(entry.url.lastPathComponent).foregroundColor(SFColor.text).lineLimit(2)
                                Spacer()
                                if entry.directory { Image(systemName: "chevron.right").foregroundColor(SFColor.dim) }
                            }
                        }.buttonStyle(.plain)
                    }.listStyle(.plain)
                    if entries.isEmpty { Text("В папке нет аудиофайлов").foregroundColor(SFColor.dim).padding() }
                }
                Button(selected.isEmpty ? "Добавить папку" : "Добавить (\(selected.count))") {
                    onImport(selected.isEmpty ? [current] : entries.filter { selected.contains($0.url) }.map(\.url))
                    dismiss()
                }.buttonStyle(.borderedProminent).disabled(loading || error != nil).padding()
            }
            .navigationTitle("Добавить музыку").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { Button("Расположение") { dismiss(); onChangeRoot() } }
            }
            .task(id: current) { await load() }
            .onAppear { scoped = root.startAccessingSecurityScopedResource() }
            .onDisappear { if scoped { root.stopAccessingSecurityScopedResource(); scoped = false } }
        }.preferredColorScheme(.dark)
    }
    @MainActor private func load() async {
        loading = true; error = nil; entries = []; selected.removeAll()
        let folder = current
        let result = await Task.detached { () -> Result<[Entry], Error> in
            Result {
                let urls = try FileManager.default.contentsOfDirectory(at: folder,
                    includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey, .contentTypeKey], options: [.skipsHiddenFiles])
                return urls.compactMap { url -> Entry? in
                    guard let v = try? url.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey, .contentTypeKey]) else { return nil }
                    let dir = v.isDirectory == true
                    guard dir || (v.isRegularFile == true && (v.contentType?.conforms(to: .audio) == true ||
                        ["aac","aif","aiff","alac","amr","flac","m4a","m4b","mp3","oga","ogg","opus","wav","wave","weba","wma"].contains(url.pathExtension.lowercased()))) else { return nil }
                    return Entry(url: url, directory: dir)
                }.sorted { a, b in
                    if a.directory != b.directory { return a.directory }
                    return a.url.lastPathComponent.localizedStandardCompare(b.url.lastPathComponent) == .orderedAscending
                }
            }
        }.value
        guard !Task.isCancelled, folder == current else { return }
        switch result {
        case .success(let items): entries = items
        case .failure: error = "Не удалось открыть папку. Выберите расположение заново."
        }
        loading = false
    }
    static func restoredRoot() -> URL? {
        guard let data = UserDefaults.standard.data(forKey: "sf.audioBrowserRoot") else { return nil }
        var stale = false
        return try? URL(resolvingBookmarkData: data, options: [.withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale)
    }
}
