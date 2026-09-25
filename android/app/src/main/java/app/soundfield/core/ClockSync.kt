package app.soundfield.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Оценка соответствия часов Point → часы Server.
 *
 * Идея: задержка в каждом направлении = минимальная (физическая) + очередь (случайная, ≥ 0).
 * Поэтому минимумы задержки по каждому направлению отдельно почти не содержат шума очередей:
 *   fwd = t2 − t1  (Point→Server, PING)        = dFwd + offset
 *   bwd = t4 − t3  (Server→Point, PONG/AUDIO)  = dBwd − offset
 *   offset ≈ (min fwd − min bwd) / 2          (в предположении dFwd_min ≈ dBwd_min)
 * Направление Server→Point измеряется ещё и по каждому аудиопакету (100 шт/с, поле sendNs),
 * поэтому его минимум находится очень точно.
 *
 * Минимумы собираются в корзины по 2 с. По корзинам за последние ~80 с строится взвешенная
 * линейная регрессия offset(t) = a + b·(t − ref); b — дрейф часов (ppm). Пока окно короткое —
 * берётся offset лучшей из последних корзин.
 *
 * add*() — из сетевого потока, toServer() — из аудиопотока (модель публикуется через @Volatile).
 */
class ClockSync {

    private class Bucket(val start: Long) {
        var minFwd = Long.MAX_VALUE; var minBwd = Long.MAX_VALUE
        var nFwd = 0; var nBwd = 0
        val usable get() = nFwd >= 3 && nBwd >= 3
        val offset get() = (minFwd - minBwd) / 2.0
        val quality get() = (minFwd + minBwd).toDouble()   // ≈ минимальный RTT в корзине
        val mid get() = start + BUCKET_NS / 2
    }

    class Model(val ref: Long, val a: Double, val b: Double, val ready: Boolean)

    @Volatile var model = Model(0, 0.0, 0.0, false); private set

    private val buckets = ArrayDeque<Bucket>()
    private val rtts = ArrayDeque<Double>()
    private val lock = Any()

    @Volatile var rttMinMs = 0.0; private set
    @Volatile var rttAvgMs = 0.0; private set
    @Volatile var rttJitterMs = 0.0; private set
    @Volatile var driftPpm = 0.0; private set
    @Volatile var pongs = 0L; private set
    /** Оценка погрешности offset, мс (половина разности «лучший RTT − 2·физическая задержка» ~ разброс корзин). */
    @Volatile var uncertaintyMs = 0.0; private set

    fun reset() = synchronized(lock) {
        buckets.clear(); rtts.clear(); pongs = 0
        model = Model(0, 0.0, 0.0, false)
    }

    private fun bucketFor(local: Long): Bucket {
        val last = buckets.lastOrNull()
        if (last != null && local >= last.start && local < last.start + BUCKET_NS) return last
        if (last != null && local < last.start) {
            // запоздавший замер — ищем его корзину
            return buckets.lastOrNull { local >= it.start && local < it.start + BUCKET_NS } ?: last
        }
        val b = Bucket(if (last == null) local else last.start + ((local - last.start) / BUCKET_NS) * BUCKET_NS)
        buckets.addLast(b)
        while (buckets.size > MAX_BUCKETS) buckets.removeFirst()
        return b
    }

    fun addPong(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = (t4 - t1) - (t3 - t2)
        if (rtt < 0 || rtt > 2_000_000_000L) return
        synchronized(lock) {
            pongs++
            val bf = bucketFor(t1)
            bf.minFwd = minOf(bf.minFwd, t2 - t1); bf.nFwd++
            val bb = bucketFor(t4)
            bb.minBwd = minOf(bb.minBwd, t4 - t3); bb.nBwd++
            rtts.addLast(rtt / 1e6); while (rtts.size > 100) rtts.removeFirst()
            rttMinMs = rtts.min(); rttAvgMs = rtts.average()
            rttJitterMs = sqrt(rtts.sumOf { (it - rttAvgMs) * (it - rttAvgMs) } / rtts.size)
            recompute()
        }
    }

    /** Односторонний замер Server→Point по аудиопакету. */
    fun addOneWay(sendServerNs: Long, arrivalLocal: Long) {
        synchronized(lock) {
            if (buckets.isEmpty()) return
            val b = bucketFor(arrivalLocal)
            val d = arrivalLocal - sendServerNs
            b.nBwd++
            if (d < b.minBwd) { b.minBwd = d; recompute() }
        }
    }

    private fun recompute() {
        val ok = buckets.filter { it.usable }
        if (ok.isEmpty()) return
        val qMin = ok.minOf { it.quality }
        val span = (ok.last().mid - ok.first().mid) / 1e9
        var a: Double; var b: Double; var ref: Long
        if (ok.size >= 5 && span >= 12.0) {
            ref = ok.last().mid
            var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            val base = ok.last().offset
            for (k in ok) {
                val w = 1.0 / sq((k.quality - qMin) / 1e6 + 0.3)
                val x = (k.mid - ref).toDouble(); val y = k.offset - base
                sw += w; sx += w * x; sy += w * y; sxx += w * x * x; sxy += w * x * y
            }
            val den = sw * sxx - sx * sx
            b = if (abs(den) > 1e-12) (sw * sxy - sx * sy) / den else 0.0
            if (abs(b) > MAX_DRIFT) b = 0.0
            a = base + (sy - b * sx) / sw
            // разброс остатков как оценка погрешности
            var r2 = 0.0
            for (k in ok) { val e = k.offset - (a + b * (k.mid - ref)); r2 += e * e }
            uncertaintyMs = sqrt(r2 / ok.size) / 1e6 + (qMin / 1e6) * 0.05
        } else {
            val recent = ok.takeLast(3)
            val best = recent.minBy { it.quality }
            b = model.b
            ref = best.mid
            a = best.offset
            uncertaintyMs = (best.quality - qMin) / 2e6 + (qMin / 1e6) * 0.1
        }
        driftPpm = b * 1e6
        model = Model(ref, a, b, true)
    }

    private fun sq(x: Double) = x * x

    val ready: Boolean get() = model.ready

    /** Перевод локального монотонного времени Point во время Server. */
    fun toServer(localNs: Long): Long {
        val m = model
        return localNs + (m.a + m.b * (localNs - m.ref)).toLong()
    }

    companion object {
        const val BUCKET_NS = 2_000_000_000L
        const val MAX_BUCKETS = 40
        const val MAX_DRIFT = 300e-6
    }
}
