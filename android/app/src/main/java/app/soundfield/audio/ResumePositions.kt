package app.soundfield.audio

import java.io.File

/**
 * Память позиций длинных файлов (лекции, миксы, аудиокниги) по ключу [TrackIdentity.key].
 * Один текстовый файл, запись атомарная, потокобезопасно. Правила (как у плееров подкастов и аудиокниг):
 *  - помнятся только треки длиннее [MIN_DURATION_MS];
 *  - первые [IGNORE_START_MS] не запоминаются (и не стирают уже сохранённое: движок может прислать позицию 0 до перемотки);
 *  - если до конца осталось не больше max([FINISHED_TAIL_MS], [FINISHED_TAIL_FRACTION] длины) — трек прослушан, запись стирается;
 *  - при возврате позиция отступает на [REWIND_ON_RESUME_MS].
 * Записей не больше [maxEntries]: вытесняются самые давно обновлённые.
 *
 * [update] дешёвый: меняет память и пишет на диск не чаще раза в [minIntervalMs]; [flush] пишет сразу
 * (звать при паузе, смене трека, уходе в фон).
 */
class ResumePositions(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val positionMs: Long, val atMs: Long)

    private val lock = Any()
    private val map = LinkedHashMap<String, Entry>()   // порядок вставки = порядок обновления (старые первыми)
    private var loaded = false
    private var dirty = false
    private var lastWriteMs = Long.MIN_VALUE

    /** Позиция для возобновления (уже с отступом назад) или null: трек короткий, записи нет или уже прослушан. */
    fun positionFor(key: String, durationMs: Long): Long? = synchronized(lock) {
        ensureLoaded()
        if (durationMs < MIN_DURATION_MS) return null
        val e = map[key] ?: return null
        if (e.positionMs < IGNORE_START_MS || isFinished(e.positionMs, durationMs)) return null
        (e.positionMs - REWIND_ON_RESUME_MS).coerceAtLeast(0)
    }

    /** Запоминает текущую позицию. Короткие треки и начало игнорируются, конец трека стирает запись. */
    fun update(key: String, positionMs: Long, durationMs: Long) = synchronized(lock) {
        ensureLoaded()
        if (durationMs < MIN_DURATION_MS || positionMs < 0) return
        if (isFinished(positionMs, durationMs)) { if (map.remove(key) != null) markDirty(); return }
        if (positionMs < IGNORE_START_MS) return
        map.remove(key)                                  // переносим в конец: самый свежий
        map[key] = Entry(positionMs, clock())
        while (map.size > maxEntries) map.remove(map.keys.first())
        markDirty()
    }

    /** Стирает запись (трек дослушан или пользователь начал сначала). */
    fun clear(key: String) = synchronized(lock) { ensureLoaded(); if (map.remove(key) != null) markDirty() }

    /** Немедленно пишет несохранённые изменения. */
    fun flush() = synchronized(lock) { if (dirty) write() }

    /** Число записей (для тестов и диагностики). */
    fun count(): Int = synchronized(lock) { ensureLoaded(); map.size }

    private fun isFinished(pos: Long, dur: Long) = dur - pos <= maxOf(FINISHED_TAIL_MS, (dur * FINISHED_TAIL_FRACTION).toLong())

    private fun markDirty() {
        dirty = true
        val now = clock()
        if (lastWriteMs == Long.MIN_VALUE || now - lastWriteMs >= minIntervalMs) write()
    }

    private fun write() {
        val text = buildString {
            append(HEADER).append('\n')
            for ((k, e) in map) append(escField(k)).append('\t').append(e.positionMs).append('\t').append(e.atMs).append('\n')
        }
        if (writeTextAtomic(file, text)) { dirty = false; lastWriteMs = clock() }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val lines = file.takeIf { it.isFile }?.readLines() ?: return
            if (lines.firstOrNull() != HEADER) return
            lines.drop(1).mapNotNull { l ->
                val p = l.split('\t')
                if (p.size != 3) null else try { Triple(unescField(p[0]), p[1].toLong(), p[2].toLong()) } catch (_: Exception) { null }
            }.sortedBy { it.third }.forEach { (k, pos, at) -> map[k] = Entry(pos, at) }
            while (map.size > maxEntries) map.remove(map.keys.first())
        } catch (_: Exception) { map.clear() }
    }

    companion object {
        const val HEADER = "SoundFieldResume v1"
        /** Короче — позицию не помним (песни, короткие записи). */
        const val MIN_DURATION_MS = 10 * 60_000L
        /** Первые секунды не запоминаются. */
        const val IGNORE_START_MS = 10_000L
        /** Остаток не больше этого (или [FINISHED_TAIL_FRACTION] длины, что больше) — считаем прослушанным. */
        const val FINISHED_TAIL_MS = 30_000L
        const val FINISHED_TAIL_FRACTION = 0.01
        /** Отступ назад при возобновлении, чтобы вспомнить контекст. */
        const val REWIND_ON_RESUME_MS = 3_000L
        const val DEFAULT_MAX_ENTRIES = 500
        const val DEFAULT_MIN_INTERVAL_MS = 5_000L
    }
}
