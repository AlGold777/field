import AVFoundation

/// Аудиовыход: AVAudioEngine + AVAudioSourceNode (стерео, float, 48 кГц).
///
/// Момент выхода первого сэмпла блока из динамика:
///   present = hostTime(timestamp) + outputLatency
/// Этот момент передаётся RenderSource (Server/Point), который выбирает кадр общей шкалы.
/// Разница между моделями телефонов, не учтённая системой, убирается акустической калибровкой.
final class AudioOutput {
    private let engine = AVAudioEngine()
    private var node: AVAudioSourceNode?
    private let equalizer = AVAudioUnitEQ(numberOfBands: 10)
    private let sourceBox = Locked<RenderSource?>(nil)
    private let outLatencyNs = Locked<Int64>(0)
    private(set) var running = false
    var onInterruption: ((Bool) -> Void)?     // true = началось
    var onRouteWarning: ((String) -> Void)?
    var onRouteChanged: (() -> Void)?
    private var routeSignature: String?

    private(set) var latencyMs = 0.0
    // Счётчик пишет только реалтайм-callback (без блокировок и без self); читается на главном потоке.
    private let underrunCounter: UnsafeMutablePointer<Int64> = {
        let p = UnsafeMutablePointer<Int64>.allocate(capacity: 1)
        p.initialize(to: 0)
        return p
    }()
    private(set) var underruns: Int64 {       // на iOS система не отдаёт счётчик — считаем отсутствие hostTime
        get { underrunCounter.pointee }
        set { underrunCounter.pointee = newValue }
    }

    var source: RenderSource? {
        get { sourceBox.value }
        set { sourceBox.value = newValue }
    }

    deinit { underrunCounter.deallocate() }

