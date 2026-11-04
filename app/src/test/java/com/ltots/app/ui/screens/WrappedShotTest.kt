package com.ltots.app.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ltots.app.data.ListenStats
import com.ltots.app.data.Store
import com.ltots.app.ui.theme.LToTSTheme
import com.ltots.app.ui.theme.ThemeState
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/** Pictures of Wrapped's slides and the stats page, for review. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class WrappedShotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() {
        Store.init(ApplicationProvider.getApplicationContext())
        Store.userFile("stats").deleteRecursively()
        ListenStats.reload()
        ThemeState.update { ThemeState.amoled }
        val songs = listOf(
            Triple("Blinding Lights", "The Weeknd", 40), Triple("Espresso", "Sabrina Carpenter", 31), Triple("505", "Arctic Monkeys", 25),
            Triple("Save Your Tears", "The Weeknd", 19), Triple("Levitating", "Dua Lipa", 14), Triple("Do I Wanna Know?", "Arctic Monkeys", 9),
        )
        songs.forEachIndexed { i, (t, a, n) ->
            repeat(n) { k ->
                val at = Calendar.getInstance().apply { clear(); set(2026, (k + i) % 10, 1 + k % 27, 18 + k % 5, 0) }.timeInMillis
                ListenStats.addPlay(t, a, "", 200_000, at)
            }
        }
    }

    @Test fun wrappedSlides() {
        rule.mainClock.autoAdvance = false
        rule.setContent { LToTSTheme { WrappedDialog(2026) {} } }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        captureScreenRoboImage("build/ui-shots/50_wrapped_intro.png")
        // Through the slides (tapping the right side moves on).
        repeat(2) { rule.mainClock.advanceTimeBy(6_200); rule.waitForIdle() }
        captureScreenRoboImage("build/ui-shots/51_wrapped_top_song.png")
        repeat(5) { rule.mainClock.advanceTimeBy(6_200); rule.waitForIdle() }
        captureScreenRoboImage("build/ui-shots/52_wrapped_summary.png")
    }

    @Test fun statsPage() {
        rule.setContent { LToTSTheme { StatsDialog({}, {}) } }
        rule.waitForIdle()
        captureScreenRoboImage("build/ui-shots/53_stats_page.png")
    }

    @Test fun homeCard() {
        rule.setContent { LToTSTheme { ListeningCard({}, {}) } }
        rule.waitForIdle()
        rule.onAllNodesWithText("All stats & import")[0].performClick()
    }
}
