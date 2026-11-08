package com.ltots.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ltots.app.data.ThemeConfig
import com.ltots.app.ui.glass.AppBackground
import com.ltots.app.ui.glass.LocalBackdrop
import com.ltots.app.ui.glass.glass
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import com.ltots.app.ui.theme.Palette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO

/**
 * The glass must redraw the moment its colour or its settings change — not only when it is
 * rebuilt (moving Corner roundness or switching tabs). This is what keeps the play button in the
 * cover's colour on a song change, and the Frost / tint slider live.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class GlassLiveTest {
    @get:Rule val rule = createComposeRule()

    private var cfg by mutableStateOf(ThemeConfig())
    private var tint by mutableStateOf<Color?>(null)

    private fun palette(dark: Boolean) = Palette(
        accent = Color(0xFF00E5FF), onAccent = Color.Black, text = Color.White, textDim = Color.White,
        textFaint = Color.White, glassTint = if (dark) Color(0xFF15102A) else Color.White, line = Color.White, dark = dark,
        glows = listOf(Color(0xFF203060), Color(0xFF203060), Color(0xFF203060)),
    )

    private fun show() = rule.setContent {
        val backdrop = rememberLayerBackdrop()
        CompositionLocalProvider(LocalThemeConfig provides cfg, LocalPalette provides palette(cfg.darkMode)) {
            Box(Modifier.size(240.dp)) {
                AppBackground(null, Modifier.fillMaxSize().layerBackdrop(backdrop))
                CompositionLocalProvider(LocalBackdrop provides backdrop) {
                    Box(Modifier.size(120.dp).align(Alignment.Center).testTag("glass").glass(RectangleShape, tint = tint, tintBoost = if (tint != null) 1f else 0f))
                }
            }
        }
    }

    /** Colour in the middle of the glass, as (r, g, b). */
    private fun centre(name: String): Triple<Int, Int, Int> {
        rule.waitForIdle()
        val f = File("build/ui-shots/glass_$name.png")
        rule.onNodeWithTag("glass").captureRoboImage(f.path)
        val img = ImageIO.read(f)
        val p = img.getRGB(img.width / 2, img.height / 2)
        return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    @Test fun colourChangesAtOnce() {
        cfg = ThemeConfig(pureBlack = true, animateBackground = false, artworkBackground = false)
        tint = Color.Red
        show()
        val red = centre("red")
        assertTrue("starts red: $red", red.first > 180 && red.third < 60)

        tint = Color.Blue // the cover changed: no rebuild, no tab switch
        val blue = centre("blue")
        assertTrue("turns blue at once: $blue", blue.third > 180 && blue.first < 60)
    }

    @Test fun frostChangesAtOnce() {
        cfg = ThemeConfig(darkMode = false, animateBackground = false, artworkBackground = false, glassTint = 0.05f, blurDp = 0f, refraction = 0f)
        show()
        val clear = centre("frost_low")

        cfg = cfg.copy(glassTint = 0.7f) // drag Frost / tint; nothing else changes
        val frosted = centre("frost_high")
        val lighter = (frosted.first + frosted.second + frosted.third) - (clear.first + clear.second + clear.third)
        assertTrue("frost shows at once: $clear -> $frosted", lighter > 60)
    }
}
