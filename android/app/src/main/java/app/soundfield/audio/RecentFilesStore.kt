package app.soundfield.audio

import java.io.File

/** Недавно добавленный или открытый файл: адрес строкой, название, исполнитель, время последнего использования (мс, epoch). */
data class RecentFile(val uri: String, val title: String, val artist: String, val usedAtMs: Long)

/**
 * Список недавних файлов: «добавил в очередь» и «открыл для воспроизведения» поднимают файл наверх.
 * Один адрес — одна запись; хранится не более [limit] записей, самые новые первыми. Формат — текстовый файл
 * с версией в первой строке и маркером конца (обрезанный или чужой файл читается как пустой список), запись атомарная.
 */
class RecentFilesStore(
    private val file: File,
    private val limit: Int = DEFAULT_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var cache: List<RecentFile>? = null

    /** Самые новые первыми. Не бросает исключений. */
    fun load(): List<RecentFile> = synchronized(lock) { loadLocked() }

    /** Поднимает файлы наверх в порядке списка (первый остаётся самым новым). Пустой заголовок не затирает известное название. */
    fun touch(items: List<RecentFile>): Boolean = synchronized(lock) {
        if (items.isEmpty()) return true
        val now = clock()
        val old = loadLocked()
        val known = old.associateBy { it.uri }
        val fresh = items.distinctBy { it.uri }.mapIndexed { i, f ->
            val prev = known[f.uri]
            RecentFile(f.uri, f.title.ifBlank { prev?.title.orEmpty() }, f.artist.ifBlank { prev?.artist.orEmpty() }, now - i)
        }
        val freshUris = fresh.mapTo(HashSet()) { it.uri }
        val merged = (fresh + old.filter { it.uri !in freshUris }).take(limit)
        writeLocked(merged)
    }

    fun touch(item: RecentFile): Boolean = touch(listOf(item))

    /** Убирает файлы, которые больше не открываются. */
    fun remove(uris: Collection<String>): Boolean = synchronized(lock) {
        if (uris.isEmpty()) return true
        val set = uris.toHashSet()
        val old = loadLocked()
        val kept = old.filter { it.uri !in set }
        if (kept.size == old.size) true else writeLocked(kept)
    }

    fun clear() = synchronized(lock) { cache = emptyList(); file.delete(); Unit }

    private fun loadLocked(): List<RecentFile> {
        cache?.let { return it }
        val list = try { parse(file.takeIf { it.isFile }?.readLines() ?: emptyList()) } catch (_: Exception) { emptyList() }
        cache = list
        return list
    }

    private fun writeLocked(list: List<RecentFile>): Boolean {
        val ok = writeTextAtomic(file, buildString {
            append(HEADER).append('\n')
            append("count=").append(list.size).append('\n')
            for (r in list) append("r=").append(escField(r.uri)).append('\t').append(escField(r.title)).append('\t')
                .append(escField(r.artist)).append('\t').append(r.usedAtMs).append('\n')
            append(END).append('\n')
        })
        if (ok) cache = list else cache = null
        return ok
    }

    private fun parse(lines: List<String>): List<RecentFile> {
        if (lines.size < 3 || lines.first() != HEADER || lines.last() != END) return emptyList()
        val count = lines[1].removePrefix("count=").toInt()
        val rows = lines.subList(2, lines.size - 1)
        if (rows.size != count) return emptyList()
        return rows.map { r ->
            val p = r.removePrefix("r=").split('\t')
            if (!r.startsWith("r=") || p.size != 4) return emptyList()
            RecentFile(unescField(p[0]), unescField(p[1]), unescField(p[2]), p[3].toLong())
        }.sortedByDescending { it.usedAtMs }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val HEADER = "SoundFieldRecent v$FORMAT_VERSION"
        private const val END = "end"
        const val DEFAULT_LIMIT = 40
    }
}
