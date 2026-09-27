package app.soundfield.engine

import android.os.SystemClock
import app.soundfield.diagnostics.Telemetry
import java.util.concurrent.atomic.AtomicLong
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
    private val telemetry: Telemetry,
    initialTag: String = "",
    val localNetwork: String = "unknown",
    val localNetworkId: String = "unknown",
) {
    companion object { private val ids = AtomicLong() }
    val id = ids.incrementAndGet()
    private fun event(kind: String, detail: String = "", error: Throwable? = null) =
        telemetry.event(kind, "link=$id tag=$tag local=${localAddress.hostAddress}:${socket.localPort} network=$localNetwork networkId=$localNetworkId remote=${socket.remoteSocketAddress} $detail", error)

    val remoteAddress: InetAddress = socket.inetAddress
    val localAddress: InetAddress = socket.localAddress
    @Volatile var lastRx = SystemClock.elapsedRealtime(); private set
    private val closed = AtomicBoolean(false)
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "sf-tcp-write") }

    /** Произвольная метка, которую Engine использует для сопоставления (invite/resume/paired). */
    @Volatile var tag: String = initialTag

    init {
        event("TCP_OPEN")
        socket.tcpNoDelay = true
        socket.keepAlive = true
        Thread({ readLoop() }, "sf-tcp-read").start()
    }

    val isOpen: Boolean get() = !closed.get()

    private fun message(direction: String, o: JSONObject) {
        val type = o.optString("t")
        if (type in setOf("invite", "resume", "accept", "reject", "busy", "cancel", "ringing", "session", "bye", "network-warning"))
            event(direction, "type=$type reason=${o.optString("reason")} tcpPort=${o.optInt("tcpPort")} udpPort=${o.optInt("udpPort")}")
    }

    fun send(o: JSONObject) {
        if (closed.get()) return
        try {
            writer.execute {
                try {
                    val b = o.toString().toByteArray(Charsets.UTF_8)
                    out.writeInt(b.size); out.write(b); out.flush()
                    message("TCP_TX", o)
                } catch (e: Exception) { event("TCP_WRITE_FAILED", error = e); close("write failed") }
            }
        } catch (e: Exception) { event("TCP_WRITER_REJECTED", error = e) }
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
                val o = try { JSONObject(String(b, Charsets.UTF_8)) } catch (e: Exception) { event("TCP_BAD_JSON", "frameBytes=$len", e); continue }
                message("TCP_RX", o)
                onMessage(this, o)
            }
        } catch (e: Exception) {
            if (!closed.get()) event("TCP_READ_ENDED", error = e)
        }
        close("reader ended")
    }

    fun close(reason: String = "local close") {
        if (!closed.compareAndSet(false, true)) return
        event("TCP_CLOSE", reason)
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
                    message("TCP_TX", o)
                } catch (e: Exception) { event("TCP_WRITE_FAILED", error = e) }
                close("sent final message")
            }
        } catch (e: Exception) { event("TCP_WRITE_FAILED", error = e); close("write failed") }
    }
}
