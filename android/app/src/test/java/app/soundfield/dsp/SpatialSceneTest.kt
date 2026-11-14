package app.soundfield.dsp

import app.soundfield.core.AudioSource
import app.soundfield.core.Ch
import app.soundfield.core.SF
import app.soundfield.core.SourceMode
import app.soundfield.core.SpatialLevel
import app.soundfield.core.SpatialSettings
import app.soundfield.core.SpatialSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Движок сцены (ширина/глубина) на реальном `SpatialSource`. Компенсатор громкости выключен, если не сказано иное.
 * Измеренные значения печатаются: их читает отчёт об изменении DSP_VERSION.
 */
class SpatialSceneTest {
    private val sr = SF.SAMPLE_RATE

    private class Buffer(val left: FloatArray, val right: FloatArray) : AudioSource {
        override val mode = SourceMode.MUSIC
        override val title = "synthetic"
        override val artist = "test"
        override val totalFrames = left.size.toLong()
        override fun sample(frame: Long, ch: Int) = if (frame !in 0 until totalFrames) 0f
            else if (ch == Ch.L) left[frame.toInt()] else right[frame.toInt()]
    }

    private fun cfg(width: Double, depth: Double, loudness: Boolean = false, span: Double = 1.0) =
        SpatialDspConfig(enabled = true, width = width, depth = depth, span = span, preserveLoudness = loudness)

    private fun render(l: FloatArray, r: FloatArray, config: SpatialDspConfig): Pair<FloatArray, FloatArray> {
        SpatialSource(Buffer(l, r), spatialConfig = config).use { spatial ->
            val total = l.size.toLong()
            spatial.requestThrough(total)
            val deadline = System.nanoTime() + 60_000_000_000L
            while (!spatial.complete && spatial.processingError == null && System.nanoTime() < deadline) Thread.sleep(2)
            spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
            assertTrue("render timed out", spatial.complete)
            return FloatArray(l.size) { spatial.sample(it.toLong(), Ch.L) } to FloatArray(l.size) { spatial.sample(it.toLong(), Ch.R) }
        }
    }

    // --- сигналы и измерения ---

    private fun white(seed: Int, n: Int, amp: Float): FloatArray {
        val random = Random(seed)
        return FloatArray(n) { (random.nextFloat() * 2f - 1f) * amp }
    }

    /** Нуль-фазовый полосовой фильтр по БПФ всего сигнала (только для анализа). */
    private fun band(x: FloatArray, lo: Double, hi: Double): FloatArray {
        var size = 1
        while (size < x.size) size = size shl 1
        val fft = Radix2Fft(size)
        val re = DoubleArray(size) { if (it < x.size) x[it].toDouble() else 0.0 }
        val im = DoubleArray(size)
        fft.transform(re, im)
        for (k in 0 until size) {
            val f = (if (k <= size / 2) k else size - k).toDouble() * sr / size
            if (f < lo || f > hi) { re[k] = 0.0; im[k] = 0.0 }
        }
        fft.transform(re, im, inverse = true)
        return FloatArray(x.size) { re[it].toFloat() }
    }

    private fun pink(seed: Int, n: Int, amp: Float): FloatArray {
        var size = 1
        while (size < n) size = size shl 1
        val fft = Radix2Fft(size)
        val w = white(seed, size, 1f)
        val re = DoubleArray(size) { w[it].toDouble() }
        val im = DoubleArray(size)
        fft.transform(re, im)
        for (k in 1 until size) {
            val f = (if (k <= size / 2) k else size - k).toDouble() * sr / size
            val g = 1.0 / sqrt(max(f, 20.0))
            re[k] *= g; im[k] *= g
        }
        re[0] = 0.0; im[0] = 0.0
        fft.transform(re, im, inverse = true)
        val out = FloatArray(n) { re[it].toFloat() }
        val rms = sqrt(out.sumOf { it.toDouble() * it } / n)
        return FloatArray(n) { (out[it] / rms * amp).toFloat() }
    }

