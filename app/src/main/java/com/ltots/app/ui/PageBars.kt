package com.ltots.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * System bars a single page wants changed while it's on screen (on top of the app-wide setting):
 * the Library hides the clock and status icons so its top menu can sit at the very top.
 */
object PageBars {
    private val _hideStatus = MutableStateFlow(false)
    /** True while the page on screen wants the status bar hidden. */
    val hideStatus: StateFlow<Boolean> = _hideStatus

    /** Hide the status bar while the calling page is shown (and [hide] stays true). */
    @Composable
    fun HideStatusBar(hide: Boolean) {
        DisposableEffect(hide) {
            _hideStatus.value = hide
            onDispose { if (hide) _hideStatus.value = false }
        }
    }
}
