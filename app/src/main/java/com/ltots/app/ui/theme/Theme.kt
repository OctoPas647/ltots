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
import com.ltots.app.data.Hsv
import com.ltots.app.data.Store
import com.ltots.app.data.ThemeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ThemeState {
    private val _config = MutableStateFlow(ThemeConfig())
    val config: StateFlow<ThemeConfig> = _config

    /** The default accent: hue 180°, saturation and brightness 100% (cyan). */
    val DEFAULT_ACCENT = Hsv(180f, 1f, 1f)

    /** The AMOLED theme: pure black with a cyan accent. Default for new installs. */
    val amoled = ThemeConfig(
        accent = DEFAULT_ACCENT.toArgb(), accentHsv = DEFAULT_ACCENT, glow = 0xFF003A42,
        pureBlack = true, animateBackground = false, artworkBackground = false,
        glassTint = 0.55f, rimLight = 0.9f, refraction = 0.4f, darkMode = true,
    )

    /** The default look: what new installs, new Custom profiles and "Reset look" start from. */
    val defaultLook: ThemeConfig get() = amoled

    /**
     * Loads the look of the user in use (at start, and after switching user).
     * @param freshInstall true on the very first run. New installs start on AMOLED; existing
     *   installs that never picked a theme keep the one they've been seeing.
     */
    fun init(freshInstall: Boolean) {
        val saved = Store.loadTheme()
        _config.value = saved ?: if (freshInstall) defaultLook else ThemeConfig()
        if (saved == null) Store.saveTheme(_config.value)

        // Look profiles. The first time, the look you already have becomes Custom 1.
        val stored = Store.loadLookProfiles()
        val items = stored?.second.orEmpty().take(MAX_PROFILES).toMutableList()
        if (items.isEmpty()) items.add(customName(0) to _config.value)
        val active = (stored?.first ?: 0).coerceIn(0, items.size - 1)
        items[active] = items[active].first to _config.value
        _profiles.value = items.map { LookProfile(it.first, it.second) }
        _activeProfile.value = active
        if (stored == null || stored.second.size != items.size) save()
    }

    /** Loads the look of a user who just became the one in use (a new user starts on the default look). */
    fun reloadForUser() = init(freshInstall = true)

    // ---- Look profiles: up to 10 named looks. The selected one saves every change. ----

    const val MAX_PROFILES = 10
    private val _profiles = MutableStateFlow(listOf(LookProfile(customName(0), ThemeConfig())))
    /** The saved looks, each with its name. */
    val profiles: StateFlow<List<LookProfile>> = _profiles
    private val _activeProfile = MutableStateFlow(0)
    val activeProfile: StateFlow<Int> = _activeProfile

    private fun save() = Store.saveLookProfiles(_activeProfile.value, _profiles.value.map { it.name to it.look })

    /** "Custom N" with the lowest number no profile is called yet. */
    fun nextName(names: List<String> = _profiles.value.map { it.name }): String =
        (1..MAX_PROFILES + 1).map { customName(it - 1) }.first { n -> names.none { it.equals(n, ignoreCase = true) } }

    private fun freeName(wanted: String, except: Int = -1): String {
        val taken = _profiles.value.filterIndexed { i, _ -> i != except }.map { it.name.lowercase() }.toSet()
        val base = wanted.trim().take(30).ifBlank { nextName() }
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
    }

    /**
     * Settings that stay the same whichever look profile is in use: the visualizer's Detail (how
     * finely the sound is split) and the app-wide screen settings on the Profile tab.
     */
    fun ThemeConfig.withSharedFrom(cur: ThemeConfig) = copy(
        analyzerBands = cur.analyzerBands,
        maxRefresh = cur.maxRefresh,
        effects = cur.effects,
        hideNavBar = cur.hideNavBar,
        hideStatusBar = cur.hideStatusBar,
        albumProgress = cur.albumProgress,
    )

    /** Switch to profile [i]. Detail and the screen settings never change by switching. */
    fun selectProfile(i: Int) {
        if (i !in _profiles.value.indices || i == _activeProfile.value) return
        val look = _profiles.value[i].look.withSharedFrom(_config.value)
        _profiles.value = _profiles.value.toMutableList().also { it[i] = it[i].copy(look = look) }
        _activeProfile.value = i
        _config.value = look
        Store.saveTheme(look)
        save()
    }

    /** A new profile (a copy of the look in use), switched to at once; null when there are 10. */
    fun addProfile(): Int? {
        if (_profiles.value.size >= MAX_PROFILES) return null
        _profiles.value = _profiles.value + LookProfile(nextName(), _config.value)
        val i = _profiles.value.size - 1
        _activeProfile.value = i
        save()
        return i
    }

    /** Adds [look] (from an exported file) as a new profile; false when there are already 10. */
    fun importProfile(name: String, look: ThemeConfig): Boolean {
        if (_profiles.value.size >= MAX_PROFILES) return false
        _profiles.value = _profiles.value + LookProfile(freeName(name), look.withSharedFrom(_config.value))
        save()
        return true
    }

    fun renameProfile(i: Int, name: String) {
        if (i !in _profiles.value.indices) return
        val n = freeName(name, except = i)
        _profiles.value = _profiles.value.toMutableList().also { it[i] = it[i].copy(name = n) }
        save()
    }

    /** Deletes profile [i] (never the last one). Deleting the one in use switches to its neighbour. */
    fun deleteProfile(i: Int) {
        val list = _profiles.value
        if (i !in list.indices || list.size <= 1) return
        val active = _activeProfile.value
        val rest = list.toMutableList().also { it.removeAt(i) }
        when {
            i == active -> {
                val next = (i - 1).coerceAtLeast(0)
                val look = rest[next].look.withSharedFrom(_config.value)
                rest[next] = rest[next].copy(look = look)
                _profiles.value = rest
                _activeProfile.value = next
                _config.value = look
                Store.saveTheme(look)
            }
            i < active -> { _profiles.value = rest; _activeProfile.value = active - 1 }
            else -> _profiles.value = rest
        }
        save()
    }

    /**
     * Switch to a theme: its colours, glass and background. Everything else on the Look tab
     * (visualizer and icon mode, refresh rate, corners, cover opacity, system bars, motion speed) stays exactly
     * as you set it. Cover colours are on only for AMOLED Cover.
     */
    fun applyPreset(p: ThemePreset) = update { cur -> presetApplied(p, cur) }

    /**
     * What [cur] becomes when theme [p] is picked. Between the two AMOLED themes your own colours
     * (hue, saturation, brightness) stay as you set them.
     */
    fun presetApplied(p: ThemePreset, cur: ThemeConfig): ThemeConfig {
        val t = themeOf(p).withPersonalFrom(cur)
        return when {
            !p.config.pureBlack -> t
            cur.pureBlack -> t.withColoursFrom(cur)
            // Back to AMOLED from another theme: your own AMOLED colour, if you set one.
            cur.amoledAccent != null -> t.copy(accent = cur.amoledAccent, accentHsv = cur.amoledAccentHsv)
            else -> t
        }
    }

    /** Whether [cfg] is currently showing preset [p] (on AMOLED, whatever accent you picked). */
    fun isActive(p: ThemePreset, cfg: ThemeConfig): Boolean {
        var t = themeOf(p).withPersonalFrom(cfg)
        if (p.config.pureBlack && cfg.pureBlack) t = t.withColoursFrom(cfg)
        return t.copy(accentHsv = null, glowHsv = null) == cfg.copy(accentHsv = null, glowHsv = null)
    }

    /** The theme [cfg] is on, by name ("AMOLED Cover", "Aurora"…), for the profile list. */
    fun themeName(cfg: ThemeConfig): String = presets.firstOrNull { isActive(it, cfg) }?.name
        ?: if (cfg.pureBlack) (if (cfg.dynamicColors) "AMOLED Cover" else "AMOLED") + " (own settings)" else "Own colours"

    private fun themeOf(p: ThemePreset) = p.config.copy(dynamicColors = p.dynamic ?: false)

    private fun ThemeConfig.withColoursFrom(cur: ThemeConfig) =
        copy(accent = cur.accent, accentHsv = cur.accentHsv, glow = cur.glow, glowHsv = cur.glowHsv)

    /** [this] theme with the settings that belong to the person, not the theme, taken from [cur]. */
    private fun ThemeConfig.withPersonalFrom(cur: ThemeConfig) = copy(
        animationSpeed = cur.animationSpeed,
        // Album art glow stays as you set it (off unless you turn it on), whichever theme.
        artworkBackground = cur.artworkBackground,
        amoledAccent = cur.amoledAccent,
        amoledAccentHsv = cur.amoledAccentHsv,
        coverOpacity = cur.coverOpacity,
        cornerDp = cur.cornerDp,
        spectrumStyle = cur.spectrumStyle,
        spectrumOpacity = cur.spectrumOpacity,
        showPreEqGhost = cur.showPreEqGhost,
        visualizerFps = cur.visualizerFps,
        analyzerBands = cur.analyzerBands,
        spectrumAroundCover = cur.spectrumAroundCover,
        ringLayout = cur.ringLayout,
        behindLayout = cur.behindLayout,
        maxRefresh = cur.maxRefresh,
        effects = cur.effects,
        hideNavBar = cur.hideNavBar,
        hideStatusBar = cur.hideStatusBar,
        albumProgress = cur.albumProgress,
        iconMode = cur.iconMode,
        iconFile = cur.iconFile,
        iconThreshold = cur.iconThreshold,
        iconInvert = cur.iconInvert,
        iconMinHole = cur.iconMinHole,
        iconFill = cur.iconFill,
        iconCover = cur.iconCover,
        iconCoverDim = cur.iconCoverDim,
        iconBars = cur.iconBars,
        iconIdle = cur.iconIdle,
        freezeVisualizer = cur.freezeVisualizer,
        gradientFlow = cur.gradientFlow,
        coverBeat = cur.coverBeat,
        beatStrength = cur.beatStrength,
        beatSource = cur.beatSource,
        coverBarsOpacity = cur.coverBarsOpacity,
        libraryHeaderGlass = cur.libraryHeaderGlass,
    )

    fun update(transform: (ThemeConfig) -> ThemeConfig) {
        val t = transform(_config.value).let {
            // On the AMOLED themes, remember the colour so it comes back after another theme.
            if (it.pureBlack && it.accent != amoled.accent) it.copy(amoledAccent = it.accent, amoledAccentHsv = it.accentHsv)
            else if (it.pureBlack) it.copy(amoledAccent = null, amoledAccentHsv = null)
            else it
        }
        _config.value = t
        Store.saveTheme(t)
        // Every change is kept in the selected profile too.
        val i = _activeProfile.value
        _profiles.value = _profiles.value.toMutableList().also { it[i] = it[i].copy(look = t) }
        save()
    }

    val presets: List<ThemePreset> = listOf(
        ThemePreset("AMOLED", amoled, dynamic = false),
        // Same pure black, but buttons, sliders and everything you can touch take their colour
        // from the current song's cover (cyan while nothing is playing).
        ThemePreset("AMOLED Cover", amoled, dynamic = true),
        ThemePreset("Aurora", ThemeConfig()),
        ThemePreset("Sunset", ThemeConfig(accent = 0xFFFF8A5C, glow = 0xFF9A3220)),
        ThemePreset("Ocean", ThemeConfig(accent = 0xFF3FD5FF, glow = 0xFF0A5A85)),
        ThemePreset("Forest", ThemeConfig(accent = 0xFF6CFFB0, glow = 0xFF146046)),
        ThemePreset("Neon", ThemeConfig(accent = 0xFFFF3DCB, glow = 0xFF6A18C4, refraction = 0.85f)),
        ThemePreset("Mono", ThemeConfig(accent = 0xFFE6E6E6, glow = 0xFF3C3C3C, glassTint = 0.1f)),
        ThemePreset("Daylight", ThemeConfig(accent = 0xFF5B3DF5, glow = 0xFFD2C6FF, darkMode = false, glassTint = 0.35f)),
    )

    /**
     * Back to the default look (AMOLED, accent hue 180°, saturation and brightness 100%):
     * everything on the Look tab. The screen settings on the Profile tab stay as they are.
     */
    fun reset() {
        update { cur -> defaultLook.copy(maxRefresh = cur.maxRefresh, effects = cur.effects, hideNavBar = cur.hideNavBar, hideStatusBar = cur.hideStatusBar, albumProgress = cur.albumProgress) }
    }

    /** Every Look profile removed but one, Custom 1, back on the default look. */
    fun resetAll() {
        val cur = _config.value
        val look = defaultLook.copy(maxRefresh = cur.maxRefresh, effects = cur.effects, hideNavBar = cur.hideNavBar, hideStatusBar = cur.hideStatusBar, albumProgress = cur.albumProgress)
        _profiles.value = listOf(LookProfile(customName(0), look))
        _activeProfile.value = 0
        _config.value = look
        Store.saveTheme(look)
        save()
    }
}

