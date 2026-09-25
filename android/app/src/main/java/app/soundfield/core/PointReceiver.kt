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
    private val tags = LongArray(cap) { -1L }

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
    private class Pending(val firstSeen: Long) { var lastNack = 0L; var tries = 0 }
    private val pending = HashMap<Int, Pending>()
    private var lastTransit = Long.MIN_VALUE

    val bufferMs: Double get() {
        val lr = lastRenderedFrame
        if (!timeline.playing || maxFrame < 0) return 0.0
        val cur = if (lr == Long.MIN_VALUE) timeline.anchorFrame else lr
        return maxOf(0L, maxFrame - cur) * 1000.0 / SF.SAMPLE_RATE
    }

    fun reset() = synchronized(lock) {
        timeline = Timeline.NONE
        clearBuffer()
        expectedSeq = -1; pending.clear(); lastTransit = Long.MIN_VALUE
    }

    private fun clearBuffer() {
        java.util.Arrays.fill(tags, -1L)
        maxFrame = -1L
        lastRenderedFrame = Long.MIN_VALUE
    }

    /** Управляющее сообщение timeline (TCP): пауза/seek/новая эпоха. */
    fun onTimeline(tl: Timeline) = synchronized(lock) {
        val cur = timeline
        if (tl.epoch < cur.epoch) return
        if (tl.epoch > cur.epoch) { clearBuffer(); expectedSeq = -1; pending.clear(); lastTransit = Long.MIN_VALUE }
        timeline = tl
    }

    fun onAudio(p: UdpMsg.Audio, arrivalLocalNs: Long) = synchronized(lock) {
        val cur = timeline
        if (p.epoch < cur.epoch) { stale++; return }
        if (p.epoch > cur.epoch) {
            clearBuffer(); expectedSeq = -1; pending.clear(); lastTransit = Long.MIN_VALUE
            timeline = Timeline(p.epoch, true, p.startFrame, p.playoutNs)
        } else if (!cur.playing) { stale++; return }

        clock.addOneWay(p.sendNs, arrivalLocalNs)

        // --- учёт последовательности ---
        if (p.isRetransmit) {
            if (pending.remove(p.seq) != null) recovered++
        } else {
            if (expectedSeq < 0) expectedSeq = p.seq
            if (p.seq >= expectedSeq) {
                var s = expectedSeq
                while (s < p.seq && pending.size < 512) { pending[s] = Pending(arrivalLocalNs); s++ }
                expectedSeq = p.seq + 1
            } else if (pending.remove(p.seq) != null) {
                recovered++ // переупорядочивание
            }
            received++
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
            samples[idx] = p.samples[i] / 32768f
            tags[idx] = f
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
        val s = samples[idx]
        return if (tags[idx] == frame) s else Float.NaN
    }
}
