package com.ltots.app.ui.components

import com.ltots.app.data.RingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Icon mode's picture → mask → outline steps, on small drawn fixtures (plain JVM, no Android). */
class IconMaskTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** A w×h picture, white unless [dark] says otherwise. */
    private fun picture(w: Int, h: Int, dark: (x: Int, y: Int) -> Boolean) =
        IntArray(w * h) { i -> if (dark(i % w, i / w)) black else white }

    private fun maskOf(w: Int, h: Int, dark: (x: Int, y: Int) -> Boolean) =
        IconMask(w, h, BooleanArray(w * h) { i -> dark(i % w, i / w) })

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    // ---- threshold, invert, transparency ------------------------------------------------

    @Test fun thresholdSplitsByBrightness() {
        val src = IconMask.prepare(intArrayOf(black, grey(102), grey(180), white), 4, 1)
        // Upscaled; look at the middle of each original pixel.
        fun darkAt(t: Float, i: Int): Boolean {
            val d = IconMask.threshold(src, t, invert = false)
            val x = ((i + 0.5f) * src.width / 4).toInt()
            return d[(src.height / 2) * src.width + x]
        }
        assertTrue(darkAt(0.5f, 0)); assertTrue("40% grey is below 50%", darkAt(0.5f, 1))
        assertFalse("70% grey is above 50%", darkAt(0.5f, 2)); assertFalse(darkAt(0.5f, 3))
        assertFalse("a lower threshold makes the grey white", darkAt(0.3f, 1))
        assertTrue("a higher threshold makes the light grey dark", darkAt(0.8f, 2))
    }

    @Test fun invertSwapsBlackAndWhite() {
        val src = IconMask.prepare(intArrayOf(black, white), 2, 1)
        val d = IconMask.threshold(src, 0.5f, invert = true)
        val row = (src.height / 2) * src.width
        assertFalse("black becomes white", d[row + src.width / 4])
        assertTrue("white becomes dark", d[row + 3 * src.width / 4])
    }

    @Test fun transparentCountsAsWhite() {
        val clearBlack = 0x00000000
        val src = IconMask.prepare(intArrayOf(clearBlack, black), 2, 1)
        val row = (src.height / 2) * src.width
        for (invert in listOf(false, true)) {
            val d = IconMask.threshold(src, 0.5f, invert)
            assertFalse("transparent is white (invert=$invert)", d[row + src.width / 4])
        }
        assertTrue(IconMask.threshold(src, 0.5f, false)[row + 3 * src.width / 4])
    }

    @Test fun smallPicturesAreScaledUpSmoothly() {
        val src = IconMask.prepare(picture(64, 32) { x, _ -> x < 32 }, 64, 32)
        assertEquals(IconMask.MIN_SIDE, src.width)
        assertEquals(512, src.height)
        assertEquals(16f, src.scale, 1e-4f)
        // The edge is a soft ramp, not a step: in-between values exist.
        val row = 256 * src.width
        assertTrue((0 until src.width).any { src.lum[row + it] in 0.1f..0.9f })
    }

    // ---- specks and holes ----------------------------------------------------------------

    @Test fun tinySpecksAreRemoved() {
        // A 3×3 speck (9 px, under 20) and a 20×20 square, in a 100×100 picture.
        val argb = picture(100, 100) { x, y -> (x in 5..7 && y in 5..7) || (x in 50..69 && y in 50..69) }
        val m = IconMask.build(IconMask.prepare(argb, 100, 100), 0.5f, invert = false)
        // Only the square is left: the crop is the square's size (×10.24 working scale).
        assertEquals(20 * 10.24f, m.width.toFloat(), 3f)
        assertEquals(20 * 10.24f, m.height.toFloat(), 3f)
    }

    @Test fun holesSmallerThanTheMinimumAreFilled() {
        // A dark square with a 2×2 hole (an "eye") and a 20×20 hole.
        val argb = picture(100, 100) { x, y ->
            x in 20..79 && y in 20..79 && !(x in 30..31 && y in 30..31) && !(x in 50..69 && y in 50..69)
        }
        val src = IconMask.prepare(argb, 100, 100)
        val keepBig = IconContours.trace(IconMask.build(src, 0.5f, false, minHole = 0.001f))
        assertEquals("outline + the big hole; the eye is filled", 2, keepBig.loops.size)
        val keepAll = IconContours.trace(IconMask.build(src, 0.5f, false, minHole = 0f))
        assertEquals("with no minimum every hole is traced", 3, keepAll.loops.size)
        val fillAll = IconContours.trace(IconMask.build(src, 0.5f, false, minHole = 0.05f))
        assertEquals("a bigger minimum fills both", 1, fillAll.loops.size)
    }

    @Test fun holesTouchingTheOutsideAreNotHoles() {
        // A "C": its white inside opens to the outside, so it is never filled, however small.
        val m = maskOf(40, 40) { x, y -> x in 5..34 && y in 5..34 && !(x in 12..34 && y in 18..21) }
        val dark = m.dark.copyOf()
        IconMask.fillHoles(dark, 40, 40, minArea = 10_000)
        assertFalse(dark[20 * 40 + 25])
    }

    // ---- outlines and normals ------------------------------------------------------------

    private fun ringFor(mask: IconMask, area: Float = 1000f, fraction: Float = 0.7f, spacing: Float = 6f) =
        IconRing.build(IconShape.of(mask, fraction), area, area, marginPx = 40f, iconFraction = fraction,
            spacingPx = spacing, minPoints = 48, maxBarWidthPx = 12f)!!

    private val noBounds = floatArrayOf(-1e9f, 1e9f, -1e9f, 1e9f)

    @Test fun filledSquareGivesOneOutlineWithOutwardNormals() {
        val mask = maskOf(200, 200) { _, _ -> true }
        val c = IconContours.trace(mask)
        assertEquals(1, c.loops.size)
        assertEquals("perimeter", 800f, c.lengths[0], 16f)
        val ring = ringFor(mask)
        assertEquals(1, ring.contourStarts.size)
        val cx = 500f; val cy = 500f
        for (k in 0 until ring.count) {
            val dx = ring.x[k] - cx; val dy = ring.y[k] - cy
            assertTrue("normal $k points away from the square", ring.nx[k] * dx + ring.ny[k] * dy > 0)
            assertEquals(1f, hypot(ring.nx[k], ring.ny[k]), 1e-3f)
        }
        // The icon fills 70% of the safe area (1000 − 2·40), centred.
        assertEquals(0.7f * 920f, ring.iconWidth, 0.5f)
        assertEquals(500f - 0.7f * 920f / 2, ring.iconLeft, 0.5f)
    }

    @Test fun ringWithHoleHasInnerOutlineFacingIntoTheHole() {
        val mask = maskOf(200, 200) { x, y -> hypot(x - 99.5f, y - 99.5f) in 50f..99f }
        val c = IconContours.trace(mask)
        assertEquals(2, c.loops.size)
        assertTrue("longest first", c.lengths[0] > c.lengths[1])
        val ring = ringFor(mask)
        assertEquals(2, ring.contourStarts.size)
        val outer = 0 until ring.contourStarts[1]
        val inner = ring.contourStarts[1] until ring.count
        fun outward(k: Int) = ring.nx[k] * (ring.x[k] - 500f) + ring.ny[k] * (ring.y[k] - 500f)
        assertTrue(outer.all { outward(it) > 0 })
        assertTrue("inner normals point into the hole (the white side)", inner.all { outward(it) < 0 })
        // Clockwise from the top: the first points of the outer outline move right.
        assertTrue(ring.x[outer.first + 2] > ring.x[outer.first])
        // Inside the round hole the bars would all meet in the middle: they stop well before.
        val holeRadius = 50f * ring.iconWidth / 200f
        assertTrue("hole r=$holeRadius reach ${inner.minOf { ring.reachOutPx[it] }}..${inner.maxOf { ring.reachOutPx[it] }} in ${inner.minOf { ring.reachInPx[it] }}..${inner.maxOf { ring.reachInPx[it] }}",
            inner.all { ring.reachOutPx[it] < 0.65f * holeRadius })
    }

    // ---- reach -------------------------------------------------------------------------

    @Test fun barsInANarrowGapStopBeforeHalfWay() {
        // Two dark columns with a 10 px white gap between them.
        val field = IconShape.of(maskOf(70, 100) { x, _ -> x < 30 || x >= 40 }).field
        // From the left column's right edge (x = 30) towards the right: a little short of the
        // middle, so bars from both sides leave a small gap.
        val r = IconRing.reachOut(field, 30f, 50f, 1f, 0f, maxLen = 100f, noBounds)
        assertTrue("under half the 10 px gap: $r", r in 4f..5f)
        // From the outer edge (x = 0) outwards there's nothing in the way: up to the area edge.
        val open = IconRing.reachOut(field, 0f, 50f, -1f, 0f, maxLen = 100f, floatArrayOf(-30f, 200f, -1e9f, 1e9f))
        assertEquals(30f, open, 0.6f)
        // Into the shape: a little under half the column's 30 px thickness.
        val inward = IconRing.reachIn(field, 30f, 50f, 1f, 0f, maxLen = 100f)
        assertTrue("inward $inward", inward in 12f..15f)
    }

    @Test fun barsInNarrowGapsAreShorterAndThinner() {
        // Two dark columns with a 3 px white slit between them.
        val mask = maskOf(63, 100) { x, _ -> x < 30 || x >= 33 }
        val ring = ringFor(mask, spacing = 20f)
        val px = ring.iconWidth / 63f
        val gapX = ring.iconLeft + 30f * px
        // Points on the slit's left wall versus points on the left column's open outer side.
        val inGap = (0 until ring.count).filter { kotlin.math.abs(ring.x[it] - gapX) < px && ring.nx[it] > 0.9f }
        val outside = (0 until ring.count).filter { ring.x[it] < ring.iconLeft + px && ring.nx[it] < -0.9f }
        assertTrue(inGap.isNotEmpty() && outside.isNotEmpty())
        val gapReach = inGap.maxOf { ring.reachOutPx[it] }
        assertTrue("shorter", gapReach < outside.minOf { ring.reachOutPx[it] } / 2)
        // Sharp convex corners still get bars (the outline is softened there).
        assertTrue((0 until ring.count).all { ring.reachOutPx[it] > 5f })
        assertTrue("thinner", inGap.maxOf { ring.halfWidthOut[it] } < outside.minOf { ring.halfWidthOut[it] })
        // Across the slit, the bars from the two walls together never fill more than the slit.
        val left = inGap.filter { ring.nx[it] > 0.999f }
        val right = (0 until ring.count).filter { kotlin.math.abs(ring.x[it] - gapX - 3 * px) < px && ring.nx[it] < -0.999f }
        assertTrue(left.isNotEmpty() && right.isNotEmpty())
        val l = left.maxOf { ring.reachOutPx[it] }; val r = right.maxOf { ring.reachOutPx[it] }
        assertTrue("left $l + right $r within the ${3 * px} px slit", l + r <= 3 * px + 0.5f)
    }

    // ---- band mapping --------------------------------------------------------------------

    @Test fun spectrumRunsOnceAlongTheWholeOutline() {
        val mask = maskOf(200, 200) { x, y -> hypot(x - 99.5f, y - 99.5f) in 50f..99f }
        val ring = ringFor(mask)
        val rising = FloatArray(64) { it / 63f }
        var last = -1f
        for (k in 0 until ring.count) {
            val v = ring.levelAt(k, rising, RingLayout.LOOP)
            assertTrue("rises along both outlines in order", v >= last - 1e-5f)
            last = v
        }
        assertTrue(ring.levelAt(0, rising, RingLayout.LOOP) < 0.02f)
        assertTrue(ring.levelAt(ring.count - 1, rising, RingLayout.LOOP) > 0.98f)
        // Mirrored: the same at both ends, treble at the start, bass halfway.
        val m = RingLayout.MIRRORED
        assertTrue(ring.levelAt(0, rising, m) > 0.95f)
        assertTrue(ring.levelAt(ring.count / 2, rising, m) < 0.05f)
        assertEquals(ring.levelAt(1, rising, m), ring.levelAt(ring.count - 2, rising, m), 1e-3f)
        assertTrue(ring.levelAt(0, rising, RingLayout.MIRRORED_FLIPPED) < 0.05f)
    }
}
