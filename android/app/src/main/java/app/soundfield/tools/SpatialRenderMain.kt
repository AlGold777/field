package app.soundfield.tools

import app.soundfield.core.AudioSource
import app.soundfield.core.Ch
import app.soundfield.core.PcmSource
import app.soundfield.core.SF
import app.soundfield.core.SpatialSource
import app.soundfield.core.SourceMode
import app.soundfield.core.Wav
import app.soundfield.dsp.DSP_VERSION
import app.soundfield.dsp.SpatialDspConfig
import app.soundfield.dsp.SpatialProfiles
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Offline JVM entry point for the exact streaming source used by the app. */
object SpatialRenderMain {
    private val variants = listOf("REF", "ANCHOR_MONO", "ANCHOR_OVER", "W2_LO", "W2_HI", "W2_W3", "W2_W3_W6")

    @JvmStatic fun main(args: Array<String>) {
        val options = parseArgs(args)
        val input = File(options.getValue("in")).canonicalFile
        val output = File(options.getValue("out")).canonicalFile
        val width = options.getValue("width").toDouble()
        val depth = options.getValue("depth").toDouble()
        val height = options["height"]?.toDouble() ?: 0.0
        val profile = options.getValue("profile")
        require(input.isFile) { "Input WAV does not exist: $input" }
        require(width in 0.0..1.0 && depth in 0.0..1.0 && height in 0.0..1.0) { "--width, --depth and --height must be in 0..1" }
        require(profile in setOf("pair_wide", "pair_near", "solo_stereo_speaker", "off")) {
            "Unsupported profile: $profile"
        }

        val profileFile = listOf(
            File("app/src/main/assets/spatial_profiles.json"),
            File("android/app/src/main/assets/spatial_profiles.json"),
        ).firstOrNull { it.isFile }
        val profileData = profileFile?.readText()?.let { SpatialProfiles.parse(it)[profile] }
            ?: SpatialProfiles.fallback(profile)
        val level = "w${width}_d${depth}_h${height}"

        val decoded = Wav.decode(input.readBytes())
        val source: AudioSource = PcmSource(decoded.pcm, input.nameWithoutExtension, "Local WAV")
        require(source.mode == SourceMode.MUSIC)
        val tracks = File(output, "tracks")
        tracks.mkdirs()
        val fragment = "J01"
        val processed = File(tracks, "$fragment-SPATIAL.wav")
        val reference = File(tracks, "$fragment-REF.wav")
        writeWav(reference, source) { frame, channel -> source.sample(frame, channel) }
        SpatialSource(source, spatialConfig = SpatialDspConfig(
            enabled = profile != "off" && (width > 0.0 || depth > 0.0 || height > 0.0), width = width, depth = depth, height = height, span = profileData.span)).use { spatial ->
            spatial.requestThrough(source.totalFrames)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!spatial.complete && spatial.processingError == null && System.nanoTime() < deadline) Thread.sleep(2)
            spatial.processingError?.let { throw IllegalStateException("Spatial render failed", it) }
            check(spatial.complete) { "Timed out rendering the input WAV" }
            writeWav(processed, source) { frame, channel -> spatial.sample(frame, channel) }
        }

        val trackEntries = mutableListOf<String>()
        val variantEntries = mutableListOf<String>()
        for (variant in variants) {
            val kind = when (variant) {
                "REF" -> "reference"
                "ANCHOR_MONO" -> "mono"
                "ANCHOR_OVER" -> "over"
                "W2_LO", "W2_HI" -> "w2"
                "W2_W3" -> "w2_w3"
                else -> "w2_w3_w6"
            }
            val variantProfile = when (variant) {
                "W2_LO", "W2_W3", "W2_W3_W6" -> "lo"
                "W2_HI" -> "hi"
                else -> null
            }
            val setting = "{\"kind\":${quoted(kind)},\"role\":\"spatial_pipeline\"" +
                (variantProfile?.let { ",\"profile\":${quoted(it)}" } ?: "") + "}"
            variantEntries += "${quoted(variant)}:$setting"
            val name = "$fragment-$variant.wav"
            val target = File(tracks, name)
            Files.copy(if (variant == "REF") reference.toPath() else processed.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            trackEntries += "${quoted(variant)}:{\"kind\":\"music\",\"fragment_id\":${quoted(fragment)}," +
                "\"blind_code\":${quoted(variant)},\"filename\":${quoted(name)},\"variant\":${quoted(variant)}," +
                "\"processing\":$setting,\"loudness\":{\"stage\":\"identity\"}}"
        }
        processed.delete()
        val variantJson = variantEntries.joinToString(",\n    ", "{\n    ", "\n  }")
        val tracksJson = trackEntries.joinToString(",\n    ", "{\n    ", "\n  }")
        val duration = source.totalFrames.toDouble() / SF.SAMPLE_RATE
        val key = """{
  "PROTO_VERSION": ${quoted(DSP_VERSION)},
  "DSP_VERSION": ${quoted(DSP_VERSION)},
  "date": ${quoted(java.time.LocalDate.now().toString())},
  "seed": 0,
  "sample_rate": ${SF.SAMPLE_RATE},
  "fragment_seconds": $duration,
  "source_files": [{"fragment_id": "$fragment", "file": ${quoted(input.name)}, "start_seconds": 0.0, "duration_seconds": $duration}],
  "config": ${evaluationConfig(variantJson, profile, level)},
  "variants": $variantJson,
  "tracks": $tracksJson
}
"""
        File(output, "key.json").writeText(key)
        println("Wrote ${tracks.listFiles()?.size ?: 0} tracks at ${SF.SAMPLE_RATE} Hz to $tracks (DSP $DSP_VERSION)")
    }