/** One saved look and its name. */
data class LookProfile(val name: String, val look: ThemeConfig)

/** Names new profiles get: Custom 1, Custom 2, … */
fun customName(i: Int) = "Custom ${i + 1}"

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
    /** The three colours the moving background is painted with (black on AMOLED). */
    val glows: List<Color> = listOf(Color.Black, Color.Black, Color.Black),
)

/**
 * The background's three shades from the one glow colour: the colour itself, and a lighter and a
 * deeper neighbour on either side of its hue, so the background moves but stays one colour.
 */
fun glowShades(glow: Long): List<Color> {
    val hsv = DynamicColor.rgbToHsv(glow.toInt())
    fun shade(dh: Float, sat: Float, value: Float) =
        Color(DynamicColor.hsvToRgb((hsv[0] + dh + 360f) % 360f, (hsv[1] * sat).coerceIn(0f, 1f), (hsv[2] * value).coerceIn(0f, 1f)))
    return listOf(glow.toColor(), shade(22f, 0.95f, 0.8f), shade(-22f, 1.05f, 0.62f))
}

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
    // With dynamic colours on, the accent (and the background glow) follow the song's cover.
    // They switch at once, in step with the cover: no fade, which lagged behind the song and
    // re-drew the whole app on every frame of the fade.
    val c = cover.takeIf { saved.dynamicColors }
    val cfg = if (c == null) saved else saved.copy(accent = (if (saved.darkMode) c.accentDark else c.accentLight).toLong() and 0xFFFFFFFFL)
    val glows = when {
        saved.pureBlack -> listOf(Color.Black, Color.Black, Color.Black)
        c != null -> listOf(Color(c.glowTop), Color(c.glowMid), Color(c.glowBottom))
        else -> glowShades(saved.glow)
    }
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
        glows = glows,
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

