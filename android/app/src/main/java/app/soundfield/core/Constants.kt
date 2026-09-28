package app.soundfield.core

/**
 * Общие константы SoundField. Значения ОБЯЗАНЫ совпадать с iOS-версией (Core/Constants.swift),
 * иначе Android↔iPhone пара не соберётся.
 */
object SF {
    const val PROTOCOL_VERSION = 1
    const val SAMPLE_RATE = 48_000
    const val PACKET_FRAMES = 480                    // 10 мс PCM в одном UDP-пакете
    const val LEAD_NS = 400_000_000L                 // Server отправляет аудио на 400 мс вперёд
    const val START_DELAY_NS = 300_000_000L          // play/seek стартуют через 300 мс (успеть разослать)
    const val PAUSE_LEAD_NS = 150_000_000L           // пауза наступает через 150 мс — одновременно на обоих телефонах
    const val SERVICE_TYPE = "_soundfield._tcp"      // Bonjour / NSD
    const val SERVICE_PREFIX = "SF-"
    const val HEARTBEAT_MS = 1_000L
    // Разрыв считается по отсутствию данных 8 с: при выключенном экране Wi‑Fi уходит в энергосбережение
    // и доставка TCP может «замирать» на 1–3 с — 4 с давали ложные разрывы.
    const val LINK_TIMEOUT_MS = 8_000L
    const val MAX_TRIM_MS = 250
    const val HARD_RESYNC_FRAMES = 960               // >20 мс рассогласования — жёсткий прыжок
    const val SLEW_THRESHOLD_FRAMES = 6.0            // >0.125 мс — плавная подстройка по 1 сэмплу
}

enum class Role { SERVER, POINT;
    val wire: String get() = if (this == SERVER) "server" else "point"
    companion object { fun fromWire(s: String?) = if (s == "server") SERVER else POINT }
}

/** Канал: 0 = левый, 1 = правый. */
object Ch {
    const val L = 0
    const val R = 1
    fun name(ch: Int) = if (ch == L) "L" else "R"
    fun parse(s: String?) = if (s == "L") L else R
    fun other(ch: Int) = 1 - ch
}

enum class SourceMode(val wire: String) {
    MUSIC("music"), CALIBRATION("calib"), METRONOME("metronome"), CHANNEL_TEST("lrtest"),
    LEVEL_MATCH("level"), CENTER_CHECK("center"), LEVEL_MEASURE("levelMeasure");
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } ?: MUSIC }
}