    private fun energy(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return e
    }

    private fun dbE(ratio: Double) = 10.0 * log10(max(ratio, 1e-30))
    private fun dbA(ratio: Double) = 20.0 * log10(max(ratio, 1e-30))

    private fun corr(a: FloatArray, b: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in from until to) { ab += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return ab / sqrt(max(aa * bb, 1e-30))
    }

    private fun diff(a: FloatArray, b: FloatArray) = FloatArray(a.size) { a[it] - b[it] }

    private fun sideMid(l: FloatArray, r: FloatArray, from: Int): Double {
        var m = 0.0; var s = 0.0
        for (i in from until l.size) {
            val mm = (l[i] + r[i]) * 0.5; val ss = (l[i] - r[i]) * 0.5
            m += mm.toDouble() * mm; s += ss.toDouble() * ss
        }
        return s / m
    }

    // --- тесты ---

    @Test fun disabledConfigIsBitExactBypassAndZeroSettingsSanitiseToDisabled() {
        val l = white(1, sr * 2, 0.5f); val r = white(2, sr * 2, 0.5f)
        val (outL, outR) = render(l, r, SpatialDspConfig(enabled = false, width = 1.0, depth = 1.0))
        assertTrue(l.contentEquals(outL)); assertTrue(r.contentEquals(outR))
        assertTrue(!SpatialSettings(level = SpatialLevel.STRONG, width = 0.0, depth = 0.0).sanitized().enabled)
    }

    @Test fun tinyWidthIsContinuousWithBypass() {
        val n = sr * 4
        val a = white(3, n, 0.3f); val b = white(4, n, 0.3f)
        // Жёстко влево, частично вправо, диффузное: все виды содержимого.
        val l = FloatArray(n) { a[it] + 0.4f * b[it] }
        val r = FloatArray(n) { 0.2f * a[it] + b[it] }
        val (outL, outR) = render(l, r, cfg(0.001, 0.0))
        val level = dbE((energy(diff(outL, l)) + energy(diff(outR, r))) / (energy(l) + energy(r)))
        println("MEASURED continuity width=0.001: ${"%.1f".format(level)} dB")
        assertTrue("out-in = $level dB", level < -60.0)
    }

    @Test fun centreStaysCentre() {
        val n = sr * 4
        val x = band(white(5, n, 0.4f), 200.0, 8_000.0)
        val (outL, outR) = render(x, x.copyOf(), cfg(1.0, 0.0))
        val from = sr / 4; val to = n - sr / 4
        val lr = dbE(energy(diff(outL, outR), from, to) / energy(outL, from, to))
        val level = dbE(energy(outL, from, to) / energy(x, from, to))
        println("MEASURED centre: L-R = ${"%.1f".format(lr)} dB re L, level change ${"%.3f".format(level)} dB")
        assertTrue("L-R $lr dB", lr < -50.0)
        assertTrue("level $level dB", abs(level) < 0.3)
    }

    private fun hardLeft(width: Double): Triple<Double, Double, Double> {
        val n = sr * 4
        val x = white(6, n, 0.3f)
        val (outL, outR) = render(x, FloatArray(n), cfg(width, 0.0))
        val from = sr / 2; val to = n - sr / 2
        val bl = band(outL, 300.0, 1_200.0); val br = band(outR, 300.0, 1_200.0)
        val corrBand = corr(bl, br, from, to)
        val rRel = dbE(energy(br, from, to) / energy(bl, from, to))
        val total = dbE((energy(outL, from, to) + energy(outR, from, to)) / energy(x, from, to))
        return Triple(corrBand, rRel, total)
    }

