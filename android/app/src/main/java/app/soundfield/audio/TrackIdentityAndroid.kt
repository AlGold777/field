package app.soundfield.audio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/**
 * Идентичность трека по ContentResolver: размер (`OpenableColumns.SIZE`, запасной путь — длина AssetFileDescriptor)
 * и дата изменения (`COLUMN_LAST_MODIFIED`, если провайдер отдаёт; иначе −1). Размер неизвестен → null (кэшировать нельзя).
 */
fun TrackIdentity.Companion.of(context: Context, uri: Uri): TrackIdentity? {
    var size = -1L
    var modified = -1L
    try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (mi >= 0 && !c.isNull(mi)) modified = c.getLong(mi)
            }
        }
    } catch (_: Exception) {}
    if (size < 0) {
        size = try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
        catch (_: Exception) { -1L }
    }
    if (size < 0) return null
    return TrackIdentity(uri.toString(), size, modified)
}
