package app.soundfield.core

import kotlin.math.abs

/**
 * Приёмная сторона Point: jitter buffer, адресуемый абсолютным номером кадра, учёт последовательностей,
 * NACK-запросы на перепосылку, статистика (jitter по RFC 3550, потери, устаревшие/опоздавшие пакеты).
 *
 * Запись — сетевой поток, чтение — аудиопоток. Каждая ячейка кольца хранит номер кадра (tag),
 * поэтому чтение без блокировок: чужой/старый кадр просто не совпадёт по тегу и будет тишиной.
 */
class PointReceiver(private val clock: ClockSync, capacityPow2: Int = 17) {
    private val cap = 1 shl capacityPow2
    private val mask = (cap - 1).toLong()
    private val samples = FloatArray(cap)
    // Теги — volatile-массив: запись «-1 → сэмпл → кадр», чтение «тег → сэмпл → тег» (seqlock),
    // иначе на ARM аудиопоток мог взять сэмпл прошлого круга кольца под новым тегом.
    private val tags = java.util.concurrent.atomic.AtomicLongArray(LongArray(cap) { -1L })

    @Volatile var timeline: Timeline = Timeline.NONE; private set
    @Volatile var lastRenderedFrame = Long.MIN_VALUE
    @Volatile var maxFrame = -1L; private set

    // --- статистика ---
    @Volatile var received = 0L; private set
    @Volatile var lostFinal = 0L; private set       // так и не восстановлены
    @Volatile var recovered = 0L; private set       // восстановлены NACK/переупорядочиванием
    @Volatile var stale = 0L; private set           // старая эпоха
    @Volatile var late = 0L; private set            // пришли после момента проигрывания
    @Volatile var jitterMs = 0.0; private set
    @Volatile var nacksSent = 0L; private set

    private val lock = Any()
    private var expectedSeq = -1
    private var seqInit = false   // expectedSeq уже задан (seq может быть отрицательным после переполнения Int)
    private class Pending(val firstSeen: Long) { var lastNack = 0L; var tries = 0 }
    private val pending = LinkedHashMap<Int, Pending>()
    private var lastTransit = Long.MIN_VALUE

    val bufferMs: Double get() {
        val lr = lastRenderedFrame
        if (!timeline.playing || maxFrame < 0) return 0.0
        val cur = if (lr == Long.MIN_VALUE) timeline.anchorFrame else lr
        return maxOf(0L, maxFrame - cur) * 1000.0 / SF.SAMPLE_RATE
    }

    /** Count a continuous prefix in the current epoch. Called off the audio thread. */
    fun contiguousFramesFrom(startFrame: Long, limit: Int, epoch: Int = timeline.epoch): Int = synchronized(lock) {
        if (!timeline.playing || timeline.epoch != epoch || startFrame < 0 || startFrame > maxFrame || limit <= 0) {
            return@synchronized 0
        }
        val count = (minOf(maxFrame - startFrame, limit.toLong() - 1, cap.toLong() - 1) + 1).toInt()
        for (i in 0 until count) {
            val frame = startFrame + i
            if (tags.get((frame and mask).toInt()) != frame) return@synchronized i
        }
        count
    }

    fun reset() = synchronized(lock) {
        timeline = Timeline.NONE
        clearBuffer()
        expectedSeq = -1; seqInit = false; pending.clear(); lastTransit = Long.MIN_VALUE
    }

    private fun clearBuffer() {
        for (i in 0 until cap) tags.set(i, -1L)
        maxFrame = -1L
        lastRenderedFrame = Long.MIN_VALUE
    }

    /** Управляющее сообщение timeline (TCP): пауза/seek/новая эпоха. */
    fun onTimeline(tl: Timeline) = synchronized(lock) {
        val cur = timeline
        if (tl.epoch < cur.epoch) return
        if (tl.epoch > cur.epoch) { clearBuffer(); expectedSeq = -1; seqInit = false; pending.clear(); lastTransit = Long.MIN_VALUE }
        timeline = tl
    }

    fun onAudio(p: UdpMsg.Audio, arrivalLocalNs: Long) = synchronized(lock) {
        val cur = timeline
        if (p.epoch < cur.epoch) { stale++; return }
        if (p.epoch > cur.epoch) {
            clearBuffer(); expectedSeq = -1; seqInit = false; pending.clear(); lastTransit = Long.MIN_VALUE
            timeline = Timeline(p.epoch, true, p.startFrame, p.playoutNs)
        } else if (!cur.playing) { stale++; return }

        clock.addOneWay(p.sendNs, arrivalLocalNs)

        // --- учёт последовательности ---
        if (p.isRetransmit) {
            if (pending.remove(p.seq) != null) recovered++
        } else {
            if (!seqInit) { expectedSeq = p.seq; seqInit = true }
            // разность по модулю 2^32: >= 0 — пакет впереди ожидаемого
            val diff = p.seq - expectedSeq
            var duplicate = false
            if (diff >= 0) {
                val gap = diff
                if (gap > 256) {
                    // Огромный провал в приёме (> 2.5 с звука) — не забиваем очередь безнадёжными NACK
                    lostFinal += gap
                } else {
                    var s = expectedSeq
                    while (s != p.seq) {
                        if (pending.size >= 512) {
                            val it = pending.keys.iterator()
                            if (it.hasNext()) {
                                it.next()
                                it.remove()
                                lostFinal++
                            }
                        }
                        pending[s] = Pending(arrivalLocalNs)
                        s++
                    }
                }
                expectedSeq = p.seq + 1
            } else if (pending.remove(p.seq) != null) {
                recovered++ // переупорядочивание
            } else {
                duplicate = true // уже обработанный seq
            }
            if (!duplicate) received++
            // jitter RFC 3550 по времени прохождения (в часах Server)
            if (clock.ready) {
                val transit = clock.toServer(arrivalLocalNs) - p.sendNs
                if (lastTransit != Long.MIN_VALUE) {
                    val d = abs(transit - lastTransit) / 1e6
                    jitterMs += (d - jitterMs) / 16.0
                }
                lastTransit = transit
            }
        }

        // --- опоздавший пакет: его кадры уже проиграны ---
        val lr = lastRenderedFrame
        if (lr != Long.MIN_VALUE && p.startFrame + p.count <= lr) { late++; return }

        for (i in 0 until p.count) {
            val f = p.startFrame + i
            val idx = (f and mask).toInt()
            tags.set(idx, -1L)
            samples[idx] = p.samples[i] / Pcm24.SCALE
            tags.set(idx, f)
        }
        val end = p.startFrame + p.count - 1
        if (end > maxFrame) maxFrame = end
    }

    /** Возвращает номера пакетов, которые пора перезапросить; финализирует безнадёжно потерянные. */
    fun collectNacks(nowLocal: Long): IntArray = synchronized(lock) {
        if (pending.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val (seq, pd) = it.next()
            val age = nowLocal - pd.firstSeen
            if (age > 350_000_000L) { it.remove(); lostFinal++; continue }
            if (pd.tries < 4 && age >= 12_000_000L && nowLocal - pd.lastNack >= 40_000_000L) {
                pd.lastNack = nowLocal; pd.tries++; out.add(seq)
                if (out.size >= UdpMsg.MAX_NACK) break
            }
        }
        if (out.isNotEmpty()) nacksSent++
        out.toIntArray()
    }

    /** NaN — кадра нет в буфере. */
    fun read(frame: Long): Float {
        val idx = (frame and mask).toInt()
        if (tags.get(idx) != frame) return Float.NaN
        val s = samples[idx]
        return if (tags.get(idx) == frame) s else Float.NaN
    }
}
