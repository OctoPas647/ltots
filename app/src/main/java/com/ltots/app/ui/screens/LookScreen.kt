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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import com.ltots.app.ui.components.ProfileMenu
import com.ltots.app.ui.components.ProfilePill
import com.ltots.app.ui.components.ProfileRow
import com.ltots.app.ui.components.ProfileAddRow
import com.ltots.app.ui.components.ProfileIconButton
import com.ltots.app.ui.components.ProfileRowActions
import com.ltots.app.ui.components.RenameDialog
import com.ltots.app.ui.components.DeleteDialog
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
import com.ltots.app.data.BehindLayout
import com.ltots.app.data.Effects
import com.ltots.app.data.RingLayout
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
fun LookScreen(artwork: android.graphics.Bitmap? = null) {
    // Edit the saved theme (LocalThemeConfig may be showing cover-based colours).
    val cfg by ThemeState.config.collectAsState()
    val pal = LocalPalette.current
    fun set(t: (ThemeConfig) -> ThemeConfig) = ThemeState.update(t)
    var resetAsk by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Look", style = MaterialTheme.typography.displaySmall, color = pal.text)
                Text("Everything here changes live.", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
            }
            LookProfiles(cfg)
            Spacer(Modifier.width(8.dp))
            // Reset everything on this page to the default look, like the EQ's reset button.
            com.ltots.app.ui.glass.GlassCircleButton({ resetAsk = true }, Modifier.size(40.dp)) {
                Icon(androidx.compose.material.icons.Icons.Rounded.RestartAlt, "Reset the look to default", tint = pal.text, modifier = Modifier.size(22.dp))
            }
        }
        if (resetAsk) ResetLookDialog(onDismiss = { resetAsk = false })
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
                // Smoothness turns these down on phones that can't keep up (grayed out then).
                val level = com.ltots.app.ui.rememberEffects()
                LabeledSlider("Blur", cfg.blurDp, { v -> set { it.copy(blurDp = v) } }, 0f..60f, "%.0f dp".format(Locale.ROOT, cfg.blurDp),
                    enabled = level == Effects.FULL)
                LabeledSlider("Refraction", cfg.refraction, { v -> set { it.copy(refraction = v) } }, 0f..1f, pct(cfg.refraction),
                    enabled = level != Effects.LIGHT)
                Text(
                    if (level == Effects.LIGHT) "Smoothness is on Light (Profile tab): the glass is plain frosted, without blur or bending, so these two are off for now."
                    else if (level == Effects.BALANCED) "Smoothness is on Balanced (Profile tab): the glass bends without blurring, so Blur is off for now." +
                        (if (cfg.pureBlack) " On the AMOLED themes the bars sit on pure black too." else "")
                    else if (cfg.pureBlack) "Blur and Refraction show on the bars at the bottom, bending the page as it scrolls under them. The panels sit on pure black, which looks the same bent or not."
                    else "Blur and Refraction bend what's behind the glass: the animated colours under the panels, and the page scrolling under the bars at the bottom.",
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
                            "These are grayed out: pick another theme above to use colours, motion and album-art glow.",
                        color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                // Grayed out on the AMOLED themes (nothing to move or tint on pure black).
                val bg = !cfg.pureBlack
                SwitchRow("Dark mode", cfg.darkMode || !bg, { on -> set { it.copy(darkMode = on) } }, enabled = bg)
                val saver by com.ltots.app.ui.rememberBatterySaver()
                val effects = com.ltots.app.ui.rememberEffects()
                SwitchRow(
                    "Animations", cfg.animateBackground && bg, { on -> set { it.copy(animateBackground = on) } }, enabled = bg,
                    sub = when {
                        !bg -> "Off on the AMOLED themes: nothing moves on pure black."
                        cfg.animateBackground && saver -> "Paused while the phone's Battery Saver is on."
                        cfg.animateBackground && effects == com.ltots.app.data.Effects.LIGHT -> "The colours behind the glass drift around. Smoothness: Light moves them at up to 30 fps."
                        else -> "The colours behind the glass drift around, and the album-art glow with them."
                    },
                )
                LabeledSlider(
                    "Motion speed", cfg.animationSpeed, { v -> set { it.copy(animationSpeed = v) } }, 0.1f..3f,
                    "%.1f×".format(Locale.ROOT, cfg.animationSpeed), enabled = bg && cfg.animateBackground,
                )
                SwitchRow(
                    "Album art glow", cfg.artworkBackground && bg, { on -> set { it.copy(artworkBackground = on) } }, enabled = bg,
                    sub = "Melts the current song's cover into the background.",
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Visualizer")
                Text("On the Playing tab", color = LocalPalette.current.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                FlowPills {
                    Pill("Around the cover", !cfg.iconMode && cfg.spectrumAroundCover, { set { it.copy(iconMode = false, spectrumAroundCover = true) } })
                    Pill("Behind the cover", !cfg.iconMode && !cfg.spectrumAroundCover, { set { it.copy(iconMode = false, spectrumAroundCover = false) } })
                    Pill("Icon", cfg.iconMode, { set { it.copy(iconMode = true) } })
                }
                Spacer(Modifier.height(8.dp))
                // Round the cover and along an icon's outline the spectrum runs the same ways.
                val ring = cfg.spectrumAroundCover || cfg.iconMode
                FlowPills {
                    if (ring) {
                        RingLayout.entries.forEach { l -> Pill(l.label, cfg.ringLayout == l, { set { it.copy(ringLayout = l) } }) }
                    } else {
                        BehindLayout.entries.forEach { l -> Pill(l.label, cfg.behindLayout == l, { set { it.copy(behindLayout = l) } }) }
                    }
                }
                Text(
                    if (cfg.iconMode) when (cfg.ringLayout) {
                        RingLayout.LOOP -> "Once along the icon's outlines, longest first: bass at the top, round to the treble."
                        RingLayout.MIRRORED -> "The same both ways round the outlines: treble at the top, bass halfway round."
                        RingLayout.MIRRORED_FLIPPED -> "The same both ways round the outlines: bass at the top, treble halfway round."
                    } else if (cfg.spectrumAroundCover) when (cfg.ringLayout) {
                        RingLayout.LOOP -> "One continuous ring round the smaller cover: bass at the top, clockwise to the treble."
                        RingLayout.MIRRORED -> "The same on both sides: bass at the bottom, treble at the top."
                        RingLayout.MIRRORED_FLIPPED -> "The same on both sides, upside down: bass at the top, treble at the bottom."
                    } else when (cfg.behindLayout) {
                        BehindLayout.BELOW -> "Rises from below the full-size cover, bass on the left."
                        BehindLayout.ABOVE -> "Hangs down from above the full-size cover, bass on the left."
                        BehindLayout.ABOVE_FLIPPED -> "Hangs down from above the full-size cover, bass on the right."
                        BehindLayout.BOTH_MIRRORED -> "Rises from below and hangs from above at once. The top is a mirror image of the bottom: bass on the left on both."
                        BehindLayout.BOTH -> "Rises from below and hangs from above at once, not mirrored: the top is the bottom turned round, bass on the right."
                    },
                    color = LocalPalette.current.textFaint, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                if (cfg.iconMode) IconSettings(cfg, artwork, ::set)
                SwitchRow(
                    "Gradient flow", cfg.gradientFlow, { on -> set { it.copy(gradientFlow = on) } },
                    enabled = !cfg.iconMode,
                    sub = when {
                        cfg.iconMode -> "Works with the cover: pick Around the cover or Behind the cover above."
                        cfg.pureBlack && !cfg.dynamicColors -> "Lights the screen round the cover in the colours of its edges, like a TV's backlight, and melts into your own colour further out."
                        else -> "Lights the screen round the cover in the colours of its edges, like a TV's backlight, fading out into the background."
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text("Style", color = LocalPalette.current.textDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpectrumStyle.entries.forEach { s -> Pill(s.label, cfg.spectrumStyle == s, { set { it.copy(spectrumStyle = s) } }) }
                }
                Spacer(Modifier.height(8.dp))
                LabeledSlider("Strength", cfg.spectrumOpacity, { v -> set { it.copy(spectrumOpacity = v) } }, 0.1f..1f, pct(cfg.spectrumOpacity))
                SwitchRow("Show sound before EQ", cfg.showPreEqGhost, { on -> set { it.copy(showPreEqGhost = on) } },
                    sub = "A faint outline of the original sound, so you can see exactly what the EQ changes.")
                SwitchRow(
                    "Freeze when music stops", cfg.freezeVisualizer, { on -> set { it.copy(freezeVisualizer = on) } },
                    sub = "When a song is paused or ends, the visualizer stays exactly as it was instead of falling away. " +
                        "Nothing runs while it's frozen, so it costs no battery.",
                )
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
            Pill("Reset everything to default", false, { resetAsk = true })
        }
        Spacer(Modifier.height(bottomChrome()))
    }
}

private fun pct(v: Float) = "%.0f%%".format(Locale.ROOT, v * 100)

/**
 * Smoothness: how much glass and motion the app draws. Automatic measures this phone while you
 * use the app and steps down by itself if frames keep running late.
 */
@Composable
internal fun SmoothnessSetting(mode: com.ltots.app.data.EffectsMode, onPick: (com.ltots.app.data.EffectsMode) -> Unit) {
    val pal = LocalPalette.current
    val auto by com.ltots.app.ui.Smoothness.auto.collectAsState()
    Spacer(Modifier.height(6.dp))
    Text("Smoothness", color = pal.textDim, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    FlowPills {
        com.ltots.app.data.EffectsMode.entries.forEach { m -> Pill(m.label, mode == m, { onPick(m) }) }
    }
    val now = com.ltots.app.ui.Smoothness.effective(mode, auto)
    Text(
        (if (mode == com.ltots.app.data.EffectsMode.AUTO) "Automatic: now ${auto.label} on this phone. It watches how fast the screen keeps up and turns effects down by itself if the app stutters. " else "") +
            when (now) {
                Effects.FULL -> "Full: real blur everywhere, and the bars at the bottom bend the page scrolling under them."
                Effects.BALANCED -> "Balanced: the bars bend the background instead of the page, panels bend without blur, motion and visualizer at up to 60 fps."
                Effects.LIGHT -> "Light: plain frosted panels with no blur or bending, animations and visualizer at up to 30 fps. The smoothest on any phone."
            },
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
    )
    if (mode == com.ltots.app.data.EffectsMode.AUTO && auto != Effects.FULL) {
        Spacer(Modifier.height(6.dp))
        Pill("Measure again from Full", false, { com.ltots.app.ui.Smoothness.recheck() })
    }
    Spacer(Modifier.height(8.dp))
}

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
 * Reset, top right: everything on the Look tab back to the default look (AMOLED, hue 180°,
 * saturation and brightness 100%), for the profile in use or for all five.
 */
@Composable
private fun ResetLookDialog(onDismiss: () -> Unit) {
    val active by ThemeState.activeProfile.collectAsState()
    val profiles by ThemeState.profiles.collectAsState()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset the look?") },
        text = {
            Text(
                "Everything on this page goes back to the default: the AMOLED theme, colours, glass, " +
                    "background, visualizer and icon settings. Can't be undone." +
                    if (profiles.size > 1) " Reset all removes your other look profiles too." else ""
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { ThemeState.reset(); onDismiss() }) { Text("Reset ${profiles.getOrNull(active)?.name ?: "this look"}") }
        },
        dismissButton = {
            Row {
                if (profiles.size > 1) androidx.compose.material3.TextButton(onClick = { ThemeState.resetAll(); onDismiss() }) { Text("Reset all") }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Look profiles, top right: up to 10 named looks. Each keeps every setting on this page; the one
 * in use saves each change as you make it, and picking another switches the whole look.
 */
@Composable
private fun LookProfiles(cfg: ThemeConfig) {
    val pal = LocalPalette.current
    val active by ThemeState.activeProfile.collectAsState()
    val profiles by ThemeState.profiles.collectAsState()
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    Box {
        ProfilePill(profiles.getOrNull(active)?.name ?: "Look", { open = true })
        ProfileMenu(
            open, { open = false }, "Look profiles",
            "Each keeps everything on this page except the visualizer's Detail, which stays the same in all. Changes save into the one in use.",
        ) {
            profiles.forEachIndexed { i, p ->
                val inUse = i == active
                ProfileRow(
                    name = p.name,
                    status = if (inUse) "In use · " + ThemeState.themeName(cfg) else ThemeState.themeName(p.look) + " · tap to switch",
                    active = inUse,
                    onClick = { ThemeState.selectProfile(i); open = false },
                    leading = { LookDot(if (inUse) cfg else p.look) },
                    trailing = {
                        ProfileRowActions {
                            ProfileIconButton(Icons.Rounded.Edit, "Rename ${p.name}", { renaming = i })
                            if (profiles.size > 1) ProfileIconButton(Icons.Rounded.Delete, "Delete ${p.name}", { deleting = i })
                            if (inUse) Icon(Icons.Rounded.Check, null, tint = pal.accent, modifier = Modifier.padding(start = 4.dp))
                        }
                    },
                )
            }
            if (profiles.size < ThemeState.MAX_PROFILES) {
                ProfileAddRow("New look profile") { ThemeState.addProfile(); open = false }
            }
        }
    }
    renaming?.let { i ->
        RenameDialog("Rename look profile", profiles.getOrNull(i)?.name ?: "", onDismiss = { renaming = null }) { ThemeState.renameProfile(i, it) }
    }
    deleting?.let { i ->
        val name = profiles.getOrNull(i)?.name ?: ""
        DeleteDialog("Delete \"$name\"?", "This look profile and its settings are removed. Can't be undone.", onDismiss = { deleting = null }) {
            ThemeState.deleteProfile(i)
        }
    }
}

/** A small round preview of a look: its background glow (black on AMOLED) and its accent. */
@Composable
internal fun LookDot(t: ThemeConfig) {
    val pal = LocalPalette.current
    val back = if (t.pureBlack) Color.Black else t.glow.toColor()
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(back, back, t.accent.toColor())))
            .border(1.dp, pal.text.copy(alpha = 0.2f), CircleShape)
    )
}
