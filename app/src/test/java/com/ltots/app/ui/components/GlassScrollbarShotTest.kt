package com.ltots.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ltots.app.data.ScrollbarShow
import com.ltots.app.ui.glass.LocalBackdrop
import com.ltots.app.ui.glass.LocalGlassOverContent
import com.ltots.app.ui.theme.LToTSTheme
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.ThemeState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Library's liquid-glass scroll slider, at rest and held (with its letter bubble). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h800dp-xxhdpi")
class GlassScrollbarShotTest {
    @get:Rule val rule = createComposeRule()

    private val names = ('A'..'Z').flatMap { c -> (1..8).map { "$c song $it" } }

    @Test fun sliderAtRestAndHeld() {
        ThemeState.update { ThemeState.defaultLook.copy(scrollbarShow = ScrollbarShow.ALWAYS) }
        var state: androidx.compose.foundation.lazy.LazyListState? = null
        rule.setContent {
            LToTSTheme {
                val pal = LocalPalette.current
                val host = remember { ScrollbarHost() }
                val layer = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize().background(Color(0xFF14102A))) {
                    Box(Modifier.fillMaxSize().layerBackdrop(layer)) {
                        CompositionLocalProvider(LocalScrollbarHost provides host) {
                            val s = rememberLazyListState().also { state = it }
                            RegisterScroll(s, label = { i -> names.getOrNull(i)?.let { ScrollLabel.Words(initialOf(it)) } })
                            androidx.compose.foundation.lazy.LazyColumn(
                                Modifier.fillMaxSize(), state = s,
                                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(names) { i, n ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                                            .background(Color.hsv((i * 7f) % 360f, 0.6f, 0.9f)))
                                        Text(n, color = pal.text, modifier = Modifier.padding(start = 12.dp).fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                    CompositionLocalProvider(LocalBackdrop provides layer, LocalGlassOverContent provides true) {
                        GlassScrollbar(host, ScrollbarShow.ALWAYS, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, bottom = 24.dp))
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/30_scrollbar_rest.png")

        // Hold the slider and drag it down: the list jumps and the bubble shows the letter.
        rule.onNodeWithContentDescription("Scroll slider").performTouchInput {
            down(Offset(width - 8f, height * 0.1f))
            moveTo(Offset(width - 8f, height * 0.3f))
            moveTo(Offset(width - 8f, height * 0.55f))
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ui-shots/31_scrollbar_held.png")
        val first = state!!.firstVisibleItemIndex
        assertTrue("dragging the slider halfway jumps into the middle of the list ($first)", first in 60..150)
        rule.onNodeWithContentDescription("Scroll slider").performTouchInput { up() }
    }
}
