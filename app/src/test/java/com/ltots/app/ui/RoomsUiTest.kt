package com.ltots.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ltots.app.MainActivity
import com.ltots.app.playback.PlaybackService
import com.ltots.app.playback.PlayerConnection
import com.ltots.app.rooms.Rooms
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rooms opens from the Profile tab and explains itself before anything starts. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class RoomsUiTest {
    private val setup = object : ExternalResource() {
        var svc: org.robolectric.android.controller.ServiceController<PlaybackService>? = null
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val ctl = Robolectric.buildService(PlaybackService::class.java).create()
            svc = ctl
            val binder = ctl.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
        }

        override fun after() {
            PlayerConnection.disconnect()
            svc?.destroy()
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(setup).around(rule)

    private fun idle(ms: Long = 600) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    @Test fun roomsPage() {
        rule.mainClock.autoAdvance = false
        idle()
        rule.openPlace("Rooms") { idle(it) }
        rule.onNodeWithText("Your phones playing as one.").assertIsDisplayed()
        rule.onNodeWithText("Start a room here").assertIsDisplayed()
        rule.onNodeWithText("Join rooms on this Wi-Fi").assertExists()
        // Nothing starts until asked.
        assertEquals(Rooms.Role.OFF, Rooms.ui.value.role)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/40_rooms.png")
    }
}
