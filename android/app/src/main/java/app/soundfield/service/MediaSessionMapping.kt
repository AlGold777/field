package app.soundfield.service

import android.support.v4.media.session.PlaybackStateCompat
import app.soundfield.engine.Phase
import app.soundfield.engine.UiState

/** Часть состояния движка, от которой зависит MediaSession (без позиции — она меняется постоянно). */
data class MediaInput(
    val hasTrack: Boolean,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val live: Boolean,
    val queueSize: Int,
    /** Скорость воспроизведения: система экстраполирует позицию с ней. */
    val speed: Float = 1f,
)

fun UiState.toMediaInput() = MediaInput(
    hasTrack = title.isNotBlank() || queueIndex >= 0 || input.live,
    title = title,
    artist = artist,
    durationMs = durationMs,
    playing = playing,
    buffering = audioBuffering || loadingTrackId != null || phase == Phase.RECONNECTING,
    live = input.live,
    queueSize = queueTracks.size,
    speed = if (input.live) 1f else playbackSpeed,
)

/** Что отдать в PlaybackStateCompat. */
data class PlaybackSpec(val state: Int, val actions: Long, val positionMs: Long, val speed: Float)

/** Что отдать в MediaMetadataCompat; durationMs = -1 — длина неизвестна (живой источник). */
data class MetadataSpec(val title: String, val artist: String, val durationMs: Long)

object MediaSessionMapping {
    fun playback(i: MediaInput, positionMs: Long): PlaybackSpec {
        if (!i.hasTrack) return PlaybackSpec(PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.ACTION_PLAY, 0L, 0f)
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (i.queueSize > 0) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (!i.live && i.durationMs > 0) actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        val state = when {
            i.buffering -> PlaybackStateCompat.STATE_BUFFERING
            i.playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val pos = if (i.live) 0L else positionMs.coerceIn(0L, if (i.durationMs > 0) i.durationMs else Long.MAX_VALUE)
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) i.speed else 0f
        return PlaybackSpec(state, actions, pos, speed)
    }

    fun metadata(i: MediaInput): MetadataSpec = MetadataSpec(
        title = i.title.ifBlank { "SoundField" },
        artist = i.artist,
        durationMs = if (i.live || i.durationMs <= 0) -1L else i.durationMs,
    )

    /** Позиция ушла от экстраполяции системы (перемотка, смена трека без смены состояния) — отдать заново. */
    fun positionDrifted(playing: Boolean, lastPos: Long, lastAtMs: Long, nowPos: Long, nowMs: Long, toleranceMs: Long = 1500L,
                        speed: Float = 1f): Boolean {
        val expected = if (playing) lastPos + ((nowMs - lastAtMs) * speed).toLong() else lastPos
        return kotlin.math.abs(nowPos - expected) > toleranceMs
    }
}
