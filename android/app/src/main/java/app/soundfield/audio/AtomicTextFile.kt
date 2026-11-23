package app.soundfield.audio

import java.io.File

/** Атомарная запись текста: во временный файл рядом, затем rename. Возвращает false при ошибке. */
internal fun writeTextAtomic(target: File, text: String): Boolean {
    val tmp = File(target.parentFile, target.name + ".tmp")
    return try {
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return false }
        tmp.writeText(text)
        if (tmp.renameTo(target)) true
        else { target.delete(); if (tmp.renameTo(target)) true else { tmp.delete(); false } }
    } catch (_: Exception) { tmp.delete(); false }
}

/** Экранирование поля для строчного формата: `%`, табуляция и переводы строк. */
internal fun escField(s: String): String =
    s.replace("%", "%25").replace("\t", "%09").replace("\n", "%0A").replace("\r", "%0D")

internal fun unescField(s: String): String =
    s.replace("%09", "\t").replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")
