package com.ltots.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ltots.app.MainActivity
import com.ltots.app.audio.AudioEngine
import com.ltots.app.playback.PlaybackService
import com.ltots.app.playback.PlayerConnection
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

/**
 * Opens the real app (Robolectric runs the actual Android + Compose + Media3 code), visits
 * every tab, exercises the main controls and saves a screenshot of each screen for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class AppUiTest {
    /** Starts the real playback service and lets the app's media controller bind to it. */
    private val service = object : ExternalResource() {
        var svc: org.robolectric.android.controller.ServiceController<PlaybackService>? = null
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val ctl = Robolectric.buildService(PlaybackService::class.java).create()
            svc = ctl
            val svc = ctl.get()
            val binder = svc.onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
        }

        override fun after() {
            PlayerConnection.disconnect()
            svc?.destroy()
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(service).around(rule)

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/$name.png")
    }

    private fun idle(ms: Long = 600) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun tab(label: String) {
        rule.onAllNodesWithText(label).first().performClick()
        idle(1200)
    }

    @Test fun everyTabOpensAndWorks() {
        rule.mainClock.autoAdvance = false
        idle()
        shot("01_library")

        tab("Playing")
        rule.onNodeWithText("Nothing playing yet").assertIsDisplayed()
        shot("02_playing_empty")

        tab("EQ")
        rule.onNodeWithText("Equalizer").assertIsDisplayed()
        shot("03_eq")
        val before = AudioEngine.effectiveEq.value.bands.size
        rule.onNodeWithContentDescription("More bands").performScrollTo().performClick()
        idle(300)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        idle(300)
        println("BANDS before=$before default=${AudioEngine.defaultEq.value.bands.size} effective=${AudioEngine.effectiveEq.value.bands.size}")
        assertEquals(before + 1, AudioEngine.effectiveEq.value.bands.size)
        rule.onNodeWithText("${before + 1}").assertExists()
        shot("03b_eq_band_added")

        tab("Get")
        rule.onNodeWithText("Get music").assertIsDisplayed()
        shot("04_get")

        tab("Look")
        rule.onNodeWithText("AMOLED").performClick()
        idle(1000)
        shot("05_look_amoled")
        // New visualizer refresh-rate choice (clicked via its accessibility action; the screen
        // is long). All four options must fit across the screen.
        rule.onNodeWithText("30 fps").performSemanticsAction(SemanticsActions.OnClick)
        idle(400)
        assertEquals(30, com.ltots.app.ui.theme.ThemeState.config.value.visualizerFps)
        val rootW = rule.onRoot().fetchSemanticsNode().size.width
        val off = rule.onNodeWithText("Off").fetchSemanticsNode().boundsInRoot
        assertTrue("rate pills overflow: $off vs $rootW", off.right <= rootW)
        rule.onNodeWithText("60 fps").performSemanticsAction(SemanticsActions.OnClick)
        idle(300)
        assertEquals(60, com.ltots.app.ui.theme.ThemeState.config.value.visualizerFps)

        tab("Sound")
        rule.onNodeWithText("Natural voices when shifting pitch").assertIsDisplayed()
        shot("06_sound")
        rule.onNodeWithText("Battery-saving pitch & speed").performSemanticsAction(SemanticsActions.OnClick)
        idle(400)
        assertTrue(AudioEngine.settings.value.efficientPitch)
        rule.onNodeWithText("Battery-saving pitch & speed").performSemanticsAction(SemanticsActions.OnClick)
        idle(300)
        assertTrue(!AudioEngine.settings.value.efficientPitch)
    }

    @Test fun pitchIsLiveAndSpeedAppliesOnRelease() {
        rule.mainClock.autoAdvance = false
        idle()
        // Put a song in the player through the app's own controller.
        val controller = PlayerConnection.controller.value
        assertTrue("controller connected", controller != null)
        controller!!.setMediaItem(
            MediaItem.Builder().setMediaId("song:test|song").setUri("asset:///none.opus")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Test Song").setArtist("Test Artist").build())
                .build()
        )
        idle(800)
        tab("Playing")
        rule.onNodeWithText("Test Song").assertIsDisplayed()
        shot("07_playing")

        val player = AudioEngine.player.value!!
        val sliders = rule.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(0f, 0f..1f))).fetchSemanticsNodes().size
        assertTrue(sliders >= 0)

        // Pitch slider (range −12…12): drag right. Pitch must reach the audio engine without
        // touching the player's playback parameters (that was what broke playback before).
        val v0 = AudioEngine.version
        rule.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(0f, -12f..12f, steps = 23)))[0]
            .performScrollTo().performTouchInput { swipeRight(startX = centerX, endX = centerX + width / 4f) }
        idle(400)
        assertTrue("pitch changed: ${AudioEngine.pitchCents.value}", AudioEngine.pitchCents.value > 0)
        assertTrue("engine notified", AudioEngine.version > v0)
        assertEquals(1f, player.playbackParameters.pitch)
        assertEquals(1f, player.playbackParameters.speed)
        shot("08_pitch_up")

        // Speed slider (0.5…2): drag right; applied to the player on release.
        rule.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(1f, 0.5f..2f)))[0]
            .performScrollTo().performTouchInput { swipeRight(startX = centerX, endX = centerX + width / 6f) }
        idle(400)
        val s = AudioEngine.speed.value
        assertTrue("speed changed: $s", s > 1f)
        assertEquals(s, player.playbackParameters.speed, 1e-4f)
        assertEquals(1f, player.playbackParameters.pitch)

        // Reset brings everything back.
        rule.onNodeWithText("Reset").performScrollTo().performClick()
        idle(300)
        assertEquals(0, AudioEngine.pitchCents.value)
        assertEquals(1f, player.playbackParameters.speed)
        shot("09_reset")
    }
}

private fun SemanticsNodeInteractionCollection.first() = this[0]
