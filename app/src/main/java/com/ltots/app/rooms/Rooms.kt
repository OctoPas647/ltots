package com.ltots.app.rooms

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import androidx.media3.common.util.UnstableApi
import com.ltots.app.CrashReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Rooms: LToTS on several phones playing as one.
 *
 * One phone is the **main**: it plays the queue with your EQ, pitch and speed as usual, but its
 * finished sound goes to the room ([RoomStream]) instead of its own speaker. Every phone in the
 * room (the main too) runs a [RoomRenderer] that plays that sound on time against one shared
 * clock ([GroupClock]).
 *
 *  - **Earbuds**: only one phone sends to the earbuds at a time — the one whose link is best.
 *    When it gets worse ([HandoffPolicy]), the other phone takes over at the same point in the
 *    song.
 *  - **Speakers**: every phone plays to its own speaker, in sync, each corrected by the mic
 *    calibration for its speaker's own Bluetooth delay.
 *
 * Any phone can become the main (Make this main): the queue, position and sound settings move
 * across without the music stopping.
 */
@UnstableApi
@SuppressLint("StaticFieldLeak") // only the application context is kept
object Rooms {
    enum class Role { OFF, MAIN, NODE }
    enum class Mode { EARBUDS, SPEAKERS }

    /** One phone in the room, as the room's screen shows it. */
    data class Member(
        val id: String,
        val name: String,
        val isMain: Boolean,
        val isMe: Boolean,
        val output: String,
        val earbuds: Boolean,
        val rssi: Int?,
        val rssiSource: String,
        val stall: Double,
        val active: Boolean,
        val rendering: Boolean,
        val syncMs: Double?,
        val adjustMs: Double,
        val plays: Boolean,
        val clockReady: Boolean,
        /** Its output's own delay (Bluetooth), ms. */
        val latencyMs: Double?,
        /** How early the music reaches it before it must be written (negative: too late), ms. */
        val marginMs: Double?,
        /** Reached over Tailscale rather than the local Wi-Fi. */
        val tailscale: Boolean,
        /** Every output the phone has now, and the ones chosen (empty: its usual one). */
        val outputs: List<OutputChoice> = emptyList(),
        val selected: List<String> = emptyList(),
        val sinks: List<OutputState> = emptyList(),
    )

    /** An output a phone has (its speaker, a Bluetooth device…). */
    data class OutputChoice(val key: String, val name: String, val kind: String)

    /** One output a phone plays to, and how it's doing. */
    data class OutputState(
        val key: String,
        val name: String,
        val rendering: Boolean,
        val syncMs: Double,
        val latencyMs: Double,
        /** Android sent it where asked (false: this phone can't play there together with the rest). */
        val routedOk: Boolean,
        val routedTo: String,
        val adjustMs: Double,
    )

    data class NearbyPhone(val id: String, val name: String, val isMain: Boolean, val trusted: Boolean)

    data class Ui(
        val role: Role = Role.OFF,
        val available: Boolean = false,
        val mode: Mode = Mode.EARBUDS,
        val mainName: String = "",
        val members: List<Member> = emptyList(),
        val nearby: List<NearbyPhone> = emptyList(),
        val nowTitle: String = "",
        val nowArtist: String = "",
        val playing: Boolean = false,
        val earbudsName: String = "",
        val earbudsAddress: String = "",
        val earbudChoices: List<Pair<String, String>> = emptyList(),
        val status: String = "",
        val calibration: String = "",
        val calibrating: Boolean = false,
        val lastSwitch: String = "",
        val handingOver: Boolean = false,
        /** How far ahead the music is sent (ms): grows by itself when a phone needs it. */
        val delayMs: Long = 0,
        /** This phone's addresses (label to address), for connecting over Tailscale by hand. */
        val myAddresses: List<Pair<String, String>> = emptyList(),
        val port: Int = 0,
        val connectError: String = "",
    )

    data class PairPrompt(val peerName: String, val code: String, internal val answer: CompletableFuture<Boolean>)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private val _pairing = MutableStateFlow<PairPrompt?>(null)
    val pairing: StateFlow<PairPrompt?> = _pairing

    private lateinit var app: Context
    private lateinit var me: RoomIdentity
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var initialized = false

    @Volatile private var role = Role.OFF
    @Volatile private var mode = Mode.EARBUDS
    private var server: ServerSocket? = null
    private var serverPort = 0
    private var discovery: RoomDiscovery? = null
    private val links = ConcurrentHashMap<String, RoomLink>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private var loop: Job? = null

    // ---- main side ----
    private var clockServer: ClockServer? = null
    private var clockToken = 0L
    private val reports = ConcurrentHashMap<String, JSONObject>()
    @Volatile private var activeEarbuds: String? = null
    private val policy = HandoffPolicy()
    private val backlog = ArrayDeque<Pair<AudioChunk, ByteArray>>()
    @Volatile private var calStatus = ""
    @Volatile private var calPlanId = 0
    private var handoffTo: String? = null

    // ---- relay side ----
    @Volatile private var mainId: String? = null
    private val clocks = ConcurrentHashMap<String, ClockClient>()
    @Volatile private var lastState: JSONObject? = null
    /** The main is moving to this phone at this group time. */
    @Volatile private var nextMain: Pair<String, Long>? = null

    val renderer = RoomRenderer()
    private var earbuds: EarbudLink? = null