    @Test fun hardLeftSourceIsPushedBeyondTheSpeakersWithAntiPhase() {
        val (c1, r1, t1) = hardLeft(1.0)
        val (_, r05, _) = hardLeft(0.5)
        println("MEASURED hard-left w=1: corr ${"%.2f".format(c1)}, R re L ${"%.1f".format(r1)} dB, total power ${"%.2f".format(t1)} dB; w=0.5: R re L ${"%.1f".format(r05)} dB")
        assertTrue("corr $c1", c1 < -0.5)
        assertTrue("R re L $r1", r1 in -14.0..-6.0)
        assertTrue("power $t1", abs(t1) < 0.7)
        assertTrue("monotonic: w0.5 $r05 < w1 $r1", r05 < r1)
    }

    @Test fun partialPanWidens() {
        val n = sr * 4
        val x = white(7, n, 0.3f)
        val (outL, outR) = render(x, FloatArray(n) { x[it] * 0.5f }, cfg(1.0, 0.0))
        val from = sr / 2; val to = n - sr / 2
        val ild = dbE(energy(band(outL, 300.0, 5_000.0), from, to) / energy(band(outR, 300.0, 5_000.0), from, to))
        println("MEASURED ILD 6 dB source -> ${"%.1f".format(ild)} dB at width 1")
        assertTrue("ILD $ild", ild >= 12.0)
    }

    @Test fun diffuseFieldGetsWiderMonotonically() {
        val n = sr * 4
        val l = white(8, n, 0.3f); val r = white(9, n, 0.3f)
        val sm = doubleArrayOf(0.0, 0.5, 1.0).map { w ->
            val (outL, outR) = render(l, r, cfg(w, 0.0))
            Triple(sideMid(outL, outR, sr / 2), dbE((energy(outL, sr / 2) + energy(outR, sr / 2)) / (energy(l, sr / 2) + energy(r, sr / 2))), w)
        }
        println("MEASURED diffuse: " + sm.joinToString { "w=${it.third}: S/M ${"%.2f".format(dbE(it.first))} dB, power ${"%.2f".format(it.second)} dB" })
        assertTrue("S/M rises", dbE(sm[1].first) > dbE(sm[0].first) + 0.05 && dbE(sm[2].first) > dbE(sm[1].first) + 0.05)
        assertTrue("power rise ${sm[2].second}", sm[2].second <= 5.0)
    }

    @Test fun bassIsUntouched() {
        val n = sr * 4
        for (hz in doubleArrayOf(60.0, 100.0)) {
            val l = FloatArray(n) { (0.4 * sin(2 * PI * hz * it / sr)).toFloat() }
            val r = FloatArray(n) { (0.15 * sin(2 * PI * hz * it / sr + 0.5)).toFloat() }
            val (outL, outR) = render(l, r, cfg(1.0, 1.0))
            val from = sr / 2; val to = n - sr / 2
            val level = dbE((energy(diff(outL, l), from, to) + energy(diff(outR, r), from, to)) / (energy(l, from, to) + energy(r, from, to)))
            println("MEASURED bass ${hz.toInt()} Hz: out-in ${"%.1f".format(level)} dB")
            assertTrue("$hz Hz out-in $level dB", level < -40.0)
        }
    }

    /** Центральный розовый шум, ширина 0: энергия (выход − сухой) к сухому. */
    private fun wetRatioDb(depth: Double, n: Int = sr * 8): Double {
        val x = pink(10, n, 0.15f)
        val (outL, outR) = render(x, x.copyOf(), cfg(0.0, depth))
        val from = sr; val to = n
        return dbE((energy(diff(outL, x), from, to) + energy(diff(outR, x), from, to)) / (2.0 * energy(x, from, to)))
    }

