package com.ltots.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.ui.theme.LookProfile
import com.ltots.app.ui.theme.ThemeState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Look profiles (up to 10, named) keep every setting, also across a restart of the app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LookProfilesTest {
    @Before fun setUp() {
        Store.init(ApplicationProvider.getApplicationContext())
        ThemeState.init(freshInstall = true)
    }

    /** Lets the debounced saves reach the disk, then starts the look again as the app does. */
    private fun restart() {
        Store.flush()
        ThemeState.init(freshInstall = false)
    }

    /** Detail is the same in every profile: switching never changes it (it used to jump back). */
    @Test fun switchingProfilesNeverChangesDetail() {
        ThemeState.update { it.copy(analyzerBands = 160) }
        assertEquals(1, ThemeState.addProfile())
        assertEquals("a new profile is in use at once", 1, ThemeState.activeProfile.value)
        assertEquals(160, ThemeState.config.value.analyzerBands)
        ThemeState.update { it.copy(analyzerBands = 48, spectrumStyle = SpectrumStyle.BARS) }
        ThemeState.selectProfile(0)
        assertEquals(48, ThemeState.config.value.analyzerBands)
        assertEquals("other settings still belong to each profile", ThemeState.defaultLook.spectrumStyle, ThemeState.config.value.spectrumStyle)
        restart()
        assertEquals(48, ThemeState.config.value.analyzerBands)
        ThemeState.selectProfile(1)
        assertEquals(48, ThemeState.config.value.analyzerBands)
        assertEquals(SpectrumStyle.BARS, ThemeState.config.value.spectrumStyle)
    }

    /** The screen settings (Profile tab) are app-wide: the same whichever look is in use. */
    @Test fun screenSettingsStayTheSameAcrossLooks() {
        ThemeState.update { it.copy(hideNavBar = false, hideStatusBar = true, maxRefresh = false, effects = EffectsMode.LIGHT) }
        ThemeState.addProfile()
        ThemeState.update { it.copy(hideNavBar = true) }
        ThemeState.selectProfile(0)
        assertEquals(true, ThemeState.config.value.hideNavBar)
        assertEquals(true, ThemeState.config.value.hideStatusBar)
        assertEquals(false, ThemeState.config.value.maxRefresh)
        assertEquals(EffectsMode.LIGHT, ThemeState.config.value.effects)
        // Resetting the look leaves them alone too.
        ThemeState.reset()
        assertEquals(true, ThemeState.config.value.hideStatusBar)
        assertEquals(EffectsMode.LIGHT, ThemeState.config.value.effects)
    }

    @Test fun resetAllLeavesOneDefaultProfile() {
        ThemeState.update { it.copy(analyzerBands = 160) }
        ThemeState.addProfile(); ThemeState.addProfile()
        ThemeState.resetAll()
        assertEquals(0, ThemeState.activeProfile.value)
        assertEquals(ThemeState.defaultLook, ThemeState.config.value)
        assertEquals(listOf(LookProfile("Custom 1", ThemeState.defaultLook)), ThemeState.profiles.value)
    }

    @Test fun switchingBackAfterARestartKeepsCustomOne() {
        ThemeState.update { it.copy(analyzerBands = 160) }
        ThemeState.addProfile()
        restart()
        ThemeState.selectProfile(0)
        assertEquals(160, ThemeState.config.value.analyzerBands)
    }

    @Test fun upToTenAndAPlusUntilThen() {
        repeat(20) { ThemeState.addProfile() }
        assertEquals(ThemeState.MAX_PROFILES, ThemeState.profiles.value.size)
        assertNull("no 11th", ThemeState.addProfile())
        assertEquals((1..10).map { "Custom $it" }, ThemeState.profiles.value.map { it.name })
    }

    @Test fun renameAndDelete() {
        ThemeState.update { it.copy(spectrumStyle = SpectrumStyle.MIRROR) }
        ThemeState.addProfile()                       // Custom 2, in use
        ThemeState.update { it.copy(spectrumStyle = SpectrumStyle.BARS) }
        ThemeState.addProfile()                       // Custom 3, in use
        ThemeState.renameProfile(0, "  Night  ")
        ThemeState.renameProfile(1, "night")          // names stay unique
        assertEquals(listOf("Night", "night (2)", "Custom 3"), ThemeState.profiles.value.map { it.name })
        // Deleting one before the one in use keeps the same look in use.
        ThemeState.deleteProfile(0)
        assertEquals(1, ThemeState.activeProfile.value)
        assertEquals("Custom 3", ThemeState.profiles.value[1].name)
        // Deleting the one in use switches to its neighbour, and its look.
        ThemeState.deleteProfile(1)
        assertEquals(0, ThemeState.activeProfile.value)
        assertEquals(SpectrumStyle.BARS, ThemeState.config.value.spectrumStyle)
        // Never the last one.
        ThemeState.deleteProfile(0)
        assertEquals(1, ThemeState.profiles.value.size)
        restart()
        assertEquals(listOf("night (2)"), ThemeState.profiles.value.map { it.name })
        // A freed name is given out again.
        ThemeState.addProfile()
        assertEquals("Custom 1", ThemeState.profiles.value.last().name)
    }

    /** Saves from before names (five fixed slots, some empty) keep the used ones as Custom N. */
    @Test fun olderFiveSlotSavesCarryOver() {
        val dir = Store.dirOf(Users.active.value)
        val a = ThemeState.amoled.copy(spectrumStyle = SpectrumStyle.MIRROR)
        val b = ThemeConfig(spectrumStyle = SpectrumStyle.BARS)
        val slots = JSONArray().put(Json.theme(a)).put(JSONObject.NULL).put(JSONObject.NULL).put(Json.theme(b)).put(JSONObject.NULL)
        Store.flush()
        File(dir, "look_profiles.json").writeText(JSONObject().put("active", 3).put("slots", slots).toString())
        File(dir, "theme.json").writeText(Json.theme(b).toString())
        ThemeState.init(freshInstall = false)
        assertEquals(listOf("Custom 1", "Custom 4"), ThemeState.profiles.value.map { it.name })
        assertEquals(1, ThemeState.activeProfile.value)
        assertEquals(SpectrumStyle.BARS, ThemeState.config.value.spectrumStyle)
        assertTrue(ThemeState.profiles.value[0].look.pureBlack)
    }

    /** Your AMOLED colour comes back after using another theme (it used to reset to cyan). */
    @Test fun yourAmoledColourComesBackAfterAnotherTheme() {
        val mine = Hsv(300f, 0.45f, 0.7f)
        val amoled = ThemeState.presets.first { it.name == "AMOLED" }
        val cover = ThemeState.presets.first { it.name == "AMOLED Cover" }
        val aurora = ThemeState.presets.first { it.name == "Aurora" }
        ThemeState.update { ThemeState.amoled }
        ThemeState.update { it.copy(accent = mine.toArgb(), accentHsv = mine) }
        ThemeState.applyPreset(aurora)
        assertEquals(aurora.config.accent, ThemeState.config.value.accent)
        ThemeState.applyPreset(amoled)
        assertEquals(mine, ThemeState.config.value.accentHsv)
        assertEquals(mine.toArgb(), ThemeState.config.value.accent)
        assertTrue(ThemeState.isActive(amoled, ThemeState.config.value))
        // Through Aurora to AMOLED Cover too.
        ThemeState.applyPreset(aurora)
        ThemeState.applyPreset(cover)
        assertEquals(mine, ThemeState.config.value.accentHsv)
        // Never changed: AMOLED's own cyan.
        ThemeState.update { ThemeState.amoled }
        ThemeState.applyPreset(aurora); ThemeState.applyPreset(amoled)
        assertEquals(ThemeState.amoled.accent, ThemeState.config.value.accent)
    }
}
