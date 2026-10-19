package com.ltots.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ltots.app.audio.Analyzer
import com.ltots.app.audio.AnalyzerTap
import com.ltots.app.data.BehindLayout
import com.ltots.app.data.RingLayout
import com.ltots.app.data.SpectrumStyle
import com.ltots.app.ui.theme.LToTSTheme
import com.ltots.app.ui.theme.ThemeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/** Pictures of the wrapped visualizer in each style, for review. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h411dp-xxhdpi")
class CoverSpectrumShotTest {
    @get:Rule val rule = createComposeRule()

    private fun visual(): LiveVisual = LiveVisual(Analyzer(AnalyzerTap(), 96)).also { v ->
        v.fps = 120
        val f = v.frame
        for (b in 0 until 96) {
            // Like music: loud bass, quieter towards the treble, with some ripple.
            f.post[b] = (0.95f - 0.75f * b / 95f + 0.08f * sin(b * 0.6f)).coerceIn(0f, 1f)
            f.pre[b] = (f.post[b] * 0.8f + 0.1f).coerceIn(0f, 1f)
        }
        v.preMirrorsPost = false
    }

    private fun shot(style: SpectrumStyle, layout: RingLayout = RingLayout.LOOP) {
        ThemeState.update { ThemeState.amoled.copy(spectrumStyle = style, ringLayout = layout) }
        rule.setContent {
            LToTSTheme {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                    CoverSpectrum(visual(), 0.62f, 24.dp, Modifier.fillMaxWidth().aspectRatio(1f), intensity = 0.9f)
                    Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f).clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B)))))
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/20_cover_ring_${style.name.lowercase()}_${layout.name.lowercase()}.png")
    }

    private fun behind(from: BehindLayout) {
        ThemeState.update { ThemeState.amoled.copy(spectrumStyle = SpectrumStyle.BARS) }
        rule.setContent {
            LToTSTheme {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                    LiveSpectrum(visual(), Modifier.fillMaxWidth().aspectRatio(1f), showWave = false, intensity = 0.8f, from = from)
                    Box(Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(RoundedCornerShape(28.dp)).alpha(0.6f)
                        .background(Brush.linearGradient(listOf(Color(0xFF3A1C71), Color(0xFFD76D77), Color(0xFFFFAF7B)))))
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/21_behind_${from.name.lowercase()}.png")
    }

    @Test fun mirrored() = shot(SpectrumStyle.BARS, RingLayout.MIRRORED)
    @Test fun mirroredFlipped() = shot(SpectrumStyle.BARS, RingLayout.MIRRORED_FLIPPED)
    @Test fun behindBelow() = behind(BehindLayout.BELOW)
    @Test fun behindAbove() = behind(BehindLayout.ABOVE)
    @Test fun behindAboveFlipped() = behind(BehindLayout.ABOVE_FLIPPED)

    @Test fun bars() = shot(SpectrumStyle.BARS)
    @Test fun curve() = shot(SpectrumStyle.CURVE)
    @Test fun mirrorStyle() = shot(SpectrumStyle.MIRROR)
}
