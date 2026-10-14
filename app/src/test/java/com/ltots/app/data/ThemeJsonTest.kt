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
}
