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

    /**
     * Red (0..255) at: the top of a loud bar, the gap between two bars, and where there are no bars,
     * for a red cover drawn with [cover] / [underBars] over black.
     */
    private fun reds(cover: Float, underBars: Float): Triple<Int, Int, Int> {
        // Left half of the spectrum loud (reaching up into the cover), right half silent.
        val levels = FloatArray(12) { if (it < 6) 0.9f else 0f }
        rule.setContent {
            Box(Modifier.size(240.dp).background(Color.Black)) {
                Box(
                    Modifier.size(240.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .background(Color.Red)
                        .drawWithContent {
                            drawContent()
                            with(SpectrumMask) {
                                drawOpacity(levels, SpectrumStyle.BARS, BehindLayout.BELOW, 0f, 0f, size.width, size.height, cover, underBars)
                            }
                        }
                )
            }
        }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        val bw = img.width / 12f
        fun red(x: Float, y: Float) = (img.getPixel(x.toInt(), y.toInt()) shr 16) and 0xFF
        val bar = red(bw * 2.5f, img.height * 0.2f)
        val gap = red(bw * 3f, img.height * 0.2f)
        val none = red(img.width * 0.8f, img.height * 0.2f)
        println("cover $cover, under bars $underBars: bar $bar, gap $gap, no bars $none")
        return Triple(bar, gap, none)
    }

    /** A solid cover the bars show through: only where the bars are drawn does it change. */
    @Test fun solidCoverSeeThroughBars() {
        val (bar, gap, none) = reds(cover = 1f, underBars = 0.4f)
        assertTrue("the cover stays solid away from the bars: $none", none > 245)
        assertTrue("and between the bars: $gap", gap > 245)
        assertTrue("about 40% on the bar: $bar", bar in 90..130)
    }

    /** The other way round too. */
    @Test fun seeThroughCoverSolidBars() {
        val (bar, gap, none) = reds(cover = 0.4f, underBars = 1f)
        assertTrue("the cover stays at 40% away from the bars: $none", none in 85..120)
        assertTrue("and between the bars: $gap", gap in 85..120)
        assertTrue("about solid on the bar: $bar", bar > 235)
    }
}
