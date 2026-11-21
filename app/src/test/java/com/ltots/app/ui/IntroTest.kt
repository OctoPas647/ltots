package com.ltots.app.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ltots.app.data.Store
import com.ltots.app.ui.theme.LToTSTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The feature tour for new people, tips once per tab, and nothing of the tour for people who've used LToTS a lot. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class IntroTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Store.init(ctx)
        ctx.getSharedPreferences("intro", Context.MODE_PRIVATE).edit().clear().commit()
        Intro.showInTests = true
    }

    @After fun tearDown() { Intro.showInTests = false }

    @Test fun newPersonGetsTheTourAndCanSkipIt() {
        assertEquals(Intro.Mode.TOUR, Intro.mode(ctx))
        rule.setContent { LToTSTheme { IntroHost(Tab.HOME, overlayOpen = false) {} } }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Welcome to LToTS").assertExists() }.isSuccess }
        rule.onNodeWithText("Welcome to LToTS").captureRoboImage("build/ui-shots/40_intro_tour.png")
        rule.onNodeWithText("Next").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Sound you shape").assertExists() }.isSuccess }
        rule.onNodeWithText("Skip").performClick()
        rule.waitForIdle()
        assertEquals(Intro.Mode.NONE, Intro.mode(ctx))
        // After the tour, Home's first tip.
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Got it").assertExists() }.isSuccess }
    }

    @Test fun usedOnThreeDaysSkipsTheTour() {
        ctx.getSharedPreferences("intro", Context.MODE_PRIVATE).edit()
            .putStringSet("days", setOf("20261101", "20261102", "20261103")).commit()
        assertTrue(Intro.experienced(ctx))
        assertEquals(Intro.Mode.NEW_FEATURES_ONLY, Intro.mode(ctx))
    }

    @Test fun tipsShowOnce() {
        assertFalse(Intro.tipSeen(ctx, "home"))
        Intro.markTip(ctx, "home")
        assertTrue(Intro.tipSeen(ctx, "home"))
        Intro.markAllTips(ctx)
        assertTrue(Intro.TIPS.all { Intro.tipSeen(ctx, it.id) })
    }
}
