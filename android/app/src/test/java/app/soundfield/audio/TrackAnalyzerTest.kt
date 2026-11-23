package app.soundfield.audio

import android.net.Uri
import android.net.FakeUri
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TrackAnalyzerTest {
    private class FakeMeter : Meter {
        var peak = -200.0
        override fun process(block: FloatArray, frames: Int) {
            for (i in 0 until frames * 2) peak = maxOf(peak, 20 * Math.log10(Math.abs(block[i]).toDouble().coerceAtLeast(1e-10)))
        }
        override val truePeakDbtp get() = peak
        override val loudnessLufs get() = -18.0
        override fun close() {}
    }

    private val store = TrackAnalysisStore(Files.createTempDirectory("ta").toFile())
    private fun id(n: Int) = TrackIdentity("content://t/$n", 100L + n, 1L)
    private fun u(s: String): Uri = FakeUri(s)
    private var analyzer: TrackAnalyzer? = null
    @After fun tearDown() { analyzer?.close() }

    private fun make(scan: (Uri, () -> Boolean, (FloatArray, Int) -> Unit) -> Unit): TrackAnalyzer =
        TrackAnalyzer(store, scan, { FakeMeter() }, Executors.newSingleThreadExecutor()).also { analyzer = it }

    private val half = FloatArray(2000) { 0.5f }

    @Test fun analyzesStoresAndCachesWithoutScan() {
        val scans = AtomicInteger()
        val a = make { _, _, c -> scans.incrementAndGet(); c(half, 1000); c(half, 500) }
        val done = CountDownLatch(1); var got: TrackAnalysisResult? = null
        a.request(id(1), u("t1"), false) { got = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1500L, got!!.frames); assertEquals(-18.0, got!!.lufs, 0.0)
        assertEquals(20 * Math.log10(0.5), got!!.truePeakDbtp, 1e-6)
        assertEquals(got, store.get(id(1)))
        var again: TrackAnalysisResult? = null
        a.request(id(1), u("t1"), false) { again = it }
        assertEquals(got, again); assertEquals(1, scans.get())
    }

    @Test fun deduplicatesAndPrioritizes() {
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val gate = CountDownLatch(1); val started = CountDownLatch(1)
        val a = make { uri, _, c ->
            order += uri.toString()
            if (order.size == 1) { started.countDown(); gate.await(5, TimeUnit.SECONDS) }
            c(half, 100)
        }
        val all = CountDownLatch(5); val cb = AtomicInteger()
        fun req(n: Int, p: Boolean) = a.request(id(n), u("t$n"), p) { cb.incrementAndGet(); all.countDown() }
        req(0, false); assertTrue(started.await(5, TimeUnit.SECONDS))
        req(1, false); req(2, false); req(2, false); req(3, true)
        gate.countDown()
        assertTrue(all.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("t0", "t3", "t1", "t2"), order.toList())
        assertEquals(5, cb.get())
    }

    @Test fun cancelMidScanStoresNothing() {
        val started = CountDownLatch(1)
        val a = make { uri, cancelled, c ->
            if (uri.toString() == "t1") { started.countDown(); while (!cancelled()) Thread.sleep(2) } else c(half, 10)
        }
        var called = false
        a.request(id(1), u("t1"), false) { called = true }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        a.cancel(id(1))
        val sentinel = CountDownLatch(1)
        a.request(id(2), u("t2"), false) { sentinel.countDown() }
        assertTrue(sentinel.await(5, TimeUnit.SECONDS))
        assertFalse(called); assertNull(store.get(id(1)))
    }

    @Test fun decoderErrorCreatesNoRecord() {
        val a = make { uri, _, c -> if (uri.toString() == "bad") throw IllegalStateException("x") else c(half, 10) }
        var done = false; val err = CountDownLatch(1)
        a.request(id(1), u("bad"), false, onError = { err.countDown() }) { done = true }
        assertTrue(err.await(5, TimeUnit.SECONDS))
        assertFalse(done); assertNull(store.get(id(1)))
    }
}
