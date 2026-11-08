package com.ltots.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicColorTest {
    private fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    @Test fun averageOfHalfRedHalfBlueIsPurple() {
        val px = IntArray(100) { if (it < 50) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val (r, g, b) = rgb(DynamicColor.average(px))
        // Averaged in linear light: 50% red + 50% blue ≈ (188, 0, 188), not the muddy (128, 0, 128).
        assertTrue(r in 180..195 && b in 180..195 && g == 0)
    }

    @Test fun darkCoverStillGivesAReadableAccentWithSameHue() {
        val darkGreen = 0xFF0A2A12.toInt()
        val accent = DynamicColor.liftForAccent(darkGreen, dark = true)
        val hIn = DynamicColor.rgbToHsv(darkGreen)[0]
        val hsv = DynamicColor.rgbToHsv(accent)
        assertEquals(hIn, hsv[0], 3f)
        assertTrue("bright enough on black", hsv[2] >= 0.8f)
        assertTrue("colourful", hsv[1] >= 0.4f)
    }

    @Test fun greyCoverStaysNeutral() {
        val accent = DynamicColor.liftForAccent(0xFF6E6E6E.toInt(), dark = true)
        assertTrue(DynamicColor.rgbToHsv(accent)[1] < 0.1f)
    }

    @Test fun glowsComeFromTopAndBottomHalves() {
        val px = IntArray(48 * 48) { if (it < 48 * 24) 0xFFFF2000.toInt() else 0xFF0030FF.toInt() }
        val c = DynamicColor.fromPixels(px, 48)
        assertTrue(rgb(c.glowTop).first > rgb(c.glowTop).third)
        assertTrue(rgb(c.glowBottom).third > rgb(c.glowBottom).first)
    }
}
