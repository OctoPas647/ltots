package com.ltots.app.ui.components

import com.ltots.app.data.RingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class CoverRingTest {
    private val ring = CoverRing(size = 1000f, coverSide = 620f, cornerRadius = 60f, gap = 20f, count = 192)

    @Test fun pointsSitOnTheRoundedSquareAroundTheCover() {
        val a = 620f / 2 + 20f
        val r = 80f
        for (k in 0 until ring.count) {
            val dx = abs(ring.x[k] - 500f); val dy = abs(ring.y[k] - 500f)
            // Distance from the rounded square's outline must be ~0.
            val qx = max(dx - (a - r), 0f); val qy = max(dy - (a - r), 0f)
            val dist = if (dx <= a - r || dy <= a - r) max(dx, dy) - a else hypot(qx, qy) - r
            assertEquals("point $k", 0f, dist, 0.5f)
            // The direction points outwards and has length 1.
            assertEquals(1f, hypot(ring.nx[k], ring.ny[k]), 1e-3f)
            assertTrue((ring.x[k] - 500f) * ring.nx[k] + (ring.y[k] - 500f) * ring.ny[k] > 0f)
        }
    }

    @Test fun pointsAreEvenlySpacedAndGoAllTheWayRound() {
        for (k in 0 until ring.count) {
            val j = (k + 1) % ring.count
            val d = hypot(ring.x[j] - ring.x[k], ring.y[j] - ring.y[k])
            assertEquals("gap after $k", ring.spacing, d, ring.spacing * 0.2f)
        }
        // Starts at the top centre.
        assertEquals(500f, ring.x[0], ring.spacing)
        assertTrue(ring.y[0] < 500f)
    }

    private val rising = FloatArray(96) { it / 95f } // bass quietest, treble loudest

    @Test fun mirroredBassAtTheBottom() {
        val l = RingLayout.MIRRORED
        assertTrue("treble at the top", ring.levelAt(0, rising, l) > 0.95f)
        assertTrue("bass at the bottom", ring.levelAt(ring.count / 2, rising, l) < 0.05f)
        for (k in 1 until ring.count / 2) assertEquals(ring.levelAt(k, rising, l), ring.levelAt(ring.count - 1 - k, rising, l), 1e-4f)
    }

    @Test fun mirroredFlippedBassAtTheTop() {
        val l = RingLayout.MIRRORED_FLIPPED
        assertTrue("bass at the top", ring.levelAt(0, rising, l) < 0.05f)
        assertTrue("treble at the bottom", ring.levelAt(ring.count / 2, rising, l) > 0.95f)
        for (k in 1 until ring.count / 2) assertEquals(ring.levelAt(k, rising, l), ring.levelAt(ring.count - 1 - k, rising, l), 1e-4f)
    }

    @Test fun oneLoopGoesOnceRoundWithoutRepeating() {
        val l = RingLayout.LOOP
        // Bass at the top, rising steadily all the way round clockwise: every band once.
        var prev = -1f
        for (k in 0 until ring.count) {
            val v = ring.levelAt(k, rising, l)
            assertTrue("point $k goes on round: $v after $prev", v > prev)
            prev = v
        }
        assertTrue(ring.levelAt(0, rising, l) < 0.02f)
        assertTrue(ring.levelAt(ring.count - 1, rising, l) > 0.98f)
        // Not mirrored: the right side (a quarter round) differs from the left (three quarters).
        assertTrue(ring.levelAt(ring.count / 4, rising, l) < ring.levelAt(3 * ring.count / 4, rising, l) - 0.4f)
    }

    @Test fun barsStayInsideTheArea() {
        val reach = 620f / 2 + 20f + ring.maxLength
        assertTrue("max length ${ring.maxLength}", ring.maxLength > 100f && reach < 500f)
    }
}
