package com.ltots.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import com.ltots.app.ui.components.ProfileMenu
import com.ltots.app.ui.components.ProfilePill
import com.ltots.app.ui.components.ProfileRow
import com.ltots.app.ui.components.customName
import com.ltots.app.ui.theme.DynamicColor
import com.ltots.app.ui.theme.glowShades
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ltots.app.data.SpectrumStyle
import com.ltots.app.data.Hsv
import com.ltots.app.data.ThemeConfig
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.components.LabeledSlider
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.Swatch
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import com.ltots.app.ui.theme.ThemeState
import com.ltots.app.ui.theme.toArgbLong
import com.ltots.app.ui.theme.toColor
import java.util.Locale

private val swatches = listOf(
    0xFF8B6CFF, 0xFF5B3DF5, 0xFF3FD5FF, 0xFF00C8A0, 0xFF6CFFB0, 0xFFC6FF3D,
    0xFFFFD23D, 0xFFFF8A5C, 0xFFFF4D6D, 0xFFFF3DCB, 0xFFE6E6E6, 0xFF2A2A2A,
    0xFF2A1260, 0xFF0B3D63, 0xFF5B1048, 0xFF07301F, 0xFF4A1230, 0xFF04263F,
)

@Composable
fun LookScreen() {
    // Edit the saved theme (LocalThemeConfig may be showing cover-based colours).
    val cfg by ThemeState.config.collectAsState()
    val pal = LocalPalette.current
    fun set(t: (ThemeConfig) -> ThemeConfig) = ThemeState.update(t)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Look", style = MaterialTheme.typography.displaySmall, color = pal.text)
                Text("Everything here changes live.", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
            }
            LookProfiles(cfg)
        }
        Spacer(Modifier.height(14.dp))

        // Theme presets
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(vertical = 16.dp)) {
                SectionTitle("Themes", Modifier.padding(horizontal = 16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeState.presets.forEach { p ->
                        val t = p.config
                        val on = ThemeState.isActive(p, cfg)
                        Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { ThemeState.applyPreset(p) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(
                                    if (p.dynamic == true) {
                                        // Black with a ring of cover-like colours.
                                        Brush.sweepGradient(listOf(Color(0xFFFF5C7A), Color(0xFFFFC14D), Color(0xFF6CFFB0), Color(0xFF3FD5FF), Color(0xFFB07CFF), Color(0xFFFF5C7A)))
                                    } else glowShades(t.glow).let { g -> Brush.linearGradient(listOf(g[0], t.accent.toColor(), g[2])) }
                                ).border(if (on) 2.dp else 1.dp, if (on) pal.text else pal.text.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (p.dynamic == true) Box(Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(Color.Black))
                            }
                            Text(
                                p.name, color = if (on) pal.text else pal.textDim, style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }


        Spacer(Modifier.height(12.dp))
        ColorCard(cfg, ::set)

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Glass")
                // Blur and Refraction bend what's behind the glass: the page scrolling under the bars
                // at the bottom (every theme), and the moving colours behind the panels.
                LabeledSlider("Blur", cfg.blurDp, { v -> set { it.copy(blurDp = v) } }, 0f..60f, "%.0f dp".format(Locale.ROOT, cfg.blurDp))
                LabeledSlider("Refraction", cfg.refraction, { v -> set { it.copy(refraction = v) } }, 0f..1f, pct(cfg.refraction))
                Text(
                    if (cfg.pureBlack) "Blur and Refraction show on the bars at the bottom, bending the page as it scrolls under them. The panels sit on pure black, which looks the same bent or not."
                    else "Blur and Refraction bend what's behind the glass: the moving colours under the panels, and the page scrolling under the bars at the bottom.",
                    color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
                )
                LabeledSlider("Frost / tint", cfg.glassTint, { v -> set { it.copy(glassTint = v) } }, 0f..0.7f, pct(cfg.glassTint / 0.7f))
                LabeledSlider("Rim light", cfg.rimLight, { v -> set { it.copy(rimLight = v) } }, 0f..1f, pct(cfg.rimLight))
                LabeledSlider("Corner roundness", cfg.cornerDp, { v -> set { it.copy(cornerDp = v) } }, 4f..44f, "%.0f dp".format(Locale.ROOT, cfg.cornerDp))
                LabeledSlider("Cover opacity", cfg.coverOpacity, { v -> set { it.copy(coverOpacity = v) } }, 0.1f..1f, pct(cfg.coverOpacity))
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Background")
                if (cfg.pureBlack) {
                    Text(
                        "The AMOLED themes keep the background pure black, so black pixels stay switched off and save battery. " +
                            "Pick another theme above for colours, motion and album-art glow.",
                        color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    SwitchRow("Dark mode", cfg.darkMode, { on -> set { it.copy(darkMode = on) } })
                    SwitchRow("Moving colours", cfg.animateBackground, { on -> set { it.copy(animateBackground = on) } })
                    if (cfg.animateBackground) {
                        LabeledSlider("Motion speed", cfg.animationSpeed, { v -> set { it.copy(animationSpeed = v) } }, 0.1f..3f, "%.1f×".format(Locale.ROOT, cfg.animationSpeed))
                    }
                    SwitchRow("Album art glow", cfg.artworkBackground, { on -> set { it.copy(artworkBackground = on) } }, sub = "Melts the current song's cover into the background.")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Screen")
                SwitchRow(
                    "Highest refresh rate", cfg.maxRefresh, { on -> set { it.copy(maxRefresh = on) } },
                    sub = "Runs LToTS at your screen's top refresh rate (120 Hz) so scrolling and animations are as smooth as they can be. " +
                        "Off lets the phone choose, which may be 60 Hz to save a little battery.",
                )
                SwitchRow(
                    "Hide navigation bar", cfg.hideNavBar, { on -> set { it.copy(hideNavBar = on) } },
                    sub = "Swipe up from the bottom edge to bring it back for a moment.",
                )
                SwitchRow(
                    "Hide status bar", cfg.hideStatusBar, { on -> set { it.copy(hideStatusBar = on) } },
                    sub = "Clock and battery at the top. Swipe down from the top edge to see them.",
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Visualizer")
                Text("On the Playing tab", color = LocalPalette.current.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Around the cover", cfg.spectrumAroundCover, { set { it.copy(spectrumAroundCover = true) } })
                    Pill("Behind the cover", !cfg.spectrumAroundCover, { set { it.copy(spectrumAroundCover = false) } })
                }
                Text(
                    if (cfg.spectrumAroundCover) "The spectrum rings the cover, which is a little smaller so the ring has room."
                    else "The spectrum rises behind the full-size cover.",
                    color = LocalPalette.current.textFaint, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                Text("Style", color = LocalPalette.current.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpectrumStyle.entries.forEach { s -> Pill(s.label, cfg.spectrumStyle == s, { set { it.copy(spectrumStyle = s) } }) }
                }
                Spacer(Modifier.height(8.dp))
                LabeledSlider("Strength", cfg.spectrumOpacity, { v -> set { it.copy(spectrumOpacity = v) } }, 0.1f..1f, pct(cfg.spectrumOpacity))
                SwitchRow("Show sound before EQ", cfg.showPreEqGhost, { on -> set { it.copy(showPreEqGhost = on) } },
                    sub = "A faint outline of the original sound, so you can see exactly what the EQ changes.")
                Spacer(Modifier.height(8.dp))
                Text("Refresh rate", color = LocalPalette.current.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30 to "30 fps", 60 to "60 fps", 120 to "120 fps", 0 to "Off").forEach { (f, l) ->
                        Pill(l, cfg.visualizerFps == f, { set { it.copy(visualizerFps = f) } })
                    }
                }
                Text(
                    "120 fps matches a 120 Hz screen. Lower uses a little less battery; Battery Saver caps it at 30. " +
                        "The visualizer stops completely with the screen off and sleeps while nothing is playing.",
                    color = LocalPalette.current.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("Detail", color = LocalPalette.current.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(48, 96, 160).forEach { n ->
                        Pill("$n bars", cfg.analyzerBands == n, { set { it.copy(analyzerBands = n) } })
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Pill("Reset look to default (AMOLED)", false, { ThemeState.reset() })
        }
        Spacer(Modifier.height(bottomChrome()))
    }
}

private fun pct(v: Float) = "%.0f%%".format(Locale.ROOT, v * 100)

@Composable
private fun ColorCard(cfg: ThemeConfig, set: ((ThemeConfig) -> ThemeConfig) -> Unit) {
    val pal = LocalPalette.current
    // The glow colours the moving background, which the AMOLED themes don't have (pure black).
    val glowLockedWhy = if (cfg.pureBlack) "Glow isn't used on the AMOLED themes: the background stays pure black. Pick another theme to use it." else null
    var pickedGlow by rememberSaveable { mutableStateOf(false) }
    val glow = pickedGlow && glowLockedWhy == null

    // Each colour keeps its own hue, saturation and brightness. A slider changes only its own
    // value: hue never touches saturation or brightness, and so on, whatever the colour.
    val argb = if (glow) cfg.glow else cfg.accent
    val hsv = Hsv.resolve(argb, if (glow) cfg.glowHsv else cfg.accentHsv)
    fun setHsv(n: Hsv) {
        val v = n.toArgb()
        set { if (glow) it.copy(glow = v, glowHsv = n) else it.copy(accent = v, accentHsv = n) }
    }

    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Colours")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ColourChip("Accent", cfg.accent.toColor(), selected = !glow, enabled = true) { pickedGlow = false }
                ColourChip("Glow", cfg.glow.toColor(), selected = glow, enabled = glowLockedWhy == null) { pickedGlow = true }
            }
            Text(
                glowLockedWhy?.takeIf { pickedGlow }
                    ?: if (glow) "Glow: the colour the moving background is made of."
                    else if (cfg.dynamicColors) "Accent: buttons, sliders and highlights. On AMOLED Cover the song's cover sets it while music plays; this one shows when nothing is."
                    else "Accent: buttons, sliders and highlights.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            Spacer(Modifier.height(12.dp))
            FlowPills {
                swatches.forEach { sw -> Swatch(sw.toColor(), (argb and 0xFFFFFFFFL) == sw, { setHsv(Hsv.of(sw)) }) }
            }
            Spacer(Modifier.height(10.dp))
            GradientSlider(
                "Hue", "%.0f°".format(Locale.ROOT, hsv.h), hsv.h, 0f..360f,
                Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }),
            ) { h -> setHsv(hsv.copy(h = h)) }
            GradientSlider(
                "Saturation", pct(hsv.s), hsv.s, 0f..1f,
                Brush.horizontalGradient(listOf(Color.hsv(hsv.h, 0f, maxOf(hsv.v, 0.35f)), Color.hsv(hsv.h, 1f, maxOf(hsv.v, 0.35f)))),
            ) { v -> setHsv(hsv.copy(s = v)) }
            GradientSlider(
                "Brightness", pct(hsv.v), hsv.v, 0f..1f,
                Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hsv.h, hsv.s, 1f))),
            ) { v -> setHsv(hsv.copy(v = v)) }
        }
    }
}

/**
 * A slider drawn as a colour strip with a ring on it. The ring follows the value exactly (no
 * rounding through a colour), so it glides smoothly at any brightness.
 */
@Composable
private fun GradientSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    brush: Brush,
    onChange: (Float) -> Unit,
) {
    val pal = LocalPalette.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = pal.textDim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = pal.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(26.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp))) {
                drawRect(brush)
                val r = size.height / 2
                val t = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val x = r + (size.width - 2 * r) * t
                drawCircle(Color.Black.copy(alpha = 0.35f), r - 1f, androidx.compose.ui.geometry.Offset(x, r),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 7f))
                drawCircle(Color.White, r - 2f, androidx.compose.ui.geometry.Offset(x, r),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
            }
            androidx.compose.material3.Slider(
                value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
                modifier = Modifier.fillMaxSize(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.Transparent, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun ColourChip(name: String, color: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier.alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(50))
            .background(if (selected) pal.text.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(if (enabled) color else pal.textFaint))
        Spacer(Modifier.width(6.dp))
        Text(name, color = pal.text, style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * Look profiles, top right: Custom 1-5. Each keeps every setting on this page; the one in use
 * saves each change as you make it, and picking another switches the whole look.
 */
@Composable
private fun LookProfiles(cfg: ThemeConfig) {
    val pal = LocalPalette.current
    val active by ThemeState.activeProfile.collectAsState()
    val profiles by ThemeState.profiles.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        ProfilePill(customName(active), { open = true })
        ProfileMenu(open, { open = false }, "Look profiles", "Each keeps everything on this page. Changes save into the one in use.") {
            profiles.forEachIndexed { i, look ->
                val inUse = i == active
                ProfileRow(
                    name = customName(i),
                    status = when {
                        inUse -> "In use"
                        look == null -> "Empty · starts as a copy of this look"
                        else -> "Saved look · tap to switch"
                    },
                    active = inUse,
                    onClick = { ThemeState.selectProfile(i); open = false },
                    leading = { LookDot(look ?: cfg) },
                    trailing = if (inUse) ({ Icon(Icons.Rounded.Check, null, tint = pal.accent) }) else null,
                )
            }
        }
    }
}

/** A small round preview of a look: its background glow (black on AMOLED) and its accent. */
@Composable
private fun LookDot(t: ThemeConfig) {
    val pal = LocalPalette.current
    val back = if (t.pureBlack) Color.Black else t.glow.toColor()
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(back, back, t.accent.toColor())))
            .border(1.dp, pal.text.copy(alpha = 0.2f), CircleShape)
    )
}
