package com.ltots.app.ui.theme

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

    fun init() {
        _config.value = Store.loadTheme()
    }

    fun update(transform: (ThemeConfig) -> ThemeConfig) {
        val t = transform(_config.value)
        _config.value = t
        Store.saveTheme(t)
    }

    fun reset() = update { ThemeConfig() }

    val presets: List<Pair<String, ThemeConfig>> = listOf(
        "Aurora" to ThemeConfig(),
        "Sunset" to ThemeConfig(accent = 0xFFFF8A5C, gradientA = 0xFF4A1230, gradientB = 0xFF6B2A10, gradientC = 0xFF2B0F4D),
        "Ocean" to ThemeConfig(accent = 0xFF3FD5FF, gradientA = 0xFF04263F, gradientB = 0xFF06435A, gradientC = 0xFF0C1C4A),
        "Forest" to ThemeConfig(accent = 0xFF6CFFB0, gradientA = 0xFF07301F, gradientB = 0xFF12403A, gradientC = 0xFF1D2A10),
        "Neon" to ThemeConfig(accent = 0xFFFF3DCB, gradientA = 0xFF1A0640, gradientB = 0xFF00334D, gradientC = 0xFF3D0033, refraction = 0.85f, chromaticAberration = true),
        "Mono" to ThemeConfig(accent = 0xFFE6E6E6, gradientA = 0xFF1A1A1A, gradientB = 0xFF2A2A2A, gradientC = 0xFF101010, glassTint = 0.1f),
        "Daylight" to ThemeConfig(accent = 0xFF5B3DF5, gradientA = 0xFFE4DCFF, gradientB = 0xFFCFEFFF, gradientC = 0xFFFFDDEE, darkMode = false, glassTint = 0.35f),
    )
}

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
    val cfg by ThemeState.config.collectAsState()
    val accent = cfg.accent.toColor()
    val dark = cfg.darkMode
    val text = if (dark) Color(0xFFF5F3FF) else Color(0xFF15121F)
    val palette = Palette(
        accent = accent,
        onAccent = if (accent.luminance() > 0.55f) Color(0xFF111111) else Color.White,
        text = text,
        textDim = text.copy(alpha = 0.72f),
        textFaint = text.copy(alpha = 0.45f),
        glassTint = if (dark) lerp(Color(0xFF15102A), accent, 0.12f) else Color.White,
        line = text.copy(alpha = 0.12f),
        dark = dark,
    )
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = palette.onAccent, secondary = accent,
            background = Color.Transparent, surface = Color(0xFF1A1630), onSurface = text, onBackground = text,
            surfaceContainerHigh = Color(0xFF241F3D), outline = palette.line,
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

