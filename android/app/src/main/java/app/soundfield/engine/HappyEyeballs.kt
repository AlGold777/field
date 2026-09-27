package app.soundfield.engine

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ConnectionAttempt<C>(val index: Int, val candidate: C, val result: String)
data class RaceWinner<C, R : Closeable>(val candidate: C, val connection: R, val attempts: List<ConnectionAttempt<C>>)

/** Happy Eyeballs for a bounded set of host × local-network candidates. */
object HappyEyeballs {
    fun <C, R : Closeable> connect(
        candidates: List<C>,
        staggerMs: Long = 200,
        cancelInFlight: () -> Unit = {},
        onAttempt: (Int, C, String, Throwable?) -> Unit = { _, _, _, _ -> },
        connector: (C) -> R,
    ): RaceWinner<C, R> {
        require(candidates.isNotEmpty())
        require(staggerMs in 150..250)
        val executor = Executors.newFixedThreadPool(candidates.size) { task ->
            Thread(task, "sf-connect-candidate").apply { isDaemon = true }
        }
        val completion = ExecutorCompletionService<Int>(executor)
        val winner = AtomicReference<Pair<Int, R>?>(null)
        val attempts = CopyOnWriteArrayList<ConnectionAttempt<C>>()
        fun report(index: Int, candidate: C, status: String, error: Throwable?) {
            runCatching { onAttempt(index, candidate, status, error) }
        }
        val futures = candidates.mapIndexed { index, candidate ->
            completion.submit(Callable {
                var connection: R? = null
                try {
                    if (index > 0) TimeUnit.MILLISECONDS.sleep(index * staggerMs)
                    connection = connector(candidate)
                    if (winner.compareAndSet(null, index to connection)) {
                        attempts += ConnectionAttempt(index, candidate, "connected")
                        report(index, candidate, "connected", null)
                    } else {
                        connection.close()
                        attempts += ConnectionAttempt(index, candidate, "cancelled_after_winner")
                        report(index, candidate, "cancelled_after_winner", null)
                    }
                } catch (error: Throwable) {
                    try { connection?.close() } catch (_: Exception) { }
                    val status = if (error is InterruptedException) "cancelled" else "failed: ${error.message ?: error.javaClass.simpleName}"
                    attempts += ConnectionAttempt(index, candidate, status)
                    report(index, candidate, status, error.takeUnless { it is InterruptedException })
                }
                index
            })
        }

        try {
            repeat(candidates.size) {
                val completed = completion.take()
                completed.get()
                val won = winner.get()
                if (won != null) {
                    cancelInFlight()
                    futures.forEachIndexed { index, future -> if (index != won.first) future.cancel(true) }
                    executor.shutdownNow()
                    val connection = won.second
                    val ordered = candidates.mapIndexed { index, candidate ->
                        attempts.firstOrNull { it.index == index } ?: ConnectionAttempt(index, candidate, "cancelled_after_winner")
                    }
                    return RaceWinner(candidates[won.first], connection, ordered)
                }
            }
        } finally {
            executor.shutdownNow()
        }
        cancelInFlight()
        val ordered = candidates.mapIndexed { index, candidate ->
            attempts.firstOrNull { it.index == index } ?: ConnectionAttempt(index, candidate, "failed: no result")
        }
        throw AllCandidatesFailed(ordered)
    }
}

class AllCandidatesFailed(val attempts: List<ConnectionAttempt<*>>) :
    Exception("All ${attempts.size} host and network candidates failed")
