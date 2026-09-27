import SwiftUI
import UniformTypeIdentifiers

struct PlayerView: View {
    let ui: UiState
    let engine: Engine
    let openCalibration: () -> Void
    let openDiagnostics: () -> Void

    @State private var pos: Int64 = 0
    @State private var dragging: Double? = nil
    @State private var confirmDisconnect = false
    @State private var master = 0.8
    @State private var volL = 1.0
    @State private var volR = 1.0
    @AppStorage("sf.queueExpanded") private var queueExpanded = false
    @State private var showingAudioSourceDialog = false
    @State private var showingAudioFiles = false
    @State private var showingAudioFolder = false
    private let timer = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    if ui.phase == .reconnecting && ui.role == .point { StatusPill(text: "Переподключение…", color: SFColor.warn) }
                    else if ui.phase == .reconnecting { StatusPill(text: "Ждём «\(ui.peerName)»", color: SFColor.warn) }
                    else { StatusPill(text: "Стереопара активна", color: SFColor.good) }
                    Spacer()
                    Badge(text: ui.role == .server ? "SERVER" : "POINT", color: SFColor.master)
                }

                stage

                syncIndicator.padding(.bottom, 20)

                if ui.mode != .music {
                    SFCard {
                        Text(ui.mode == .metronome ? "🥁 Метроном для ручной подстройки"
                             : ui.mode == .lrtest ? "🔈 Проверка каналов: сначала L, потом R" : "🎯 Идёт калибровка")
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
                    H3(text: "Громкость")
                    SliderRow(label: "Общая (оба канала)", valueText: "\(Int(master * 100))%", value: $master, color: SFColor.master)
                        .onChange(of: master) { if abs($0 - Double(ui.master)) > 0.004 { engine.setVolume("M", Float($0)) } }
                    SliderRow(label: "Левый канал · L", valueText: "\(Int(volL * 100))%", value: $volL, color: SFColor.left)
                        .onChange(of: volL) { if abs($0 - Double(ui.volL)) > 0.004 { engine.setVolume("L", Float($0)) } }
                    SliderRow(label: "Правый канал · R", valueText: "\(Int(volR * 100))%", value: $volR, color: SFColor.right)
                        .onChange(of: volR) { if abs($0 - Double(ui.volR)) > 0.004 { engine.setVolume("R", Float($0)) } }
                }

                SecondaryButton(title: "🎯  Калибровка задержки") { openCalibration() }
                SecondaryButton(title: "📊  Диагностика пары") { openDiagnostics() }
                DangerButton(title: "⛓  Разорвать пару") { confirmDisconnect = true }
            }
            .padding(.horizontal, 20).padding(.vertical, 24)
        }
        .onReceive(timer) { _ in pos = engine.positionMs() }
        .onAppear { syncVolumes(); engine.setQueueExpanded(queueExpanded) }
        .onChange(of: ui.master) { _ in syncVolumes() }
        .onChange(of: ui.volL) { _ in syncVolumes() }
        .onChange(of: ui.volR) { _ in syncVolumes() }
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
        .alert("Разорвать пару?", isPresented: $confirmDisconnect) {
            Button("Разорвать", role: .destructive) { engine.disconnect() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Воспроизведение остановится на обоих телефонах. Пара сохранится — её можно быстро подключить снова.")
        }
    }

    private func syncVolumes() {
        if abs(master - Double(ui.master)) > 0.005 { master = Double(ui.master) }
        if abs(volL - Double(ui.volL)) > 0.005 { volL = Double(ui.volL) }
        if abs(volR - Double(ui.volR)) > 0.005 { volR = Double(ui.volR) }
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
