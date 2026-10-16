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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ltots.app.data.ThemeConfig
import com.ltots.app.ui.BottomChrome
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
        Text("Look", style = MaterialTheme.typography.displaySmall, color = pal.text, modifier = Modifier.padding(top = 12.dp))
        Text("Everything here changes live.", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))

        // Theme presets
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(vertical = 16.dp)) {
                SectionTitle("Themes", Modifier.padding(horizontal = 16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeState.presets.forEach { (name, t) ->
                        val on = cfg.copy(hideNavBar = t.hideNavBar, hideStatusBar = t.hideStatusBar, dynamicColors = t.dynamicColors) == t
                        Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { ThemeState.applyPreset(t) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(
                                    Brush.linearGradient(listOf(t.gradientA.toColor(), t.accent.toColor(), t.gradientB.toColor()))
                                ).border(if (on) 2.dp else 1.dp, if (on) pal.text else pal.text.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                            )
                            Text(
                                name, color = if (on) pal.text else pal.textDim, style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(16.dp)) {
                SwitchRow(
                    "Colours from the song's cover", cfg.dynamicColors, { on -> set { it.copy(dynamicColors = on) } },
                    sub = "The accent and background glow take the average colour of the current cover, like Spotify and YouTube Music. The colours below are used when nothing is playing.",
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        ColorCard(cfg, ::set)

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Glass")
                LabeledSlider("Blur", cfg.blurDp, { v -> set { it.copy(blurDp = v) } }, 0f..60f, "%.0f dp".format(Locale.ROOT, cfg.blurDp))
                LabeledSlider("Refraction", cfg.refraction, { v -> set { it.copy(refraction = v) } }, 0f..1f, pct(cfg.refraction))
                LabeledSlider("Frost / tint", cfg.glassTint, { v -> set { it.copy(glassTint = v) } }, 0f..0.7f, pct(cfg.glassTint / 0.7f))
                LabeledSlider("Rim light", cfg.rimLight, { v -> set { it.copy(rimLight = v) } }, 0f..1f, pct(cfg.rimLight))
                LabeledSlider("Corner roundness", cfg.cornerDp, { v -> set { it.copy(cornerDp = v) } }, 4f..44f, "%.0f dp".format(Locale.ROOT, cfg.cornerDp))
                SwitchRow("Prism edges", cfg.chromaticAberration, { on -> set { it.copy(chromaticAberration = on) } }, sub = "Splits colours slightly at the glass edges, like real glass.")
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Background")
                SwitchRow(
                    "Pure black (AMOLED)", cfg.pureBlack, { on -> set { it.copy(pureBlack = on, darkMode = if (on) true else it.darkMode) } },
                    sub = "True black background — black pixels switch off, saving battery.",
                )
                if (!cfg.pureBlack) {
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
                    "Higher is smoother but uses more battery. 30 fps (the default) is already smooth and the easiest on the battery; Battery Saver caps it at 30. The visualizer stops completely with the screen off and sleeps while nothing is playing.",
                    color = LocalPalette.current.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Pill("Reset look to default (AMOLED)", false, { ThemeState.reset() })
        }
        Spacer(Modifier.height(BottomChrome.calculateBottomPadding() - 80.dp))
    }
}

private fun pct(v: Float) = "%.0f%%".format(Locale.ROOT, v * 100)

@Composable
private fun ColorCard(cfg: ThemeConfig, set: ((ThemeConfig) -> ThemeConfig) -> Unit) {
    val pal = LocalPalette.current
    var target by rememberSaveable { mutableIntStateOf(0) }
    val names = listOf("Accent", "Glow 1", "Glow 2", "Glow 3")
    val current = when (target) {
        0 -> cfg.accent; 1 -> cfg.gradientA; 2 -> cfg.gradientB; else -> cfg.gradientC
    }.toColor()
    fun apply(c: Color) {
        val v = c.toArgbLong()
        set {
            when (target) {
                0 -> it.copy(accent = v); 1 -> it.copy(gradientA = v); 2 -> it.copy(gradientB = v); else -> it.copy(gradientC = v)
            }
        }
    }
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(current.toArgbLong().toInt(), it) }

    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Colours")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                names.forEachIndexed { i, n ->
                    val c = when (i) { 0 -> cfg.accent; 1 -> cfg.gradientA; 2 -> cfg.gradientB; else -> cfg.gradientC }.toColor()
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (target == i) pal.text.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { target = i }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(6.dp))
                        Text(n, color = pal.text, style = MaterialTheme.typography.bodySmall, fontWeight = if (target == i) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowPills {
                swatches.forEach { s -> Swatch(s.toColor(), current.toArgbLong() == s, { apply(s.toColor()) }) }
            }
            Spacer(Modifier.height(10.dp))
            HueBar(hsv[0]) { h -> apply(Color.hsv(h, hsv[1].coerceAtLeast(0.05f), hsv[2].coerceAtLeast(0.05f))) }
            LabeledSlider("Saturation", hsv[1], { s -> apply(Color.hsv(hsv[0], s, hsv[2])) }, 0f..1f, pct(hsv[1]))
            LabeledSlider("Brightness", hsv[2], { v -> apply(Color.hsv(hsv[0], hsv[1], v)) }, 0f..1f, pct(hsv[2]))
        }
    }
}

@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    val pal = LocalPalette.current
    Column {
        Text("Hue", color = pal.textDim, style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(26.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp))) {
                drawRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
                val x = size.width * hue / 360f
                drawCircle(Color.White, size.height / 2 - 2, androidx.compose.ui.geometry.Offset(x, size.height / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
            }
            androidx.compose.material3.Slider(
                value = hue, onValueChange = onHue, valueRange = 0f..359.9f, modifier = Modifier.fillMaxSize(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.Transparent, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                ),
            )
        }
    }
}
