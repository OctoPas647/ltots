package com.ltots.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import com.ltots.app.ui.theme.DynamicColor
import com.ltots.app.ui.theme.ThemeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * The home-screen icon follows the app's colour: the same accent the UI takes from the song's
 * cover (or your own accent when the cover doesn't set it).
 *
 * Android can't repaint a launcher icon, so the app ships the icon in 20 colours (one every 18°
 * of hue, res/mipmap-anydpi-v26/ic_launcher_cNN) as activity aliases and switches which one is
 * enabled. Switching while LToTS is open can make some launchers close it, so it only happens
 * while the app is in the background: when you leave it, and on later song changes while you're
 * away (at most once a minute). Grey covers keep the original icon.
 */
object IconColor {
    const val COUNT = 20
    private const val DEFAULT = "com.ltots.app.LauncherDefault"
    private const val MIN_GAP_MS = 60_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _enabled = MutableStateFlow(true)
    /** "Icon follows the song colour" (Profile > App settings). */
    val enabled: StateFlow<Boolean> = _enabled
    private val inFront = MutableStateFlow(false)
    private var lastSwitch = 0L

    private fun prefs() = app.getSharedPreferences("icon_color", Context.MODE_PRIVATE)

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        app = context.applicationContext
        _enabled.value = prefs().getBoolean("enabled", true)
        // Song changes while LToTS is in the background.
        scope.launch {
            combine(DynamicColor.colors, ThemeState.config, inFront, _enabled) { _, _, front, _ -> front }
                .debounce(3_000)
                .collect { front -> if (!front && System.currentTimeMillis() - lastSwitch > MIN_GAP_MS) apply() }
        }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs().edit().putBoolean("enabled", on).apply()
        if (!on && !inFront.value) apply()
    }

    /** Called by the activity: true while it's on screen. Leaving the app swaps the icon. */
    fun onForeground(front: Boolean) {
        inFront.value = front
        if (!front) scope.launch { apply() }
    }

    /** The colour the UI shows right now, as the theme computes it. */
    private fun currentAccent(): Int {
        val cfg = ThemeState.config.value
        val cover = DynamicColor.colors.value?.takeIf { cfg.dynamicColors }
        return cover?.let { if (cfg.darkMode) it.accentDark else it.accentLight } ?: cfg.accent.toInt()
    }

    /** Which icon fits [argb]: 0..19, or -1 for the original (grey or near-grey colours). */
    fun variantFor(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff, hsv)
        return variantFor(hsv[0], hsv[1], hsv[2])
    }

    fun variantFor(hue: Float, saturation: Float, value: Float): Int {
        if (saturation < 0.18f || value < 0.12f) return -1
        val step = 360f / COUNT
        return (Math.round(((hue % 360f) + 360f) % 360f / step) % COUNT)
    }

    fun componentFor(variant: Int): String = if (variant < 0) DEFAULT else "com.ltots.app.LauncherC%02d".format(variant)

    @Synchronized
    private fun apply() {
        if (!::app.isInitialized || inFront.value) return
        val want = if (_enabled.value) componentFor(variantFor(currentAccent())) else DEFAULT
        val pm = app.packageManager
        val all = listOf(DEFAULT) + (0 until COUNT).map { componentFor(it) }
        val on = all.filter { runCatching { isEnabled(pm, it) }.getOrDefault(false) }
        if (on == listOf(want)) return
        runCatching {
            // The new one first, so there's never a moment with no icon at all.
            pm.setComponentEnabledSetting(ComponentName(app, want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (c in on) if (c != want) {
                pm.setComponentEnabledSetting(ComponentName(app, c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            lastSwitch = System.currentTimeMillis()
        }
    }

    private fun isEnabled(pm: PackageManager, cls: String): Boolean =
        when (pm.getComponentEnabledSetting(ComponentName(app, cls))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> cls == DEFAULT // as in the manifest
            else -> false
        }
}
