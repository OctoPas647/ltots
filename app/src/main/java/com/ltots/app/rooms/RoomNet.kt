package com.ltots.app.rooms

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.ltots.app.CrashReport
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Who's on the other end of a link, after the handshake. */
class Hello(val peer: Peer, val role: String, val port: Int)

/**
 * One phone-to-phone connection, after the handshake: a reader thread handing frames to the
 * room, and a writer thread with a queue (so a slow phone never holds up the others; if one
 * falls far behind, its oldest music is dropped rather than everyone's).
 */
class RoomLink(
    val socket: Socket,
    val hello: Hello,
    private val onFrame: (RoomLink, RoomWire.Frame) -> Unit,
    private val onClosed: (RoomLink) -> Unit,
) {
    val peer: Peer get() = hello.peer
    val id: String get() = hello.peer.id
    val remoteHost: String = socket.inetAddress?.hostAddress ?: ""
    val localHost: String = socket.localAddress?.hostAddress ?: ""
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val queue = LinkedBlockingDeque<Pair<Int, ByteArray>>()
    private val audioQueued = AtomicInteger(0)
    @Volatile var closed = false; private set
    @Volatile var lastHeardMs: Long = System.currentTimeMillis(); private set

    fun start() {
        socket.soTimeout = 0
        Thread({ readLoop() }, "LToTS-room-rx-$id").apply { isDaemon = true; start() }
        Thread({ writeLoop() }, "LToTS-room-tx-$id").apply { isDaemon = true; start() }
    }

    fun send(type: Int, payload: ByteArray) {
        if (closed) return
        if (type == RoomWire.AUDIO) {
            // Over 3 s of music waiting: this phone can't keep up; drop the oldest piece.
            if (audioQueued.get() > 150) {
                val it = queue.iterator()
                while (it.hasNext()) {
                    if (it.next().first == RoomWire.AUDIO) { it.remove(); audioQueued.decrementAndGet(); break }
                }
            }
            audioQueued.incrementAndGet()
        }
        queue.offer(type to payload)
    }

    fun sendJson(type: Int, json: JSONObject) = send(type, json.toString().toByteArray(Charsets.UTF_8))

    private fun readLoop() {
        try {
            while (!closed) {
                val f = RoomWire.read(input) ?: break
                lastHeardMs = System.currentTimeMillis()
                try {
                    onFrame(this, f)
                } catch (e: Throwable) {
                    CrashReport.handled("RoomLink.onFrame ${f.type}", e)
                }
            }
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val (type, payload) = queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (type == RoomWire.AUDIO) audioQueued.decrementAndGet()
                synchronized(out) {
                    out.writeByte(type)
                    out.writeInt(payload.size)
                    out.write(payload)
                    // Send at once when nothing else is waiting (TCP_NODELAY is on).
                    if (queue.isEmpty()) out.flush()
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        queue.clear()
        onClosed(this)
    }

    companion object {
        /**
         * The handshake: who are you, do we know each other (if not, both phones show a code
         * to compare), and prove it. [ask] blocks until the person answers the code (or times
         * out). Returns null if the phones didn't agree.
         */
        fun handshake(
            socket: Socket,
            me: RoomIdentity,
            myRole: String,
            myPort: Int,
            ask: (peerName: String, code: String) -> Boolean,
        ): Hello? {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 15_000
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val myNonce = RoomCrypto.nonce()
            RoomWire.writeJson(
                out, RoomWire.HELLO,
                JSONObject().put("v", RoomWire.VERSION).put("id", me.id).put("name", me.name)
                    .put("pub", RoomCrypto.b64(me.publicKey)).put("nonce", RoomCrypto.b64(myNonce))
                    .put("role", myRole).put("port", myPort),
            )
            val h = RoomWire.read(input)?.takeIf { it.type == RoomWire.HELLO }?.json() ?: return null
            if (h.optInt("v") != RoomWire.VERSION) return null
            val pub = RoomCrypto.unb64(h.getString("pub"))
            val peerId = h.getString("id")
            if (RoomCrypto.idOf(pub) != peerId || peerId == me.id) return null
            val peer = Peer(peerId, h.optString("name", "Phone"), pub)
            val theirNonce = RoomCrypto.unb64(h.getString("nonce"))
            val shared = RoomCrypto.shared(me.keys.private, RoomCrypto.publicFrom(pub))

            // Do we both know each other already?
            val iTrust = me.isTrusted(peerId, pub)
            RoomWire.writeJson(out, RoomWire.PAIR_OK, JSONObject().put("trusts", iTrust))
            val theyTrust = RoomWire.read(input)?.takeIf { it.type == RoomWire.PAIR_OK }?.json()?.optBoolean("trusts") ?: return null
            if (!iTrust || !theyTrust) {
                // First meeting (or one of them forgot): compare the codes.
                val code = RoomCrypto.pairingCode(shared, myNonce, theirNonce)
                socket.soTimeout = 120_000
                val ok = ask(peer.name, code)
                RoomWire.writeJson(out, if (ok) RoomWire.PAIR_OK else RoomWire.PAIR_NO, JSONObject().put("ok", ok))
                if (!ok) return null
                val answer = RoomWire.read(input) ?: return null
                if (answer.type != RoomWire.PAIR_OK) return null
                me.trust(peer)
                socket.soTimeout = 15_000
            }
            RoomWire.writeJson(out, RoomWire.AUTH, JSONObject().put("mac", RoomCrypto.b64(RoomCrypto.authMac(shared, me.id, myNonce, theirNonce))))
            val a = RoomWire.read(input)?.takeIf { it.type == RoomWire.AUTH }?.json() ?: return null
            val expected = RoomCrypto.authMac(shared, peerId, myNonce, theirNonce)
            if (!java.security.MessageDigest.isEqual(expected, RoomCrypto.unb64(a.getString("mac")))) return null
            if (!me.isTrusted(peerId, pub)) me.trust(peer)
            return Hello(peer, h.optString("role", "node"), h.optInt("port", 0))
        }

        fun connect(host: String, port: Int): Socket = Socket().apply {
            connect(InetSocketAddress(host, port), 5_000)
        }
    }
}

/**
 * The main's clock: answers pings with its group time (see [ClockEstimator]).
 */
class ClockServer(private val token: Long) {
    private val socket = DatagramSocket(0)
    val port: Int get() = socket.localPort
    @Volatile private var running = true

    init {
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val buf = ByteArray(64)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val t1 = GroupClock.now()
                    val reply = ClockPacket.reply(buf, p.length, token, t1, GroupClock.now()) ?: continue
                    // t2 is taken as late as possible: just before sending.
                    val t2 = GroupClock.now()
                    java.nio.ByteBuffer.wrap(reply).putLong(ClockPacket.REQUEST_SIZE + 8, t2)
                    socket.send(DatagramPacket(reply, reply.size, p.socketAddress))
                } catch (_: IOException) {
                    if (!running) break
                }
            }
        }, "LToTS-room-clock").apply { isDaemon = true; start() }
    }

    fun close() {
        running = false
        socket.close()
    }
}

