import AVFoundation

/// Запись микрофона для акустической калибровки (отдельный AVAudioEngine, режим .measurement — без AGC/AEC).
final class MicRecorder {
    struct Recording { let samples: [Float]; let sampleRate: Int; let startNs: Int64 }

    static var permissionGranted: Bool {
        AVAudioSession.sharedInstance().recordPermission == .granted
    }

    static func requestPermission(_ done: @escaping (Bool) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { ok in DispatchQueue.main.async { done(ok) } }
    }

    /// Асинхронная запись: onStarted — после первого буфера; completion — по окончании.
    func record(durationSec: Double, onStarted: @escaping () -> Void,
                completion: @escaping (Result<Recording, Error>) -> Void) {
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let fmt = input.outputFormat(forBus: 0)
        let sr = Int(fmt.sampleRate)
        guard sr > 0, fmt.channelCount > 0 else {
            completion(.failure(NSError(domain: "SoundField", code: 1, userInfo: [NSLocalizedDescriptionKey: "Микрофон недоступен"])))
            return
        }
        let total = Int(Double(sr) * durationSec)
        var out = [Float](); out.reserveCapacity(total)
        var startNs: Int64 = 0
        var finished = false
        let lock = NSLock()
        input.installTap(onBus: 0, bufferSize: 4096, format: fmt) { buf, time in
            lock.lock(); defer { lock.unlock() }
            guard !finished, let ch = buf.floatChannelData?[0] else { return }
            let n = Int(buf.frameLength)
            if out.isEmpty {
                startNs = time.isHostTimeValid ? MonoClock.ns(fromHost: time.hostTime) : MonoClock.nowNs() - Int64(Double(n) / Double(sr) * 1e9)
                DispatchQueue.main.async(execute: onStarted)
            }
            let take = min(n, total - out.count)
            out.append(contentsOf: UnsafeBufferPointer(start: ch, count: take))
            if out.count >= total {
                finished = true
                let rec = Recording(samples: out, sampleRate: sr, startNs: startNs)
                DispatchQueue.main.async {
                    input.removeTap(onBus: 0); engine.stop()
                    completion(.success(rec))
                }
            }
        }
        do {
            engine.prepare()
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            completion(.failure(error))
            return
        }
        // страховка: если буферы не пришли
        DispatchQueue.main.asyncAfter(deadline: .now() + durationSec + 3) {
            lock.lock(); let done = finished; finished = true; let got = out; lock.unlock()
            if !done {
                input.removeTap(onBus: 0); engine.stop()
                // Частичная запись не считается успехом: Engine покажет причину из описания ошибки.
                let message = got.isEmpty ? "Нет данных с микрофона" : "Запись с микрофона прервана: получено \(got.count * 100 / max(total, 1))% нужной длины"
                completion(.failure(NSError(domain: "SoundField", code: 2, userInfo: [NSLocalizedDescriptionKey: message])))
            }
        }
    }
}
