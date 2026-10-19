package com.ltots.app.ui.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Typing an exact pitch: semitones + cents, a reference A in Hz, and the ± flip. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ExactPitchDialogTest {
    @get:Rule val rule = createComposeRule()
    private var set: Int? = null

    private fun show(cents: Int) = rule.setContent { ExactPitchDialog(cents, onDismiss = {}, onSet = { set = it }) }

    /** The dialog's three boxes: semitones, cents, A in Hz. */
    private fun field(i: Int) = rule.onAllNodes(hasSetTextAction())[i]

    @Test fun startsFromTheCurrentPitch() {
        show(119)
        rule.onNodeWithText("= +1 st 19 ct").assertExists()
        rule.onNodeWithText("Set").performClick()
        assertEquals(119, set)
    }

    @Test fun tuneToA432() {
        show(0)
        field(2).performTextInput("432")
        rule.onNodeWithText("= −32 ct (A = 432 Hz)").assertExists()
        @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
        captureScreenRoboImage("build/ui-shots/19_exact_pitch.png")
        rule.onNodeWithText("Set").performClick()
        assertEquals(-32, set)
    }

    @Test fun semitonesAndCentsAndFlip() {
        show(0)
        field(0).performTextInput("2")
        field(1).performTextInput("7")
        rule.onNodeWithText("= +2 st 7 ct").assertExists()
        rule.onNodeWithText("±").performClick()
        rule.onNodeWithText("= −2 st 7 ct").assertExists()
        rule.onNodeWithText("Set").performClick()
        assertEquals(-207, set)
    }

    @Test fun refusesTooFar() {
        show(0)
        field(0).performTextInput("13")
        rule.onNodeWithText("Set").assertIsNotEnabled()
    }
}