/**
 * A relay's side of the clock: pings the main and feeds [estimator]. Fast at first (to be
 * ready within seconds), then a few times a second.
 */
class ClockClient(private val host: String, private val port: Int, private val token: Long, val estimator: ClockEstimator) {
    private val socket = DatagramSocket()
    @Volatile private var running = true
    private val sent = ConcurrentHashMap<Int, Long>()

    init {
        socket.soTimeout = 300
        val addr = InetAddress.getByName(host)
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            var seq = 0
            val started = System.nanoTime()
            val buf = ByteArray(64)
            while (running) {
                try {
                    seq++
                    val t0 = System.nanoTime()
                    val req = ClockPacket.request(token, seq, t0)
                    socket.send(DatagramPacket(req, req.size, addr, port))
                    // Read every answer that arrives before the next ping.
                    val fast = System.nanoTime() - started < 15_000_000_000L
                    val gap = if (fast) 100_000_000L else 400_000_000L
                    val until = t0 + gap
                    while (running) {
                        val left = (until - System.nanoTime()) / 1_000_000L
                        if (left <= 0) break
                        socket.soTimeout = left.toInt().coerceAtLeast(1)
                        val p = DatagramPacket(buf, buf.size)
                        try {
                            socket.receive(p)
                        } catch (_: java.net.SocketTimeoutException) {
                            break
                        }
                        val t3 = System.nanoTime()
                        val r = ClockPacket.parseReply(buf, p.length, token) ?: continue
                        estimator.add(ClockSample(r.t0, r.t1, r.t2, t3))
                    }
                } catch (_: IOException) {
                    if (!running) break
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                }
            }
        }, "LToTS-room-ping").apply { isDaemon = true; start() }
    }

    fun close() {
        running = false
        socket.close()
        sent.clear()
    }
}