    init() {
        let nc = NotificationCenter.default
        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            self?.handleInterruption(n)
        }
        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
            self?.handleRouteChange(n)
        }
        nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self, self.running else { return }
            // Старый source node остаётся в графе движка: отсоединяем, иначе рендер идёт дважды.
            if let old = self.node { self.engine.detach(old) }
            self.running = false; self.node = nil; self.start()
        }
        nc.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            guard let self, self.running else { return }
            self.updateLatency()
            if !self.engine.isRunning { try? self.engine.start() }
        }
    }

    /// Обычный режим — воспроизведение. Категория .playback: звук не глушится переключателем «бесшумно»
    /// и продолжается в фоне (UIBackgroundModes = audio).
    static func configurePlaybackSession() {
        let s = AVAudioSession.sharedInstance()
        do {
            try s.setCategory(.playback, mode: .default, options: [])
            try s.setPreferredSampleRate(Double(SF.sampleRate))
            try s.setPreferredIOBufferDuration(0.005)
            try s.setActive(true)
        } catch { NSLog("SoundField session: \(error)") }
    }

    /// Режим калибровки: запись + воспроизведение через громкий динамик, без обработки голоса.
    static func configureMeasurementSession() throws {
        let s = AVAudioSession.sharedInstance()
        try s.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetoothA2DP])
        try s.setPreferredSampleRate(Double(SF.sampleRate))
        try s.setActive(true)
        try? s.overrideOutputAudioPort(.speaker)
    }

    func updateLatency() {
        let s = AVAudioSession.sharedInstance()
        let ns = Int64(s.outputLatency * 1e9)
        outLatencyNs.value = ns
        latencyMs = (s.outputLatency + s.ioBufferDuration) * 1000
    }

    func start() {
        guard !running else { return }
        Self.configurePlaybackSession()
        updateLatency()
        let fmt = AVAudioFormat(standardFormatWithSampleRate: Double(SF.sampleRate), channels: 2)!
        if node == nil {
            let src = sourceBox
            let lat = outLatencyNs
            let underrunCount = underrunCounter
            let n = AVAudioSourceNode(format: fmt) { _, ts, frameCount, abl -> OSStatus in
                let bufs = UnsafeMutableAudioBufferListPointer(abl)
                guard let first = bufs.first, let data = first.mData else { return noErr }
                let out = data.assumingMemoryBound(to: Float.self)
                let count = Int(frameCount)
                let present: Int64
                if ts.pointee.mFlags.contains(.hostTimeValid) {
                    present = MonoClock.ns(fromHost: ts.pointee.mHostTime) + lat.value
                } else {
                    underrunCount.pointee += 1
                    present = MonoClock.nowNs() + lat.value + 5_000_000
                }
                if bufs.count >= 2, let second = bufs[1].mData {
                    let right = second.assumingMemoryBound(to: Float.self)
                    if let r = src.value { r.renderStereo(out, right, count, present) }
                    else { out.update(repeating: 0, count: count); right.update(repeating: 0, count: count) }
                } else if let r = src.value { r.render(out, count, present) }
                else { out.update(repeating: 0, count: count) }
                return noErr
            }
            engine.attach(n)
            if equalizer.engine == nil { engine.attach(equalizer) }
            engine.connect(n, to: equalizer, format: fmt)
            engine.connect(equalizer, to: engine.mainMixerNode, format: fmt)
            node = n
        }
        engine.mainMixerNode.outputVolume = 1
        engine.prepare()
        do { try engine.start(); running = true } catch { NSLog("SoundField engine: \(error)") }
    }

    /// Main-thread graph parameters; the unit owns its render state.
    func setEqualizer(_ settings: EqSettings, music: Bool) {
        let active = music && settings.enabled && !settings.bypass
        for i in 0..<10 {
            let band = equalizer.bands[i]
            band.filterType = .parametric; band.frequency = EqSettings.frequencies[i]
            band.bandwidth = 1; band.gain = settings.gains[i]; band.bypass = !active
        }
        // Conservative headroom for a cascade of boosts; no hard clipping introduced by EQ.
        let positive = settings.gains.reduce(Float(0)) { $0 + max(0,$1) }
        let mean = settings.gains.reduce(0,+) / 10
        let restore: Float = settings.preserveLoudness && positive == 0 ? min(-mean, -(settings.gains.max() ?? 0)) : 0
        equalizer.globalGain = active ? max(-96, -positive + restore) : 0
    }

    func stop() {
        guard running else { return }
        running = false
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Возврат в приложение / конец прерывания: если движок должен играть, но остановлен — запускаем снова.
    func ensureRunning() {
        guard running, !engine.isRunning else { return }
        Self.configurePlaybackSession()
        updateLatency()
        engine.prepare()
        do { try engine.start() } catch { NSLog("SoundField engine restart: \(error)") }
    }

    /// Перезапуск после смены категории сессии (калибровка).
    func restart() {
        guard running else { return }
        engine.stop()
        updateLatency()
        engine.prepare()
        startAfterRestart(retry: true)
    }

    /// Движок не запустился после restart(), даже после повтора.
    private(set) var restartFailed = false

    private func startAfterRestart(retry: Bool) {
        do {
            try engine.start()
            restartFailed = false
        } catch {
            NSLog("SoundField engine restart: \(error)")
            if retry {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                    guard let self, self.running else { return }
                    self.startAfterRestart(retry: false)
                }
            } else {
                restartFailed = true
            }
        }
    }

    private func handleInterruption(_ n: Notification) {
        guard let info = n.userInfo, let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            onInterruption?(true)
        case .ended:
            if running { try? AVAudioSession.sharedInstance().setActive(true); try? engine.start() }
            onInterruption?(false)
        @unknown default: break
        }
    }

    private func handleRouteChange(_ n: Notification) {
        updateLatency()
        let outs = AVAudioSession.sharedInstance().currentRoute.outputs
        let signature = outs.map { "\($0.portType.rawValue):\($0.uid)" }.sorted().joined(separator: "|")
        if let old = routeSignature, old != signature { onRouteChanged?() }
        routeSignature = signature
        let onSpeaker = outs.contains { $0.portType == .builtInSpeaker || $0.portType == .builtInReceiver }
        if running && !onSpeaker {
            onRouteWarning?("Звук ушёл на «\(outs.first?.portName ?? "внешнее устройство")». Для стереопары отключите наушники/Bluetooth")
        }
    }
}
