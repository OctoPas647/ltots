package com.ltots.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ltots.app.data.ThemeConfig
import com.ltots.app.playback.PlayerConnection
import com.ltots.app.ui.AppRoot
import com.ltots.app.ui.Inbox
import com.ltots.app.ui.theme.LToTSTheme
import com.ltots.app.ui.theme.ThemeState

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "ltots.tab"
    }

    private var bars: ThemeConfig = ThemeState.config.value

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PlayerConnection.connect(this)
        handleIntent(intent)
        applySystemBars()
        com.ltots.app.ui.Smoothness.init()
        setContent {
            val cfg by ThemeState.config.collectAsState()
            LaunchedEffect(cfg.hideNavBar, cfg.hideStatusBar, cfg.darkMode) {
                bars = cfg
                applySystemBars()
            }
            LaunchedEffect(cfg.maxRefresh) { applyRefreshRate(cfg.maxRefresh) }
            LToTSTheme { AppRoot() }
        }
    }

    // Smoothness > Automatic: frame timings are only watched while the app is in front.
    private var stopWatching: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        stopWatching?.invoke()
        stopWatching = com.ltots.app.ui.Smoothness.watch(this)
    }

    override fun onPause() {
        stopWatching?.invoke()
        stopWatching = null
        super.onPause()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        intent ?: return
        if (intent.action == android.content.Intent.ACTION_SEND) {
            val stream = androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
            val type = intent.type.orEmpty()
            if (stream != null && (type.startsWith("video/") || type.startsWith("audio/"))) Inbox.sharedFile.value = stream
            else intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { Inbox.sharedText.value = it }
        }
        intent.getStringExtra(EXTRA_TAB)?.let { Inbox.openTab.value = it }
    }

    // Android shows the bars again after dialogs, the notification shade or switching apps;
    // re-hide them whenever LToTS gets focus back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    /**
     * Highest refresh rate: ask for the screen's fastest mode (120 Hz on the Xiaomi 17 Ultra) at
     * the current resolution. Without this many phones (HyperOS included) keep apps at 60 Hz.
     * Off hands the choice back to the phone. (Same approach as Metrolist's "high refresh rate".)
     */
    private fun applyRefreshRate(max: Boolean) {
        val d = runCatching { display }.getOrNull() ?: return
        val attrs = window.attributes
        val want = if (!max) 0 else {
            val cur = d.mode
            d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }?.modeId ?: 0
        }
        if (attrs.preferredDisplayModeId != want) {
            attrs.preferredDisplayModeId = want
            window.attributes = attrs
        }
    }

    private fun applySystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        // Swiping in from the edge shows the bars briefly, then they hide again on their own.
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (bars.hideNavBar) c.hide(WindowInsetsCompat.Type.navigationBars())
        else c.show(WindowInsetsCompat.Type.navigationBars())
        if (bars.hideStatusBar) c.hide(WindowInsetsCompat.Type.statusBars())
        else c.show(WindowInsetsCompat.Type.statusBars())
        // Dark icons on light themes, light icons on dark ones.
        c.isAppearanceLightStatusBars = !bars.darkMode
        c.isAppearanceLightNavigationBars = !bars.darkMode
    }
}