/** A phone running LToTS Rooms that this one can see on the Wi-Fi. */
data class Nearby(val id: String, val name: String, val host: String, val port: Int, val isMain: Boolean, val seenMs: Long)

/**
 * Finding the other phones on the Wi-Fi (Android's network service discovery, mDNS): each
 * phone in Rooms announces itself, with whether it's a room's main.
 */
class RoomDiscovery(context: Context, private val onChange: (List<Nearby>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val found = ConcurrentHashMap<String, Nearby>()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = LinkedBlockingDeque<NsdServiceInfo>()
    @Volatile private var resolving = false
    @Volatile private var myId = ""

    fun announce(id: String, name: String, port: Int, isMain: Boolean) {
        myId = id
        unannounce()
        val info = NsdServiceInfo().apply {
            serviceName = "LToTS-$id"
            serviceType = TYPE
            this.port = port
            setAttribute("id", id)
            setAttribute("name", name.take(60))
            setAttribute("main", if (isMain) "1" else "0")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {}
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { CrashReport.handled("RoomDiscovery.announce", it) }
    }

    private fun unannounce() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    fun start() {
        if (discovery != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName == "LToTS-$myId") return
                resolveQueue.offer(s)
                resolveNext()
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                val id = s.serviceName.removePrefix("LToTS-")
                if (found.remove(id) != null) onChange(list())
            }
        }
        discovery = l
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { CrashReport.handled("RoomDiscovery.start", it) }
    }

    @Suppress("DEPRECATION") // resolveService works on every Android version LToTS runs on.
    private fun resolveNext() {
        synchronized(this) {
            if (resolving) return
            val s = resolveQueue.poll() ?: return
            resolving = true
            runCatching {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        runCatching {
                            val attrs = info.attributes
                            val id = attrs["id"]?.let { String(it) } ?: info.serviceName.removePrefix("LToTS-")
                            val host = info.host?.hostAddress
                            if (host != null && id != myId) {
                                found[id] = Nearby(
                                    id, attrs["name"]?.let { String(it) } ?: "Phone", host, info.port,
                                    attrs["main"]?.let { String(it) } == "1", System.currentTimeMillis(),
                                )
                                onChange(list())
                            }
                        }
                        done()
                    }
                    fun done() {
                        synchronized(this@RoomDiscovery) { resolving = false }
                        resolveNext()
                    }
                })
            }.onFailure {
                resolving = false
            }
        }
    }

    /** Looks again (a phone that changed role re-announces itself). */
    fun refresh() {
        stopDiscovery()
        found.clear()
        onChange(emptyList())
        start()
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    fun list(): List<Nearby> = found.values.sortedBy { it.name }

    fun stop() {
        unannounce()
        stopDiscovery()
        found.clear()
        resolveQueue.clear()
    }

    companion object {
        const val TYPE = "_ltotsroom._tcp."
    }
}
