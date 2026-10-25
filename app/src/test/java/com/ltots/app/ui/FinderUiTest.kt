package com.ltots.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ltots.app.identify.FinderState
import com.ltots.app.identify.IdentifyProgress
import com.ltots.app.identify.Step
import com.ltots.app.identify.StepState
import com.ltots.app.identify.Suggestion
import com.ltots.app.ui.screens.FinderContent
import com.ltots.app.ui.theme.LToTSTheme
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ytm.YtRef
import com.ltots.app.ytm.YtSong
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The song finder's views on the Get tab, with screenshots for review. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class FinderUiTest {
    @get:Rule val rule = createComposeRule()

    private val progress = IdentifyProgress(
        steps = mapOf(
            Step.SOUND to StepState.DONE, Step.TAG to StepState.SKIPPED, Step.SEPARATE to StepState.DONE,
            Step.MATCH to StepState.DONE, Step.SPEED to StepState.DONE, Step.LYRICS to StepState.SKIPPED,
            Step.YOUTUBE to StepState.RUNNING,
        ),
        details = mapOf(
            Step.SOUND to "34 s of sound", Step.TAG to "No song tag on this video",
            Step.SEPARATE to "Voices over the music — separated them", Step.MATCH to "No match at normal speed",
            Step.SPEED to "Found it at 1.26× — the video speeds it up", Step.YOUTUBE to "Looking up 2 song(s)…",
        ),
        fraction = null,
    )

    private fun show(state: FinderState) = rule.setContent {
        LToTSTheme {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.glows.first())) {
                FinderContent(state, PlayerUi(), {}, {}, {}, {})
            }
        }
    }

    @Test fun workingShowsEveryStep() {
        show(FinderState.Working("https://vm.tiktok.com/ZMabc/", progress))
        rule.onNodeWithText("Finding the song…").assertIsDisplayed()
        rule.onNodeWithText(Step.SEPARATE.label).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/ui-shots/30_finder_working.png")
    }

    @Test fun resultsAreRankedWithActions() {
        val a = YtSong("fast1", "Espresso (Sped Up)", listOf(YtRef("Sabrina Carpenter", "UC1")), YtRef("Espresso (Sped Up)", "MPREb1"))
        val b = YtSong("orig1", "Espresso", listOf(YtRef("Sabrina Carpenter", "UC1")), YtRef("Espresso", "MPREb2"))
        val c = YtSong("other", "Please Please Please", listOf(YtRef("Sabrina Carpenter", "UC1")))
        val rows = listOf(
            Suggestion(a.title, "Sabrina Carpenter", a.album?.name, null, a, 0.86, listOf("Sound match at 1.26× speed", "Sound match after AI removed the voices"), "Sped up", "https://x/p.m4a", "k1"),
            Suggestion(b.title, "Sabrina Carpenter", b.album?.name, null, b, 0.66, listOf("Another version of this song"), "Original", null, "k1"),
            Suggestion(c.title, "Sabrina Carpenter", null, null, c, 0.31, listOf("Lyrics match"), null, null, "k2"),
        )
        show(FinderState.Results("https://vm.tiktok.com/ZMabc/", rows, progress))
        rule.onNodeWithText("Best matches").assertIsDisplayed()
        rule.onNodeWithText("86% likely").assertIsDisplayed()
        rule.onNodeWithText("Download & play").assertIsDisplayed()
        rule.onNodeWithText("Preview").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/ui-shots/31_finder_results.png")
        rule.onNodeWithText("How it was found").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/32_finder_results_steps.png")
    }

    @Test fun listening() {
        rule.mainClock.autoAdvance = false
        show(FinderState.Listening(0.4f, 8))
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithText("Listening…").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/ui-shots/33_finder_listening.png")
    }
}
