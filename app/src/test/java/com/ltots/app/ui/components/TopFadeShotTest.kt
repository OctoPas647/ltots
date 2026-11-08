package com.ltots.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ltots.app.data.ThemeConfig
import com.ltots.app.ui.TopFade
import com.ltots.app.ui.glass.AppBackground
import com.ltots.app.ui.theme.LToTSTheme
import com.ltots.app.ui.theme.ThemeState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Playing tab's top fade: album-art background and Gradient flow light going into black at
 * the top of the screen, the light reaching further down than up. Pictures for review.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class TopFadeShotTest {
    @get:Rule val rule = createComposeRule()

    private fun cover(): Bitmap {
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        fun fill(color: Int, l: Float, t: Float, r: Float, bb: Float) = c.drawRect(l, t, r, bb, Paint().apply { this.color = color })
        fill(0xFFE0405A.toInt(), 0f, 0f, 300f, 300f)
        fill(0xFF3060F0.toInt(), 0f, 0f, 150f, 300f)
        fill(0xFFF0C030.toInt(), 0f, 220f, 300f, 300f)
        return b
    }

    private fun shot(name: String, cfg: ThemeConfig) {
        ThemeState.update { cfg }
        val art = cover()
        rule.setContent {
            LToTSTheme {
                TopFade.WhileShown()
                Box(Modifier.fillMaxSize()) {
                    AppBackground(art, Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(70.dp))
                        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                            GradientFlowLight(art, 0.82f, null, Modifier.fillMaxSize(), reachDown = cfg.glowReachDown)
                            Image(
                                art.asImageBitmap(), null,
                                Modifier.fillMaxWidth(0.82f).aspectRatio(1f).clip(RoundedCornerShape(28.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Song title", color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/$name.png")
    }

    @Test fun fadeOnColourTheme() = shot(
        "40_top_fade_on",
        ThemeConfig(gradientFlow = true, artworkBackground = true, animateBackground = false),
    )

    @Test fun fadeOff() = shot(
        "41_top_fade_off",
        ThemeConfig(gradientFlow = true, artworkBackground = true, animateBackground = false, playingTopFade = false, glowReachDown = 1f),
    )

    @Test fun fadeDimmedLonger() = shot(
        "42_top_fade_dim_long",
        ThemeConfig(gradientFlow = true, artworkBackground = true, animateBackground = false, topFadeStart = 0.05f, topFadeLength = 0.6f, topFadeDarkness = 0.6f),
    )
}
