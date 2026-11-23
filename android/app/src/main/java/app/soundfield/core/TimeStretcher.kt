package app.soundfield.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Потоковое изменение скорости без смены высоты тона: WSOLA (наложение с поиском по корреляции).
 * Стерео, интерлив L/R. Позиция сегмента ищется один раз по моно-сумме и одинакова для L и R,
 * поэтому межканальные фазы и задержки сохраняются.
 *
 * Параметры (при 48 кГц), подобраны под речь и музыку одновременно:
 *  - окно 40 мс (Хэнн), шаг синтеза 20 мс (перекрытие 50 %, окна в сумме дают ровно 1);
 *    короче 30 мс речь на 2× начинает «трещать», длиннее 50 мс размываются согласные и атаки;
 *  - поиск ±15 мс вокруг номинальной позиции k·шаг·speed (без накопления дрейфа): покрывает период
 *    основного тона до ~67 Гц, то есть любой мужской голос, и период нот музыки от баса;
 *  - сравнивается перекрываемый участок (20 мс) с «естественным продолжением» прошлого сегмента;
 *    критерий — нормированная корреляция (не отдаёт предпочтения громким кандидатам);
 *  - поиск грубо по моно, усреднённому в 4 раза (12 кГц), затем уточнение ±4 кадра на полной частоте.
 *
 * Первый сегмент идёт без нарастания, поэтому начало потока воспроизводится дословно.
 * При speed == 1.0 данные проходят без обработки (бит-в-бит), задержка 0.
 * Выходной кадр j соответствует входному j·speed с точностью до сдвига поиска (не более 15 мс).
 */
class TimeStretcher(val speed: Double, val sampleRate: Int = 48_000) {
    init { require(speed in 0.25..4.0) { "speed $speed вне 0.25..4.0" } }

