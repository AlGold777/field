package app.soundfield.audio

import java.io.File

/** Трек сохранённой очереди: адрес строкой (без android.net.Uri), название и исполнитель. */
data class SavedTrack(val uri: String, val title: String, val artist: String)

/**
 * Снимок состояния плеера: очередь, номер текущего трека, позиция в нём (мс), играл ли он и время снимка (мс, epoch).
 * [index] = -1, если текущего трека нет.
 */
data class QueueSnapshot(
    val tracks: List<SavedTrack>,
    val index: Int,
    val positionMs: Long,
    val playing: Boolean,
    val savedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Постоянное хранилище очереди в одном текстовом файле (версия формата в первой строке, маркер конца в последней —
 * обрезанный или чужой файл читается как «нет снимка»). Запись атомарная (tmp + rename), потокобезопасно.
 *
 * Частые вызовы: [saveDebounced] пишет на диск не чаще раза в [minIntervalMs], остальное время только запоминает
 * последний снимок. Отложенный снимок уходит на диск при следующем [saveDebounced] после интервала либо в [flush].
 * Движку надо звать [flush] при паузе, смене трека, изменении очереди и уходе приложения в фон.
 */
class QueueStateStore(
    private val file: File,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var pending: QueueSnapshot? = null
    private var lastWriteMs = Long.MIN_VALUE

    /** Пишет снимок немедленно (отменяя отложенный). Возвращает false при ошибке записи. */
    fun save(snapshot: QueueSnapshot): Boolean = synchronized(lock) { writeLocked(snapshot) }

    /** Запоминает снимок; на диск пишет, если с прошлой записи прошло не меньше [minIntervalMs]. */
    fun saveDebounced(snapshot: QueueSnapshot) = synchronized(lock) {
        pending = snapshot
        val now = clock()
        if (lastWriteMs == Long.MIN_VALUE || now - lastWriteMs >= minIntervalMs) writeLocked(snapshot)
        Unit
    }

    /** Немедленно пишет отложенный снимок, если он есть. */
    fun flush() = synchronized(lock) { pending?.let { writeLocked(it) }; Unit }

    /** Удаляет сохранённое состояние (например, при «очистить очередь»). */
    fun clear() = synchronized(lock) { pending = null; file.delete(); Unit }

    /** Читает снимок; нет файла, битый, обрезанный или другой версии — null. Не бросает исключений. */
    fun load(): QueueSnapshot? = synchronized(lock) {
        try { parse(file.takeIf { it.isFile }?.readLines() ?: return null) } catch (_: Exception) { null }
    }

    private fun writeLocked(s: QueueSnapshot): Boolean {
        val ok = writeTextAtomic(file, format(s))
        if (ok) { pending = null; lastWriteMs = clock() }
        return ok
    }

    private fun format(s: QueueSnapshot): String = buildString {
        append(HEADER).append('\n')
        append("index=").append(s.index).append('\n')
        append("pos=").append(s.positionMs).append('\n')
        append("playing=").append(if (s.playing) 1 else 0).append('\n')
        append("at=").append(s.savedAtMs).append('\n')
        append("count=").append(s.tracks.size).append('\n')
        for (t in s.tracks) append("t=").append(escField(t.uri)).append('\t').append(escField(t.title)).append('\t')
            .append(escField(t.artist)).append('\n')
        append(END).append('\n')
    }

    private fun parse(lines: List<String>): QueueSnapshot? {
        if (lines.size < 7 || lines.first() != HEADER || lines.last() != END) return null
        val head = lines.subList(1, 6).associate { l -> l.indexOf('=').let { l.substring(0, it) to l.substring(it + 1) } }
        val count = head.getValue("count").toInt()
        val rows = lines.subList(6, lines.size - 1)
        if (rows.size != count) return null
        val tracks = rows.map { r ->
            val p = r.removePrefix("t=").split('\t')
            if (!r.startsWith("t=") || p.size != 3) return null
            SavedTrack(unescField(p[0]), unescField(p[1]), unescField(p[2]))
        }
        val index = head.getValue("index").toInt()
        if (index < -1 || index >= tracks.size) return null
        return QueueSnapshot(tracks, index, head.getValue("pos").toLong().coerceAtLeast(0),
            head.getValue("playing") == "1", head.getValue("at").toLong())
    }

    companion object {
        /** Версия формата: при смене строка заголовка меняется, старые файлы читаются как отсутствующие. */
        const val FORMAT_VERSION = 1
        const val HEADER = "SoundFieldQueue v$FORMAT_VERSION"
        private const val END = "end"

        /** Не чаще раза в 5 с при частых [saveDebounced]. */
        const val DEFAULT_MIN_INTERVAL_MS = 5_000L
    }
}
