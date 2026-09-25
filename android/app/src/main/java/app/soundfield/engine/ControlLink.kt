package app.soundfield.engine

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Управляющий контур: TCP, кадры [u32 длина BE][UTF-8 JSON].
 * Колбэки вызываются из потока чтения — Engine сам перекладывает их в свой поток.
 */
class ControlLink(
    private val socket: Socket,
    private val onMessage: (ControlLink, JSONObject) -> Unit,
    private val onClosed: (ControlLink) -> Unit,
) {
    val remoteAddress: InetAddress = socket.inetAddress
    @Volatile var lastRx = SystemClock.elapsedRealtime(); private set
    private val closed = AtomicBoolean(false)
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "sf-tcp-write") }

    /** Произвольная метка, которую Engine использует для сопоставления (invite/resume/paired). */
    @Volatile var tag: String = ""

    init {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        Thread({ readLoop() }, "sf-tcp-read").start()
    }

    val isOpen: Boolean get() = !closed.get()

    fun send(o: JSONObject) {
        if (closed.get()) return
        try {
            writer.execute {
                try {
                    val b = o.toString().toByteArray(Charsets.UTF_8)
                    out.writeInt(b.size); out.write(b); out.flush()
                } catch (e: Exception) { close() }
            }
        } catch (e: Exception) { /* executor уже остановлен */ }
    }

    private fun readLoop() {
        try {
            val inp = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (!closed.get()) {
                val len = inp.readInt()
                if (len <= 0 || len > 1_000_000) throw IllegalStateException("bad frame $len")
                val b = ByteArray(len)
                inp.readFully(b)
                lastRx = SystemClock.elapsedRealtime()
                val o = try { JSONObject(String(b, Charsets.UTF_8)) } catch (e: Exception) { continue }
                onMessage(this, o)
            }
        } catch (e: Exception) {
            if (!closed.get()) Log.i("SoundField", "link closed: ${e.message}")
        }
        close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { socket.close() } catch (_: Exception) {}
        writer.shutdown()
        onClosed(this)
    }

    /** Закрыть после отправки последнего сообщения (например, bye/reject). */
    fun sendAndClose(o: JSONObject) {
        if (closed.get()) return
        try {
            writer.execute {
                try {
                    val b = o.toString().toByteArray(Charsets.UTF_8)
                    out.writeInt(b.size); out.write(b); out.flush()
                } catch (_: Exception) {}
                close()
            }
        } catch (e: Exception) { close() }
    }
}
