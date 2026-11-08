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

    private val _showStatus = MutableStateFlow(false)
    /** True while the page on screen wants the status bar shown, even with Hide status bar on. */
    val showStatus: StateFlow<Boolean> = _showStatus

    /** While the calling page is shown: hide the status bar ([hide]), or insist on it ([show]). */
    @Composable
    fun StatusBar(hide: Boolean, show: Boolean) {
        DisposableEffect(hide, show) {
            _hideStatus.value = hide
            _showStatus.value = show
            onDispose {
                if (hide) _hideStatus.value = false
                if (show) _showStatus.value = false
            }
        }
    }
}
