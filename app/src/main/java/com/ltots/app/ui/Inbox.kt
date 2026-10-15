package com.ltots.app.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Things handed to the UI from outside: shared links and "open this tab" requests. */
object Inbox {
    /** Text shared to LToTS (e.g. a YouTube Music link), waiting for the Downloads tab. */
    val sharedText = MutableStateFlow<String?>(null)
    val openTab = MutableStateFlow<String?>(null)
}
