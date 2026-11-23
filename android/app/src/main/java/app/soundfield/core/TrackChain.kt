package app.soundfield.core

/**
 * Очередь треков на одной шкале — переход без паузы (gapless). Следующий трек начинается ровно с кадра,
 * на котором кончился предыдущий: [append] возможен, когда точная длина последнего трека известна
 * ([SegmentedPcmSource.endKnown]). Обёртки (Spatial, предлимитер), рендер Server и отправка на Point
 * видят один непрерывный источник: на границе нет новой эпохи, сетевого события и затухания.
 *
 * Кадр цепочки f лежит в треке, у которого `offset ≤ f`; внутри трека — кадр `f − offset`.
 * Цепочка сбрасывается до одного трека при любой новой эпохе (перемотка, выбор трека) — это делает движок.
 */
class TrackChain(first: SegmentedPcmSource) : AudioSource, TrackMeters, PositionalTrackMeters {
    class Entry(val track: SegmentedPcmSource, val offset: Long) {
        val end: Long get() = offset + track.totalFrames
    }

    /** Снимок для чтения без блокировок (аудиопоток); меняется только целиком. */
    @Volatile private var entries: Array<Entry> = arrayOf(Entry(first, 0L))
    @Volatile private var focusFrame = 0L

    override val mode = SourceMode.MUSIC
    override val title: String get() = entryAt(focusFrame).track.title
    override val artist: String get() = entryAt(focusFrame).track.artist
    override val totalFrames: Long get() = entries.last().end
    /** Точная длина всей цепочки известна (декодер последнего трека дошёл до конца файла). */
    val endKnown: Boolean get() = entries.last().track.endKnown

    /** Готовый участок от позиции: через границу трека, если предыдущий готов до конца, а следующий — с начала. */
    override val readyFrames: Long get() {
        val list = entries
        var i = indexAt(list, focusFrame)
        while (true) {
            val e = list[i]
            val local = e.offset + e.track.readyFrames
            if (i == list.lastIndex || !e.track.endKnown || e.track.readyFrames < e.track.totalFrames) return local
            i++
        }
    }

    /** Больше данных не будет: от позиции до конца последнего трека всё готово. */
    override val complete: Boolean get() {
        val list = entries
        if (indexAt(list, focusFrame) < list.lastIndex) {
            return readyFrames >= totalFrames && list.last().track.complete
        }
        return list.last().track.complete
    }

    val entryCount: Int get() = entries.size
    val first: Entry get() = entries.first()
    val last: Entry get() = entries.last()
    fun entriesSnapshot(): List<Entry> = entries.toList()

    fun entryAt(frame: Long): Entry = entries.let { it[indexAt(it, frame)] }

    /** Позиция воспроизведения: окно декодера трека, в котором она лежит. */
    fun focus(frame: Long) {
        focusFrame = frame.coerceAtLeast(0)
        val e = entryAt(focusFrame)
        e.track.focus((focusFrame - e.offset).coerceAtLeast(0))
    }

    /** Приклеить [track] к концу цепочки. Длина последнего трека должна быть точно известна. */
    fun append(track: SegmentedPcmSource): Entry {
        val list = entries
        check(list.last().track.endKnown) { "Длина последнего трека ещё не известна" }
        val entry = Entry(track, list.last().end)
        entries = list + entry
        return entry
    }

    /** Отцепить треки после [entry] (очередь изменилась). Возвращает отцепленные. */
    fun truncateAfter(entry: Entry): List<SegmentedPcmSource> {
        val list = entries
        val at = list.indexOfFirst { it === entry }
        if (at < 0 || at == list.lastIndex) return emptyList()
        entries = list.copyOfRange(0, at + 1)
        return list.drop(at + 1).map { it.track }
    }

    /** Убрать треки, закончившиеся раньше [frame] (уже сыграны); последний остаётся всегда. Возвращает убранные. */
    fun dropEndedBefore(frame: Long): List<SegmentedPcmSource> {
        val list = entries
        val keep = list.filterIndexed { i, e -> i == list.lastIndex || e.end > frame }
        if (keep.size == list.size) return emptyList()
        entries = keep.toTypedArray()
        return list.filter { e -> keep.none { it === e } }.map { it.track }
    }

    override fun sample(frame: Long, ch: Int): Float {
        val list = entries
        for (i in list.indices.reversed()) {
            val e = list[i]
            if (frame >= e.offset) return e.track.sample(frame - e.offset, ch)
        }
        return 0f
    }

    override val truePeakDbtp: Double get() = entryAt(focusFrame).track.truePeakDbtp
    override val loudnessLufs: Double get() = entryAt(focusFrame).track.loudnessLufs
    override fun truePeakAt(frame: Long): Double = entryAt(frame).track.truePeakDbtp
    override fun loudnessAt(frame: Long): Double = entryAt(frame).track.loudnessLufs
    override fun trackKeyAt(frame: Long): Long = entryAt(frame).offset
    override fun nextTrackStartAfter(frame: Long): Long =
        entries.firstOrNull { it.offset > frame }?.offset ?: Long.MAX_VALUE

    private fun indexAt(list: Array<Entry>, frame: Long): Int {
        for (i in list.indices.reversed()) if (frame >= list[i].offset) return i
        return 0
    }
}
