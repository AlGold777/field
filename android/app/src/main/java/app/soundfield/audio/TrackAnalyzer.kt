package app.soundfield.audio

import android.net.Uri
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Измеритель громкости и true-peak (адаптер к `TruePeakMeter` — отдельно). Ожидает 48 кГц стерео интерлив. */
interface Meter : AutoCloseable {
    fun process(block: FloatArray, frames: Int)
    val truePeakDbtp: Double
    val loudnessLufs: Double
}

/**
 * Фоновый анализатор треков: один раз на файл считает LUFS, true-peak и длину, результат кладёт в [store].
 * Декодер приходит через [scan] (48 кГц стерео интерлив блоками); работа идёт в одном потоке [executor]
 * (по умолчанию [backgroundExecutor]: низкий приоритет). [request] принимает onDone, который вызывается из рабочего
 * потока (или сразу из вызывающего, если результат уже в хранилище).
 */
class TrackAnalyzer(
    private val store: TrackAnalysisStore,
    private val scan: (uri: Uri, cancelled: () -> Boolean, consumer: (block: FloatArray, frames: Int) -> Unit) -> Unit,
    private val meterFactory: () -> Meter,
    private val executor: ExecutorService = backgroundExecutor(),
) {
    private class Job(val id: TrackIdentity, val uri: Uri) {
        val callbacks = ArrayList<(TrackAnalysisResult) -> Unit>()
        val errors = ArrayList<(Throwable) -> Unit>()
        @Volatile var cancelled = false
    }

    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val pending = ArrayDeque<Job>()
    private var closed = false

    /**
     * Запрашивает анализ. Результат уже есть — [onDone] сразу. Тот же трек уже в работе — коллбек присоединяется,
     * работа не дублируется. [priority] (играющий трек) ставит задачу в начало очереди.
     * Ошибка декодера: запись не создаётся, [onDone] не вызывается, вызывается [onError].
     */
    fun request(
        id: TrackIdentity, uri: Uri, priority: Boolean,
        onError: ((Throwable) -> Unit)? = null, onDone: (TrackAnalysisResult) -> Unit,
    ) {
        store.get(id)?.let { onDone(it); return }
        synchronized(lock) {
            if (closed) return
            val existing = jobs[id.key]
            if (existing != null) {
                existing.callbacks += onDone
                onError?.let { existing.errors += it }
                if (priority && pending.remove(existing)) pending.addFirst(existing)
                return
            }
            val job = Job(id, uri)
            job.callbacks += onDone
            onError?.let { job.errors += it }
            jobs[id.key] = job
            if (priority) pending.addFirst(job) else pending.addLast(job)
        }
        try { executor.execute(::runNext) } catch (_: Exception) { cancel(id) }
    }

    /** Отмена: убирает из очереди, идущее сканирование прерывается (cancelled()), результат не сохраняется. */
    fun cancel(id: TrackIdentity) {
        synchronized(lock) { jobs.remove(id.key)?.let { it.cancelled = true; pending.remove(it) } }
    }

    fun cancelAll() {
        synchronized(lock) { jobs.values.forEach { it.cancelled = true }; jobs.clear(); pending.clear() }
    }

    fun close() {
        synchronized(lock) { closed = true }
        cancelAll()
        executor.shutdownNow()
    }

    private fun runNext() {
        val job = synchronized(lock) { pending.pollFirst() } ?: return
        if (job.cancelled) return
        try {
            var frames = 0L
            val result = meterFactory().use { meter ->
                scan(job.uri, { job.cancelled }) { block, n -> if (n > 0) { meter.process(block, n); frames += n } }
                if (job.cancelled) return
                check(frames > 0) { "Декодер не отдал ни одного кадра" }
                TrackAnalysisResult(meter.loudnessLufs, meter.truePeakDbtp, frames)
            }
            store.put(job.id, result)
            val callbacks = synchronized(lock) {
                if (jobs[job.id.key] === job) jobs.remove(job.id.key)
                job.callbacks.toList()
            }
            if (!job.cancelled) callbacks.forEach { try { it(result) } catch (_: Exception) {} }
        } catch (e: Throwable) {
            val errors = synchronized(lock) {
                if (jobs[job.id.key] === job) jobs.remove(job.id.key)
                job.errors.toList()
            }
            if (!job.cancelled) errors.forEach { try { it(e) } catch (_: Exception) {} }
        }
    }

    companion object {
        /** Один поток минимального приоритета (фон), демон. */
        fun backgroundExecutor(): ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread({
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
                r.run()
            }, "track-analyzer").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
        }
    }
}
