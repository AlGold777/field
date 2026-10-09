import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct PlayerView: View {
    let ui: UiState
    let engine: Engine
    let openCalibration: () -> Void
    let openDiagnostics: () -> Void
    let openPairing: () -> Void

    @State private var showingEqualizer = false
    @State private var showingSpatial = false
    @AppStorage("sf.soundExpanded") private var soundExpanded = true
    @State private var pos: Int64 = 0
    @State private var dragging: Double? = nil
    @State private var seekHoldFraction: Double? = nil
    @State private var seekHoldUntil = Date.distantPast
    @State private var confirmDisconnect = false
    @State private var confirmCancelTransfer = false
    @State private var showConnectChooser = false
    @State private var selectingTracks = false
    @State private var selectedTracks = Set<String>()
    @State private var showPlaylists = false
    @State private var showPlaylistName = false
    @State private var playlistName = ""
    @State private var editingPlaylist: PlaylistInfo?
    @State private var playlistToOpen: PlaylistInfo?
    @State private var playlistToDelete: PlaylistInfo?
    @State private var master = 0.8
    @State private var masterDragging = false
    @State private var balance = 0.5
    @State private var balanceDragging = false
    @State private var balanceWasInCenter = true
    @AppStorage("sf.queueExpanded") private var queueExpanded = false
    @State private var showingAudioImporter = false
    @State private var audioBrowserRoot: URL? = AudioBrowserView.restoredRoot()
    @State private var showingAudioBrowser = false
    @State private var changingAudioRoot = false
    private let timer = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                topControls
                Color.clear.frame(height: 24)

                stage
                Text("Чтобы поменять L и R, удерживайте кружок")
                    .font(.system(size: 12)).foregroundColor(SFColor.dim)
                    .frame(maxWidth: .infinity).opacity(ui.soloMode ? 0 : 1).padding(.bottom, 12)

                syncIndicator.opacity(ui.soloMode ? 0 : 1).allowsHitTesting(!ui.soloMode).padding(.bottom, 20)

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
                // После отпускания держим позицию перемотки, пока движок не догонит её (или 300 мс)
                let held = Date() < seekHoldUntil ? seekHoldFraction : nil
                Slider(value: Binding(get: { dragging ?? held ?? min(1, Double(pos) / dur) }, set: { dragging = $0 }),
                       in: 0...1, onEditingChanged: { editing in
                           if !editing, let d = dragging {
                               engine.seekMs(Int64(d * dur))
                               seekHoldFraction = d; seekHoldUntil = Date().addingTimeInterval(0.3)
                               dragging = nil
                           }
                       })
                    .tint(SFColor.accent1)
                HStack {
                    Text(fmtTime(dragging.map { Int64($0 * dur) } ?? held.map { Int64($0 * dur) } ?? pos))
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
                    .accessibilityLabel(ui.playing ? "Пауза" : "Играть")
                    Spacer()
                    roundIcon("forward.end.fill") { engine.nextTrack() }.accessibilityLabel("Следующий трек")
                    Spacer()
                }
                .padding(.vertical, 18)

                queueSection

                SFCard {
                    Button { soundExpanded.toggle() } label: {
                        HStack { Text("Sound").font(.system(size:13,weight:.semibold)).kerning(1); Spacer()
                            Image(systemName:soundExpanded ? "chevron.up" : "chevron.down") }.foregroundColor(SFColor.dim)
                    }.buttonStyle(.plain).padding(.bottom,8)
                    if soundExpanded {
                    SliderRow(label: "Громкость", valueText: "\(Int(master * 100))%", value: $master, color: SFColor.master,
                              enabled: ui.mode != .levelMeasure,
                              onEditingChanged: { masterDragging = $0 },
                              onEditingEnded: { engine.setVolume("M", Float(master), finished: true) })
                        .onChange(of: master) { if abs($0 - Double(ui.master)) > 0.004 { engine.setVolume("M", Float($0), finished: false) } }
                    if !ui.soloMode {
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
                    HStack {
                        Button { showingEqualizer = true } label: {
                            Label(ui.eq.enabled ? "EQ · \(ui.eq.bypass ? "Bypass" : ui.eq.preset)" : "EQ · Off",systemImage:"slider.vertical.3")
                        }.disabled(!ui.eqAvailable)
                        Spacer()
                        Button { showingSpatial = true } label: { Image(systemName:"slider.horizontal.3") }.accessibilityLabel("Настройки пространства")
                            .disabled(!ui.spatialAvailable)
                    }.font(.system(size:13)).foregroundColor(SFColor.accent1)
                    HStack {
                        Text("Пространство").font(.system(size:13)).foregroundColor(SFColor.dim)
                        Spacer()
                        ForEach(["off","soft","strong"],id: \.self) { level in
                            Button(level.capitalized) {
                                var settings = ui.spatial; settings.level = level; settings.enabled = level != "off"
                                engine.setSpatial(settings)
                            }.font(.system(size:12,weight:.semibold)).padding(.horizontal,9).padding(.vertical,7)
                                .background(Capsule().fill(ui.spatial.level == level ? SFColor.accent1.opacity(0.3) : SFColor.secondary))
                                .disabled(!ui.spatialAvailable)
                                .onLongPressGesture(minimumDuration:0.5) { showingSpatial = true }
                        }
                    }
                    }
                }

                HStack(spacing: 8) {
                    if !ui.soloMode { PlayerActionButton(title: "Калибровка", action: openCalibration) }
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
        .overlay {
            if showConnectChooser {
                ZStack {
                    Color.black.opacity(0.65).ignoresSafeArea().onTapGesture { showConnectChooser = false }
                    connectChooser
                }
            }
        }
        .onReceive(timer) { _ in
            pos = engine.positionMs()
            if let h = seekHoldFraction, abs(Double(pos) - h * Double(max(1, ui.durationMs))) < 1000 { seekHoldFraction = nil }
        }
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
        .sheet(isPresented: $showingEqualizer) { EqualizerControls(settings:ui.eq) { engine.setEq($0) } }
        .sheet(isPresented: $showingSpatial) { SpatialControls(settings:ui.spatial,solo:ui.soloMode) { engine.setSpatial($0) } }
        .sheet(isPresented: $showPlaylists) { playlistsView }
        .sheet(isPresented: $showingAudioBrowser, onDismiss: {
            if changingAudioRoot { changingAudioRoot = false; showingAudioImporter = true }
        }) {
            if let root = audioBrowserRoot {
                AudioBrowserView(root: root, onChangeRoot: {
                    changingAudioRoot = true
                }, onImport: { engine.addAudioFiles($0, securityRoot: root) })
            }
        }
        .alert("Отменить передачу?", isPresented: $confirmCancelTransfer) {
            Button("Продолжить", role: .cancel) {}
            Button("Отменить передачу", role: .destructive) { engine.cancelFileTransfer() }
        }
        .alert("Disconnect \((ui.peerName.isEmpty ? "Phone" : String(ui.peerName.prefix(7))))?", isPresented: $confirmDisconnect) {
            Button("No", role: .cancel) {}
            Button("Yes", role: .destructive) { engine.disconnect() }
        }
    }

    private func connectAction() {
        if ui.inPair { confirmDisconnect = true }
        else if ui.remembered != nil { engine.resumeRemembered() }
        else { showConnectChooser = true }
    }

    private var topControls: some View {
        HStack {
            StatusPill(text: ui.soloMode ? "Connect" : ui.phase == .reconnecting ? "Reconnecting…" : "Active",
                       color: ui.soloMode ? SFColor.accent1 : ui.phase == .reconnecting ? SFColor.warn : SFColor.good)
                .frame(width: 104, height: 36).contentShape(Rectangle())
                .gesture(LongPressGesture(minimumDuration: 0.5).exclusively(before: TapGesture()).onEnded { gesture in
                    switch gesture {
                    case .first: if ui.soloMode { showConnectChooser = true }
                    case .second: connectAction()
                    }
                })
                .highPriorityGesture(DragGesture(minimumDistance: 12).onEnded { drag in
                    if ui.soloMode && drag.translation.height >= 32 && drag.translation.height > abs(drag.translation.width) {
                        showConnectChooser = true
                    }
                })
                .accessibilityAddTraits(.isButton).accessibilityAction { connectAction() }
            Spacer()
            Badge(text: ui.soloMode ? "Solo" : ui.role.title, color: ui.soloMode ? SFColor.accent1 : SFColor.master)
                .frame(width: 76, height: 32).contentShape(Rectangle())
                .onLongPressGesture(minimumDuration: 0.5) {
                    if ui.soloMode && ui.remembered == nil { showConnectChooser = true }
                    else { engine.toggleSoloMode() }
                }
                .accessibilityAddTraits(.isButton)
                .accessibilityAction(named: Text(ui.soloMode ? "Восстановить пару" : "Включить Solo")) {
                    if ui.soloMode && ui.remembered == nil { showConnectChooser = true }
                    else { engine.toggleSoloMode() }
                }
        }.frame(height: 48)
    }

    private var connectChooser: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Connect").font(.system(size: 16, weight: .bold))
            if let remembered = ui.remembered {
                Text("Последний телефон").font(.system(size: 9)).foregroundColor(SFColor.dim)
                HStack { Spacer(); phoneTile(remembered.peerName) { showConnectChooser = false; engine.resumeRemembered() }; Spacer() }
            }
            Text("Доступные телефоны").font(.system(size: 9)).foregroundColor(SFColor.dim)
            let nearby = ui.nearby.filter { $0.id != ui.remembered?.peerId }
            if nearby.isEmpty {
                HStack { ProgressView(); Text("Ищем телефоны…").font(.system(size: 13)) }.padding(.vertical, 12)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 8) {
                        ForEach(nearby) { peer in phoneTile(peer.name) { showConnectChooser = false; engine.invite(peer) } }
                    }
                }
            }
        }.padding(12).frame(width: 280)
            .background(Color(red: 0.098, green: 0.153, blue: 0.278), in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(SFColor.cardBorder, lineWidth: 1))
    }

    private func phoneTile(_ name: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: "iphone").font(.system(size: 44, weight: .ultraLight)).foregroundStyle(SFColor.accent)
                    .frame(width: 42, height: 58)
                Text(name).font(.system(size: 11)).lineLimit(2).multilineTextAlignment(.center).foregroundColor(SFColor.text)
            }.frame(width: 72)
        }.buttonStyle(.plain)
    }

    private func syncVolumes() {
        if !masterDragging && abs(master - Double(ui.master)) > 0.005 { master = Double(ui.master) }
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
        if !ui.fileTransferText.isEmpty { return ui.fileTransferText }
        if ui.mode != .music {
            switch ui.mode {
            case .metronome: return "🥁 Метроном для ручной подстройки"
            case .lrtest: return "🔈 Проверка каналов: сначала L, потом R"
            case .level: return "Уровень: L и R звучат по очереди"
            case .center: return "Проверка центра"
            case .levelMeasure: return "Измерение микрофонами"
            default: return "🎯 Идёт калибровка"
            }
        }
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
            .contentShape(Rectangle()).onTapGesture {
                if ui.role == .point && !ui.fileTransferText.isEmpty { confirmCancelTransfer = true }
            }
    }

    private var queueSection: some View {
        VStack(spacing: 8) {
            HStack(spacing: 4) {
                Button { queueExpanded.toggle() } label: {
                    HStack(spacing: 5) {
                        Text("ОЧЕРЕДЬ · \(ui.queueTracks.count)").font(.system(size: 12, weight: .semibold)).tracking(1)
                        Image(systemName: queueExpanded ? "chevron.up" : "chevron.down").font(.system(size: 11))
                    }.foregroundColor(SFColor.dim)
                }.buttonStyle(.plain)
                Spacer(minLength: 0)
                queueIcon("text.badge.plus", "Добавить музыку", enabled: ui.role == .server || ui.fileTransferAvailable) { engine.requestTrackPicker() }
                queueIcon("checklist", "Выбрать треки", enabled: !ui.queueTracks.isEmpty) {
                    selectingTracks.toggle(); selectedTracks.removeAll(); if selectingTracks { queueExpanded = true }
                }
                queueIcon("music.note.list", "Плейлисты", enabled: ui.role == .server) { showPlaylists = true }
            }
            if queueExpanded {
                if ui.queueTracks.isEmpty {
                    Text("Добавьте музыку значком в меню очереди").font(.system(size: 13)).foregroundColor(SFColor.dim)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 2) {
                            ForEach(Array(ui.queueTracks.enumerated()), id: \.element.id) { index, track in
                                let current = index == ui.queueIndex || track.id == ui.loadingTrackId
                                HStack(spacing: 8) {
                                    Button {
                                        if selectingTracks {
                                            if selectedTracks.contains(track.id) { selectedTracks.remove(track.id) }
                                            else { selectedTracks.insert(track.id) }
                                        } else { engine.selectQueueTrack(track.id) }
                                    } label: {
                                        HStack {
                                            if selectingTracks {
                                                Image(systemName: selectedTracks.contains(track.id) ? "checkmark.circle.fill" : "circle").foregroundColor(SFColor.accent1)
                                            } else { Text("\(index + 1).").foregroundColor(SFColor.dim).frame(width: 25) }
                                            Text(track.title).lineLimit(1)
                                            if !track.artist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                                Text("· \(track.artist)").foregroundColor(SFColor.dim).lineLimit(1)
                                            }
                                            Spacer(minLength: 0)
                                            if current { Image(systemName: "music.note").foregroundColor(SFColor.accent1) }
                                        }.frame(maxWidth: .infinity, alignment: .leading)
                                    }.buttonStyle(.plain).disabled(ui.loadingTracks && !selectingTracks)
                                    if selectingTracks {
                                        Button { engine.removeTracks([track.id]) } label: { Image(systemName: "xmark").foregroundColor(.red) }
                                            .accessibilityLabel("Удалить \(track.title)")
                                    }
                                }.font(.system(size: 12)).padding(9)
                                    .background(current ? SFColor.accent1.opacity(0.16) : .clear, in: RoundedRectangle(cornerRadius: 9))
                            }
                        }
                    }.frame(maxHeight: 230)
                }
                if selectingTracks {
                    HStack {
                        Button("Отмена") { selectingTracks = false; selectedTracks.removeAll() }
                        Button(selectedTracks.count == ui.queueTracks.count ? "Снять все" : "Выбрать все") {
                            selectedTracks = selectedTracks.count == ui.queueTracks.count ? [] : Set(ui.queueTracks.map(\.id))
                        }
                        Spacer(minLength: 0)
                        Button("Удалить (\(selectedTracks.count))", role: .destructive) {
                            engine.removeTracks(selectedTracks); selectedTracks.removeAll(); selectingTracks = false
                        }.disabled(selectedTracks.isEmpty)
                    }.font(.system(size: 11))
                }
            }
            if !ui.undoText.isEmpty {
                HStack {
                    Text(ui.undoText).font(.system(size: 11)).foregroundColor(SFColor.dim).lineLimit(1)
                    Spacer(); Button("Вернуть") { engine.undoRemove() }.font(.system(size: 12))
                }
            }
        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
            .background(SFColor.card, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(SFColor.cardBorder, lineWidth: 1)).padding(.bottom, 16)
            .onChange(of: ui.queueTracks) { tracks in selectedTracks.formIntersection(Set(tracks.map(\.id))) }
    }
    private func queueIcon(_ symbol: String, _ label: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).frame(width: 35, height: 35) }
            .buttonStyle(.plain).accessibilityLabel(label).disabled(!enabled)
    }
    private var playlistsView: some View {
        NavigationStack {
            List {
                Button("Сохранить текущую очередь…") { playlistName = ""; editingPlaylist = nil; showPlaylistName = true }
                    .disabled(ui.queueTracks.isEmpty)
                ForEach(ui.playlists) { playlist in
                    Button { playlistToOpen = playlist } label: {
                        HStack { Text(playlist.name); Spacer(); Text("\(playlist.count)").foregroundColor(SFColor.dim) }
                    }.contextMenu {
                        Button("Переименовать") { editingPlaylist = playlist; playlistName = playlist.name; showPlaylistName = true }
                        Button("Удалить", role: .destructive) { playlistToDelete = playlist }
                    }
                }
            }.navigationTitle("Плейлисты").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { showPlaylists = false } } }
                .alert(editingPlaylist == nil ? "Новый плейлист" : "Переименовать плейлист", isPresented: $showPlaylistName) {
                    TextField("Название", text: $playlistName)
                    Button("Отмена", role: .cancel) {}
                    Button("Сохранить") {
                        if let playlist = editingPlaylist { engine.renamePlaylist(playlist.id, name: playlistName) }
                        else { engine.savePlaylist(playlistName) }
                    }.disabled(playlistName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .confirmationDialog("Открыть плейлист", isPresented: Binding(get: { playlistToOpen != nil }, set: { if !$0 { playlistToOpen = nil } })) {
                    if let playlist = playlistToOpen {
                        Button("Добавить в конец") { engine.openPlaylist(playlist.id, append: true); showPlaylists = false }
                        Button("Заменить очередь") { engine.openPlaylist(playlist.id, append: false); showPlaylists = false }
                    }
                    Button("Отмена", role: .cancel) {}
                }
                .alert("Удалить плейлист?", isPresented: Binding(get: { playlistToDelete != nil }, set: { if !$0 { playlistToDelete = nil } })) {
                    Button("Отмена", role: .cancel) {}
                    Button("Удалить", role: .destructive) { if let playlist = playlistToDelete { engine.deletePlaylist(playlist.id) } }
                }
        }.preferredColorScheme(.dark)
    }

    private var stage: some View {
        let serverCh = Ch.other(ui.pointChannel)
        let leftIsMe = ui.myChannel == Ch.L
        func role(_ ch: Int) -> String { ch == serverCh ? "Server" : "Point" }
        return HStack {
            SpeakerCircle(label: "L", ch: Ch.L, playing: ui.playing, caption: ui.soloMode ? "MyPhone" : leftIsMe ? "Этот телефон" : ui.peerName,
                          sub: ui.soloMode ? "" : role(Ch.L), highlight: ui.soloMode || leftIsMe) { if !ui.soloMode { engine.swapChannels() } }
            Rectangle().fill(SFColor.accent).frame(width: 40, height: 2)
                .frame(width: 48, height: 44)
                .contentShape(Rectangle())
                .onLongPressGesture(minimumDuration: 0.5) { if !ui.soloMode { engine.swapChannels() } }
                .offset(y: -20)
                .accessibilityLabel("Поменять каналы L и R")
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { if !ui.soloMode { engine.swapChannels() } }
            SpeakerCircle(label: "R", ch: Ch.R, playing: ui.playing, caption: ui.soloMode ? "MyPhone" : leftIsMe ? ui.peerName : "Этот телефон",
                          sub: ui.soloMode ? "" : role(Ch.R), highlight: ui.soloMode || !leftIsMe) { if !ui.soloMode { engine.swapChannels() } }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 28)
    }

    private var syncIndicator: some View {
        let p = ui.pointDiag, s = ui.serverDiag
        let est: Double? = (p.valid && s.valid && p.clockReady && s.clockReady) ? abs(p.syncErrMs - s.syncErrMs) + p.clockUncMs : nil
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
            .onDisappear { if scoped { root.stopAccessingSecurityScopedResource(); scoped = false } }
        }.preferredColorScheme(.dark)
    }
    @MainActor private func load() async {
        if !scoped { scoped = root.startAccessingSecurityScopedResource() }
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


private struct EqualizerControls: View {
    @State var settings: EqSettings
    let onChange: (EqSettings) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Toggle("Эквалайзер",isOn:$settings.enabled).onChange(of:settings.enabled) { _ in onChange(settings) }
                Toggle("Bypass",isOn:$settings.bypass).onChange(of:settings.bypass) { _ in onChange(settings) }
                Picker("Пресет",selection:$settings.preset) {
                    ForEach(EqSettings.presets.map { $0.0 },id: \.self) { Text($0).tag($0) }
                    Text("Свой").tag("Свой")
                }.onChange(of:settings.preset) { name in
                    if let preset = EqSettings.presets.first(where: { $0.0 == name }) { settings.gains = preset.1; onChange(settings) }
                }
                // The line visualizes the selected band gains, not a fabricated live analyzer.
                Canvas { context,size in
                    var path = Path()
                    for i in 0..<10 {
                        let point = CGPoint(x:size.width*Double(i)/9,y:size.height*(0.5-Double(settings.gains[i])/30))
                        if i == 0 { path.move(to:point) } else { path.addLine(to:point) }
                    }
                    context.stroke(path,with:.color(SFColor.accent1),lineWidth:2)
                }.frame(height:90)
                ForEach(0..<10,id: \.self) { i in
                    HStack {
                        Text(bandName(i)).frame(width:42,alignment:.leading).font(.system(size:12))
                        Slider(value:Binding(get:{Double(settings.gains[i])},set:{ settings.moveBand(i,to:Float($0)) }),in:-12...12,step:0.5,
                               onEditingChanged:{ editing in if !editing { onChange(settings) } })
                        Text(String(format:"%+.1f",settings.gains[i])).font(.system(size:12)).monospacedDigit().frame(width:40)
                    }
                }
                Picker("Магнит",selection:$settings.magnet) {
                    Text("Off").tag("OFF"); Text("Soft").tag("SOFT"); Text("Strong").tag("STRONG")
                }.onChange(of:settings.magnet) { _ in onChange(settings) }
                Toggle("Сохранять громкость",isOn:$settings.preserveLoudness).onChange(of:settings.preserveLoudness) { _ in onChange(settings) }
                Button("Сбросить полосы") { settings.gains = Array(repeating:0,count:10); settings.preset = "Ровно"; onChange(settings) }
            }.navigationTitle("Эквалайзер").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement:.confirmationAction) { Button("Готово") { onChange(settings); dismiss() } } }
        }.preferredColorScheme(.dark)
    }
    private func bandName(_ i: Int) -> String { let f = EqSettings.frequencies[i]; return f >= 1000 ? "\(Int(f/1000))k" : "\(Int(f))" }
}

