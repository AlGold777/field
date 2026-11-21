package app.soundfield.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Модель соответствия часов Point → Server на виртуальном времени (сценарии из android/coretest testClockSync).
 * Время берётся из аргументов addPong/addOneWay, System.nanoTime не используется; база — 10 с, не ноль.
 */
class ClockSyncTest {
    private val ms = 1_000_000L
    private val baseServerNs = 10_000_000_000L

    /** Часы Point: local = server·(1 + drift) + offset. */
    private class Clocks(val offsetNs: Long, val driftPpm: Double) {
        fun localFromServer(s: Long) = (s * (1 + driftPpm * 1e-6)).toLong() + offsetNs
    }

    private class Result(val cs: ClockSync, val maxErrMs: Double, val lastServer: Long, val clk: Clocks)

    /**
     * 20 быстрых PING (по 50 мс) + [n] − 20 по 200 мс. Задержка каждого направления 2–5 мс, с вероятностью
     * [spikeUp]/[spikeDown] добавляется выброс до 40 мс (асимметрия — выбросы только в одну сторону).
     * maxErrMs — максимум |toServer(local) − server| на горизонте +1 с после 60-го замера.
     */
    private fun run(
        clk: Clocks, seed: Int, n: Int = 320, spikeUp: Double = 0.2, spikeDown: Double = 0.2,
        cs: ClockSync = ClockSync(), startServer: Long = baseServerNs,
    ): Result {
        val rnd = Random(seed)
        var server = startServer
        var maxErr = 0.0
        for (i in 0 until n) {
            server += if (i < 20) 50 * ms else 200 * ms
            val up = (2 + rnd.nextDouble() * 3 + if (rnd.nextDouble() < spikeUp) rnd.nextDouble() * 40 else 0.0) * 1e6
            val down = (2 + rnd.nextDouble() * 3 + if (rnd.nextDouble() < spikeDown) rnd.nextDouble() * 40 else 0.0) * 1e6
            val t1 = clk.localFromServer(server)
            val t2 = server + up.toLong()
            val t3 = t2 + 100_000
            val t4 = clk.localFromServer(t3 + down.toLong())
            cs.addPong(t1, t2, t3, t4)
            if (i > 60) {
                val probe = server + 1_000_000_000L
                maxErr = max(maxErr, abs(cs.toServer(clk.localFromServer(probe)) - probe) / 1e6)
            }
        }
        return Result(cs, maxErr, server, clk)
    }

    @Test fun offsetConvergesWithSymmetricDelay() {
        val r = run(Clocks(987_654_321_000L, 0.0), seed = 1, spikeUp = 0.0, spikeDown = 0.0)
        assertTrue(r.cs.ready)
        assertTrue("ошибка offset ${r.maxErrMs} мс", r.maxErrMs < 0.5)
        assertTrue("дрейф ${r.cs.driftPpm} ppm", abs(r.cs.driftPpm) < 10)
        assertEquals(320L, r.cs.pongs)
        // физическая задержка 2–5 мс в каждую сторону: минимальный RTT около 4 мс
        assertTrue("rttMin ${r.cs.rttMinMs}", r.cs.rttMinMs in 3.9..6.0)
    }

    @Test fun negativeOffsetAndNotReadyBeforeEnoughSamples() {
        val clk = Clocks(-3_210_000_000L, 0.0)
        val cs = ClockSync()
        assertFalse(cs.ready)
        // до накопления 3 замеров в корзине модель не готова, перевод времени тождественный
        val t1 = clk.localFromServer(baseServerNs)
        cs.addPong(t1, baseServerNs + 3 * ms, baseServerNs + 3 * ms + 100_000, clk.localFromServer(baseServerNs + 6 * ms))
        assertFalse(cs.ready)
        assertEquals(t1, cs.toServer(t1))
        val r = run(clk, seed = 3, spikeUp = 0.0, spikeDown = 0.0)
        assertTrue("ошибка ${r.maxErrMs} мс", r.maxErrMs < 0.5)
    }

