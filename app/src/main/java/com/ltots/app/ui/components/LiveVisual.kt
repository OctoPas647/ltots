package com.ltots.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.preferredFrameRate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import com.ltots.app.audio.Analyzer
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.SpectrumFrame
import com.ltots.app.data.SpectrumStyle
import com.ltots.app.ui.WhileOnScreen
import com.ltots.app.ui.rememberBatterySaver
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Holds the analyzer and a frame counter that triggers redraws (not recompositions). */
class LiveVisual(val analyzer: Analyzer) {
    var tick by mutableLongStateOf(0L)
    var frame: SpectrumFrame = SpectrumFrame(analyzer.bands)
    /** Frames per second the visual is running at (0 = off). */
    var fps = 60
}

/**
 * Drives the live spectrum. Battery-conscious by design:
 *  - runs at the chosen rate (60 fps by default) instead of every display refresh;
 *  - sleeps completely while nothing is playing (once the bars have fallen to rest);
 *  - stops when the app is in the background or the screen is off;
 *  - drops to 30 fps when the phone's Battery Saver is on.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberLiveVisual(): LiveVisual {
    val settings by AudioEngine.settings.collectAsState()
    val cfg = LocalThemeConfig.current
    val saver by rememberBatterySaver()
    val fps = if (saver) minOf(cfg.visualizerFps, 30) else cfg.visualizerFps
    val lv = remember(settings.analyzerBands) { LiveVisual(Analyzer(AudioEngine.tap, settings.analyzerBands)) }
    lv.fps = fps
    // Tell the audio thread someone is looking, only while the app is actually visible: with
    // the screen off or the app in the background it does no visualizer work at all.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, fps > 0) {
        if (fps <= 0) return@DisposableEffect onDispose { }
        var release: (() -> Unit)? = null
        fun sync(state: Lifecycle.State) {
            val visible = state.isAtLeast(Lifecycle.State.STARTED)
            if (visible && release == null) release = AudioEngine.tap.watch()
            if (!visible) { release?.invoke(); release = null }
        }
        val observer = LifecycleEventObserver { _, _ -> sync(lifecycle.currentState) }
        lifecycle.addObserver(observer)
        sync(lifecycle.currentState)
        onDispose {
            lifecycle.removeObserver(observer)
            release?.invoke()
        }
    }
    WhileOnScreen(lv, fps) {
        if (fps <= 0) {
            lv.frame = SpectrumFrame(lv.analyzer.bands); lv.tick++
            return@WhileOnScreen
        }
        val intervalNs = 1_000_000_000L / fps
        var last = 0L
        while (true) {
            val playing = AudioEngine.isPlaying.value
            if (!playing && lv.frame.isAtRest()) {
                // Nothing moving: no frames at all until music plays again.
                AudioEngine.isPlaying.first { it }
                last = 0L
            }
            // Wait out the rest of this frame's slot without asking for display frames.
            val now = System.nanoTime()
            val wait = (last + intervalNs - now) / 1_000_000L
            if (last != 0L && wait > 1) delay(wait - 1)
            last = withInfiniteAnimationFrameNanos { it }
            val p = AudioEngine.player.value
            lv.frame = lv.analyzer.update((p?.currentPosition ?: 0L) * 1000L, AudioEngine.isPlaying.value)
            lv.tick++
        }
    }
    return lv
}

private fun SpectrumFrame.isAtRest(): Boolean {
    for (v in post) if (v > 0.004f) return false
    for (v in pre) if (v > 0.004f) return false
    return true
}

/** Live spectrum (after EQ), optional pre-EQ ghost, and a scrolling waveform underneath. */
@Composable
fun LiveSpectrum(
    visual: LiveVisual,
    modifier: Modifier = Modifier,
    showWave: Boolean = true,
    intensity: Float = 1f,
) {
    val cfg = LocalThemeConfig.current
    val pal = LocalPalette.current
    if (visual.fps <= 0) return
    // Own render layer: redrawing the spectrum never forces the rest of the screen (and the
    // glass panels on it) to be redrawn. The frame-rate hint lets the display drop to the
    // visualizer's rate instead of running at 120 Hz just for it.
    Canvas(modifier.graphicsLayer().preferredFrameRate(visual.fps.toFloat())) {
        @Suppress("UNUSED_VARIABLE") val t = visual.tick // read to redraw on every new frame
        val f = visual.frame
        val alpha = cfg.spectrumOpacity * intensity
        if (showWave) drawWave(f, pal.text.copy(alpha = 0.10f * alpha / 0.85f))
        if (cfg.showPreEqGhost) drawSpectrum(f.pre, cfg.spectrumStyle, pal.text.copy(alpha = 0.22f * alpha), ghost = true)
        drawSpectrum(f.post, cfg.spectrumStyle, pal.accent.copy(alpha = alpha), ghost = false)
    }
}

