package app.soundfield.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Only SoundField events, no logcat capture or network upload. Disk I/O runs off the audio/UI threads. */
class Telemetry private constructor(private val app: Context) {
    private val journal = TelemetryJournal(File(app.filesDir, "telemetry"))
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(512),
        { job -> Thread(job, "sf-telemetry") }, ThreadPoolExecutor.AbortPolicy())
    private val dropped = AtomicLong()
    private val writeErrors = AtomicLong()
    private data class Limit(var at: Long, var skipped: Long = 0)
    private val limits = HashMap<String, Limit>()
    @Suppress("DEPRECATION")
    private fun metadata(): String {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "app=${app.packageName} version=${info.versionName} build=$code " +
            "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} " +
            "sdk=${Build.VERSION.SDK_INT} pid=${Process.myPid()}"
    }

    init { event("PROCESS_START", metadata()) }

    private fun line(event: String, detail: String, error: Throwable?): String {
        val failure = error?.let { " ${it.javaClass.name}: ${it.message} " + it.stackTrace.take(12).joinToString(" <- ") } ?: ""
        return "${Instant.now()} elapsedMs=${SystemClock.elapsedRealtime()} " +
            "pid=${Process.myPid()} thread=${Thread.currentThread().name} $event ${TelemetryText.clean(detail + failure)}"
    }

    fun event(event: String, detail: String = "", error: Throwable? = null) {
        val entry = line(event, detail, error)
        try {
            worker.execute {
                try { journal.append(entry) }
                catch (e: Exception) { writeErrors.incrementAndGet(); Log.e("SoundField.Telemetry", "Journal write failed", e) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    /** Repetitive UDP/network faults keep a count, with one detailed entry per 5 seconds. */
    fun limited(event: String, detail: String = "", error: Throwable? = null) {
        val now = SystemClock.elapsedRealtime()
        val skipped = synchronized(limits) {
            val previous = limits[event]
            if (previous != null && now - previous.at < 5_000) { previous.skipped++; return }
            val count = previous?.skipped ?: 0
            limits[event] = Limit(now)
            count
        }
        event(event, "$detail suppressedSinceLast=$skipped", error)
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { journal.append(line("UNCAUGHT", "thread=${thread.name}", error)) } catch (_: Exception) { }
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    fun export(snapshot: String, complete: (Result<String>) -> Unit) {
        event("EXPORT_REQUEST")
        try {
            worker.execute {
                val result = runCatching {
                    val suppressed = synchronized(limits) {
                        limits.filterValues { it.skipped > 0 }.map { "${it.key}=${it.value.skipped}" }.joinToString()
                    }
                    val report = "SoundField Telemetria\nExported UTC: ${Instant.now()}\n${metadata()}\n" +
                        "Recent history: up to 3 x 512 KiB, across app restarts.\n" +
                        "Dropped queue events=${dropped.get()} diskWriteErrors=${writeErrors.get()}\n" +
                        "Repeated events not yet summarized: $suppressed\n\nSTATE\n$snapshot\n\nEVENTS\n" + journal.read()
                    TelemetryDownloads.save(app, report)
                }
                result.fold({ event("EXPORT_SAVED", it) }, { event("EXPORT_FAILED", error = it) })
                complete(result)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) { complete(Result.failure(e)) }
    }

    companion object {
        @Volatile private var instance: Telemetry? = null
        fun get(context: Context): Telemetry = instance ?: synchronized(this) {
            instance ?: Telemetry(context.applicationContext).also { instance = it }
        }
    }
}
