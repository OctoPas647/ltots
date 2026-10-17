package com.ltots.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class WaveIndexTest {
    private val sr = 48000

    /** 1 s quiet, 1 s loud, 1 s quiet. */
    private fun build(): Wave {
        val b = WaveBuilder(sr)
        for (i in 0 until 3 * sr) {
            val amp = if (i in sr until 2 * sr) 0.8f else 0.1f
            b.add((amp * sin(2 * Math.PI * 220.0 * i / sr)).toFloat())
        }
        return b.finish()
    }

    @Test fun oneColumnEvery10ms() {
        val w = build()
        assertEquals(300, w.columns)
        assertEquals(3000L, w.durationMs)
        // Column 150 is in the loud second, column 50 in the quiet one.
        assertEquals(0.8f, w.hi[150] / 127f, 0.02f)
        assertEquals(-0.8f, w.lo[150] / 127f, 0.02f)
        assertEquals(0.1f, w.hi[50] / 127f, 0.02f)
    }

    @Test fun sliceFollowsThePlayerPosition() {
        val w = build()
        val n = 240
        val mn = FloatArray(n); val mx = FloatArray(n)
        // At 2.0 s the last 1 s on screen (right side) is loud, the left part quiet.
        w.fill(endMs = 2000, spanMs = 2500, mn, mx)
        assertTrue(mx[n - 5] > 0.7f)
        assertTrue(mx[10] < 0.2f)
        // Before the start of the song: silent.
        assertEquals(0f, mx[0], 0f)
        // At 3.0 s the loud second sits in the middle of the window.
        w.fill(endMs = 3000, spanMs = 2500, mn, mx)
        assertTrue(mx[n - 5] < 0.2f)
        assertTrue(mx[n / 2] > 0.7f)
    }
}