    // ------------------------------------------------------------------------------------------
    // Setup

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = app.getSharedPreferences("rooms", Context.MODE_PRIVATE)
        me = RoomIdentity.get(app)
        mode = runCatching { Mode.valueOf(prefs.getString("mode", Mode.EARBUDS.name)!!) }.getOrDefault(Mode.EARBUDS)
        earbuds = EarbudLink(app)
        publish()
    }

    /** Was this phone left available for rooms (the service brings that back)? */
    fun wantsAvailable(): Boolean = initialized && prefs.getBoolean("available", false)

    val myName: String get() = if (initialized) me.name else ""

    /** This phone starts a room and plays the main role. */
    fun startHere(context: Context) {
        init(context)
        if (role == Role.MAIN) return
        // Already in someone's room: take it over instead of starting a second one.
        if (role == Role.NODE && mainId != null) { makeMeMain(); return }
        RoomService.start(app)
        openServer()
        becomeMain(ClockMap.IDENTITY, inherit = null)
    }

    /** This phone joins rooms on the Wi-Fi by itself (and waits for one). */
    fun setAvailable(context: Context, on: Boolean) {
        init(context)
        prefs.edit().putBoolean("available", on).apply()
        if (on) {
            if (role == Role.OFF) {
                RoomService.start(app)
                openServer()
                role = Role.NODE
                // No room time until a main is found and the clock has synced.
                GroupClock.follow(ClockEstimator())
                discovery?.announce(me.id, me.name, serverPort, false)
                startLoop()
            }
        } else if (role == Role.NODE) {
            leave()
        }
        publish()
    }

    /** Leave the room (as main: end it for everyone). */
    fun leave() {
        if (!initialized) return
        scope.launch { leaveNow() }
    }

    private fun leaveNow() {
        val wasMain = role == Role.MAIN
        prefs.edit().putBoolean("available", false).apply()
        for (l in links.values) runCatching { l.sendJson(RoomWire.BYE, JSONObject()) }
        Thread.sleep(50)
        links.values.forEach { it.close() }
        links.clear()
        loop?.cancel(); loop = null
        clocks.values.forEach { it.close() }; clocks.clear()
        clockServer?.close(); clockServer = null
        discovery?.stop(); discovery = null
        runCatching { server?.close() }; server = null
        earbuds?.stop()
        renderer.stop()
        RoomMediaServer.stop()
        role = Role.OFF
        mainId = null
        nextMain = null
        activeEarbuds = null
        reports.clear()
        synchronized(backlog) { backlog.clear() }
        GroupClock.lead()
        RoomStream.listener = null
        RoomStream.stopAtNs = Long.MAX_VALUE
        RoomStream.pendingAnchorNs = 0L
        if (RoomStream.active || wasMain) {
            RoomStream.active = false
            RoomPlayer.onMain { com.ltots.app.audio.AudioEngine.roomChanged() }
        }
        RoomPlayer.restoreOwnEq()
        RoomService.stop(app)
        publish()
    }

    private fun openServer() {
        if (server != null) return
        // The usual port first, so it can be typed in for Tailscale; any free one otherwise.
        val s = runCatching { ServerSocket(RoomLink.PORT) }.getOrElse { ServerSocket(0) }
        server = s
        serverPort = s.localPort
        Thread({
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: IOException) { break }
                Thread({ accept(sock) }, "LToTS-room-hello").apply { isDaemon = true; start() }
            }
        }, "LToTS-room-accept").apply { isDaemon = true; start() }
        discovery = RoomDiscovery(app) { autoJoin(); publish() }.also { it.start() }
        earbuds?.start()
        renderer.start()
    }

    private fun startLoop() {
        loop?.cancel()
        loop = scope.launch {
            var tick = 0
            while (isActive) {
                try {
                    when (role) {
                        Role.MAIN -> mainTick(tick)
                        Role.NODE -> nodeTick(tick)
                        Role.OFF -> {}
                    }
                } catch (e: Throwable) {
                    CrashReport.handled("Rooms.tick", e)
                }
                tick++
                delay(250)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Connections

    private fun myRoleName() = if (role == Role.MAIN) "main" else "node"

    private fun ask(peerName: String, code: String): Boolean {
        val f = CompletableFuture<Boolean>()
        _pairing.value = PairPrompt(peerName, code, f)
        RoomService.notifyPairing(app, peerName, code)
        return try {
            f.get(90, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            if (_pairing.value?.answer === f) _pairing.value = null
        }
    }

    /** The person's answer to the code on screen. */
    fun answerPairing(ok: Boolean) {
        _pairing.value?.answer?.complete(ok)
        _pairing.value = null
    }

    private fun accept(sock: java.net.Socket) {
        try {
            val hello = RoomLink.handshake(sock, me, myRoleName(), serverPort, ::ask, myAddrs = myAddrs()) ?: run { sock.close(); return }
            linked(sock, hello)
        } catch (e: Throwable) {
            runCatching { sock.close() }
        }
    }

    private fun myAddrs(): List<String> = RoomLink.myAddresses().map { it.second }

    /** Opens a link to a phone (found on the Wi-Fi, remembered, or typed in). */
    private fun dial(n: Nearby, wantRole: String? = null, onFail: ((String) -> Unit)? = null) {
        val key = n.id.ifBlank { "${n.host}:${n.port}" }
        if (!connecting.add(key)) return
        Thread({
            try {
                val sock = RoomLink.connect(n.host, n.port)
                val hello = RoomLink.handshake(sock, me, myRoleName(), serverPort, ::ask, wantRole, myAddrs())
                if (hello == null) {
                    sock.close()
                    onFail?.invoke("That phone isn't ${if (wantRole == "main") "running a room" else "in Rooms"} right now, or the code wasn't confirmed.")
                } else linked(sock, hello)
            } catch (e: Throwable) {
                onFail?.invoke("Couldn't reach ${n.host}:${n.port} (${e.message ?: e.javaClass.simpleName}).")
            } finally {
                connecting.remove(key)
            }
        }, "LToTS-room-dial").apply { isDaemon = true; start() }
    }

    /** Where a known phone was last reached (Wi-Fi and Tailscale addresses), newest first. */
    private fun knownHosts(id: String): List<Pair<String, Int>> = runCatching {
        val a = JSONArray(prefs.getString("hosts_$id", "[]"))
        (0 until a.length()).map { a.getString(it) }.mapNotNull { hp ->
            val host = hp.substringBeforeLast(':')
            val port = hp.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            host to port
        }
    }.getOrDefault(emptyList())

    private fun remember(link: RoomLink) {
        val port = link.hello.port.takeIf { it > 0 } ?: RoomLink.PORT
        val hosts = (listOf(link.remoteHost) + link.hello.addrs).filter { it.isNotBlank() }.map { "$it:$port" }
        val merged = (hosts + knownHosts(link.id).map { "${it.first}:${it.second}" }).distinct().take(6)
        prefs.edit().putString("hosts_${link.id}", JSONArray(merged).toString()).apply()
    }

    @Volatile private var redialAt = 0

    /**
     * Without a main: try the remembered addresses of the phones this one knows, one at a time.
     * This is how rooms find each other over Tailscale, where there's no discovery.
     */
    private fun redialKnown() {
        val all = me.trustedPeers().flatMap { p -> knownHosts(p.id).map { p.id to it } }
        if (all.isEmpty()) return
        val (id, hp) = all[redialAt++ % all.size]
        if (links.containsKey(id)) return
        dial(Nearby(id, "", hp.first, hp.second, true, System.currentTimeMillis()), wantRole = "main")
    }

    /** Connect by address: a Tailscale IP or name (100.x.y.z, phone.tailnet.ts.net), optional :port. */
    fun connectByAddress(text: String) {
        val t = text.trim().removePrefix("http://").removePrefix("https://")
        if (t.isBlank()) return
        val host: String
        val port: Int
        if (t.startsWith("[")) { // [IPv6]:port
            host = t.substringAfter('[').substringBefore(']')
            port = t.substringAfter("]:", "").toIntOrNull() ?: RoomLink.PORT
        } else if (t.count { it == ':' } == 1) {
            host = t.substringBefore(':'); port = t.substringAfter(':').toIntOrNull() ?: RoomLink.PORT
        } else {
            host = t; port = RoomLink.PORT
        }
        _ui.value = _ui.value.copy(connectError = "")
        dial(Nearby("", "", host, port, false, System.currentTimeMillis())) { msg -> connectError = msg; publish() }
    }

    @Volatile private var connectError = ""

    private fun linked(sock: java.net.Socket, hello: Hello) {
        val ok = when (role) {
            Role.MAIN -> hello.role == "node"
            Role.NODE -> hello.role == "main"
            Role.OFF -> false
        }
        if (!ok) { sock.close(); return }
        val link = RoomLink(sock, hello, ::onFrame, ::onClosed)
        links.put(hello.peer.id, link)?.close()
        link.start()
        connectError = ""
        remember(link)
        if (role == Role.MAIN) welcome(link)
        else if (mainId == null || nextMain?.first == hello.peer.id) {
            if (mainId == null) mainId = hello.peer.id
        }
        publish()
    }

    private fun welcome(link: RoomLink) {
        link.sendJson(
            RoomWire.WELCOME,
            JSONObject().put("udp", clockServer?.port ?: 0).put("token", clockToken)
                .put("members", JSONArray().apply {
                    for (p in me.trustedPeers()) put(JSONObject().put("id", p.id).put("name", p.name).put("pub", RoomCrypto.b64(p.pub)))
                    put(JSONObject().put("id", me.id).put("name", me.name).put("pub", RoomCrypto.b64(me.publicKey)))
                }),
        )
        sendState(link)
        // The music already on its way, so a phone joining mid-song plays at once.
        val now = GroupClock.now()
        synchronized(backlog) {
            for ((c, packet) in backlog) if (c.groupNs > now + 80_000_000L) link.send(RoomWire.AUDIO, packet)
        }
    }

    private fun onClosed(link: RoomLink) {
        // A link replaced by a newer one to the same phone: nothing else changes.
        if (links[link.id] !== link) return
        links.remove(link.id)
        reports.remove(link.id)
        clocks.remove(link.id)?.close()
        val moving = nextMain
        if (role == Role.NODE && mainId == link.id && moving != null && links.containsKey(moving.first)) {
            // The old main let go a little early: follow the new one now.
            mainId = moving.first
            nextMain = null
            clocks[moving.first]?.estimator?.let { GroupClock.follow(it) }
        } else if (role == Role.NODE && mainId == link.id) {
            // The main went away.
            mainId = null
            lastState = null
            GroupClock.follow(ClockEstimator())
            renderer.clear()
            renderer.wantPlay = false
        }
        publish()
    }

    /** Joins a trusted room by itself; offers the others in the list. */
    private fun autoJoin() {
        val d = discovery ?: return
        if (role != Role.NODE || mainId != null) return
        val trusted = me.trustedPeers().map { it.id }.toSet()
        d.list().firstOrNull { it.isMain && it.id in trusted && !links.containsKey(it.id) }?.let { dial(it) }
    }

    /** "Join" / "Add" on a phone in the nearby list (first time: compare codes). */
    fun connectTo(id: String) {
        val n = discovery?.list()?.firstOrNull { it.id == id } ?: return
        dial(n)
    }

    fun forget(id: String) {
        me.forget(id)
        links[id]?.close()
        publish()
    }

    // ------------------------------------------------------------------------------------------
    // Messages

    private fun onFrame(link: RoomLink, f: RoomWire.Frame) {
        when (f.type) {
            RoomWire.AUDIO -> {
                // Only the main (or the one taking over) sends music.
                if (link.id == mainId || link.id == nextMain?.first || link.id == handoffTo) {
                    renderer.offer(AudioPacket.decode(f.payload))
                }
            }
            RoomWire.CUT -> {
                val o = f.json()
                renderer.cut(o.getInt("epoch"), o.getLong("at"))
            }
            RoomWire.STATE -> if (role == Role.NODE && link.id == mainId) applyState(f.json())
            RoomWire.WELCOME -> {
                val o = f.json()
                o.optJSONArray("members")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        runCatching { me.trust(Peer(m.getString("id"), m.optString("name"), RoomCrypto.unb64(m.getString("pub")))) }
                    }
                }
                val est = ClockEstimator()
                clocks.put(link.id, ClockClient(link.remoteHost, o.getInt("udp"), o.getLong("token"), est))?.close()
                if (role == Role.NODE && link.id == mainId) GroupClock.follow(est)
            }
            RoomWire.REPORT -> if (role == Role.MAIN) reports[link.id] = f.json().put("_at", System.currentTimeMillis())
            RoomWire.CONTROL -> if (role == Role.MAIN) control(link.id, f.json())
            RoomWire.CAL_PLAN -> onCalPlan(f.json())
            RoomWire.CAL_RESULT -> if (role == Role.MAIN) onCalResult(f.json())
            RoomWire.ADJUST -> adjust(f.json())
            RoomWire.OUTPUTS -> f.json().optJSONArray("keys")?.let { k -> setOutputs((0 until k.length()).map { k.getString(it) }) }
            RoomWire.MAIN_REQUEST -> if (role == Role.MAIN) handOver(link.id)
            RoomWire.MAIN_HANDOFF -> takeOver(link, f.json())
            RoomWire.NEW_MAIN -> onNewMain(f.json())
            RoomWire.BYE -> link.close()
        }
    }

    // ------------------------------------------------------------------------------------------
    // The main

    private fun becomeMain(clock: ClockMap, inherit: JSONObject?) {
        role = Role.MAIN
        mainId = me.id
        nextMain = null
        GroupClock.lead(clock)
        clockToken = RoomCrypto.randomLong()
        clockServer?.close()
        clockServer = ClockServer(clockToken)
        if (inherit != null) {
            mode = runCatching { Mode.valueOf(inherit.optString("mode")) }.getOrDefault(mode)
            activeEarbuds = inherit.optString("active").takeIf { it.isNotBlank() }
            inherit.optJSONObject("plays")?.let { p -> for (k in p.keys()) prefs.edit().putBoolean("plays_$k", p.getBoolean(k)).apply() }
            inherit.optString("earbuds").takeIf { it.isNotBlank() }?.let { prefs.edit().putString("earbuds", it).putString("earbudsName", inherit.optString("earbudsName")).apply() }
        }
        policy.reset()
        RoomStream.delayNs = delayFor(mode)
        RoomStream.stopAtNs = Long.MAX_VALUE
        RoomStream.listener = stream
        RoomStream.active = true
        if (inherit == null) RoomPlayer.onMain { com.ltots.app.audio.AudioEngine.roomChanged() }
        discovery?.announce(me.id, me.name, serverPort, true)
        // Phones already linked (the old main, after a handoff) are this main's phones now.
        for (l in links.values) welcome(l)
        startLoop()
        publish()
    }

    /**
     * Where the delay starts: what the room needed last time in this mode (it learns), or a
     * safe start that covers Bluetooth's own delay.
     */
    private fun delayFor(m: Mode): Long =
        prefs.getLong("delay_${m.name}", if (m == Mode.SPEAKERS) 800_000_000L else 700_000_000L).coerceIn(MIN_DELAY, MAX_DELAY)

    private var delayRaisedAtMs = 0L
    private var roomySinceMs = 0L

    /**
     * Keeps the delay just big enough for the slowest phone: each one reports how early the
     * music reaches it compared to when it must write it ([RoomRenderer.minMarginNs]). Too little
     * (Bluetooth earbuds take ~0.3 s, Tailscale adds its own) and the music arrives late on that
     * phone: sound, nothing, sound, nothing. So it goes up at once; it comes down slowly when
     * there's plenty to spare (a shorter delay makes pause and skip answer sooner).
     */
    private fun tuneDelay(nowMs: Long) {
        val who = (listOf(me.id) + links.keys).filter { shouldHear(it) }
        val margins = who.mapNotNull { id -> reports[id]?.takeIf { it.has("margin") }?.optDouble("margin") }
        if (margins.isEmpty()) return
        val worst = margins.min()
        val cur = RoomStream.delayNs
        // After a raise the music already on its way still has the old margin: wait for it.
        if (nowMs - delayRaisedAtMs < cur / 1_000_000L + 2_000) return
        if (worst < 80.0) {
            val next = (cur + ((150.0 - worst) * 1e6).toLong()).roundUpTo(50_000_000L).coerceAtMost(MAX_DELAY)
            if (next > cur) {
                RoomStream.extendDelay(next)
                delayRaisedAtMs = nowMs
                roomySinceMs = 0
                prefs.edit().putLong("delay_${mode.name}", next).apply()
            }
        } else if (worst > 450.0) {
            if (roomySinceMs == 0L) roomySinceMs = nowMs
            if (nowMs - roomySinceMs > 30_000) {
                val next = (cur - ((worst - 250.0) * 1e6).toLong()).roundUpTo(50_000_000L).coerceAtLeast(MIN_DELAY)
                if (next < cur) {
                    RoomStream.delayNs = next
                    prefs.edit().putLong("delay_${mode.name}", next).apply()
                }
                roomySinceMs = 0
            }
        } else roomySinceMs = 0
    }

    private fun Long.roundUpTo(step: Long): Long = ((this + step - 1) / step) * step

    private const val MIN_DELAY = 300_000_000L
    private const val MAX_DELAY = 3_000_000_000L

    /** Where the main's music goes: this phone's renderer and every phone that should hear it. */
    private val stream = object : RoomStream.Listener {
        override fun onChunk(chunk: AudioChunk, packet: ByteArray) {
            renderer.offer(chunk)
            synchronized(backlog) {
                backlog.addLast(chunk to packet)
                val now = GroupClock.now()
                while (backlog.isNotEmpty() && backlog.first().first.endNs < now) backlog.removeFirst()
                while (backlog.size > 200) backlog.removeFirst()
            }
            for (l in links.values) if (shouldHear(l.id)) l.send(RoomWire.AUDIO, packet)
        }

        override fun onCut(epoch: Int, atNs: Long) {
            renderer.cut(epoch, atNs)
            synchronized(backlog) { backlog.removeAll { it.first.epoch == epoch && it.first.groupNs >= atNs } }
            val o = JSONObject().put("epoch", epoch).put("at", atNs)
            for (l in links.values) l.sendJson(RoomWire.CUT, o)
        }
    }

    /** Does this phone need the music? (Saves Wi-Fi and battery on the ones that don't.) */
    private fun shouldHear(id: String): Boolean {
        if (id == handoffTo) return true
        return when (mode) {
            Mode.SPEAKERS -> plays(id)
            Mode.EARBUDS -> id == activeEarbuds || reports[id]?.optBoolean("earbuds") == true
        }
    }

    private fun plays(id: String) = prefs.getBoolean("plays_$id", true)

    private fun earbudsTarget(): String? = prefs.getString("earbuds", null)

    private fun mainTick(tick: Int) {
        reports[me.id] = myReport().put("_at", System.currentTimeMillis())
        // Links that went quiet.
        val nowMs = System.currentTimeMillis()
        for (l in links.values) if (nowMs - l.lastHeardMs > 10_000) l.close()
        // Which earbuds the room follows: the chosen ones, or a guess from what's connected.
        if (earbudsTarget() == null) guessEarbuds()
        var changed = false
        if (mode == Mode.EARBUDS) {
            val linkReports = reports.values.mapNotNull { r ->
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LinkReport(id, r.optBoolean("earbuds"), if (r.has("rssi")) r.optInt("rssi") else null, r.optDouble("stall", 0.0), r.optLong("_at"))
            }
            val next = policy.decide(nowMs, activeEarbuds, linkReports)
            if (next != activeEarbuds) {
                activeEarbuds = next
                changed = true
            }
        }
        tuneDelay(nowMs)
        if (changed || tick % 4 == 0) {
            val st = state()
            for (l in links.values) l.sendJson(RoomWire.STATE, st)
            applyState(st)
        }
        if (tick % 2 == 0) publish()
    }

    private fun guessEarbuds() {
        val all = reports.values.flatMap { r ->
            val arr = r.optJSONArray("a2dp") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.optString("n") to o.optString("a") } }
        }
        val pick = all.firstOrNull { EarbudLink.looksLikeEarbuds(it.first) } ?: return
        prefs.edit().putString("earbuds", pick.second).putString("earbudsName", pick.first).apply()
    }

    private fun state(): JSONObject {
        val members = JSONArray()
        val ids = listOf(me.id) + links.keys.toList()
        for (id in ids) {
            val r = reports[id] ?: JSONObject()
            members.put(
                JSONObject(r.toString()).put("id", id)
                    .put("name", if (id == me.id) me.name else links[id]?.peer?.name ?: r.optString("name"))
                    .put("plays", plays(id))
                    .put("ts", links[id]?.let { RoomLink.isTailscale(it.remoteHost) } ?: false)
            )
        }
        return JSONObject()
            .put("mode", mode.name)
            .put("main", me.id).put("mainName", me.name)
            .put("active", activeEarbuds ?: "")
            .put("reason", policy.lastReason)
            .put("earbuds", earbudsTarget() ?: "")
            .put("earbudsName", prefs.getString("earbudsName", "") ?: "")
            .put("members", members)
            .put("now", RoomPlayer.nowPlayingAnyThread())
            .put("cal", calStatus)
            .put("delay", RoomStream.delayNs / 1_000_000L)
    }

    private fun sendState(l: RoomLink) = l.sendJson(RoomWire.STATE, state())

    /** A request from a phone in the room (or this one). */
    private fun control(from: String, o: JSONObject) {
        when (val cmd = o.optString("cmd")) {
            "toggle", "next", "prev", "play", "pause" -> RoomPlayer.control(cmd)
            "earbuds_here" -> {
                val target = o.optString("node", from)
                activeEarbuds = target
                policy.switched(System.currentTimeMillis())
            }
            "mode" -> setModeHere(runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(mode))
            "earbuds_target" -> prefs.edit().putString("earbuds", o.optString("address")).putString("earbudsName", o.optString("name")).apply()
            "plays" -> prefs.edit().putBoolean("plays_${o.optString("node")}", o.optBoolean("on", true)).apply()
            "adjust" -> sendAdjust(o.optString("node"), JSONObject().put("set", o.optDouble("ms")).put("out", o.optString("out")))
            "outputs" -> {
                val node = o.optString("node", from)
                val keys = o.optJSONArray("keys") ?: JSONArray()
                if (node == me.id) setOutputs((0 until keys.length()).map { keys.getString(it) })
                else links[node]?.sendJson(RoomWire.OUTPUTS, JSONObject().put("keys", keys))
            }
            "calibrate" -> startCalibration(o.optString("node", from))
        }
        val st = state()
        for (l in links.values) l.sendJson(RoomWire.STATE, st)
        applyState(st)
        publish()
    }

    private fun setModeHere(m: Mode) {
        if (m == mode) return
        mode = m
        prefs.edit().putString("mode", m.name).apply()
        RoomStream.delayNs = delayFor(m)
        if (m == Mode.EARBUDS) policy.reset()
    }

    private fun sendAdjust(node: String, o: JSONObject) {
        if (node == me.id) adjust(o) else links[node]?.sendJson(RoomWire.ADJUST, o)
    }

    // ------------------------------------------------------------------------------------------
    // Every phone (the main too): apply the room's state, report this phone's

    private fun applyState(st: JSONObject) {
        lastState = st
        mode = runCatching { Mode.valueOf(st.optString("mode")) }.getOrDefault(mode)
        val target = st.optString("earbuds").takeIf { it.isNotBlank() }
        earbuds?.targetName = st.optString("earbudsName").takeIf { it.isNotBlank() }
        earbuds?.target = target
        val myMember = st.optJSONArray("members")?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("id") == me.id } }
        val plays = myMember?.optBoolean("plays", true) ?: true
        val active = st.optString("active")
        renderer.wantPlay = when (mode) {
            Mode.SPEAKERS -> plays
            Mode.EARBUDS -> active == me.id
        }
        refreshTargets()
        if (role == Role.NODE) publish()
    }

    // ------------------------------------------------------------------------------------------
    // Where this phone plays: its outputs

    /** The outputs chosen on this phone (empty: its usual one). */
    private fun chosenOutputs(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("outputs", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** The key a sink's delay adjustment is kept under (the usual output: per device name). */
    private fun adjustPref(out: String): String =
        if (out == AUTO) "adjust_" + (earbuds?.outputName() ?: "") else "adjust_$out"

    private fun adjustMs(out: String): Double = prefs.getFloat(adjustPref(out), 0f).toDouble()

    /**
     * Turns the room's mode and this phone's choice into the outputs to play to. Earbuds mode:
     * the earbuds only. Speakers mode: every chosen output that's there now — the speaker, a
     * Bluetooth speaker, USB… each in sync on its own — or the phone's usual one.
     */
    private fun refreshTargets() {
        val e = earbuds ?: return
        val targets = if (mode == Mode.EARBUDS) {
            val d = e.audioDevice()
            if (d == null) emptyList()
            else listOf(RoomRenderer.Target("earbuds", d.productName?.toString() ?: "Earbuds", d, 0L))
        } else {
            val have = e.outputs().associateBy { it.key }
            val chosen = chosenOutputs().mapNotNull { have[it] }
            if (chosen.isEmpty()) listOf(RoomRenderer.Target(AUTO, e.outputName(), null, (adjustMs(AUTO) * 1e6).roundToLong()))
            else chosen.map { RoomRenderer.Target(it.key, it.name, it.device, (adjustMs(it.key) * 1e6).roundToLong()) }
        }
        renderer.setTargets(targets)
    }

    private fun setOutputs(keys: List<String>) {
        prefs.edit().putString("outputs", JSONArray(keys.distinct()).toString()).apply()
        refreshTargets()
        publish()
    }

    private fun adjust(o: JSONObject) {
        // Which output: the one named, or every output this phone plays to.
        val outs = o.optString("out").takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: renderer.sinks().map { it.key }.ifEmpty { listOf(AUTO) }
        for (out in outs) {
            val cur = adjustMs(out)
            val next = when {
                o.has("set") -> o.getDouble("set")
                o.has("delta") -> cur + o.getDouble("delta")
                else -> cur
            }.coerceIn(-1000.0, 1000.0)
            prefs.edit().putFloat(adjustPref(out), next.toFloat()).apply()
        }
        refreshTargets()
        publish()
    }

    private fun myReport(): JSONObject {
        val e = earbuds
        val o = JSONObject()
            .put("id", me.id).put("name", me.name)
            .put("output", renderer.sinks().map { it.name }.ifEmpty { listOf(e?.outputName() ?: "") }.joinToString(" + "))
            .put("earbuds", e?.connected == true)
            .put("stall", if (renderer.rendering) renderer.stall else 0.0)
            .put("sync", renderer.syncErrorNs / 1e6)
            .put("rendering", renderer.rendering)
            .put("adj", adjustMs(AUTO))
            // Every output this phone has, the ones chosen, and how each one is doing.
            .put("outs", JSONArray().apply { e?.outputs()?.forEach { put(JSONObject().put("k", it.key).put("n", it.name).put("t", it.kind)) } })
            .put("sel", JSONArray(chosenOutputs()))
            .put("sinks", JSONArray().apply {
                renderer.sinks().forEach { k ->
                    put(
                        JSONObject().put("k", k.key).put("n", k.name).put("r", k.rendering)
                            .put("sync", k.syncErrorNs / 1e6).put("lat", k.latencyNs / 1e6)
                            .put("ok", k.routedOk).put("to", k.routedName).put("adj", k.adjustNs / 1e6)
                    )
                }
            })
            .put("clock", GroupClock.ready)
            .put("rssiSrc", e?.rssiSource ?: "")
            .put("a2dp", JSONArray().apply { e?.connectedDevices?.forEach { (n, a) -> put(JSONObject().put("n", n).put("a", a)) } })
        e?.rssi?.let { o.put("rssi", it) }
        renderer.minMarginNs()?.let { o.put("margin", it / 1e6) }
        o.put("lat", (if (renderer.rendering) renderer.outputLatencyNs else renderer.lastLatencyNs) / 1e6)
        mainId?.let { links[it] }?.let { o.put("ts", RoomLink.isTailscale(it.remoteHost)) }
        return o
    }

    private fun nodeTick(tick: Int) {
        val main = mainId?.let { links[it] }
        if (main == null) {
            if (tick % 8 == 0) autoJoin()
            // Tailscale (no discovery there): the addresses phones were reached at before.
            if (tick % 12 == 6) redialKnown()
            if (tick % 4 == 0) publish()
            return
        }
        // The main moves to another phone now (linking up again if the first try was too early).
        nextMain?.let { (id, _) -> if (!links.containsKey(id) && tick % 4 == 0) nextMainAt?.let { dial(it) } }
        nextMain?.let { (id, at) ->
            if (GroupClock.now() >= at) {
                val est = clocks[id]?.estimator
                if (est != null && est.ready) {
                    val old = mainId
                    mainId = id
                    nextMain = null
                    GroupClock.follow(est)
                    scope.launch { delay(1500); if (old != null && old != mainId) links[old]?.close() }
                }
            }
        }
        if (tick % 4 == 0) main.sendJson(RoomWire.REPORT, myReport())
        if (tick % 4 == 0) publish()
    }

    // ------------------------------------------------------------------------------------------
    // Requests from this phone's screen

    private fun sendToMain(o: JSONObject) {
        if (role == Role.MAIN) control(me.id, o)
        else mainId?.let { links[it] }?.sendJson(RoomWire.CONTROL, o)
    }

    fun transport(cmd: String) = sendToMain(JSONObject().put("cmd", cmd))
    fun setMode(m: Mode) = sendToMain(JSONObject().put("cmd", "mode").put("mode", m.name))
    fun earbudsHere(node: String) = sendToMain(JSONObject().put("cmd", "earbuds_here").put("node", node))
    fun setEarbuds(name: String, address: String) = sendToMain(JSONObject().put("cmd", "earbuds_target").put("name", name).put("address", address))
    fun setPlays(node: String, on: Boolean) = sendToMain(JSONObject().put("cmd", "plays").put("node", node).put("on", on))
    fun setAdjust(node: String, ms: Double, out: String = "") =
        sendToMain(JSONObject().put("cmd", "adjust").put("node", node).put("ms", ms).put("out", out))
    /** Which outputs [node] plays to (empty: its usual one). */
    fun setOutputs(node: String, keys: List<String>) =
        sendToMain(JSONObject().put("cmd", "outputs").put("node", node).put("keys", JSONArray(keys)))
    fun calibrate() = sendToMain(JSONObject().put("cmd", "calibrate").put("node", me.id))

    /** Make this phone the main. */
    fun makeMeMain() {
        if (role != Role.NODE) return
        mainId?.let { links[it] }?.sendJson(RoomWire.MAIN_REQUEST, JSONObject())
        _ui.value = _ui.value.copy(handingOver = true)
    }

    // ------------------------------------------------------------------------------------------
    // Moving the main to another phone

    /** On the main: hand everything to [to], to carry on from a moment a few seconds ahead. */
    private fun handOver(to: String) {
        if (role != Role.MAIN) return
        val link = links[to] ?: return
        if (handoffTo != null) return
        if (reports[to]?.optBoolean("clock") != true) return
        handoffTo = to
        val at = GroupClock.now() + 3_000_000_000L
        val room = JSONObject().put("mode", mode.name).put("active", activeEarbuds ?: "")
            .put("earbuds", earbudsTarget() ?: "").put("earbudsName", prefs.getString("earbudsName", ""))
            .put("plays", JSONObject().apply { for (id in reports.keys) put(id, plays(id)) })
        RoomPlayer.snapshot(app, link.localHost, at) { snap ->
            snap.put("room", room)
            link.sendJson(RoomWire.MAIN_HANDOFF, snap)
            val news = JSONObject().put("id", to).put("host", link.remoteHost).put("port", link.hello.port).put("at", at)
            for (l in links.values) if (l.id != to) l.sendJson(RoomWire.NEW_MAIN, news)
            RoomStream.stopAtNs = at
            calStatus = ""
            RoomPlayer.stopAt(at) {
                // From here this phone is one of the room's phones, following the new main.
                RoomStream.active = false
                RoomStream.listener = null
                RoomStream.stopAtNs = Long.MAX_VALUE
                com.ltots.app.audio.AudioEngine.roomChanged()
                RoomPlayer.onMain { com.ltots.app.audio.AudioEngine.player.value?.stop() }
                role = Role.NODE
                clockServer?.close(); clockServer = null
                discovery?.announce(me.id, me.name, serverPort, false)
                val est = clocks[to]?.estimator
                mainId = to
                handoffTo = null
                if (est != null && est.ready) GroupClock.follow(est)
                else nextMain = to to at // follow once its clock is ready (nodeTick)
                scope.launch {
                    delay(1500)
                    // The other phones have moved over; their links to this phone can go.
                    for (l in links.values) if (l.id != to) l.close()
                }
                publish()
            }
        }
    }

    /** On the new main: take the queue and the room, and start exactly at "at". */
    private fun takeOver(from: RoomLink, h: JSONObject) {
        if (role != Role.NODE) return
        val at = h.optLong("at")
        // Group time carries on unbroken: this phone's clock as it maps onto the room's now.
        val frozen = GroupClock.map
        handoffTo = from.id // still take the old main's music until "at"
        becomeMain(frozen, h.optJSONObject("room"))
        RoomPlayer.takeOver(app, h)
        scope.launch {
            delay(((at - GroupClock.now()) / 1_000_000L).coerceAtLeast(0) + 500)
            handoffTo = null
            publish()
        }
        _ui.value = _ui.value.copy(handingOver = false)
    }

    @Volatile private var nextMainAt: Nearby? = null

    /** On the other phones: link up with the new main now, switch over at "at". */
    private fun onNewMain(o: JSONObject) {
        val id = o.getString("id")
        nextMain = id to o.getLong("at")
        val n = Nearby(id, "", o.getString("host"), o.getInt("port"), true, System.currentTimeMillis())
        nextMainAt = n
        if (!links.containsKey(id)) dial(n)
    }

    // ------------------------------------------------------------------------------------------
    // Calibration (Speakers mode): every speaker chirps in turn, one phone listens

    private fun startCalibration(listener: String) {
        if (mode != Mode.SPEAKERS) { calStatus = "Calibration is for Speakers mode."; return }
        val nodes = (listOf(me.id) + links.keys).filter { plays(it) && (it == me.id || reports[it]?.optBoolean("clock") == true) }
        // Every output of every playing phone chirps on its own: each is lined up separately.
        val outs = nodes.flatMap { n ->
            val sinks = reports[n]?.optJSONArray("sinks")
            val keys = sinks?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("k") } }.orEmpty()
            keys.ifEmpty { listOf(AUTO) }.map { n to it }
        }
        if (outs.isEmpty()) { calStatus = "No phone is playing to a speaker."; return }
        RoomPlayer.pauseForCalibration()
        val id = ++calPlanId
        val start = GroupClock.now() + 2_200_000_000L
        val slots = JSONArray()
        var t = start
        repeat(ROUNDS) {
            for ((n, out) in outs) {
                slots.put(JSONObject().put("node", n).put("out", out).put("at", t))
                t += SLOT_NS
            }
        }
        val plan = JSONObject().put("id", id).put("listener", listener).put("from", start - 400_000_000L).put("to", t + 300_000_000L).put("slots", slots)
        calStatus = "Calibrating: keep quiet for a few seconds…"
        for (l in links.values) l.sendJson(RoomWire.CAL_PLAN, plan)
        onCalPlan(plan)
        // Give up if no answer comes back.
        scope.launch {
            delay((t - GroupClock.now()) / 1_000_000L + 15_000)
            if (calPlanId == id && calStatus.startsWith("Calibrating")) {
                calStatus = "The listening phone didn't answer. Try again."
                RoomPlayer.resumeAfterCalibration()
            }
        }
    }

    private fun onCalPlan(plan: JSONObject) {
        val slots = plan.getJSONArray("slots")
        var last = 0L
        for (i in 0 until slots.length()) {
            val s = slots.getJSONObject(i)
            last = maxOf(last, s.getLong("at"))
            if (s.getString("node") == me.id) renderer.chirpAt(s.getLong("at"), s.optString("out").ifBlank { null })
        }
        // Only the phones that chirp keep their output running for it.
        if ((0 until slots.length()).any { slots.getJSONObject(it).getString("node") == me.id }) {
            renderer.keepAliveUntilNs = last + 600_000_000L
        }
        if (plan.getString("listener") == me.id) {
            Thread({ listen(plan) }, "LToTS-room-listen").apply { isDaemon = true; start() }
        }
    }

    /** Records the chirps with this phone's mic and works out how early or late each speaker was. */
    @SuppressLint("MissingPermission") // the screen asks for the mic before calibrating
    private fun listen(plan: JSONObject) {
        val rate = 48000
        val result = JSONObject().put("id", plan.getInt("id"))
        val errs = JSONObject()
        try {
            val from = plan.getLong("from")
            val to = plan.getLong("to")
            val wait = (GroupClock.toLocal(from) - System.nanoTime()) / 1_000_000L
            if (wait > 0) Thread.sleep(wait)
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val source = MediaRecorder.AudioSource.UNPROCESSED
            val rec = runCatching { AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, rate)) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, rate))
            val total = ((to - from) * rate / 1_000_000_000L).toInt() + rate
            val data = FloatArray(total)
            var got = 0
            val ts = AudioTimestamp()
            var tsFrame = -1L
            var tsNano = 0L
            rec.startRecording()
            try {
                while (got < total && GroupClock.now() < to + 200_000_000L) {
                    val n = rec.read(data, got, minOf(2048, total - got), AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    got += n
                    if (tsFrame < 0 && rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        tsFrame = ts.framePosition; tsNano = ts.nanoTime
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            if (tsFrame < 0) throw IOException("The microphone gave no timing")
            // Group time of recorded sample i.
            fun groupAt(i: Double): Long = GroupClock.toGroup(tsNano + ((i - tsFrame) * 1e9 / rate).roundToLong())
            val start0 = groupAt(0.0)
            val chirp = Chirp.make(rate)
            val slots = plan.getJSONArray("slots")
            val byNode = HashMap<String, MutableList<Long>>()
            for (i in 0 until slots.length()) {
                val s = slots.getJSONObject(i)
                val at = s.getLong("at")
                val expected = ((at - start0) * rate / 1_000_000_000L).toInt()
                val win = (SLOT_NS / 2 * rate / 1_000_000_000L).toInt()
                val hit = Chirp.find(data.copyOf(got), chirp, expected - win, expected + win) ?: continue
                if (hit.clarity < 6.0) continue
                val heardAt = groupAt(hit.index)
                byNode.getOrPut(s.getString("node") + "|" + s.optString("out", AUTO)) { ArrayList() }.add(heardAt - at)
            }
            for ((node, list) in byNode) {
                list.sort()
                errs.put(node, list[list.size / 2] / 1e6)
            }
        } catch (e: Throwable) {
            CrashReport.handled("Rooms.listen", e)
            result.put("error", e.message ?: "Recording failed")
        }
        result.put("errs", errs)
        if (role == Role.MAIN) onCalResult(result) else mainId?.let { links[it] }?.sendJson(RoomWire.CAL_RESULT, result)
    }

    private fun onCalResult(r: JSONObject) {
        if (r.optInt("id") != calPlanId) return
        RoomPlayer.resumeAfterCalibration()
        val errs = r.optJSONObject("errs") ?: JSONObject()
        val heard = errs.keys().asSequence().associateWith { errs.getDouble(it) }
        if (heard.isEmpty()) {
            calStatus = r.optString("error").ifBlank { "Couldn't hear any speaker. Put the phone near them, turn them up, and try again." }
            return
        }
        // Everyone lines up with the middle one: the smallest total change.
        val ref = heard.values.sorted()[heard.size / 2]
        val parts = ArrayList<String>()
        for ((nodeOut, err) in heard) {
            val node = nodeOut.substringBefore('|')
            val out = nodeOut.substringAfter('|', AUTO)
            val delta = -(err - ref)
            if (kotlin.math.abs(delta) >= 0.3) sendAdjust(node, JSONObject().put("delta", delta).put("out", out))
            parts += "${nameOf(node)}${outName(node, out)} ${"%+.0f".format(delta)} ms"
        }
        val missed = (listOf(me.id) + links.keys).filter { n -> plays(n) && heard.keys.none { it.startsWith("$n|") } }
        calStatus = "Lined up: " + parts.joinToString(", ") +
            (if (missed.isNotEmpty()) ". Not heard: " + missed.joinToString(", ") { nameOf(it) } else "")
    }

    private fun nameOf(id: String) = if (id == me.id) me.name else links[id]?.peer?.name ?: "Phone"

    /** " (JBL Go 4)" when that phone plays to more than one output, else nothing. */
    private fun outName(node: String, out: String): String {
        val sinks = reports[node]?.optJSONArray("sinks") ?: return ""
        if (sinks.length() < 2) return ""
        val name = (0 until sinks.length()).map { sinks.getJSONObject(it) }.firstOrNull { it.optString("k") == out }?.optString("n")
        return if (name.isNullOrBlank()) "" else " ($name)"
    }

    /** The phone's usual output (no output chosen). */
    private const val AUTO = "auto"

    private const val ROUNDS = 3
    private const val SLOT_NS = 700_000_000L

    // ------------------------------------------------------------------------------------------
    // The screen

    private fun publish() {
        if (!initialized) return
        val st = lastState
        val trusted = me.trustedPeers().map { it.id }.toSet()
        val members = ArrayList<Member>()
        val arr = st?.optJSONArray("members")
        val active = st?.optString("active") ?: ""
        if (arr != null && role != Role.OFF) for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val id = m.optString("id")
            members += Member(
                id = id, name = m.optString("name", "Phone"),
                isMain = id == st.optString("main"), isMe = id == me.id,
                output = m.optString("output"),
                earbuds = m.optBoolean("earbuds"),
                rssi = if (m.has("rssi")) m.optInt("rssi") else null,
                rssiSource = m.optString("rssiSrc"),
                stall = m.optDouble("stall", 0.0),
                active = id == active,
                rendering = m.optBoolean("rendering"),
                syncMs = if (m.optBoolean("rendering")) m.optDouble("sync") else null,
                adjustMs = m.optDouble("adj", 0.0),
                plays = m.optBoolean("plays", true),
                clockReady = m.optBoolean("clock"),
                latencyMs = if (m.has("lat")) m.optDouble("lat") else null,
                marginMs = if (m.has("margin")) m.optDouble("margin") else null,
                tailscale = m.optBoolean("ts"),
                outputs = m.optJSONArray("outs")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.map { OutputChoice(it.optString("k"), it.optString("n"), it.optString("t")) }
                }.orEmpty(),
                selected = m.optJSONArray("sel")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                sinks = m.optJSONArray("sinks")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.map {
                        OutputState(
                            it.optString("k"), it.optString("n"), it.optBoolean("r"), it.optDouble("sync"), it.optDouble("lat"),
                            it.optBoolean("ok", true), it.optString("to"), it.optDouble("adj"),
                        )
                    }
                }.orEmpty(),
            )
        }
        val nearby = discovery?.list().orEmpty()
            .filter { n -> members.none { it.id == n.id } && !links.containsKey(n.id) }
            .map { NearbyPhone(it.id, it.name, it.isMain, it.id in trusted) }
        val now = st?.optJSONObject("now")
        val choices = members.firstOrNull { it.isMe }?.let { earbuds?.connectedDevices }.orEmpty()
            .ifEmpty { earbuds?.connectedDevices.orEmpty() }
        val status = when (role) {
            Role.OFF -> ""
            Role.MAIN -> if (links.isEmpty()) "Waiting for your other phones…" else ""
            Role.NODE -> when {
                mainId == null -> "Looking for a room on this Wi-Fi…"
                !GroupClock.ready -> "Syncing the clock…"
                else -> ""
            }
        }
        _ui.value = Ui(
            role = role,
            available = prefs.getBoolean("available", false),
            mode = mode,
            mainName = st?.optString("mainName") ?: "",
            members = members,
            nearby = nearby,
            nowTitle = now?.optString("title") ?: "",
            nowArtist = now?.optString("artist") ?: "",
            playing = now?.optBoolean("playing") ?: false,
            earbudsName = st?.optString("earbudsName") ?: "",
            earbudsAddress = st?.optString("earbuds") ?: "",
            earbudChoices = choices,
            status = status,
            calibration = st?.optString("cal") ?: "",
            calibrating = (st?.optString("cal") ?: "").startsWith("Calibrating"),
            lastSwitch = st?.optString("reason") ?: "",
            handingOver = _ui.value.handingOver && role == Role.NODE,
            delayMs = st?.optLong("delay") ?: 0,
            myAddresses = if (role == Role.OFF) emptyList() else RoomLink.myAddresses(),
            port = serverPort,
            connectError = connectError,
        )
        RoomService.update(app, _ui.value)
    }
}