    @Test fun positiveDriftIsEstimated() {
        // local = server·(1+100 ppm): server per local ≈ −100 ppm
        val r = run(Clocks(987_654_321_000L, 100.0), seed = 2)
        assertTrue("ошибка offset ${r.maxErrMs} мс", r.maxErrMs < 1.5)
        assertEquals(-100.0, r.cs.driftPpm, 15.0)
    }

    @Test fun negativeDriftIsEstimated() {
        val r = run(Clocks(-3_210_000_000L, -100.0), seed = 4)
        assertTrue("ошибка offset ${r.maxErrMs} мс", r.maxErrMs < 1.5)
        assertEquals(100.0, r.cs.driftPpm, 15.0)
    }

    @Test fun asymmetricRttOutliersDoNotSpoilEstimate() {
        // выбросы очереди только на пути Point→Server (40 % замеров), обратный путь чистый
        val oneWay = run(Clocks(987_654_321_000L, 45.0), seed = 5, spikeUp = 0.4, spikeDown = 0.0)
        assertTrue("выбросы вверх: ${oneWay.maxErrMs} мс", oneWay.maxErrMs < 1.5)
        // и наоборот: выбросы только Server→Point
        val reverse = run(Clocks(987_654_321_000L, -45.0), seed = 6, spikeUp = 0.0, spikeDown = 0.4)
        assertTrue("выбросы вниз: ${reverse.maxErrMs} мс", reverse.maxErrMs < 1.5)
        assertEquals(45.0, reverse.cs.driftPpm, 15.0)
        // RTT-статистика видит выбросы, а оценка смещения — нет
        assertTrue(oneWay.cs.rttJitterMs > 1.0)
    }

    @Test fun mixedOutliersKeepDriftNearTruth() {
        val r = run(Clocks(987_654_321_000L, 45.0), seed = 1)
        assertTrue("ошибка ${r.maxErrMs} мс", r.maxErrMs < 1.5)
        assertEquals(-45.0, r.cs.driftPpm, 10.0)
    }

    @Test fun resetDropsModelAndRelearnsNewClock() {
        val first = run(Clocks(987_654_321_000L, 45.0), seed = 1)
        val cs = first.cs
        assertTrue(cs.ready)
        cs.reset()
        assertFalse(cs.ready)
        assertFalse(cs.model.ready)
        assertEquals(0L, cs.pongs)
        assertTrue(cs.takeModelStepsUs().isEmpty())
        val probe = first.clk.localFromServer(first.lastServer)
        assertEquals(probe, cs.toServer(probe))          // без модели — тождественно
        // после reset принимается совсем другое смещение (новая сессия), старое не влияет
        val second = run(Clocks(-55_000_000_000L, -20.0), seed = 7, cs = cs, startServer = first.lastServer + 5_000_000_000L)
        assertTrue(second.cs.ready)
        assertTrue("ошибка после reset ${second.maxErrMs} мс", second.maxErrMs < 1.5)
    }

    @Test fun oneWayWithoutPongsIsIgnoredAndLaterTightensBackwardMin() {
        val clk = Clocks(987_654_321_000L, 0.0)
        val cs = ClockSync()
        cs.addOneWay(baseServerNs, clk.localFromServer(baseServerNs + 3 * ms))
        assertFalse(cs.ready)                              // корзин ещё нет
        run(clk, seed = 8, n = 120, spikeUp = 0.0, spikeDown = 0.0, cs = cs)
        assertTrue(cs.ready)
        val s = baseServerNs + 40_000_000_000L
        cs.addOneWay(s, clk.localFromServer(s + 2 * ms))   // быстрый пакет не ломает модель
        val probe = s + 1_000_000_000L
        assertTrue(abs(cs.toServer(clk.localFromServer(probe)) - probe) / 1e6 < 1.5)
    }
}
