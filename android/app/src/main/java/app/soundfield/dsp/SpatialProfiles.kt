package app.soundfield.dsp

/** Profile values ship as JSON data. The parser only reads fields consumed by the spatial signal path. */
data class SpatialProfile(
    val id: String,
    val gMidDb: Double,
    val gHighDb: Double,
    val gAmbientDb: Double,
    val depthDb: Double,
    val reflectionsEnabled: Boolean = false,
    val reflectionTaps: List<SpatialReflectionTap> = emptyList(),
) {
    fun dspConfig(enabled: Boolean, strength: Double) = SpatialDspConfig(
        enabled = enabled, gMidDb = gMidDb, gHighDb = gHighDb,
        gAmbientDb = gAmbientDb, strength = strength, depthDb = depthDb,
        reflectionsEnabled = reflectionsEnabled, reflectionTaps = reflectionTaps,
    )
}

object SpatialProfiles {
    const val VERSION = 2
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
        val reflections = Regex("\\\"reflectionsEnabled\\\"\\s*:\\s*(true|false)")
            .find(entry)?.groupValues?.getOrNull(1)?.toBooleanStrictOrNull() ?: false
        val taps = Regex("\\{\\s*\\\"leftMs\\\"\\s*:\\s*([0-9.]+)\\s*,\\s*\\\"rightMs\\\"\\s*:\\s*([0-9.]+)\\s*,\\s*\\\"gainDb\\\"\\s*:\\s*(-?[0-9.]+)")
            .findAll(entry).mapNotNull { match ->
                val (left, right, gain) = match.destructured
                SpatialReflectionTap(left.toDoubleOrNull() ?: return@mapNotNull null,
                    right.toDoubleOrNull() ?: return@mapNotNull null, gain.toDoubleOrNull() ?: return@mapNotNull null)
            }.toList()
        id to SpatialProfile(id, number("gMidDb", 0.0), number("gHighDb", 0.0),
            number("gAmbientDb", 0.0), number("depthDb", 0.0), reflections, taps)
    }.toMap()

    fun fallback(id: String): SpatialProfile = when (id) {
        "pair_near" -> SpatialProfile(id, 5.0, 3.0, 3.0, -1.5, true, listOf(
            SpatialReflectionTap(5.0, 7.5, -17.0), SpatialReflectionTap(9.0, 12.0, -16.0),
            SpatialReflectionTap(14.0, 11.5, -18.0)))
        "solo_stereo_speaker" -> SpatialProfile(id, 2.0, 2.0, 2.0, -1.0)
        "off" -> SpatialProfile(id, 0.0, 0.0, 0.0, 0.0)
        else -> SpatialProfile("pair_wide", 3.0, 2.0, 2.0, -2.0, true, listOf(
            SpatialReflectionTap(7.2, 10.5, -17.0), SpatialReflectionTap(13.4, 18.1, -16.0),
            SpatialReflectionTap(21.0, 15.7, -18.0)))
    }
}