    @Test fun roomDecaysWithExpectedRt60AndDecorrelatedChannels() {
        val burst = sr; val n = sr * 5
        val x = FloatArray(n)
        val noise = pink(11, burst, 0.3f)
        for (i in 0 until burst) x[i + sr / 2] = noise[i]
        val (outL, outR) = render(x, x.copyOf(), cfg(0.0, 1.0))
        val wl = diff(outL, x); val wr = diff(outR, x)
        val rho = corr(wl, wr, sr, sr + burst)
        // Обратное интегрирование Шрёдера по хвосту после конца пакета.
        val tailStart = sr / 2 + burst + sr / 10
        val e = DoubleArray(n - tailStart) { (wl[tailStart + it].toDouble() * wl[tailStart + it] + wr[tailStart + it].toDouble() * wr[tailStart + it]) }
        for (i in e.size - 2 downTo 0) e[i] += e[i + 1]
        fun timeAt(db: Double): Double {
            val target = e[0] * Math.pow(10.0, db / 10.0)
            val index = e.indexOfFirst { it < target }
            return if (index < 0) Double.NaN else index.toDouble() / sr
        }
        val rt = (timeAt(-25.0) - timeAt(-5.0)) * 3.0
        val tail = dbE(energy(wl, tailStart, tailStart + sr / 4) / energy(wl, sr, sr + burst))
        println("MEASURED room: RT60(T20) ${"%.2f".format(rt)} s, wet L/R corr ${"%.3f".format(rho)}, tail first 250 ms vs burst ${"%.1f".format(tail)} dB")
        assertTrue("tail present: $tail dB", tail > -40.0)
        assertTrue("RT60 $rt", rt in 0.45..0.85)
        assertTrue("wet L/R corr $rho", abs(rho) < 0.3)
    }

    @Test fun wetLevelMatchesCalibrationAndIsMonotonicInDepth() {
        val w = doubleArrayOf(0.25, 0.5, 0.75, 1.0).map { wetRatioDb(it) }
        println("MEASURED wet/dry (centre pink, width 0): " + w.joinToString { "%.2f dB".format(it) } + " at depth 0.25/0.5/0.75/1")
        assertEquals("depth 1", -8.0, w[3], 1.5)
        assertEquals("depth 0.5", -15.0, w[1], 2.0)
        assertTrue("monotonic $w", w[0] < w[1] && w[1] < w[2] && w[2] < w[3])
    }

