package com.ltots.app.ui.components

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

    @Test fun bassAtTheBottomTrebleAtTheTopMirrored() {
        val levels = FloatArray(96) { it / 95f } // treble loudest
        val top = ring.levelAt(0, levels)
        val bottom = ring.levelAt(ring.count / 2, levels)
        assertTrue("treble at the top: $top", top > 0.95f)
        assertTrue("bass at the bottom: $bottom", bottom < 0.05f)
        for (k in 1 until ring.count / 2) assertEquals(ring.levelAt(k, levels), ring.levelAt(ring.count - 1 - k, levels), 1e-4f)
    }

    @Test fun barsStayInsideTheArea() {
        val reach = 620f / 2 + 20f + ring.maxLength
        assertTrue("max length ${ring.maxLength}", ring.maxLength > 100f && reach < 500f)
    }
}
