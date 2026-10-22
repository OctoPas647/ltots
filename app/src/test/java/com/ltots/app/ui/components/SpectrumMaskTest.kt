package com.ltots.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.data.BehindLayout
import com.ltots.app.data.SpectrumStyle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cover opacity with bars: the cover turns see-through only where the bars pass behind it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SpectrumMaskTest {
    @get:Rule val rule = createComposeRule()

    /** Red under bars and away from them, drawn with [cover] / [underBars]. */
    private fun reds(cover: Float, underBars: Float): Pair<Int, Int> {
        // Left half of the spectrum loud (reaching up into the cover), right half silent.
        val levels = FloatArray(48) { if (it < 24) 0.9f else 0f }
        rule.setContent {
            Box(Modifier.size(200.dp).background(Color.Black)) {
                Box(
                    Modifier.size(200.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .background(Color.Red)
                        .drawWithContent {
                            drawContent()
                            with(SpectrumMask) {
                                drawOpacity(levels, SpectrumStyle.CURVE, BehindLayout.BELOW, 0f, 0f, size.width, size.height, cover, underBars)
                            }
                        }
                )
            }
        }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        val under = (img.getPixel(img.width / 5, img.height * 3 / 4) shr 16) and 0xFF
        val away = (img.getPixel(img.width * 4 / 5, img.height * 3 / 4) shr 16) and 0xFF
        println("cover $cover, under bars $underBars: red under bars $under, away $away")
        return under to away
    }

    /** A solid cover the bars show through: only the parts the bars are behind change. */
    @Test fun solidCoverSeeThroughBars() {
        val (under, away) = reds(cover = 1f, underBars = 0.4f)
        assertTrue("the cover stays at Cover opacity (100%): $away", away > 245)
        assertTrue("40% where the bars are: $under", under in 85..120)
    }

    /** The other way round too: each part has exactly its own setting. */
    @Test fun seeThroughCoverSolidBars() {
        val (under, away) = reds(cover = 0.4f, underBars = 1f)
        assertTrue("the cover stays at Cover opacity (40%): $away", away in 85..120)
        assertTrue("100% where the bars are: $under", under > 245)
    }
}
