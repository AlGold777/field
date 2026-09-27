package app.soundfield.engine

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.soundfield.core.SF
import app.soundfield.diagnostics.Telemetry

/**
 * Обнаружение через DNS-SD (NSD) — совместимо с Bonjour на iOS.
 * Имя сервиса: "SF-<id>" (ASCII), человекочитаемое имя и платформа — в TXT-записи (n, p, id).
 */
class Discovery(
    private val ctx: Context,
    private val myId: String,
    private val onPeers: (List<Peer>) -> Unit,
) {
    private val telemetry = Telemetry.get(ctx)
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock: WifiManager.MulticastLock =
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("soundfield-mdns").apply { setReferenceCounted(false) }

    @Volatile private var regListener: NsdManager.RegistrationListener? = null
    @Volatile private var discListener: NsdManager.DiscoveryListener? = null
    private val peers = LinkedHashMap<String, Peer>()      // serviceName → Peer
    private val resolvedAt = HashMap<String, Long>()       // serviceName → elapsedRealtime
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
        telemetry.event("NSD_ADVERTISE", "port=$port")
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
            setAttribute("ringing", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "registered ${i.serviceName}"); telemetry.event("NSD_REGISTERED", i.serviceName); regAttempts = 0 }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {
                Log.w(TAG, "register failed $e"); telemetry.event("NSD_REGISTER_FAILED", "code=$e attempt=$regAttempts")
                if (regListener !== this) return
                regListener = null
                // без повтора телефон остаётся невидимым для второго до перезапуска приложения
                if (wantAdvertise && regAttempts++ < 6) main.postDelayed({ if (wantAdvertise && regListener == null) register() }, 1_500)
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) { telemetry.event("NSD_UNREGISTERED", i.serviceName) }
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) { telemetry.event("NSD_UNREGISTER_FAILED", "code=$e") }
        }
        regListener = l
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { Log.w(TAG, "register", e); telemetry.event("NSD_REGISTER_EXCEPTION", error = e) }
    }

    fun unadvertise() {
        telemetry.event("NSD_UNADVERTISE")
        wantAdvertise = false
        regListener?.let { try { nsd.unregisterService(it) } catch (e: Exception) { telemetry.event("NSD_UNREGISTER_EXCEPTION", error = e) } }
        regListener = null
    }

    fun startBrowse() {
        wantBrowse = true
        if (discListener != null) return
        multicastLock.acquire()
        synchronized(lock) { peers.clear(); resolvedAt.clear(); resolveQueue.clear(); resolving = false; resolveToken++ }
        publish()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) { telemetry.event("NSD_BROWSE_STARTED", t) }
            override fun onDiscoveryStopped(t: String) { telemetry.event("NSD_BROWSE_STOPPED", t) }
            override fun onStartDiscoveryFailed(t: String, e: Int) {
                Log.w(TAG, "discovery failed $e"); telemetry.event("NSD_BROWSE_FAILED", "code=$e")
                if (discListener === this) discListener = null
                main.postDelayed({ if (wantBrowse && discListener == null) startBrowse() }, 2_000)
            }
            override fun onStopDiscoveryFailed(t: String, e: Int) { telemetry.event("NSD_STOP_FAILED", "code=$e") }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith(SF.SERVICE_PREFIX)) return
                if (info.serviceName == SF.SERVICE_PREFIX + myId) return
                telemetry.event("NSD_FOUND", info.serviceName)
                val now = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    val last = resolvedAt[info.serviceName] ?: 0L
                    val alreadyKnown = peers.containsKey(info.serviceName)
                    // Не копим дубликаты. Если сервис уже известен, но прошло > 20 с, разрешаем повторный resolve для обновления IP/порта.
                    if (resolveQueue.none { it.serviceName == info.serviceName } && (!alreadyKnown || now - last > 20_000)) {
                        resolveQueue.addLast(info)
                    }
                }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                telemetry.event("NSD_LOST", info.serviceName)
                synchronized(lock) {
                    peers.remove(info.serviceName)
                    resolvedAt.remove(info.serviceName)
                }
                publish()
            }
        }
        discListener = l
        try { nsd.discoverServices(SF.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) {
            Log.w(TAG, "discover", e); telemetry.event("NSD_BROWSE_EXCEPTION", error = e); discListener = null
        }
    }

    fun stopBrowse() {
        wantBrowse = false
        discListener?.let { try { nsd.stopServiceDiscovery(it) } catch (e: Exception) { telemetry.event("NSD_STOP_EXCEPTION", error = e) } }
        discListener = null
    }

    /** Перезапуск поиска — обновляет адреса (после смены IP, выхода из сна и т. п.). */
    fun restartBrowse() { stopBrowse(); startBrowse() }

    fun peer(id: String): Peer? = synchronized(lock) { peers.values.firstOrNull { it.id == id } }

    /** Инвалидация устаревшего пира (например, при ошибке TCP-подключения к старому IP/порту). */
    fun invalidatePeer(id: String) {
        synchronized(lock) {
            val key = peers.entries.firstOrNull { it.value.id == id }?.key ?: return
            peers.remove(key)
            resolvedAt.remove(key)
        }
        publish()
    }

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
        // Сторож снимает зависший resolve через 2.5 с (вместо 5 с), чтобы очередь быстрее двигалась к другим сервисам
        main.postDelayed({
            val stuck = synchronized(lock) { (resolving && resolveToken == token).also { if (it) resolving = false } }
            if (stuck) { Log.w(TAG, "resolve timeout ${next.serviceName}"); telemetry.event("NSD_RESOLVE_TIMEOUT", next.serviceName); resolveNext() }
        }, 2_500)
        telemetry.event("NSD_RESOLVE_START", "service=${next.serviceName} token=$token")
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    telemetry.limited("NSD_RESOLVE_FAILED", "service=${i.serviceName} code=$e token=$token")
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
                    // Keep every mDNS address; the engine orders candidates and matches each against
                    // available local Wi-Fi networks. Link-local IPv6 is omitted because NSD's
                    // InetAddress value does not retain the interface scope needed by Socket.connect.
                    val hosts = buildList {
                        if (android.os.Build.VERSION.SDK_INT >= 34) addAll(i.hostAddresses)
                        i.host?.let(::add)
                    }.filterNot { it is java.net.Inet6Address && it.isLinkLocalAddress }
                        .distinctBy { it.hostAddress }
                        .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                    val host = hosts.firstOrNull()
                    val addresses = hosts.mapNotNull { address -> address.hostAddress?.let { PeerAddress(it, "", "") } }
                    telemetry.record("NSD_RESOLVED", mapOf("service" to i.serviceName,
                        "hosts" to hosts.mapNotNull { it.hostAddress }, "host" to host?.hostAddress,
                        "port" to i.port, "platform" to attr("p"), "ringing" to (attr("ringing") == "1"), "token" to token))
                    val p = Peer(id, attr("n") ?: id, attr("p") ?: "?", host, i.port,
                        supportsRinging = attr("ringing") == "1", hosts = hosts, addresses = addresses)
                    synchronized(lock) {
                        peers[i.serviceName] = p
                        resolvedAt[i.serviceName] = SystemClock.elapsedRealtime()
                        if (resolveToken == token) resolving = false
                    }
                    publish(); resolveNext()
                }
            })
        } catch (e: Exception) {
            telemetry.event("NSD_RESOLVE_EXCEPTION", next.serviceName, e)
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
