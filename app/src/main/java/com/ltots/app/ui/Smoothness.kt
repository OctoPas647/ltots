package com.ltots.app.ui

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.ltots.app.data.Effects
import com.ltots.app.data.EffectsMode
import com.ltots.app.data.Store
import com.ltots.app.ui.theme.LocalThemeConfig
import com.ltots.app.ui.theme.ThemeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The effects level in use right now (see [Effects]); provided by the app root. */
val LocalEffects = staticCompositionLocalOf { Effects.FULL }

/**
 * Smoothness, Automatic: watches how long each frame really takes on this phone. When frames
 * keep missing their deadline (the app visibly stutters), the effects step down one level and
 * stay there; the result is remembered for this phone. The same glass costs some phones much
 * more than others (screen resolution, graphics drivers), so this adapts to whichever it runs on.
 */
object Smoothness {
    private val _auto = MutableStateFlow(Effects.FULL)
    /** What Automatic settled on for this phone. */
    val auto: StateFlow<Effects> = _auto

    private var loaded = false

    fun init() {
        if (loaded) return
        loaded = true
        Store.loadSmoothness()?.let { a -> _auto.value = runCatching { Effects.valueOf(a) }.getOrDefault(Effects.FULL) }
    }

    fun effective(mode: EffectsMode, auto: Effects = _auto.value): Effects = when (mode) {
        EffectsMode.AUTO -> auto
        EffectsMode.FULL -> Effects.FULL
        EffectsMode.BALANCED -> Effects.BALANCED
        EffectsMode.LIGHT -> Effects.LIGHT
    }

    /** Automatic starts again from Full and measures afresh. */
    fun recheck() {
        _auto.value = Effects.FULL
        meter.reset()
        Store.saveSmoothness(Effects.FULL.name)
    }

    /** Frames keep running late: one level lighter (only while Smoothness is Automatic). */
    internal fun tooSlow() {
        if (ThemeState.config.value.effects != EffectsMode.AUTO) return
        val next = if (_auto.value == Effects.FULL) Effects.BALANCED else Effects.LIGHT
        if (next != _auto.value) {
            _auto.value = next
            Store.saveSmoothness(next.name)
        }
    }

    private val meter = JankMeter()
    private var thread: HandlerThread? = null

    /** Starts measuring the frames of [activity]'s window; returns the function that stops it. */
    fun watch(activity: Activity): () -> Unit {
        val window: Window = activity.window
        val t = thread ?: HandlerThread("ltots-frame-metrics").also { it.start(); thread = it }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val slow = meter.add(System.nanoTime(), m.getMetric(FrameMetrics.TOTAL_DURATION), m.getMetric(FrameMetrics.DEADLINE))
            if (slow) activity.runOnUiThread { tooSlow() }
        }
        meter.reset()
        runCatching { window.addOnFrameMetricsAvailableListener(listener, Handler(t.looper)) }
        return { runCatching { window.removeOnFrameMetricsAvailableListener(listener) } }
    }
}

/** The effects level to use: the Smoothness setting of the look, or what Automatic measured. */
@Composable
fun rememberEffects(): Effects {
    val auto by Smoothness.auto.collectAsState()
    return Smoothness.effective(LocalThemeConfig.current.effects, auto)
}

/**
 * Decides "this is visibly stuttering" from frame timings. Only sustained trouble counts: two
 * windows of 120 frames in a row in which at least 30% of the frames took more than 1.5× their
 * deadline. A single slow tab switch, or the app starting up, doesn't. After deciding, it waits a
 * few seconds so the lighter level can take effect before judging again.
 */
class JankMeter(
    private val window: Int = 120,
    private val ratio: Float = 0.30f,
    private val windowsNeeded: Int = 2,
    private val settleNs: Long = 3_000_000_000L,
) {
    private var frames = 0
    private var late = 0
    private var badWindows = 0
    private var quietUntil = 0L

    @Synchronized
    fun reset() { frames = 0; late = 0; badWindows = 0; quietUntil = 0L }

    /** Adds one frame; returns true when the app should get lighter. */
    @Synchronized
    fun add(nowNs: Long, totalNs: Long, deadlineNs: Long): Boolean {
        if (quietUntil == 0L) quietUntil = nowNs + settleNs // ignore the first moments (startup)
        if (nowNs < quietUntil || deadlineNs <= 0) return false
        frames++
        if (totalNs > deadlineNs * 3 / 2) late++
        if (frames < window) return false
        val bad = late >= window * ratio
        frames = 0; late = 0
        badWindows = if (bad) badWindows + 1 else 0
        if (badWindows >= windowsNeeded) {
            badWindows = 0
            quietUntil = nowNs + settleNs
            return true
        }
        return false
    }
}