    private fun writeWav(file: File, source: AudioSource, sample: (Long, Int) -> Float) {
        val dataBytes = source.totalFrames * 4L
        require(dataBytes <= 0xffff_ffffL - 36) { "WAV output exceeds the RIFF size limit" }
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeBytes("RIFF"); out.writeIntLE((36 + dataBytes).toInt()); out.writeBytes("WAVE")
            out.writeBytes("fmt "); out.writeIntLE(16); out.writeShortLE(1); out.writeShortLE(2)
            out.writeIntLE(SF.SAMPLE_RATE); out.writeIntLE(SF.SAMPLE_RATE * 4); out.writeShortLE(4); out.writeShortLE(16)
            out.writeBytes("data"); out.writeIntLE(dataBytes.toInt())
            var frame = 0L
            while (frame < source.totalFrames) {
                for (channel in Ch.L..Ch.R) {
                    val value = sample(frame, channel)
                    check(!value.isNaN()) { "Frame $frame is not ready in the spatial cache" }
                    val pcm = (value.coerceIn(-1f, 32767f / 32768f) * 32768f).toInt().coerceIn(-32768, 32767)
                    out.writeShortLE(pcm)
                }
                frame++
            }
        }
    }

    private fun evaluationConfig(variants: String, profile: String, level: String) = """{
    "sample_rate": ${SF.SAMPLE_RATE},
    "spatial_profile": ${quoted(profile)},
    "spatial_level": ${quoted(level)},
    "stft": {"n_fft": 2048, "hop": 512, "coherence_time_seconds": 0.08, "coherence_band_fraction_octave": 0.1666666667,
      "transient_recovery_seconds": 0.03, "transient_suppression": 0.3, "transient_flux_threshold": 0.25,
      "coherence_floor": 0.3, "low_frequency_guard_hz": 300, "ild_direct_db": 12, "ild_ramp_db": 6},
    "w2": {"low_hz": 300, "high_hz": 5000, "lo_mid_db": 3.0, "lo_high_db": 2.0, "hi_mid_db": 6.0, "hi_high_db": 4.0,
      "anchor_over_side_db": 8.0},
    "ambient": {"level_db": -2.0, "side_gain_db": 2.0, "decorrelation_highpass_hz": 300, "theta_max_deg": 45,
      "decorrelation_cycles_per_octave": 1.5, "d3_enabled": true, "d3_corner_hz": 8000, "d3_gain_db": -2.0},
    "reflections": {"enabled": true, "low_hz": 500, "high_hz": 6000,
      "taps": [{"left_delay_ms": 7.2, "right_delay_ms": 10.5, "gain_db": -17.0},
        {"left_delay_ms": 13.4, "right_delay_ms": 18.1, "gain_db": -16.0},
        {"left_delay_ms": 21.0, "right_delay_ms": 15.7, "gain_db": -18.0}]},
    "limiter": {"lookahead_ms": 5.0, "ceiling_dbtp": -1.0, "release_ms": 80.0, "oversample": 4},
    "blind_test": {"target_lufs": -18.0, "allowed_lufs_delta": 0.3, "synthetic_seconds": 20,
      "sync_probe_seconds": 600, "sync_chirp_ms": 20, "sync_pair_interval_seconds": 1.0,
      "sync_lr_offset_ms": 100.0, "measurement_pause_seconds": 2.0},
    "xtc": {"speaker_spacing_m": 0.15, "listener_distances_m": [0.4, 0.6], "head_radius_m": 0.0875,
      "sound_speed_m_s": 343.0, "low_hz": 500, "high_hz": 6000, "fft_size": 32768,
      "causal_delay_ms": 30.0, "regularization": {"weak": 0.015, "medium": 0.06, "strong": 0.2}},
    "variants": $variants
  }"""

    private fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index].removePrefix("--")
            require(index + 1 < args.size) { "Missing value for --$key" }
            result[key] = args[index + 1]
            index += 2
        }
        for (required in listOf("in", "width", "depth", "profile", "out")) require(required in result) {
            "Usage: --in track.wav --width 0..1 --depth 0..1 [--height 0..1] --profile pair_wide|pair_near|solo_stereo_speaker|off --out folder"
        }
        return result
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        for (shift in 0..24 step 8) writeByte(value ushr shift)
    }
    private fun DataOutputStream.writeShortLE(value: Int) {
        writeByte(value); writeByte(value ushr 8)
    }
}