    private val passthrough = speed == 1.0
    private val hs = (Math.round(sampleRate * 0.020) / 4 * 4).toInt()   // шаг синтеза
    private val n = hs * 2                                               // окно
    private val delta = (Math.round(sampleRate * 0.015) / 4 * 4).toInt() // полуширина поиска
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }

    /**
     * Сколько входных кадров вперёд от позиции, соответствующей выходному кадру, нужно подать,
     * чтобы этот выход был окончательным (константа; во входной шкале).
     */
    val latencyFrames: Int = if (passthrough) 0 else n + delta

    // входной буфер: абсолютные кадры [base, inEnd)
    private var cap = n * 16
    private var st = FloatArray(cap * 2)
    private var mono = FloatArray(cap)
    private var dec = FloatArray(cap / 4 + 1)
    private var base = 0L
    private var inEnd = 0L
    private var inReal = 0L
    private var k = 0L
    private var prevP = 0L
    private var emitted = 0L
    private val acc = FloatArray(n * 2)
    private var out = FloatArray(n * 8)
    private var outN = 0    // кадров в out
    private val refDec = FloatArray(hs / 4)

    /** Возвращает все готовые выходные кадры (интерлив). Вход — [frames] кадров интерлива. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        require(frames >= 0 && input.size >= frames * 2)
        if (passthrough) return input.copyOf(frames * 2)
        var done = 0
        while (done < frames) {
            val c = minOf(CHUNK, frames - done)
            append(input, done, c)
            inReal += c
            run()
            done += c
        }
        return takeOut()
    }

    /** Дописывает хвост (до длительности вход/speed) и сбрасывает состояние для нового потока. */
    fun flush(): FloatArray {
        if (passthrough) return FloatArray(0)
        val target = Math.round(inReal / speed)
        var guard = 0
        val zeros = FloatArray(hs * 2)
        while (emitted < target && guard++ < 1_000_000) { append(zeros, 0, hs); run() }
        var res = takeOut()
        val extra = (emitted - target).toInt()
        if (extra > 0) res = res.copyOf((res.size - extra * 2).coerceAtLeast(0))
        reset()
        return res
    }

    /** Начинает новый поток (позиция входа и накопленный хвост сбрасываются). */
    fun reset() {
        base = 0; inEnd = 0; inReal = 0; k = 0; prevP = 0; emitted = 0; outN = 0
        acc.fill(0f)
    }

    private fun takeOut(): FloatArray { val r = out.copyOf(outN * 2); outN = 0; return r }

    private fun append(src: FloatArray, off: Int, frames: Int) {
        val used = (inEnd - base).toInt()
        if (used + frames > cap) {
            cap = maxOf(cap * 2, used + frames)
            st = st.copyOf(cap * 2); mono = mono.copyOf(cap); dec = dec.copyOf(cap / 4 + 1)
        }
        for (i in 0 until frames) {
            val l = src[(off + i) * 2]; val r = src[(off + i) * 2 + 1]
            st[(used + i) * 2] = l; st[(used + i) * 2 + 1] = r
            mono[used + i] = (l + r) * 0.5f
        }
        // новые полные четвёрки: абсолютные группы [inEnd/4, (inEnd+frames)/4)
        val g0 = inEnd / 4; val g1 = (inEnd + frames) / 4; val b4 = base / 4
        for (g in g0 until g1) {
            val m = (g * 4 - base).toInt()
            dec[(g - b4).toInt()] = (mono[m] + mono[m + 1] + mono[m + 2] + mono[m + 3]) * 0.25f
        }
        inEnd += frames
    }

    private fun nominal(frame: Long): Long = Math.round(frame * hs * speed)

    private fun run() {
        while (true) {
            val t = if (k == 0L) 0L else nominal(k)
            val need = if (k == 0L) n.toLong() else t + delta + n
            if (inEnd < need) break
            val p = if (k == 0L) 0L else search(t)
            addFrame(p, k == 0L)
            emit()
            prevP = p
            k++
        }
        compact()
    }

    private fun search(t: Long): Long {
        val lo = maxOf(t - delta, base, 0L)
        val hi = t + delta
        val ref = prevP + hs
        val b4 = base / 4
        val rd = (ref / 4 - b4).toInt()
        val len = hs / 4
        var refEnergy = 0.0
        for (i in 0 until len) { val v = dec[rd + i]; refDec[i] = v; refEnergy += v * v }
        if (refEnergy < 1e-10) return t.coerceIn(lo, hi)
        var best = -1L
        var bestScore = Float.NEGATIVE_INFINITY
        var c = (lo + 3) / 4 * 4
        while (c <= hi) {
            val d = (c / 4 - b4).toInt()
            var cor = 0f; var en = 0f
            for (i in 0 until len) { val v = dec[d + i]; cor += refDec[i] * v; en += v * v }
            val s = cor / sqrt(en + 1e-9f)
            if (s > bestScore) { bestScore = s; best = c }
            c += 4
        }
        if (best < 0) return t.coerceIn(lo, hi)
        // уточнение на полной частоте
        val rm = (ref - base).toInt()
        var bestFull = best
        var bestFullScore = Float.NEGATIVE_INFINITY
        for (cc in maxOf(lo, best - 4)..minOf(hi, best + 4)) {
            val m = (cc - base).toInt()
            var cor = 0f; var en = 0f
            for (i in 0 until hs) { val v = mono[m + i]; cor += mono[rm + i] * v; en += v * v }
            val s = cor / sqrt(en + 1e-9f)
            if (s > bestFullScore) { bestFullScore = s; bestFull = cc }
        }
        return bestFull
    }

    private fun addFrame(p: Long, first: Boolean) {
        val o = (p - base).toInt()
        for (i in 0 until n) {
            val w = if (first && i < hs) 1f else window[i]
            acc[i * 2] += w * st[(o + i) * 2]
            acc[i * 2 + 1] += w * st[(o + i) * 2 + 1]
        }
    }

    private fun emit() {
        if ((outN + hs) * 2 > out.size) out = out.copyOf(maxOf(out.size * 2, (outN + hs) * 2))
        System.arraycopy(acc, 0, out, outN * 2, hs * 2)
        outN += hs
        System.arraycopy(acc, hs * 2, acc, 0, hs * 2)
        java.util.Arrays.fill(acc, hs * 2, n * 2, 0f)
        emitted += hs
    }

    private fun compact() {
        val keep = if (k == 0L) base else minOf(prevP + hs, maxOf(0L, nominal(k) - delta))
        val nb = keep / 4 * 4
        if (nb <= base) return
        val s = (nb - base).toInt()
        val used = (inEnd - base).toInt()
        System.arraycopy(st, s * 2, st, 0, (used - s) * 2)
        System.arraycopy(mono, s, mono, 0, used - s)
        val groups = (inEnd / 4 - base / 4).toInt()
        System.arraycopy(dec, s / 4, dec, 0, groups - s / 4)
        base = nb
    }

    private companion object { const val CHUNK = 4096 }
}