    @Test fun roomEarlyAndLateShareEnergyAndDecorrelate() {
        val n = sr * 8
        val x = pink(12, n, 0.15f)
        for (depth in doubleArrayOf(1.0, 0.5)) {
            val room = SceneRoom(depth)
            var er = 0.0; var late = 0.0; var dry = 0.0; var ab = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 0 until n) {
                room.process(x[i] * 0.5f, x[i] * 0.5f)
                if (i >= sr) {
                    er += room.earlyL * room.earlyL + room.earlyR * room.earlyR
                    late += room.lateL * room.lateL + room.lateR * room.lateR
                    dry += 2.0 * x[i].toDouble() * x[i]
                    ab += room.wetL * room.wetR; aa += room.wetL * room.wetL; bb += room.wetR * room.wetR
                }
            }
            println("MEASURED room parts depth=$depth (send 0.5·x, no STFT filtering): ER ${"%.2f".format(dbE(er / dry))} dB, late ${"%.2f".format(dbE(late / dry))} dB, ER/late ${"%.2f".format(dbE(er / late))} dB, rho ${"%.3f".format(ab / sqrt(aa * bb))}")
            assertTrue("ER/late at depth $depth: ${dbE(er / late)} dB", abs(dbE(er / late)) < 2.0)
            assertTrue("wet rho at depth $depth", abs(ab / sqrt(aa * bb)) < 0.3)
        }
    }

    @Test fun hardLeftOnsetIsAntiPhaseWithinTwentyMilliseconds() {
        val n = sr * 2
        val burstStart = sr; val burstLen = sr / 2
        val x = FloatArray(n)
        val noise = band(white(13, burstLen, 0.5f), 500.0, 2_000.0)
        for (i in 0 until burstLen) x[burstStart + i] = noise[i]
        val (outL, outR) = render(x, FloatArray(n), cfg(1.0, 0.0))
        val bl = band(outL, 300.0, 1_200.0); val br = band(outR, 300.0, 1_200.0)
        val early = corr(bl, br, burstStart, burstStart + sr / 50)
        println("MEASURED onset: band corr in first 20 ms ${"%.2f".format(early)}")
        assertTrue("onset corr $early", early < -0.3)
    }

    @Test fun clickHasNoPreEcho() {
        val n = sr * 3
        val click = sr + 12_345
        val x = FloatArray(n); x[click] = 0.8f
        val (outL, outR) = render(x, FloatArray(n), cfg(1.0, 1.0))
        val from = click - (0.040 * sr).toInt(); val to = click - (0.003 * sr).toInt()
        val pre = dbE((energy(outL, from, to) + energy(outR, from, to)) / (energy(outL, click - 50, click + 50) + energy(outR, click - 50, click + 50)))
        println("MEASURED click pre-echo 40..3 ms: ${"%.1f".format(pre)} dB re click")
        assertTrue("pre-echo $pre dB", pre < -40.0)
    }

    private fun mixedMaterial(n: Int): Pair<FloatArray, FloatArray> {
        val random = Random(77)
        val a = white(21, n, 1f); val b = white(22, n, 1f)
        val l = FloatArray(n); val r = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val tone = sin(2 * PI * 220.0 * t) * 0.35 + sin(2 * PI * 3_100.0 * t) * 0.15
            val gate = if ((i / 6_000) % 5 == 0) 1.0 else 0.2
            l[i] = (tone + 0.35 * a[i] * gate + 0.25 * b[i]).toFloat()
            r[i] = (0.5 * tone + 0.25 * a[i] + 0.35 * b[i] * gate).toFloat()
            if (i % 24_000 == 7) { l[i] = 1f; r[i] = if (random.nextBoolean()) -1f else 0.2f }
        }
        val peak = max(l.maxOf { abs(it) }, r.maxOf { abs(it) })
        for (i in 0 until n) { l[i] = l[i] / peak; r[i] = r[i] / peak }
        return l to r
    }

    @Test fun fullScaleMaterialIsStable() {
        val n = sr * 20
        val (l, r) = mixedMaterial(n)
        SpatialSource(Buffer(l, r), spatialConfig = cfg(1.0, 1.0, loudness = true)).use { spatial ->
            spatial.requestThrough(n.toLong())
            val deadline = System.nanoTime() + 60_000_000_000L
            while (!spatial.complete && spatial.processingError == null && System.nanoTime() < deadline) Thread.sleep(2)
            spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
            assertTrue(spatial.complete)
            var peak = 0.0; var bad = 0
            for (i in 0 until n) for (c in 0..1) {
                val v = spatial.sample(i.toLong(), c)
                if (v.isNaN() || v.isInfinite()) bad++ else peak = max(peak, abs(v.toDouble()))
            }
            val comp = spatial.loudnessCompensationDb
            println("MEASURED stability: peak ${"%.2f".format(dbA(peak))} dBFS, compensation ${"%.2f".format(comp)} dB, bad samples $bad")
            assertEquals(0, bad)
            assertTrue("peak ${dbA(peak)} dBFS", dbA(peak) < 9.0)
            assertTrue("compensation $comp dB", abs(comp) <= 3.0 + 1e-9)
        }
    }

    @Test fun realtimeFactorOfTheWholeSourceIsAtLeast25x() {
        val n = sr * 30
        val (l, r) = mixedMaterial(n)
        fun run(config: SpatialDspConfig = cfg(1.0, 1.0, loudness = true)): Double {
            SpatialSource(Buffer(l, r), spatialConfig = config, cacheSeconds = 40).use { spatial ->
                val start = System.nanoTime()
                spatial.requestThrough(n.toLong())
                while (!spatial.complete && spatial.processingError == null) Thread.sleep(1)
                spatial.processingError?.let { throw AssertionError("spatial worker failed", it) }
                return 30.0 / ((System.nanoTime() - start) / 1e9)
            }
        }
        run() // прогрев JIT
        val factor = run()
        println("MEASURED realtime factor (width 1, depth 1, host JVM, 30 s): ${"%.1f".format(factor)}x")
        assertTrue("realtime factor $factor", factor >= 25.0)
    }
}
