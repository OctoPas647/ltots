package com.ltots.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ltots.app.data.Store
import com.ltots.app.data.ThemeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ThemeState {
    private val _config = MutableStateFlow(ThemeConfig())
    val config: StateFlow<ThemeConfig> = _config

    /** The AMOLED theme: pure black with an electric-cyan accent. Default for new installs. */
    val amoled = ThemeConfig(
        accent = 0xFF00E5FF, gradientA = 0xFF000000, gradientB = 0xFF000000, gradientC = 0xFF003A42,
        pureBlack = true, animateBackground = false, artworkBackground = false,
        glassTint = 0.55f, rimLight = 0.9f, refraction = 0.4f, darkMode = true,
    )

    /**
     * @param freshInstall true on the very first run. New installs start on AMOLED; existing
     *   installs that never picked a theme keep the one they've been seeing.
     */
    fun init(freshInstall: Boolean) {
        val saved = Store.loadTheme()
        _config.value = saved ?: if (freshInstall) amoled else ThemeConfig()
        if (saved == null) Store.saveTheme(_config.value)
    }

    /**
     * Switch to a preset look without touching the system-bar choices. Cover colours stay as
     * they were, except for the two AMOLED themes, which are defined by them (off / on).
     */
    fun applyPreset(p: ThemePreset) = update { cur ->
        p.config.copy(hideNavBar = cur.hideNavBar, hideStatusBar = cur.hideStatusBar, dynamicColors = p.dynamic ?: cur.dynamicColors)
    }

    /** Whether [cfg] is currently showing preset [p]. */
    fun isActive(p: ThemePreset, cfg: ThemeConfig): Boolean {
        val t = p.config
        if (cfg.copy(hideNavBar = t.hideNavBar, hideStatusBar = t.hideStatusBar, dynamicColors = t.dynamicColors) != t) return false
        return p.dynamic == null || p.dynamic == cfg.dynamicColors
    }

    fun update(transform: (ThemeConfig) -> ThemeConfig) {
        val t = transform(_config.value)
        _config.value = t
        Store.saveTheme(t)
    }

    val presets: List<ThemePreset> = listOf(
        ThemePreset("AMOLED", amoled, dynamic = false),
        // Same pure black, but buttons, sliders and everything you can touch take their colour
        // from the current song's cover (cyan while nothing is playing).
        ThemePreset("AMOLED Cover", amoled, dynamic = true),
        ThemePreset("Aurora", ThemeConfig()),
        ThemePreset("Sunset", ThemeConfig(accent = 0xFFFF8A5C, gradientA = 0xFF4A1230, gradientB = 0xFF6B2A10, gradientC = 0xFF2B0F4D)),
        ThemePreset("Ocean", ThemeConfig(accent = 0xFF3FD5FF, gradientA = 0xFF04263F, gradientB = 0xFF06435A, gradientC = 0xFF0C1C4A)),
        ThemePreset("Forest", ThemeConfig(accent = 0xFF6CFFB0, gradientA = 0xFF07301F, gradientB = 0xFF12403A, gradientC = 0xFF1D2A10)),
        ThemePreset("Neon", ThemeConfig(accent = 0xFFFF3DCB, gradientA = 0xFF1A0640, gradientB = 0xFF00334D, gradientC = 0xFF3D0033, refraction = 0.85f, chromaticAberration = true)),
        ThemePreset("Mono", ThemeConfig(accent = 0xFFE6E6E6, gradientA = 0xFF1A1A1A, gradientB = 0xFF2A2A2A, gradientC = 0xFF101010, glassTint = 0.1f)),
        ThemePreset("Daylight", ThemeConfig(accent = 0xFF5B3DF5, gradientA = 0xFFE4DCFF, gradientB = 0xFFCFEFFF, gradientC = 0xFFFFDDEE, darkMode = false, glassTint = 0.35f)),
    )

    fun reset() = applyPreset(presets.first())
}

/** A ready-made look. [dynamic] = cover colours on/off for this theme; null leaves them as they are. */
data class ThemePreset(val name: String, val config: ThemeConfig, val dynamic: Boolean? = null)

/** Colors derived from the user's theme, used everywhere in the UI. */
data class Palette(
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val glassTint: Color,
    val line: Color,
    val dark: Boolean,
)

val LocalThemeConfig = staticCompositionLocalOf { ThemeConfig() }
val LocalPalette = staticCompositionLocalOf {
    Palette(Color.White, Color.Black, Color.White, Color.White, Color.White, Color.White, Color.White, true)
}

fun Long.toColor() = Color(this.toInt())
fun Color.toArgbLong(): Long = (toArgb().toLong() and 0xFFFFFFFFL)

@Composable
fun LToTSTheme(content: @Composable () -> Unit) {
    val saved by ThemeState.config.collectAsState()
    val cover by DynamicColor.colors.collectAsState()
    // With dynamic colours on, the accent (and the background glow) follow the song's cover,
    // fading smoothly from one song to the next.
    val dyn = saved.dynamicColors && cover != null
    val spec = tween<Color>(900)
    val accentAnim by animateColorAsState(
        if (dyn) Color(if (saved.darkMode) cover!!.accentDark else cover!!.accentLight) else saved.accent.toColor(), spec, label = "accent",
    )
    val gA by animateColorAsState(if (dyn) Color(cover!!.glowTop) else saved.gradientA.toColor(), spec, label = "gA")
    val gB by animateColorAsState(if (dyn) Color(cover!!.glowMid) else saved.gradientB.toColor(), spec, label = "gB")
    val gC by animateColorAsState(if (dyn) Color(cover!!.glowBottom) else saved.gradientC.toColor(), spec, label = "gC")
    val cfg = if (!saved.dynamicColors) saved else saved.copy(
        accent = accentAnim.toArgbLong(),
        gradientA = if (saved.pureBlack) saved.gradientA else gA.toArgbLong(),
        gradientB = if (saved.pureBlack) saved.gradientB else gB.toArgbLong(),
        gradientC = if (saved.pureBlack) saved.gradientC else gC.toArgbLong(),
    )
    val accent = cfg.accent.toColor()
    val dark = cfg.darkMode
    val text = if (dark) Color(0xFFF5F3FF) else Color(0xFF15121F)
    val palette = Palette(
        accent = accent,
        onAccent = if (accent.luminance() > 0.55f) Color(0xFF111111) else Color.White,
        text = text,
        textDim = text.copy(alpha = 0.72f),
        textFaint = text.copy(alpha = 0.45f),
        glassTint = when {
            cfg.pureBlack -> lerp(Color(0xFF0B0B0D), accent, 0.05f)
            dark -> lerp(Color(0xFF15102A), accent, 0.12f)
            else -> Color.White
        },
        line = text.copy(alpha = 0.12f),
        dark = dark,
    )
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = palette.onAccent, secondary = accent,
            background = Color.Transparent,
            surface = if (cfg.pureBlack) Color(0xFF0E0E10) else Color(0xFF1A1630),
            onSurface = text, onBackground = text,
            surfaceContainerHigh = if (cfg.pureBlack) Color(0xFF151518) else Color(0xFF241F3D),
            outline = palette.line,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = palette.onAccent, secondary = accent,
            background = Color.Transparent, surface = Color(0xFFF7F5FF), onSurface = text, onBackground = text,
            outline = palette.line,
        )
    }
    val typography = Typography(
        displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 14.sp),
        bodySmall = TextStyle(fontSize = 12.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
    CompositionLocalProvider(LocalThemeConfig provides cfg, LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

