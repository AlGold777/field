package app.soundfield.dsp

/** Profile values ship as JSON data. The parser only reads fields consumed by the spatial signal path. */
data class SpatialProfile(
    val id: String,
    val gMidDb: Double,
    val gHighDb: Double,
    val gAmbientDb: Double,
    val depthDb: Double,
) {
    fun dspConfig(enabled: Boolean, strength: Double) = SpatialDspConfig(
        enabled = enabled, gMidDb = gMidDb, gHighDb = gHighDb,
        gAmbientDb = gAmbientDb, strength = strength,
    )
}

object SpatialProfiles {
    const val VERSION = 1
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
        fun number(key: String, fallback: Double) = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
            .find(entry)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: fallback
        id to SpatialProfile(id, number("gMidDb", 0.0), number("gHighDb", 0.0),
            number("gAmbientDb", 0.0), number("depthDb", 0.0))
    }.toMap()

    fun fallback(id: String): SpatialProfile = when (id) {
        "pair_near" -> SpatialProfile(id, 5.0, 3.0, 3.0, -1.5)
        "solo_stereo_speaker" -> SpatialProfile(id, 2.0, 2.0, 2.0, -1.0)
        "off" -> SpatialProfile(id, 0.0, 0.0, 0.0, 0.0)
        else -> SpatialProfile("pair_wide", 3.0, 2.0, 2.0, -2.0)
    }
}
