package com.ltots.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether LToTS's UI can currently be seen.
 *
 * Audio playback must continue in the background, but visualizers, position polling, dynamic
 * colors and other UI-only work should stop when the screen is off or the app is hidden.
 */
object AppVisibility {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground

    fun set(value: Boolean) {
        _foreground.value = value
    }
}
