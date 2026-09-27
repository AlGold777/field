package app.soundfield.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.soundfield.core.SF

/**
 * Обнаружение через DNS-SD (NSD) — совместимо с Bonjour на iOS.
 * Имя сервиса: "SF-<id>" (ASCII), человекочитаемое имя и платформа — в TXT-записи (n, p, id).
 */
class Discovery(
    private val ctx: Context,
    private val myId: String,
    private val onPeers: (List<Peer>) -> Unit,
) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock: WifiManager.MulticastLock =
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("soundfield-mdns").apply { setReferenceCounted(false) }

    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private val peers = LinkedHashMap<String, Peer>()      // serviceName → Peer
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var resolveToken = 0
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    val browsing: Boolean get() = discListener != null
    /** Сервис этого телефона опубликован (или публикуется) в сети. */
    val advertising: Boolean get() = wantAdvertise
    @Volatile private var wantAdvertise = false
    @Volatile private var wantBrowse = false
    @Volatile private var adPort = 0
    @Volatile private var adName = ""
    private var regAttempts = 0

    /**
     * Публикация сервиса. Повторная публикация идёт с паузой после снятия старой: мгновенный
     * unregister+register с тем же именем на многих прошивках даёт отказ, и телефон становится невидим.
     */
    fun advertise(port: Int, name: String) {
        val had = regListener != null
        unadvertise()
        wantAdvertise = true; adPort = port; adName = name; regAttempts = 0
        multicastLock.acquire()
        if (had) main.postDelayed({ if (wantAdvertise && regListener == null) register() }, 700) else register()
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = SF.SERVICE_PREFIX + myId
            serviceType = SF.SERVICE_TYPE
            setPort(adPort)
            setAttribute("n", adName.take(60))
            setAttribute("p", "android")
            setAttribute("id", myId)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "registered ${i.serviceName}"); regAttempts = 0 }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {
                Log.w(TAG, "register failed $e")
                if (regListener !== this) return
                regListener = null
                // без повтора телефон остаётся невидимым для второго до перезапуска приложения
                if (wantAdvertise && regAttempts++ < 6) main.postDelayed({ if (wantAdvertise && regListener == null) register() }, 1_500)
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        regListener = l
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { Log.w(TAG, "register", e) }
    }

    fun unadvertise() {
        wantAdvertise = false
        regListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        regListener = null
    }

    fun startBrowse() {
        wantBrowse = true
        if (discListener != null) return
        multicastLock.acquire()
        synchronized(lock) { peers.clear(); resolveQueue.clear(); resolving = false; resolveToken++ }
        publish()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {
                Log.w(TAG, "discovery failed $e")
                if (discListener === this) discListener = null
                main.postDelayed({ if (wantBrowse && discListener == null) startBrowse() }, 2_000)
            }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith(SF.SERVICE_PREFIX)) return
                if (info.serviceName == SF.SERVICE_PREFIX + myId) return
                // NSD часто сообщает об одном и том же сервисе много раз — не копим дубликаты в очереди resolve
                synchronized(lock) { if (resolveQueue.none { it.serviceName == info.serviceName }) resolveQueue.addLast(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(lock) { peers.remove(info.serviceName) }
                publish()
            }
        }
        discListener = l
        try { nsd.discoverServices(SF.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) {
            Log.w(TAG, "discover", e); discListener = null
        }
    }

    fun stopBrowse() {
        wantBrowse = false
        discListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discListener = null
    }

    /** Перезапуск поиска — обновляет адреса (после смены IP, выхода из сна и т. п.). */
    fun restartBrowse() { stopBrowse(); startBrowse() }

    fun peer(id: String): Peer? = synchronized(lock) { peers.values.firstOrNull { it.id == id } }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next: NsdServiceInfo
        val token: Int
        synchronized(lock) {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            next = resolveQueue.removeFirst()
            token = ++resolveToken
        }
        // Известная проблема NSD: колбэк resolve иногда не приходит вовсе, и очередь «застревает» навсегда —
        // тогда новые адреса не находятся до перезапуска приложения. Сторож снимает блокировку через 5 с.
        main.postDelayed({
            val stuck = synchronized(lock) { (resolving && resolveToken == token).also { if (it) resolving = false } }
            if (stuck) { Log.w(TAG, "resolve timeout ${next.serviceName}"); resolveNext() }
        }, 5_000)
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    synchronized(lock) {
                        if (resolveToken == token) resolving = false
                        if (e == NsdManager.FAILURE_ALREADY_ACTIVE) resolveQueue.addLast(i)
                    }
                    if (e == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ resolveNext() }, 300) else resolveNext()
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val attrs = i.attributes
                    fun attr(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }
                    val id = attr("id") ?: i.serviceName.removePrefix(SF.SERVICE_PREFIX)
                    // Android 14+: у сервиса может быть несколько адресов; IPv6 link-local без зоны часто
                    // недоступен и соединение висит до таймаута — берём IPv4, если он есть.
                    val host = if (android.os.Build.VERSION.SDK_INT >= 34)
                        (i.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: i.host) else i.host
                    val p = Peer(id, attr("n") ?: id, attr("p") ?: "?", host, i.port)
                    synchronized(lock) { peers[i.serviceName] = p; if (resolveToken == token) resolving = false }
                    publish(); resolveNext()
                }
            })
        } catch (e: Exception) {
            synchronized(lock) { resolving = false }
        }
    }

    private fun publish() {
        val list = synchronized(lock) { peers.values.filter { it.id != myId }.toList() }
        onPeers(list)
    }

    fun shutdown() { stopBrowse(); unadvertise(); try { multicastLock.release() } catch (_: Exception) {} }

    companion object { private const val TAG = "SoundField.NSD" }
}
