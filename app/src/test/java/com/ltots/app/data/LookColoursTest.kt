package com.ltots.app.data

import com.ltots.app.ui.theme.ThemeState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colour sliders are independent, and themes only change theme things. */
class LookColoursTest {
    @Test fun hsvRoundTripsThroughTheStoredColour() {
        for (argb in listOf(0xFF8B6CFF, 0xFF00E5FF, 0xFF000000, 0xFFFFFFFF, 0xFF2A2A2A, 0xFFFF4D6D)) {
            assertEquals(argb, Hsv.of(argb).toArgb())
        }
    }

    @Test fun brightnessNeverChangesSaturationOrHue() {
        // Walk brightness all the way down and back up, as the slider would; the stored hue and
        // saturation stay exactly as they were (they used to drift and jump near black).
        var hsv = Hsv(212f, 0.83f, 1f)
        var argb = hsv.toArgb()
        for (step in (100 downTo 0) + (0..100)) {
            val stored = Hsv.resolve(argb, hsv)
            hsv = stored.copy(v = step / 100f)
            argb = hsv.toArgb()
            assertEquals(212f, Hsv.resolve(argb, hsv).h, 0f)
            assertEquals(0.83f, Hsv.resolve(argb, hsv).s, 0f)
        }
    }

    @Test fun hueMovesSmoothlyEvenWhenDark() {
        var hsv = Hsv(0f, 0.9f, 0.03f)
        var lastH = 0f
        for (h in 0..359) {
            val argb = hsv.copy(h = h.toFloat()).toArgb()
            hsv = Hsv.resolve(argb, hsv.copy(h = h.toFloat()))
            assertEquals(h.toFloat(), hsv.h, 0f)
            assertTrue(hsv.h - lastH <= 1f)
            lastH = hsv.h
        }
    }

    @Test fun anOutsideChangeIsPickedUp() {
        // A theme or profile changed the colour itself: the old slider values no longer apply.
        val stale = Hsv(10f, 0.5f, 0.5f)
        assertEquals(Hsv.of(0xFF00E5FF), Hsv.resolve(0xFF00E5FF, stale))
    }

    @Test fun lookSettingsSurviveSaving() {
        val t = ThemeConfig(accentHsv = Hsv(200f, 0.4f, 0.02f), glowHsv = Hsv(10f, 1f, 0.5f), analyzerBands = 160, visualizerFps = 60, maxRefresh = false)
        assertEquals(t, Json.theme(JSONObject(Json.theme(t).toString())))
    }

    @Test fun oldThirtyFpsMovesTo120Once() {
        val old = JSONObject().put("vfps", 30).put("vfpsV", 2)
        assertEquals(120, Json.theme(old).visualizerFps)
        val picked = JSONObject(Json.theme(ThemeConfig(visualizerFps = 30)).toString())
        assertEquals(30, Json.theme(picked).visualizerFps)
    }

    @Test fun coverColoursOnlyOnAmoled() {
        val old = JSONObject().put("dyn", true).put("black", false)
        assertFalse(Json.theme(old).dynamicColors)
        assertTrue(Json.theme(JSONObject().put("dyn", true).put("black", true)).dynamicColors)
    }
}

class ThemePresetTest {
    @Test fun themesKeepYourOwnLookSettings() {
        val mine = ThemeState.amoled.copy(visualizerFps = 60, analyzerBands = 160, cornerDp = 12f, coverOpacity = 0.5f, maxRefresh = false, hideStatusBar = true)
        for (p in ThemeState.presets) {
            val after = ThemeState.presetApplied(p, mine)
            assertEquals(p.name, 60, after.visualizerFps)
            assertEquals(160, after.analyzerBands)
            assertEquals(12f, after.cornerDp, 0f)
            assertEquals(0.5f, after.coverOpacity, 0f)
            assertEquals(false, after.maxRefresh)
            assertEquals(true, after.hideStatusBar)
            assertTrue(ThemeState.isActive(p, after))
            // Cover colours only on AMOLED Cover.
            assertEquals(p.name == "AMOLED Cover", after.dynamicColors)
        }
    }
}