fun DrawScope.drawWave(f: SpectrumFrame, color: Color) {
    val n = SpectrumFrame.WAVE_COLUMNS
    val colW = size.width / n
    val mid = size.height * 0.62f
    val amp = size.height * 0.28f
    for (i in 0 until n) {
        val x = i * colW + colW / 2
        val y0 = mid - f.waveMax[i] * amp
        val y1 = mid - f.waveMin[i] * amp
        // Older columns fade out to the left.
        val fade = 0.25f + 0.75f * (i / n.toFloat())
        drawLine(color.copy(alpha = color.alpha * fade), Offset(x, y0), Offset(x, maxOf(y1, y0 + 1f)), strokeWidth = colW * 0.7f)
    }
}

fun DrawScope.drawSpectrum(levels: FloatArray, style: SpectrumStyle, color: Color, ghost: Boolean) {
    val n = levels.size
    if (n == 0) return
    // Nothing playing: draw nothing (instead of a flat line along the bottom).
    if (levels.all { it < 0.01f }) return
    val w = size.width
    val h = size.height
    when (style) {
        SpectrumStyle.CURVE -> {
            val path = Path()
            fun px(i: Int) = w * i / (n - 1f)
            fun py(i: Int) = h - levels[i] * h * 0.92f
            path.moveTo(px(0), py(0))
            for (i in 1 until n) {
                val mx = (px(i - 1) + px(i)) / 2
                val my = (py(i - 1) + py(i)) / 2
                path.quadraticTo(px(i - 1), py(i - 1), mx, my)
            }
            path.lineTo(px(n - 1), py(n - 1))
            if (ghost) {
                drawPath(path, color, style = Stroke(width = 1.5f))
            } else {
                val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = color.alpha * 0.55f), color.copy(alpha = 0f))))
                drawPath(path, color, style = Stroke(width = 2.5f))
            }
        }
        SpectrumStyle.BARS -> {
            val bw = w / n
            for (i in 0 until n) {
                val bh = levels[i] * h * 0.92f
                if (ghost) {
                    drawLine(color, Offset(i * bw, h - bh), Offset((i + 1) * bw, h - bh), strokeWidth = 2f)
                } else {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(color, color.copy(alpha = color.alpha * 0.25f)), h - bh, h),
                        topLeft = Offset(i * bw + bw * 0.15f, h - bh),
                        size = Size(bw * 0.7f, bh),
                        cornerRadius = CornerRadius(bw * 0.35f),
                    )
                }
            }
        }
        SpectrumStyle.MIRROR -> {
            val bw = w / n
            val mid = h / 2
            for (i in 0 until n) {
                val bh = levels[i] * mid * 0.95f
                if (ghost) {
                    drawLine(color, Offset(i * bw, mid - bh), Offset((i + 1) * bw, mid - bh), strokeWidth = 1.5f)
                    drawLine(color, Offset(i * bw, mid + bh), Offset((i + 1) * bw, mid + bh), strokeWidth = 1.5f)
                } else {
                    drawRoundRect(
                        color,
                        topLeft = Offset(i * bw + bw * 0.15f, mid - bh),
                        size = Size(bw * 0.7f, bh * 2),
                        cornerRadius = CornerRadius(bw * 0.35f),
                    )
                }
            }
        }
    }
}
