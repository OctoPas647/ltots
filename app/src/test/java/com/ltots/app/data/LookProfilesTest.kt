package com.ltots.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.ui.theme.ThemeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Look profiles (Custom 1-5) keep every setting, also across a restart of the app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LookProfilesTest {
    @Before fun setUp() {
        Store.init(ApplicationProvider.getApplicationContext())
        ThemeState.init(freshInstall = true)
    }

    /** Lets the debounced saves reach the disk, then starts the look again as the app does. */
    private fun restart() {
        Thread.sleep(900)
        ThemeState.init(freshInstall = false)
    }

    /** Detail is the same in every profile: switching never changes it (it used to jump back). */
    @Test fun switchingProfilesNeverChangesDetail() {
        ThemeState.update { it.copy(analyzerBands = 160) }
        ThemeState.selectProfile(1)
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

    @Test fun resetAllEmptiesEveryProfile() {
        ThemeState.update { it.copy(analyzerBands = 160) }
        ThemeState.selectProfile(3)
        ThemeState.resetAll()
        assertEquals(0, ThemeState.activeProfile.value)
        assertEquals(ThemeState.defaultLook, ThemeState.config.value)
        assertEquals(listOf(ThemeState.defaultLook, null, null, null, null), ThemeState.profiles.value)
    }

    @Test fun switchingBackAfterARestartKeepsCustomOne() {
        ThemeState.update { it.copy(analyzerBands = 160) }
        ThemeState.selectProfile(2)
        restart()
        ThemeState.selectProfile(0)
        assertEquals(160, ThemeState.config.value.analyzerBands)
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
