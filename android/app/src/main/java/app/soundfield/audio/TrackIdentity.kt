package app.soundfield.audio

import java.security.MessageDigest

/**
 * Идентичность файла трека для постоянного кэша анализа: ссылка, размер и дата изменения.
 * Файл изменился (размер или дата) — ключ другой, прежний анализ не применяется.
 * Получение полей из ContentResolver — в `TrackIdentityAndroid.kt` (`TrackIdentity.of`).
 */
data class TrackIdentity(val uri: String, val size: Long, val lastModified: Long) {
    /** Стабильный ключ: SHA-256 от полей, первые 16 байт в hex (32 символа). */
    val key: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest("$uri\n$size\n$lastModified".toByteArray(Charsets.UTF_8))
        buildString(32) { for (i in 0 until 16) append("%02x".format(digest[i])) }
    }

    companion object
}
