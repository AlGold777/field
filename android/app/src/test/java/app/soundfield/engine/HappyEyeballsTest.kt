package app.soundfield.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HappyEyeballsTest {
    private class FakeConnection(val name: String, private val closed: AtomicBoolean = AtomicBoolean(),
                                 private val onClose: () -> Unit = {}) : Closeable {
        override fun close() { closed.set(true); onClose() }
        val isClosed get() = closed.get()
    }

    @Test fun firstSuccessfulRouteWinsAndLateSocketsAreClosed() {
        val firstStarted = CountDownLatch(1)
        val lateClosed = AtomicBoolean()
        val lateCloseSignal = CountDownLatch(1)
        val result = HappyEyeballs.connect(listOf("slow", "fast", "unused"), staggerMs = 150,
            connector = { name ->
                when (name) {
                    "slow" -> {
                        firstStarted.countDown()
                        try { Thread.sleep(420) } catch (_: InterruptedException) { }
                        FakeConnection(name, lateClosed) { lateCloseSignal.countDown() }
                    }
                    "fast" -> { assertTrue(firstStarted.await(1, TimeUnit.SECONDS)); FakeConnection(name) }
                    else -> FakeConnection(name)
                }
            })
        assertEquals("fast", result.candidate)
        assertEquals("fast", result.connection.name)
        assertTrue(result.attempts.any { it.candidate == "slow" && it.result == "cancelled_after_winner" })
        assertTrue(lateCloseSignal.await(1, TimeUnit.SECONDS))
        assertTrue(lateClosed.get())
    }

    @Test fun failuresAreReportedForEveryAddressAndNetworkCandidate() {
        val error = runCatching {
            HappyEyeballs.connect(listOf("a/wifi0", "a/wifi1", "b/wifi0"), staggerMs = 150) {
                throw java.io.IOException("unreachable")
            }
        }.exceptionOrNull() as AllCandidatesFailed
        assertEquals(3, error.attempts.size)
        assertTrue(error.attempts.all { it.result.startsWith("failed:") })
    }
}
