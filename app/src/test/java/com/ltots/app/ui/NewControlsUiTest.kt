package com.ltots.app.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ltots.app.MainActivity
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.EqMath
import com.ltots.app.audio.EqTone
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.Store
import com.ltots.app.data.Track
import com.ltots.app.playback.PlaybackService
import com.ltots.app.playback.PlayerConnection
import com.ltots.app.ui.theme.ThemeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The full-page library, the EQ's reset and profiles, and the Look tab's profiles and gray-outs. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class NewControlsUiTest {
    private val setup = object : ExternalResource() {
        var svc: org.robolectric.android.controller.ServiceController<PlaybackService>? = null
        override fun before() {
            com.ltots.app.lyrics.Lyrics.api = com.ltots.app.lyrics.LrcLib("http://127.0.0.1:9/api")
            val app = ApplicationProvider.getApplicationContext<Application>()
            val ctl = Robolectric.buildService(PlaybackService::class.java).create()
            svc = ctl
            val binder = ctl.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
            // A library of songs, as if read from the phone.
            shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            val songs = (1..30).map { i ->
                Track(i.toLong(), Uri.parse("content://media/external/audio/media/$i"), "Song number $i", "Artist ${(i % 4) + 1}",
                    "Album ${(i % 3) + 1}", (i % 3).toLong(), 180_000L + i * 1000, 44100, 16, "audio/flac")
            }
            fun set(name: String, v: Any?) = LocalLibrary::class.java.getDeclaredField(name).apply { isAccessible = true }.set(LocalLibrary, v)
            set("cache", songs); set("stale", false)
            set("cachedVersion", com.ltots.app.download.DownloadManager.libraryVersion.value)
        }

        override fun after() {
            PlayerConnection.disconnect()
            svc?.destroy()
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(setup).around(rule)

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/$name.png")
    }

    /** The whole screen, pop-up menus included. */
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun screen(name: String) {
        rule.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/ui-shots/$name.png")
    }

    private fun idle(ms: Long = 600) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    private fun tab(label: String) {
        rule.onAllNodesWithText(label)[0].performClick()
        idle(1200)
    }

    private fun click(text: String) {
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        idle(500)
    }

    @Test fun newControls() {
        rule.mainClock.autoAdvance = false
        idle()

        // 1. Library: no box around the songs; the list runs down the whole page.
        rule.onNodeWithText("Song number 1").assertIsDisplayed()
        val rootH = rule.onRoot().fetchSemanticsNode().size.height
        val shown = (1..30).filter { i ->
            rule.onAllNodesWithText("Song number $i").fetchSemanticsNodes().isNotEmpty()
        }
        shot("10_library_full_page")
        println("LIBRARY rootH=$rootH shown=${shown.size}")

        // 2. EQ: reset and profiles, top right.
        tab("EQ")
        val rootW = rule.onRoot().fetchSemanticsNode().size.width
        val reset = rule.onNodeWithContentDescription("Reset EQ: every gain back to 0 dB").fetchSemanticsNode().boundsInRoot
        val profiles = rule.onNodeWithContentDescription("EQ profiles").fetchSemanticsNode().boundsInRoot
        val undo = rule.onNodeWithContentDescription("Undo the last EQ change").fetchSemanticsNode().boundsInRoot
        assertTrue("reset is in the top-right corner: $reset of $rootW", reset.right > rootW * 0.88f && reset.top < 400f)
        assertTrue("undo is just left of reset: $undo vs $reset", undo.right <= reset.left && undo.right > reset.left - 80f)
        assertTrue("profiles is just left of undo: $profiles vs $undo", profiles.right <= undo.left && profiles.right > undo.left - 80f)

        click("Genres"); click("Rock")
        val rock = Store.builtInPresets.first { it.name == "Rock" }.eq
        assertTrue(EqTone.sameShape(AudioEngine.effectiveEq.value, rock))

        // Save Rock into Custom 1 (an empty slot saves when tapped).
        rule.onNodeWithContentDescription("EQ profiles").performClick(); idle()
        screen("11_eq_profiles_menu")
        click("Custom 1")
        rule.onNodeWithText("In use").assertExists()
        screen("11a_eq_profile_saved")
        click("Custom 1") // a saved one loads and closes the menu

        // Reset: every gain back to 0 dB.
        rule.onNodeWithContentDescription("Reset EQ: every gain back to 0 dB").performClick(); idle()
        assertTrue("reset flattens the EQ", EqMath.isNeutral(AudioEngine.effectiveEq.value) || AudioEngine.effectiveEq.value.bands.all { !it.type.usesGain || it.gainDb == 0f })
        shot("12_eq_after_reset")

        // Load Custom 1 again: Rock is back.
        rule.onNodeWithContentDescription("EQ profiles").performClick(); idle()
        click("Custom 1")
        assertTrue("profile loads", EqTone.sameShape(AudioEngine.effectiveEq.value, rock))

        // Undo: the last change was loading Custom 1 (Rock), so one undo returns to the reset EQ.
        rule.onNodeWithContentDescription("Undo the last EQ change").performClick(); idle()
        assertTrue("undo goes back one step", !EqTone.sameShape(AudioEngine.effectiveEq.value, rock))

        // 3. Look: AMOLED grays out Glow (no background to colour); Blur and Refraction stay on:
        // they shape the bars at the bottom.
        tab("Look")
        rule.onNodeWithText("AMOLED").performClick(); idle(1000)
        assertTrue(ThemeState.config.value.pureBlack)
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(ThemeState.config.value.blurDp, 0f..60f))).assertIsEnabled()
        rule.onNodeWithText("Glow").assertIsNotEnabled()
        rule.onAllNodesWithText("Prism edges").fetchSemanticsNodes().let { assertTrue("Prism edges removed", it.isEmpty()) }
        shot("13_look_amoled_grayed")

        // Look profiles, top right: Custom 1 is in use; Custom 2 starts as a copy of this look.
        click("Custom 1")
        screen("14_look_profiles_menu")
        click("Custom 2"); idle(1500)
        assertEquals(1, ThemeState.activeProfile.value)
        assertTrue(ThemeState.config.value.pureBlack)

        // Make Custom 2 Aurora with cover colours off: the glow is in use and can be changed.
        rule.onNodeWithText("Aurora").performClick(); idle(1000)
        assertTrue(!ThemeState.config.value.pureBlack)
        assertTrue("cover colours belong to AMOLED Cover only", !ThemeState.config.value.dynamicColors)
        rule.onAllNodesWithText("Colours from the song's cover").fetchSemanticsNodes().let { assertTrue("cover switch removed", it.isEmpty()) }
        rule.onNodeWithText("Glow").assertIsEnabled()
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(ThemeState.config.value.blurDp, 0f..60f))).assertIsEnabled()
        shot("15_look_aurora_glow")
        val aurora = ThemeState.config.value

        // Back to Custom 1: the AMOLED look returns; Custom 2 kept Aurora.
        click("Custom 2")
        click("Custom 1"); idle(1500)
        assertEquals(0, ThemeState.activeProfile.value)
        assertTrue(ThemeState.config.value.pureBlack)
        click("Custom 1")
        click("Custom 2"); idle(1500)
        assertEquals(aurora, ThemeState.config.value)
    }

    /** A tall screen, so the Glass card shows without scrolling (scrolling stalls under Robolectric). */
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    @Test fun glassCardOnAmoled() {
        rule.mainClock.autoAdvance = false
        idle()
        tab("Look")
        rule.onNodeWithText("AMOLED").performClick(); idle(1000)
        rule.onNodeWithText("Frost / tint").assertIsDisplayed()
        // Refraction works on AMOLED too: it bends the page under the bars at the bottom.
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(ThemeState.config.value.refraction, 0f..1f))).assertIsEnabled()
        shot("13b_look_glass_amoled")
    }

    /** Battery EQ: the switch, its live status, and the phone's simplified curve on the EQ graph. */
    @Config(qualifiers = "w411dp-h2300dp-xxhdpi")
    @Test fun batteryEq() {
        rule.mainClock.autoAdvance = false
        idle()
        tab("EQ")
        click("Genres"); click("Rock")
        tab("Sound")
        click("Battery EQ (experimental)")
        idle(1000)
        assertTrue(AudioEngine.settings.value.batteryEq)
        assertTrue("the phone's EQ is doing the work: ${AudioEngine.phoneEq.status.value}", AudioEngine.batteryEqActive.value)
        rule.onNodeWithText("Play a song to see whether it works on this phone.").assertExists()
        shot("16_sound_battery_eq")
        tab("EQ")
        rule.onNodeWithText("Battery EQ is on", substring = true).assertExists()
        shot("17_eq_battery_curve")
        // Off again: LToTS's own EQ is back.
        tab("Sound")
        click("Battery EQ (experimental)")
        idle(1000)
        assertTrue(!AudioEngine.batteryEqActive.value)
    }

    /** Pitch can be tuned to the cent: fine slider, ±1 ct, and typing an exact value or A = 432 Hz. */
    @Config(qualifiers = "w411dp-h3000dp-xxhdpi")
    @Test fun finePitch() {
        rule.mainClock.autoAdvance = false
        idle()
        click("Song number 1") // a song in the player shows the pitch controls
        idle(1500)
        tab("Playing")
        AudioEngine.setPitch(0)
        idle()
        // Fine slider: 1-cent resolution, on top of the semitone.
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(0f, -50f..50f)))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(17f) }
        idle()
        assertEquals(17, AudioEngine.pitchCents.value)
        click("+1 ct"); click("+1 ct")
        assertEquals(19, AudioEngine.pitchCents.value)
        click("+1 st")
        assertEquals(119, AudioEngine.pitchCents.value)
        rule.onNodeWithText("+1 st 19 ct").assertExists()
        shot("18_fine_pitch")
        // Tapping the value opens the exact-pitch dialog (tested on its own in ExactPitchDialogTest:
        // dialogs don't settle inside the whole app under the test runner).
        AudioEngine.setPitch(0)
    }
}
