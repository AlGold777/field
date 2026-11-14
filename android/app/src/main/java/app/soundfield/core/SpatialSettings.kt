package app.soundfield.core

import org.json.JSONObject
import app.soundfield.dsp.SpatialProfile

/** Уровень остался для совместимости провода (iOS, старые версии) и слепого A/B: задаёт готовую пару ширина/глубина. */
enum class SpatialLevel(val wire: String, val multiplier: Double, val presetWidth: Double, val presetDepth: Double) {
    OFF("off", 0.0, 0.0, 0.0), SOFT("soft", 0.6, 0.4, 0.3), STRONG("strong", 1.4, 0.8, 0.6);

    companion object { fun parse(value: String?) = entries.firstOrNull { it.wire == value } ?: OFF }
}

/**
 * Настройки сцены. Пользователь управляет двумя величинами 0…1: [width] — ширина, [depth] — глубина.
 * Обе в нуле — обработка выключена (точный обход).
 */
data class SpatialSettings(
    val enabled: Boolean = true,
    val level: SpatialLevel = SpatialLevel.SOFT,
    val profile: String = "pair_wide",
    val preserveLoudness: Boolean = true,
    val width: Double = DEFAULT_WIDTH,
    val depth: Double = DEFAULT_DEPTH,
) {
    fun sanitized(): SpatialSettings {
        val w = unit(width)
        val d = unit(depth)
        val on = enabled && level != SpatialLevel.OFF && (w > 0.0 || d > 0.0)
        return copy(
            enabled = on,
            level = if (on) level else SpatialLevel.OFF,
            profile = profile.takeIf { it in setOf("pair_wide", "pair_near", "solo_stereo_speaker", "off") } ?: "pair_wide",
            width = w, depth = d,
        )
    }

    /** Положение ползунков → настройки. Уровень выводится из них: нужен проводу и силе XTC. */
    fun withScene(width: Double, depth: Double): SpatialSettings {
        val w = unit(width)
        val d = unit(depth)
        val level = when {
            w <= 0.0 && d <= 0.0 -> SpatialLevel.OFF
            maxOf(w, d) > 0.5 -> SpatialLevel.STRONG
            else -> SpatialLevel.SOFT
        }
        return copy(enabled = level != SpatialLevel.OFF, level = level, width = w, depth = d)
    }

    /** Готовая пара ширина/глубина для уровня (слепое A/B, команды без width/depth). */
    fun withLevel(level: SpatialLevel): SpatialSettings = copy(
        enabled = level != SpatialLevel.OFF, level = level, width = level.presetWidth, depth = level.presetDepth)

    fun toJson(): JSONObject {
        val clean = sanitized()
        return JSONObject().put("enabled", clean.enabled).put("level", clean.level.wire).put("profile", clean.profile)
            .put("preserveLoudness", clean.preserveLoudness).put("width", clean.width).put("depth", clean.depth)
    }

    fun dspConfig(profileData: SpatialProfile): app.soundfield.dsp.SpatialDspConfig {
        val clean = sanitized()
        return app.soundfield.dsp.SpatialDspConfig(
            enabled = clean.enabled, width = clean.width, depth = clean.depth,
            span = profileData.span, preserveLoudness = clean.preserveLoudness)
    }

    companion object {
        const val DEFAULT_WIDTH = 0.5
        const val DEFAULT_DEPTH = 0.4

        private fun unit(value: Double) = if (value.isNaN()) 0.0 else value.coerceIn(0.0, 1.0)

        /** Без width/depth (iOS, записи до 1.10.36) положение берётся из уровня. */
        fun fromJson(json: JSONObject?, defaultProfile: String = "pair_wide") = json?.let { o ->
            val level = SpatialLevel.parse(o.optString("level", "off"))
            SpatialSettings(
                enabled = o.optBoolean("enabled"), level = level,
                profile = o.optString("profile", defaultProfile),
                preserveLoudness = o.optBoolean("preserveLoudness", true),
                width = if (o.has("width")) o.optDouble("width", level.presetWidth) else level.presetWidth,
                depth = if (o.has("depth")) o.optDouble("depth", level.presetDepth) else level.presetDepth,
            ).sanitized()
        } ?: SpatialSettings()
    }
}
