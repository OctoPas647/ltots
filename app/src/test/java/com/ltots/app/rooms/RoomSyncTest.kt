package com.ltots.app.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The room's timing: the shared clock over a jittery Wi-Fi, and the controller that keeps each
 * phone's sound on the timeline. Simulated phones, simulated networks.
 */
class RoomSyncTest {

    /** A node clock that runs [driftPpm] fast and starts [offsetNs] behind the group clock. */
    private class Clocks(val offsetNs: Long, val driftPpm: Double) {
        fun local(group: Long): Long = ((group - offsetNs) * (1 + driftPpm / 1e6)).toLong()
        fun group(local: Long): Long = (local / (1 + driftPpm / 1e6)).toLong() + offsetNs
    }

    /** One ping over a Wi-Fi with base latency, jitter and power-save spikes (sometimes 100 ms). */
    private fun ping(c: Clocks, groupNow: Long, rnd: Random): ClockSample {
        fun leg(): Long {
            var d = 1_500_000L + (rnd.nextDouble() * 1_500_000).toLong()
            if (rnd.nextDouble() < 0.15) d += (rnd.nextDouble() * 100_000_000).toLong()
            return d
        }
        val t0 = c.local(groupNow)
        val t1 = groupNow + leg()
        val t2 = t1 + 50_000L
        val t3 = c.local(t2 + leg())
        return ClockSample(t0, t1, t2, t3)
    }

    @Test fun clockLocksOnThroughJitter() {
        val rnd = Random(7)
        val c = Clocks(offsetNs = 123_456_789_000L, driftPpm = 41.0)
        val e = ClockEstimator()
        var g = 5_000_000_000_000L
        repeat(150) { // 30 s of pings every 200 ms
            e.add(ping(c, g, rnd))
            g += 200_000_000L
        }
        assertTrue("ready", e.ready)
        // Compare the estimate with the truth now and 5 s ahead (drift must be learnt).
        for (ahead in listOf(0L, 5_000_000_000L)) {
            val local = c.local(g + ahead)
            val err = e.map.toGroup(local) - (g + ahead)
            assertTrue("clock error ${err / 1e6} ms at +${ahead / 1e9}s", abs(err) < 400_000L)
        }
        // And back: group → local is the exact inverse.
        val l = 9_876_543_210_123L
        assertEquals(l, e.map.toLocal(e.map.toGroup(l)))
    }

    @Test fun clockReadyWithinSecondsWithoutDrift() {
        val rnd = Random(3)
        val c = Clocks(offsetNs = -42_000_000L, driftPpm = 0.0)
        val e = ClockEstimator()
        var g = 1_000_000_000_000L
        repeat(15) { e.add(ping(c, g, rnd)); g += 100_000_000L }
        assertTrue(e.ready)
        val err = e.map.toGroup(c.local(g)) - g
        assertTrue("clock error ${err / 1e6} ms", abs(err) < 1_000_000L)
    }

    /**
     * A phone whose audio clock runs 80 ppm fast, starting 40 ms late, with noisy Bluetooth
     * timestamps: one jump fixes the start, then rate steering keeps it within a millisecond.
     */
    @Test fun controllerJumpsThenSteersOutDrift() {
        val rnd = Random(11)
        val ctl = SyncController()
        var error = 40_000_000.0 // ns, late
        var ppm = 0.0
        val dt = 0.05
        var t = 0L
        var jumps = 0
        var worstLate = 0.0
        repeat((60 / dt).toInt()) { i ->
            // The phone's clock drift: it plays 80 ppm fast (error shrinks), our rate adds to it.
            error -= (80.0 + ppm) * 1e-6 * dt * 1e9
            val measured = error + rnd.nextDouble(-1.5e6, 1.5e6)
            when (val d = ctl.update(measured.toLong(), dt, t)) {
                is SyncController.Decision.Jump -> { jumps++; error -= d.ns }
                is SyncController.Decision.Rate -> ppm = d.ppm
            }
            t += (dt * 1e9).toLong()
            if (i * dt > 20) worstLate = maxOf(worstLate, abs(error))
        }
        assertEquals("one jump at the start", 1, jumps)
        assertTrue("worst error after settling ${worstLate / 1e6} ms", worstLate < 1_000_000.0)
        // It learnt to play 80 ppm slow to cancel the drift.
        assertEquals(-80.0, ppm, 25.0)
    }

    @Test fun oneWildTimestampDoesNotJump() {
        val ctl = SyncController()
        var t = 0L
        repeat(40) { ctl.update(0, 0.05, t); t += 50_000_000L }
        // A single 50 ms spike (a Bluetooth timestamp glitch) is smoothed, not jumped on.
        val d = ctl.update(50_000_000L, 0.05, t)
        assertTrue(d is SyncController.Decision.Rate)
    }

    @Test fun rateDitherAveragesToTheWish() {
        val d = RateDither(48000)
        val n = 1000
        val sum = (0 until n).sumOf { d.next(7.3).toLong() }
        val avgPpm = (sum.toDouble() / n / 48000 - 1) * 1e6
        assertEquals(7.3, avgPpm, 0.5)
    }
}
