package app.soundfield.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

internal object TelemetryDownloads {
    data class Saved(val displayPath: String, val shareUri: android.net.Uri)

    fun save(context: Context, report: String): Saved {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val name = "telemetria_${time}_${UUID.randomUUID().toString().take(8)}.txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в Download")
            try {
                val stream = resolver.openOutputStream(uri) ?: error("Не удалось открыть файл в Download")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                    "Не удалось завершить сохранение в Download"
                }
            } catch (e: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                throw e
            }
            return Saved("Download/$name", uri)
        } else {
            // API 26–28: the button requests WRITE_EXTERNAL_STORAGE before this operation.
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            check(dir.isDirectory || dir.mkdirs()) { "Не удалось открыть Download" }
            val file = File(dir, name)
            check(file.createNewFile()) { "Файл уже существует" }
            try { file.writeText(report, Charsets.UTF_8) }
            catch (e: Exception) { file.delete(); throw e }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
            val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.telemetry", file)
            return Saved("Download/$name", shareUri)
        }
    }

    fun share(context: Context, saved: Saved) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, saved.shareUri)
            clipData = ClipData.newUri(context.contentResolver, "SoundField telemetry", saved.shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Поделиться телеметрией").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
