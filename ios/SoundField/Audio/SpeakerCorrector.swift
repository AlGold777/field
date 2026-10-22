import Foundation

/// Коррекция динамика (A2.4): обёртка над C-ядром sf_speaker (dsp/include/sfdsp.h, раздел «Коррекция динамика»).
///
/// Профиль из DeviceProfiles.swift переводится в цепочку биквадов: секции профиля, затем allpass-секции
/// относительно партнёрской модели; preamp профиля передаётся в ядро. Смена цепочки — кроссфейдом в ядре.
/// nil-профиль — пустая цепочка (тождественная обработка).
/// Одна инстанция: setProfile — из управляющего потока, process — из аудиопотока; process не аллоцирует и не блокирует.
final class SpeakerCorrector {
    private let handle: OpaquePointer?
    let sampleRate: Int
    let channels: Int

    init(sampleRate: Int, channels: Int) {
        self.sampleRate = sampleRate
        self.channels = channels
        handle = sf_speaker_create(Int32(sampleRate), Int32(channels))
    }

    deinit {
        if let h = handle { sf_speaker_destroy(h) }
    }

    /// Задаёт профиль устройства. [allpass] — секции фазовой коррекции для пары с партнёрской моделью
    /// (получать через ProfileTable.allpass(for:partnerKey:)); [partnerKey] — ключ этой пары, для журнала/отладки.
    /// Возвращает false, если ядро отклонило цепочку (неверные параметры или больше SF_SPEAKER_MAX_SECTIONS секций);
    /// тогда прежняя цепочка остаётся активной.
    @discardableResult
    func setProfile(_ profile: Profile?, partnerKey: String?, allpass: [AllpassSection] = []) -> Bool {
        guard let h = handle else { return false }
        guard let profile = profile else {
            return sf_speaker_set_chain(h, nil, 0, 0) == 0
        }

        var sections: [sf_speaker_section] = profile.sections.map { section in
            let type: Int32
            switch section.type {
            case .highpass: type = SF_SECTION_HIGHPASS
            case .lowshelf: type = SF_SECTION_LOWSHELF
            case .highshelf: type = SF_SECTION_HIGHSHELF
            case .peak: type = SF_SECTION_PEAK
            }
            return sf_speaker_section(type: type, f0_hz: section.f0Hz, q: section.q, gain_db: section.gainDb ?? 0)
        }
        sections += allpass.map { item in
            sf_speaker_section(type: SF_SECTION_ALLPASS, f0_hz: item.f0Hz, q: item.q, gain_db: 0)
        }

        return sections.withUnsafeBufferPointer { buf in
            sf_speaker_set_chain(h, buf.baseAddress, Int32(buf.count), profile.preampDb) == 0
        }
    }

    /// Обработка на месте, интерлив float, [frames] кадров по [channels] каналов.
    func process(_ io: UnsafeMutablePointer<Float>, frames: Int) {
        guard let h = handle, frames > 0 else { return }
        sf_speaker_process(h, io, Int32(frames))
    }
}
