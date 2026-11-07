package com.ltots.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction

/**
 * Opens a place in the app the way a person would. The tabs in the bar are Home, Library,
 * Playing, Get and Profile; the EQ opens from the Playing tab (its Equalizer card, or the EQ
 * button on the history it shows while nothing plays), and Look from the switch at the top of
 * the Profile tab. [idle] lets the springs settle.
 */
internal fun SemanticsNodeInteractionsProvider.openPlace(label: String, idle: (Long) -> Unit) {
    fun tapLast(text: String) {
        // The bar comes after the page, so the last clickable "Profile" is the bar's.
        val all = onAllNodes(hasText(text) and hasClickAction())
        val n = all.fetchSemanticsNodes().size
        check(n > 0) { "nothing to tap called $text" }
        all[n - 1].performSemanticsAction(SemanticsActions.OnClick)
        idle(1200)
    }
    fun tapFirst(text: String) {
        onAllNodes(hasText(text) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        idle(1200)
    }
    when (label) {
        "EQ" -> {
            tapLast("Playing")
            val card = onAllNodes(hasText("Equalizer") and hasClickAction()).fetchSemanticsNodes()
            tapFirst(if (card.isNotEmpty()) "Equalizer" else "EQ")
        }
        // The switch at the top of the Profile tab (it comes before the bar).
        "Look" -> { tapLast("Profile"); tapFirst("Look") }
        "Profile" -> { tapLast("Profile"); tapFirst("Profile") }
        else -> tapLast(label)
    }
}
