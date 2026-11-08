package com.ltots.app.audio

import com.ltots.app.data.BeatSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BeatSyncTest {
    /** 40 s at 128 BPM: a kick on every beat, a hi-hat between, a little noise; louder after 20 s (a drop). */
    private fun groove(): FloatArray {
        val rate = BeatTracker.RATE
        val period = 60.0 / 128
        val rnd = Random(3)
        return FloatArray(40 * rate) { i ->
            val t = i / rate.toDouble()
            val sinceBeat = t % period
            val sinceHat = (t + period / 2) % period
            val gain = if (t < 20) 0.25 else 0.9
            val kick = exp(-sinceBeat * 30) * sin(2 * PI * (55 + 80 * exp(-sinceBeat * 40)) * sinceBeat)
            val hat = exp(-sinceHat * 120) * (rnd.nextDouble() - 0.5) * 0.4
            (gain * kick + hat + (rnd.nextDouble() - 0.5) * 0.01).toFloat()
        }
    }

    @Test fun classicTrackerFindsTheGridAndTheDrop() {
        val m = BeatTracker.classic(groove())
        val period = 60f / 128
        val gaps = (1 until m.size).map { m.times[it] - m.times[it - 1] }.sorted()
        assertEquals(period, gaps[gaps.size / 2], 0.03f)
        // On the kicks (multiples of the period), not between them.
        val off = m.times.drop(4).map { t -> val r = t % period; if (r > period / 2) r - period else r }.sorted()
        assertTrue("median offset ${off[off.size / 2]}", abs(off[off.size / 2]) < 0.025f)
        // The beat where the music gets much louder hits hardest.
        val dropAt = m.lastAtOrBefore(20.3)
        assertTrue(m.song.slice(dropAt - 1..dropAt + 1).any { it >= 0.99f })
        assertTrue(m.song.take(10).all { it < 0.99f })
    }

    @Test fun pulseJumpsOnTheBeatAndFallsAway() {
        val times = floatArrayOf(1f, 1.5f, 2f)
        val s = floatArrayOf(1f, 0.5f, 1f)
        val m = BeatMap(times, s, s, s, s, BooleanArray(3), byAi = true)
        assertEquals(0f, m.pulse(0.5, BeatSource.SONG), 0f)
        assertEquals(1f, m.pulse(1.0 - BeatMap.LEAD_S, BeatSource.SONG), 0.01f)
        assertTrue(m.pulse(1.2, BeatSource.SONG) < 0.5f)
        assertEquals(0.5f, m.pulse(1.5 - BeatMap.LEAD_S, BeatSource.SONG), 0.01f)
        // At half speed the same song time is twice as long ago in real time: smaller.
        assertTrue(m.pulse(1.1, BeatSource.SONG, speed = 0.5f) < m.pulse(1.1, BeatSource.SONG))
    }

    @Test fun windowsAndPeaksFollowTheReference() {
        // 4000 frames: windows of 1500 overlapping by 12, the last one moved back to end at the end.
        val starts = ArrayList<Int>()
        val (b, _) = BeatTracker.splitPredict(4000) { from, to, padL, padR ->
            starts += from - padL
            val n = padL + (to - from) + padR
            FloatArray(n) { if ((from - padL + it) % 25 == 0) 5f else -5f } to FloatArray(n) { -5f }
        }
        assertEquals(listOf(-6, 1482, 2506), starts)
        val beats = BeatTracker.peaks(b)
        assertEquals(160, beats.size)
        assertEquals(0.5f, beats[1], 1e-4f)
        // Two neighbouring frames above zero count as one beat between them.
        val two = BeatTracker.peaks(floatArrayOf(-1f, 2f, 2f, -1f, -1f))
        assertEquals(1, two.size); assertEquals(1.5f / 50, two[0], 1e-5f)
    }
}
