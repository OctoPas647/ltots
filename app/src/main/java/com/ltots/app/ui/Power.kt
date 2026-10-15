package com.ltots.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope

/** Runs [block] while this part of the screen exists (cancelled when it leaves). */
@Composable
fun WhileOnScreen(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    LaunchedEffect(*keys) { block() }
}

/**
 * Returns immediately while the app is visible; while it's in the background or the screen is
 * off, suspends until it's visible again. Timers call this each turn so they never keep the
 * phone awake behind the user's back. (Animation loops don't need it: Compose stops handing out
 * frames when the app isn't visible.)
 */
suspend fun awaitOnScreen(lifecycle: Lifecycle) {
    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
}

/** The phone's Battery Saver switch, kept up to date. */
@Composable
fun rememberBatterySaver(): State<Boolean> {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    val state = remember { mutableStateOf(pm?.isPowerSaveMode == true) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { state.value = pm?.isPowerSaveMode == true }
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}
