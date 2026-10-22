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

    @Test fun barsAreCutOutOfTheCoverOnly() {
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
                                drawMask(levels, SpectrumStyle.CURVE, BehindLayout.BELOW, 0f, 0f, size.width, size.height, erase = 0.6f)
                            }
                        }
                )
            }
        }
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        val w = img.width; val h = img.height
        val underBars = img.getPixel(w / 5, h * 3 / 4)
        val noBars = img.getPixel(w * 4 / 5, h * 3 / 4)
        val r1 = (underBars shr 16) and 0xFF
        val r2 = (noBars shr 16) and 0xFF
        println("red under bars $r1, without $r2")
        assertTrue("solid where no bars pass: $r2", r2 > 240)
        assertTrue("see-through (40% left) where the bars pass: $r1", r1 in 80..120)
    }
}