private struct SpatialControls: View {
    @State var settings: SpatialSettings
    let solo: Bool
    let onChange: (SpatialSettings) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Picker("Интенсивность",selection:$settings.level) {
                    Text("Off").tag("off"); Text("Soft").tag("soft"); Text("Strong").tag("strong")
                }.pickerStyle(.segmented).onChange(of:settings.level) { level in settings.enabled = level != "off"; onChange(settings) }
                if !solo {
                    Toggle("Телефоны близко",isOn:Binding(get:{settings.profile == "pair_near"},set:{ near in
                        settings.profile = near ? "pair_near" : "pair_wide"; onChange(settings)
                    }))
                }
                parameter("Ширина",value:$settings.width,range:0...8)
                parameter("Воздух",value:$settings.air,range:0...8)
                parameter("Окружение",value:$settings.ambient,range:0...6)
                parameter("Глубина",value:$settings.depth,range:-6...0)
                parameter("Ранние отражения",value:$settings.reflection,range:-30 ... -10)
                Toggle("Сохранять громкость",isOn:$settings.preserveLoudness).onChange(of:settings.preserveLoudness) { _ in onChange(settings) }
                Button("Сбросить настройки") {
                    let level = settings.level
                    settings = SpatialSettings(); settings.profile = solo ? "solo_stereo_speaker" : "pair_wide"
                    settings.level = level; settings.enabled = level != "off"; onChange(settings)
                }
            }.navigationTitle("Пространство").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement:.confirmationAction) { Button("Готово") { onChange(settings); dismiss() } } }
        }.preferredColorScheme(.dark)
    }
    private func parameter(_ label: String,value:Binding<Double>,range:ClosedRange<Double>) -> some View {
        VStack {
            HStack { Text(label); Spacer(); Text(String(format:"%+.1f дБ",value.wrappedValue)).monospacedDigit() }.font(.system(size:14))
            Slider(value:value,in:range,step:0.5,onEditingChanged:{ editing in if !editing { onChange(settings) } })
        }
    }
}
