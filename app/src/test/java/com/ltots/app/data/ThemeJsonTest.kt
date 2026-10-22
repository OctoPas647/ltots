package com.ltots.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeJsonTest {
    @Test fun amoledAndBarSettingsSurviveSaveAndLoad() {
        val t = ThemeConfig(accent = 0xFF00E5FF, pureBlack = true, hideNavBar = false, hideStatusBar = true)
        val back = Json.theme(JSONObject(Json.theme(t).toString()))
        assertEquals(t, back)
    }

    @Test fun olderSavedThemesGetSensibleDefaults() {
        // A theme saved by version 0.1.1 has none of the new fields.
        val old = JSONObject().put("accent", 0xFF8B6CFF).put("dark", true)
        val t = Json.theme(old)
        assertEquals(false, t.pureBlack)
        assertTrue("navigation bar hidden by default", t.hideNavBar)
        assertEquals(false, t.hideStatusBar)
    }

    @Test fun iconModeSettingsSurviveSaveAndLoad() {
        val t = ThemeConfig(
            iconMode = true, iconFile = "icon-123.png", iconThreshold = 0.62f, iconInvert = true, iconMinHole = 0.004f,
            iconFill = IconFill.GLASS, iconCover = IconCover.FULL, iconCoverDim = 0.35f, iconBars = IconBars.BOTH, iconIdle = IconIdle.COVER,
        )
        assertEquals(t, Json.theme(JSONObject(Json.theme(t).toString())))
        // Older saves: icon mode off, no picture, 50% threshold, nothing shown while paused.
        val old = Json.theme(JSONObject().put("accent", 0xFF8B6CFF))
        assertEquals(false, old.iconMode)
        assertEquals(null, old.iconFile)
        assertEquals(0.5f, old.iconThreshold, 0f)
        assertEquals(IconIdle.NOTHING, old.iconIdle)
        assertEquals(IconFill.DARK, old.iconFill)
        assertEquals(IconCover.OFF, old.iconCover)
        // The earlier on/off cover switch becomes "inside the icon".
        assertEquals(IconCover.INSIDE, Json.theme(JSONObject().put("iconCover", true)).iconCover)
    }

    @Test fun beatAndCoverWithBarsSurviveSaveAndLoad() {
        val t = ThemeConfig(coverBeat = true, beatStrength = 0.8f, coverBarsOpacity = 0.4f)
        assertEquals(t, Json.theme(JSONObject(Json.theme(t).toString())))
        val old = Json.theme(JSONObject().put("accent", 0xFF8B6CFF))
        assertEquals(false, old.coverBeat)
        assertEquals(1f, old.coverBarsOpacity, 0f)
    }
}
