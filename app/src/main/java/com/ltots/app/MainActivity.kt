package com.ltots.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    private var hideStatusForPage = false
    private var showStatusForPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PlayerConnection.connect(this)
        // Rooms: a phone left waiting for rooms carries on doing so. Off the main thread: it opens
        // sockets, reads keys and starts network discovery, which must never hold up the screen.
        val appCtx = applicationContext
        Thread({
            runCatching {
                com.ltots.app.rooms.Rooms.init(appCtx)
                if (com.ltots.app.rooms.Rooms.wantsAvailable()) com.ltots.app.rooms.Rooms.setAvailable(appCtx, true)
            }.onFailure { CrashReport.handled("Rooms.init", it) }
        }, "LToTS-rooms-start").apply { isDaemon = true }.start()
        handleIntent(intent)
        applySystemBars()
        com.ltots.app.ui.Smoothness.init()
        setContent {
            val cfg by ThemeState.config.collectAsState()
            // A page can hide the status bar while it's open (the Library, by default).
            val pageHidesStatus by com.ltots.app.ui.PageBars.hideStatus.collectAsState()
            val pageShowsStatus by com.ltots.app.ui.PageBars.showStatus.collectAsState()
            LaunchedEffect(cfg.hideNavBar, cfg.hideStatusBar, cfg.darkMode, pageHidesStatus, pageShowsStatus) {
                bars = cfg
                hideStatusForPage = pageHidesStatus
                showStatusForPage = pageShowsStatus
                applySystemBars()
            }
            LaunchedEffect(cfg.maxRefresh) { applyRefreshRate(cfg.maxRefresh) }
            // Screen on but untouched for a while: the app rests (still background, slower visualizer).
            com.ltots.app.ui.WhileOnScreen {
                com.ltots.app.ui.ScreenRest.wake()
                com.ltots.app.ui.ScreenRest.run { runCatching { com.ltots.app.audio.AudioEngine.settings.value.restAfterSeconds }.getOrDefault(0) }
            }
            val app by com.ltots.app.audio.AudioEngine.settings.collectAsState()
            androidx.compose.runtime.SideEffect { com.ltots.app.ui.Springs.firmness = app.motion.firmness }
            LToTSTheme { AppRoot() }
        }
        // LToTS crashed (or froze, or crashed in native code) since you last looked: the report
        // opens first. It's also under Profile › Crash reports.
        if (savedInstanceState == null) {
            runCatching {
                com.ltots.app.crash.CrashLog.takeUnseen(this)?.let { f ->
                    startActivity(com.ltots.app.crash.CrashNotify.openIntent(this, f, clearTask = false))
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        com.ltots.app.ui.ScreenRest.touched()
        return super.dispatchTouchEvent(ev)
    }

    // Smoothness > Automatic: frame timings are only watched while the app is in front.
    private var stopWatching: (() -> Unit)? = null

    // The home-screen icon swaps colour only while LToTS is in the background.
    override fun onStart() {
        super.onStart()
        setDrawingHeld(false)
        CrashReport.inFront = true
        com.ltots.app.ui.IconColor.onForeground(true)
        // Screen-off sound modes give way to full quality while LToTS is open.
        runCatching { com.ltots.app.audio.AudioEngine.setAppInFront(true) }
    }

    override fun onStop() {
        super.onStop()
        CrashReport.inFront = false
        if (!isChangingConfigurations) {
            setDrawingHeld(true)
            com.ltots.app.ui.IconColor.onForeground(false)
            runCatching { com.ltots.app.audio.AudioEngine.setAppInFront(false) }
        }
    }

    /**
     * While LToTS is hidden, its screen asks for no frames. Anything that changes on it (the
     * seek bar, the visualizer, a cover loading) used to ask Android to redraw the hidden
     * window; that puts a "sync barrier" in the main thread's queue until the next frame, and
     * HyperOS gives a hidden app no frames — so every ordinary message waited behind it, the
     * player's own included (songs stopped moving on, the media notification stopped updating,
     * and LToTS looked frozen to its own freeze watch for minutes). An invisible view never asks
     * to be redrawn; the screen is drawn fresh the moment LToTS is visible again.
     */
    private fun setDrawingHeld(held: Boolean) {
        runCatching {
            val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
            val compose = content.getChildAt(0) as? android.view.ViewGroup ?: return
            val target = compose.getChildAt(0) ?: compose
            val v = if (held) android.view.View.INVISIBLE else android.view.View.VISIBLE
            if (target.visibility != v) target.visibility = v
        }
    }

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
        // "Continue in LToTS" (notification): take over the song another app is playing.
        if (intent.getBooleanExtra(com.ltots.app.elsewhere.OtherApps.EXTRA_CONTINUE, false)) {
            intent.removeExtra(com.ltots.app.elsewhere.OtherApps.EXTRA_CONTINUE)
            val app = applicationContext
            lifecycleScope.launch {
                if (com.ltots.app.elsewhere.OtherApps.continueHere(app) == com.ltots.app.elsewhere.OtherApps.Outcome.PLAYING) {
                    Inbox.openTab.value = "PLAYER"
                }
            }
        }
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
        // Always lay out behind the camera cutout (some skins, like HyperOS, letterbox the app
        // with a black strip there once the status bar is hidden). The pages keep clear of the
        // camera themselves; only the background reaches under it.
        val attrs = window.attributes
        if (attrs.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.attributes = attrs
        }
        val c = WindowCompat.getInsetsController(window, window.decorView)
        // Swiping in from the edge shows the bars briefly, then they hide again on their own.
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (bars.hideNavBar) c.hide(WindowInsetsCompat.Type.navigationBars())
        else c.show(WindowInsetsCompat.Type.navigationBars())
        if ((bars.hideStatusBar && !showStatusForPage) || hideStatusForPage) c.hide(WindowInsetsCompat.Type.statusBars())
        else c.show(WindowInsetsCompat.Type.statusBars())
        // Dark icons on light themes, light icons on dark ones.
        c.isAppearanceLightStatusBars = !bars.darkMode
        c.isAppearanceLightNavigationBars = !bars.darkMode
    }
}
