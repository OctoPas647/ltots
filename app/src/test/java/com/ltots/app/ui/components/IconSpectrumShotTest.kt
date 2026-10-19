package com.ltots.app.ui.components

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.ltots.app.audio.Analyzer
import com.ltots.app.audio.AnalyzerTap
import com.ltots.app.data.IconBars
import com.ltots.app.data.IconCover
import com.ltots.app.data.IconFill
import com.ltots.app.data.IconIdle
import com.ltots.app.data.RingLayout
import com.ltots.app.data.SpectrumStyle
import com.ltots.app.data.ThemeConfig
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.screens.NowPlayingScreen
import com.ltots.app.ui.theme.LToTSTheme
import com.ltots.app.ui.theme.ThemeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sin

/**
 * Pictures of icon mode, for review: the visualizer growing out of the reference snake's outline
 * in each style and fill, and the Playing tab with an icon in the cover's place.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class IconSpectrumShotTest {
    @get:Rule val rule = createComposeRule()

    private val snake: IconShape by lazy {
        val img = ImageIO.read(javaClass.getResourceAsStream("/icons/snake.jpg")!!)
        IconShape.build(IconMask.prepare(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height), 0.5f, false)
    }

    /** A stand-in for a song's cover: a warm diagonal gradient. */
    private val cover: Bitmap by lazy {
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { b ->
            android.graphics.Canvas(b).drawPaint(Paint().apply {
                shader = LinearGradient(0f, 0f, 300f, 300f, intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFFFFAF7B.toInt()), null, Shader.TileMode.CLAMP)
            })
        }
    }

    private fun visual(): LiveVisual = LiveVisual(Analyzer(AnalyzerTap(), 96)).also { v ->
        v.fps = 120
        val f = v.frame
        for (b in 0 until 96) {
            // Like music: loud bass, quieter towards the treble, with some ripple.
            f.post[b] = (0.95f - 0.6f * b / 95f + 0.12f * sin(b * 0.6f)).coerceIn(0f, 1f)
            f.pre[b] = (f.post[b] * 0.8f + 0.1f).coerceIn(0f, 1f)
        }
        v.preMirrorsPost = false
    }

    /** Lets the background work (outline layout) finish and the result reach the screen. */
    private fun settle() {
        repeat(30) { Thread.sleep(50); rule.waitForIdle() }
    }

    private fun shot(name: String, playing: Boolean = true, look: (ThemeConfig) -> ThemeConfig) {
        ThemeState.update { look(ThemeState.amoled.copy(iconMode = true)) }
        rule.setContent {
            LToTSTheme {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color.Black)) {
                    IconSpectrum(visual(), snake, cover, playing, Modifier.fillMaxSize())
                }
            }
        }
        settle()
        rule.onRoot().captureRoboImage("build/ui-shots/22_icon_$name.png")
    }

    @Test fun barsOutOfTheDarkParts() = shot("bars_dark") { it.copy(spectrumStyle = SpectrumStyle.BARS) }

    @Test fun curveWithGlass() = shot("curve_glass") { it.copy(spectrumStyle = SpectrumStyle.CURVE, iconFill = IconFill.GLASS) }

    @Test fun mirrorWithAccentAndCover() = shot("mirror_accent_cover") {
        it.copy(spectrumStyle = SpectrumStyle.MIRROR, iconFill = IconFill.ACCENT, iconCover = IconCover.INSIDE, ringLayout = RingLayout.MIRRORED)
    }

    @Test fun barsBothWaysWithCover() = shot("bars_both_cover") {
        it.copy(spectrumStyle = SpectrumStyle.BARS, iconBars = IconBars.BOTH, iconCover = IconCover.INSIDE)
    }

    @Test fun barsInward() = shot("bars_inward_glass") {
        it.copy(spectrumStyle = SpectrumStyle.BARS, iconBars = IconBars.IN, iconFill = IconFill.GLASS)
    }

    @Test fun fullSizeCoverDimmedBehind() = shot("full_cover_dimmed") {
        it.copy(spectrumStyle = SpectrumStyle.BARS, iconCover = IconCover.FULL, iconCoverDim = 0.6f)
    }

    @Test fun fullSizeCoverWithGlass() = shot("full_cover_glass_curve") {
        it.copy(spectrumStyle = SpectrumStyle.CURVE, iconFill = IconFill.GLASS, iconCover = IconCover.FULL, iconCoverDim = 0.4f)
    }

    @Test fun pausedShowsNothing() = shot("paused_nothing", playing = false) { it.copy(iconFill = IconFill.ACCENT, iconIdle = IconIdle.NOTHING) }

    @Test fun pausedShowsTheCover() = shot("paused_cover", playing = false) { it.copy(iconIdle = IconIdle.COVER) }

    /** The real Playing tab with a picked icon (the snake, copied in like the photo picker does). */
    @Test fun playingTabInIconMode() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "ltots/icons").apply { mkdirs() }
        javaClass.getResourceAsStream("/icons/snake.jpg")!!.use { input -> File(dir, "snake.jpg").outputStream().use { input.copyTo(it) } }
        // Worked out up front, so the screen finds it ready.
        assertNotNull(runBlocking { IconCache.shape(context, "snake.jpg", 0.5f, false, IconMask.DEFAULT_MIN_HOLE) })
        ThemeState.update {
            ThemeState.amoled.copy(iconMode = true, iconFile = "snake.jpg", iconFill = IconFill.ACCENT, iconIdle = IconIdle.SHAPE)
        }
        val player = PlayerUi().apply {
            hasMedia = true; isPlaying = false; title = "Song title"; artist = "Artist"; durationMs = 200_000; positionMs = 61_000
        }
        rule.setContent {
            LToTSTheme { Box(Modifier.background(Color.Black)) { NowPlayingScreen(player, cover, onOpenEq = {}, onOpenSound = {}) } }
        }
        settle()
        rule.onRoot().captureRoboImage("build/ui-shots/22_icon_playing_tab.png")
    }
}
