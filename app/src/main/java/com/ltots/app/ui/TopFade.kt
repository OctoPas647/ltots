package com.ltots.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ltots.app.data.ThemeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Playing tab's fade into black at the top of the screen (Look > Visualizer > Top fade).
 * One still gradient fixed to the screen, used twice with exactly the same shape: laid over the
 * album-art background, and as a mask on the Gradient flow light round the cover. So both fade
 * together, and the light pumping with the beat moves under it while the fade itself stays put.
 */
object TopFade {
    private val _active = MutableStateFlow(false)
    /** True while the Playing tab shows a song (the background then fades at the top). */
    val active: StateFlow<Boolean> = _active

    /** Call from the Playing tab while it shows the song. */
    @Composable
    fun WhileShown() {
        DisposableEffect(Unit) {
            _active.value = true
            onDispose { _active.value = false }
        }
    }

    fun on(cfg: ThemeConfig) = cfg.playingTopFade && cfg.topFadeDarkness > 0.005f

    private fun stops(cfg: ThemeConfig): Pair<Float, Float> {
        val start = cfg.topFadeStart.coerceIn(0f, 0.95f)
        return start to (start + cfg.topFadeLength).coerceIn(start + 0.01f, 1f)
    }

    /**
     * Black over what's under it: [cfg]'s darkness at the top down to where the fade starts, then
     * clear. [screenTop]: where the top of the screen is in the drawing's own coordinates,
     * [screenHeight]: the screen's height there.
     */
    fun overlay(cfg: ThemeConfig, screenTop: Float, screenHeight: Float, strength: Float = 1f): Brush {
        val (s, e) = stops(cfg)
        val dark = Color.Black.copy(alpha = (cfg.topFadeDarkness * strength).coerceIn(0f, 1f))
        return Brush.verticalGradient(
            0f to dark, s to dark, e to Color.Transparent,
            startY = screenTop, endY = screenTop + screenHeight,
        )
    }

    /**
     * The same fade as a mask (draw with BlendMode.DstIn): keeps 1 − darkness of the light at the
     * top, all of it below the fade.
     */
    fun mask(cfg: ThemeConfig, screenTop: Float, screenHeight: Float): Brush {
        val (s, e) = stops(cfg)
        val keep = Color.Black.copy(alpha = (1f - cfg.topFadeDarkness).coerceIn(0f, 1f))
        return Brush.verticalGradient(
            0f to keep, s to keep, e to Color.Black,
            startY = screenTop, endY = screenTop + screenHeight,
        )
    }
}
