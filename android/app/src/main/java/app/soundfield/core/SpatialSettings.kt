package app.soundfield.core

import org.json.JSONArray
import org.json.JSONObject
import app.soundfield.dsp.SpatialProfile
import app.soundfield.dsp.SpatialProfiles

enum class SpatialLevel(val wire: String, val multiplier: Double) {
    OFF("off", 0.0), SOFT("soft", 0.6), STRONG("strong", 1.4);

    companion object { fun parse(value: String?) = entries.firstOrNull { it.wire == value } ?: OFF }
}

data class SpatialCustom(
    val gMidDb: Double,
    val gHighDb: Double,
    val gAmbientDb: Double,
    val depthDb: Double,
    val reflectionDb: Double,
) {
    fun sanitized() = copy(
        gMidDb = gMidDb.coerceIn(0.0, 8.0), gHighDb = gHighDb.coerceIn(0.0, 8.0),
        gAmbientDb = gAmbientDb.coerceIn(0.0, 6.0), depthDb = depthDb.coerceIn(-6.0, 0.0),
        reflectionDb = reflectionDb.coerceIn(-30.0, -10.0),
    )
}

data class SpatialSettings(
    val enabled: Boolean = false,
    val level: SpatialLevel = SpatialLevel.OFF,
    val profile: String = "pair_wide",
    val custom: SpatialCustom? = null,
) {
    fun sanitized(): SpatialSettings = copy(
        enabled = enabled && level != SpatialLevel.OFF,
        level = if (enabled) level else SpatialLevel.OFF,
        profile = profile.takeIf { it in setOf("pair_wide", "pair_near", "solo_stereo_speaker", "off") } ?: "pair_wide",
        custom = custom?.sanitized(),
    )

    fun toJson(): JSONObject {
        val clean = sanitized()
        return JSONObject().put("enabled", clean.enabled).put("level", clean.level.wire).put("profile", clean.profile).apply {
            clean.custom?.let { values -> put("custom", JSONObject()
                .put("gMidDb", values.gMidDb).put("gHighDb", values.gHighDb).put("gAmbientDb", values.gAmbientDb)
                .put("depthDb", values.depthDb).put("reflectionDb", values.reflectionDb)) }
        }
    }

    fun dspConfig(profileData: SpatialProfile): app.soundfield.dsp.SpatialDspConfig {
        val clean = sanitized()
        val customValues = clean.custom
        return app.soundfield.dsp.SpatialDspConfig(
            enabled = clean.enabled,
            gMidDb = customValues?.gMidDb ?: profileData.gMidDb,
            gHighDb = customValues?.gHighDb ?: profileData.gHighDb,
            gAmbientDb = customValues?.gAmbientDb ?: profileData.gAmbientDb,
            strength = clean.level.multiplier,
        )
    }

    companion object {
        fun fromJson(json: JSONObject?) = json?.let { o ->
            val custom = o.optJSONObject("custom")?.let { c -> SpatialCustom(
                c.optDouble("gMidDb", 3.0), c.optDouble("gHighDb", 2.0), c.optDouble("gAmbientDb", 2.0),
                c.optDouble("depthDb", -2.0), c.optDouble("reflectionDb", -17.0),
            ).sanitized() }
            SpatialSettings(o.optBoolean("enabled"), SpatialLevel.parse(o.optString("level", "off")),
                o.optString("profile", "pair_wide"), custom).sanitized()
        } ?: SpatialSettings()
    }
}
