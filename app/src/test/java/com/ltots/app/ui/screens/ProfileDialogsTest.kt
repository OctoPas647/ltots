package com.ltots.app.ui.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.ltots.app.ui.components.DeleteDialog
import com.ltots.app.ui.components.RenameDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renaming and deleting a profile (look, EQ or user) from its menu or the Profile tab. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ProfileDialogsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun renameTakesTheNewName() {
        var got: String? = null
        rule.setContent { RenameDialog("Rename EQ profile", "Custom 1", onDismiss = {}, onDone = { got = it }) }
        rule.onNode(hasSetTextAction()).performTextReplacement("  Rock  ")
        @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
        captureScreenRoboImage("build/ui-shots/32_rename_profile.png")
        rule.onNodeWithText("Rename").performClick()
        assertEquals("Rock", got)
    }

    @Test fun aBlankNameIsNotAllowed() {
        var got: String? = null
        rule.setContent { RenameDialog("Rename user", "User 1", onDismiss = {}, onDone = { got = it }) }
        rule.onNode(hasSetTextAction()).performTextReplacement("   ")
        rule.onNodeWithText("Rename").assertIsNotEnabled()
        assertNull(got)
    }

    @Test fun deleteAsksFirst() {
        var deleted = false
        var dismissed = false
        rule.setContent { DeleteDialog("Delete \"Night\"?", "Can't be undone.", onDismiss = { dismissed = true }, onConfirm = { deleted = true }) }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(false, deleted)
        assertEquals(true, dismissed)
        rule.onNodeWithText("Delete").performClick()
        assertEquals(true, deleted)
    }
}
