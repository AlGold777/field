package app.soundfield.dsp

/** Профиль размещения: масштаб геометрии колонок ([span]) и сила подавления перекрёстных помех XTC. */
data class SpatialProfile(
    val id: String,
    val span: Double,
    val xtcBeta: Double? = null,
)

object SpatialProfiles {
    const val VERSION = 3
    private val knownIds = listOf("pair_wide", "pair_near", "solo_stereo_speaker", "off")

    fun parse(json: String): Map<String, SpatialProfile> = knownIds.mapNotNull { id ->
        val start = Regex("\\\"${Regex.escape(id)}\\\"\\s*:\\s*\\{").find(json)?.range?.last?.plus(1)
            ?: return@mapNotNull null
        var depth = 1
        var end = start
        while (end < json.length && depth > 0) {
            when (json[end]) { '{' -> depth++; '}' -> depth-- }
            end++
        }
        val entry = json.substring(start, (end - 1).coerceAtLeast(start))
        val span = Regex("\\\"span\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
            .find(entry)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        val xtcBeta = Regex("\\\"xtcBeta\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
            .find(entry)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        id to SpatialProfile(id, span, xtcBeta)
    }.toMap()

    fun fallback(id: String): SpatialProfile = when (id) {
        "pair_near" -> SpatialProfile(id, 1.25)
        "solo_stereo_speaker" -> SpatialProfile(id, 1.5, xtcBeta = 0.06)
        "off" -> SpatialProfile(id, 1.0)
        else -> SpatialProfile("pair_wide", 1.0)
    }
}
