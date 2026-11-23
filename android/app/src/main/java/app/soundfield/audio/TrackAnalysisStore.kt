package app.soundfield.audio

import java.io.File

/** Версия алгоритма анализа: запись другой версии считается отсутствующей. */
const val ANALYZER_VERSION = 1

/**
 * Результат анализа трека: интегральная громкость BS.1770 (NaN — неизвестна/тише порога), true-peak,
 * точная длина в кадрах 48 кГц (−1 — неизвестна), версия анализатора и время записи (мс, epoch).
 */
data class TrackAnalysisResult(
    val lufs: Double,
    val truePeakDbtp: Double,
    val frames: Long,
    val analyzerVersion: Int = ANALYZER_VERSION,
    val writtenAtMs: Long = System.currentTimeMillis(),
)

/**
 * Постоянное хранилище результатов анализа, по одному маленькому текстовому файлу на ключ [TrackIdentity.key].
 * Запись атомарная (tmp + rename), битый файл читается как «нет записи». Потокобезопасно.
 * Записей не больше [maxEntries]: при превышении удаляются самые старые по времени записи.
 */
class TrackAnalysisStore(private val dir: File, private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val lock = Any()

    /** Запись текущей версии анализатора или null (нет, битая, другая версия). */
    fun get(id: TrackIdentity): TrackAnalysisResult? = synchronized(lock) {
        val r = read(File(dir, id.key + EXT)) ?: return null
        if (r.analyzerVersion == ANALYZER_VERSION) r else null
    }

    /** Сохраняет результат (атомарно) и при необходимости вытесняет старые записи. */
    fun put(id: TrackIdentity, result: TrackAnalysisResult) = synchronized(lock) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        val target = File(dir, id.key + EXT)
        val tmp = File(dir, id.key + TMP)
        try {
            tmp.writeText(
                "v=${result.analyzerVersion}\nlufs=${result.lufs}\ntp=${result.truePeakDbtp}\n" +
                    "frames=${result.frames}\nat=${result.writtenAtMs}\n",
            )
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { tmp.delete(); return } }
            target.setLastModified(result.writtenAtMs)
        } catch (_: Exception) { tmp.delete(); return }
        evict()
    }

    /** Число записей на диске (для тестов и диагностики). */
    fun count(): Int = synchronized(lock) { dir.listFiles { f -> f.name.endsWith(EXT) }?.size ?: 0 }

    private fun evict() {
        val files = dir.listFiles { f -> f.name.endsWith(EXT) } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }.take(files.size - maxEntries).forEach { it.delete() }
    }

    private fun read(file: File): TrackAnalysisResult? {
        if (!file.isFile) return null
        return try {
            val m = file.readLines()
                .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
            TrackAnalysisResult(
                lufs = m.getValue("lufs").toDouble(), truePeakDbtp = m.getValue("tp").toDouble(),
                frames = m.getValue("frames").toLong(), analyzerVersion = m.getValue("v").toInt(),
                writtenAtMs = m.getValue("at").toLong(),
            )
        } catch (_: Exception) { null }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5000
        private const val EXT = ".txt"
        private const val TMP = ".tmp"
    }
}
