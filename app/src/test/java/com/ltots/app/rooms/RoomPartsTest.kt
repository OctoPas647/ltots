package com.ltots.app.rooms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** The room's pieces: audio packets, the wire, pairing, the chirp finder, the handoff rules. */
class RoomPartsTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- audio packets ---------------------------------------------------------------------

    @Test fun audioPacketRoundTrips24Bit() {
        val frames = 960
        val src = FloatArray(frames * 2) { i -> (sin(i * 0.013) * 0.9).toFloat() }
        src[0] = 1.5f // clipped
        src[1] = -1f
        val bytes = AudioPacket.encode(77, 12_345L, 9_000_000_000L, 48000, 2, src, 0, frames, gain = 1f)
        val c = AudioPacket.decode(bytes)
        assertEquals(77, c.epoch)
        assertEquals(12_345L, c.startFrame)
        assertEquals(9_000_000_000L, c.groupNs)
        assertEquals(48000, c.sampleRate)
        assertEquals(2, c.channels)
        assertEquals(frames, c.frames)
        assertEquals(9_000_000_000L + 20_000_000L, c.endNs)
        assertEquals(1f, c.samples[0], 1e-6f)
        assertEquals(-1f, c.samples[1], 1e-6f)
        // Half a 24-bit step at most.
        for (i in 2 until src.size) assertEquals(src[i], c.samples[i], 6.5e-8f)
    }

    @Test fun wireFramesAndClockPackets() {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        RoomWire.writeJson(out, RoomWire.STATE, org.json.JSONObject().put("mode", "SPEAKERS"))
        RoomWire.write(out, RoomWire.AUDIO, byteArrayOf(1, 2, 3))
        val input = DataInputStream(ByteArrayInputStream(buf.toByteArray()))
        val a = RoomWire.read(input)!!
        assertEquals(RoomWire.STATE, a.type)
        assertEquals("SPEAKERS", a.json().getString("mode"))
        val b = RoomWire.read(input)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), b.payload)
        assertNull(RoomWire.read(input))

        val token = 0x1234_5678_9ABCL
        val req = ClockPacket.request(token, 5, 111L)
        assertNull("wrong token is ignored", ClockPacket.reply(req, req.size, token + 1, 1, 2))
        val rep = ClockPacket.reply(req, req.size, token, 222L, 333L)!!
        val r = ClockPacket.parseReply(rep, rep.size, token)!!
        assertEquals(5, r.seq); assertEquals(111L, r.t0); assertEquals(222L, r.t1); assertEquals(333L, r.t2)
    }

    // ---- pairing ----------------------------------------------------------------------------

    @Test fun bothPhonesShowTheSameCodeAndAnIntruderCannotMatchIt() {
        val a = RoomCrypto.newKeyPair()
        val b = RoomCrypto.newKeyPair()
        val m = RoomCrypto.newKeyPair() // someone in the middle
        val na = RoomCrypto.nonce(); val nb = RoomCrypto.nonce()
        val sa = RoomCrypto.shared(a.private, b.public)
        val sb = RoomCrypto.shared(b.private, a.public)
        assertArrayEquals(sa, sb)
        val codeA = RoomCrypto.pairingCode(sa, na, nb)
        assertEquals(codeA, RoomCrypto.pairingCode(sb, nb, na))
        assertEquals(4, codeA.length)
        val sm = RoomCrypto.shared(a.private, m.public)
        assertNotEquals(codeA, RoomCrypto.pairingCode(sm, na, nb))
        // Each phone's proof only checks out for its own id.
        val mac = RoomCrypto.authMac(sa, "aaaa", na, nb)
        assertArrayEquals(mac, RoomCrypto.authMac(sb, "aaaa", nb, na))
        assertTrue(!mac.contentEquals(RoomCrypto.authMac(sb, "bbbb", nb, na)))
    }

    /** Two phones over a real socket: the code once, then they know each other. */
    @Test fun handshakeAsksOnceThenRemembers() {
        val ida = RoomIdentity(tmp.newFolder("a"), "Xiaomi")
        val idb = RoomIdentity(tmp.newFolder("b"), "Nothing")
        val pool = Executors.newFixedThreadPool(2)
        fun meet(): Triple<Hello?, Hello?, Int> {
            var asked = 0
            val server = ServerSocket(0)
            val fb = pool.submit<Hello?> {
                server.accept().use { s -> RoomLink.handshake(s, idb, "node", 1, { _, _ -> synchronized(this) { asked++ }; true }) }
            }
            val ha = Socket("127.0.0.1", server.localPort).use { s ->
                RoomLink.handshake(s, ida, "main", 2, { _, _ -> synchronized(this) { asked++ }; true })
            }
            val hb = fb.get(10, TimeUnit.SECONDS)
            server.close()
            return Triple(ha, hb, asked)
        }
        val (ha, hb, asked) = meet()
        assertNotNull(ha); assertNotNull(hb)
        assertEquals("node", ha!!.role)
        assertEquals("main", hb!!.role)
        assertEquals(idb.id, ha.peer.id)
        assertEquals("Nothing", ha.peer.name)
        assertEquals("both phones show the code the first time", 2, asked)
        val (ha2, hb2, asked2) = meet()
        assertNotNull(ha2); assertNotNull(hb2)
        assertEquals("no code once paired", 0, asked2)
        // Remembered on disk too.
        assertTrue(RoomIdentity(tmp.root.resolve("a"), "Xiaomi").isTrusted(idb.id, idb.publicKey))
        pool.shutdownNow()
    }

    @Test fun refusedCodeMeansNoLink() {
        val ida = RoomIdentity(tmp.newFolder("a"), "A")
        val idb = RoomIdentity(tmp.newFolder("b"), "B")
        val pool = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0)
        val fb = pool.submit<Hello?> { server.accept().use { s -> RoomLink.handshake(s, idb, "node", 1, { _, _ -> false }) } }
        val ha = Socket("127.0.0.1", server.localPort).use { s -> RoomLink.handshake(s, ida, "main", 2, { _, _ -> true }) }
        assertNull(ha)
        assertNull(fb.get(10, TimeUnit.SECONDS))
        assertTrue(!ida.isTrusted(idb.id, idb.publicKey))
        server.close()
        pool.shutdownNow()
    }

    // ---- calibration chirp ------------------------------------------------------------------

    @Test fun chirpIsFoundToAFractionOfAMillisecond() {
        val rate = 48000
        val rnd = Random(5)
        val chirp = Chirp.make(rate)
        val rec = FloatArray(rate * 3) { (rnd.nextDouble(-0.02, 0.02)).toFloat() }
        // A hum, the chirp at 1.2345 s (quiet, through a room) and a wall echo 9 ms later.
        val at = 1.2345 * rate
        for (i in rec.indices) rec[i] += (0.05 * sin(2 * PI * 50 * i / rate)).toFloat()
        fun add(start: Double, gain: Float) {
            val s0 = start.toInt()
            val frac = start - s0
            for (j in chirp.indices) {
                val k = s0 + j
                if (k + 1 >= rec.size) break
                // Fractional delay by linear interpolation between neighbours.
                rec[k] += (gain * chirp[j] * (1 - frac)).toFloat()
                rec[k + 1] += (gain * chirp[j] * frac).toFloat()
            }
        }
        add(at, 0.15f)
        add(at + 0.009 * rate, 0.12f)
        val hit = Chirp.find(rec, chirp, (at - 0.3 * rate).toInt(), (at + 0.3 * rate).toInt())!!
        val errMs = (hit.index - at) / rate * 1000
        assertTrue("found ${"%.3f".format(errMs)} ms off", abs(errMs) < 0.15)
        assertTrue("clarity ${hit.clarity}", hit.clarity > 6)
        // Nothing there: no confident hit.
        val quiet = FloatArray(rate) { (rnd.nextDouble(-0.02, 0.02)).toFloat() }
        val none = Chirp.find(quiet, chirp, 1000, 30000)
        assertTrue("noise clarity ${none?.clarity}", none == null || none.clarity < 6)
    }

    // ---- earbud handoff ---------------------------------------------------------------------

    private fun r(id: String, at: Long, connected: Boolean = true, rssi: Int? = null, stall: Double = 0.0) =
        LinkReport(id, connected, rssi, stall, at)

    @Test fun startsOnTheConnectedPhone() {
        val p = HandoffPolicy()
        assertEquals("b", p.decide(0, null, listOf(r("a", 0, connected = false), r("b", 0))))
    }

    @Test fun switchesWhenTheMusicStallsForAMoment_notOnABlip() {
        val p = HandoffPolicy()
        var t = 0L
        var active: String? = p.decide(t, null, listOf(r("a", t, rssi = -60), r("b", t, rssi = -70)))
        assertEquals("a", active)
        t = 20_000
        // A short stall (0.5 s) is ignored.
        for (k in 0..5) { active = p.decide(t, active, listOf(r("a", t, stall = 0.8), r("b", t))); t += 100 }
        active = p.decide(t, active, listOf(r("a", t), r("b", t)))
        assertEquals("a", active)
        // A lasting one switches.
        for (k in 0..20) { active = p.decide(t, active, listOf(r("a", t, stall = 0.8), r("b", t))); t += 100 }
        assertEquals("b", active)
        assertTrue(p.lastReason.contains("stall"))
    }

    @Test fun walkingToTheOtherRoomHandsOverOnce() {
        val p = HandoffPolicy()
        var active: String? = null
        val seen = ArrayList<String>()
        // Walk from A's end to B's end over 20 s: A fades from −50 to −90, B rises from −90 to −50.
        var t = 0L
        while (t <= 40_000) {
            val x = (t / 20_000.0).coerceAtMost(1.0)
            val ra = (-50 - 40 * x).toInt()
            val rb = (-90 + 40 * x).toInt()
            active = p.decide(t, active, listOf(r("a", t, rssi = ra), r("b", t, rssi = rb)))
            if (seen.lastOrNull() != active) seen += active!!
            t += 250
        }
        assertEquals(listOf("a", "b"), seen)
    }

    @Test fun noisySignalAtSimilarDistanceDoesNotPingPong() {
        val p = HandoffPolicy()
        val rnd = java.util.Random(7)
        var active: String? = null
        var switches = -1
        var t = 0L
        var at = 0L
        var ra = -70; var rb = -72
        while (t <= 120_000) {
            // A new reading each second, ±6 dB of noise around nearly the same strength.
            if (t - at >= 1_000) { at = t; ra = -70 + rnd.nextInt(13) - 6; rb = -72 + rnd.nextInt(13) - 6 }
            val next = p.decide(t, active, listOf(r("a", at, rssi = ra), r("b", at, rssi = rb)))
            if (next != active) switches++
            active = next
            t += 250
        }
        assertTrue("switched $switches times", switches <= 1)
    }

    @Test fun lostLinkSwitchesAtOnce() {
        val p = HandoffPolicy()
        var active = p.decide(0, null, listOf(r("a", 0), r("b", 0)))!!
        val other = if (active == "a") "b" else "a"
        active = p.decide(1000, active, listOf(r(active, 1000, connected = false), r(other, 1000)))!!
        assertEquals(other, active)
    }
}
