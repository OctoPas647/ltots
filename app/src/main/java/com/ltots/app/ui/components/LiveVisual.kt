package com.ltots.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
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
import androidx.media3.common.util.UnstableApi
import com.ltots.app.audio.Analyzer
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.EqMath
import com.ltots.app.audio.SpectrumFrame
import com.ltots.app.data.SpectrumStyle
import com.ltots.app.ui.WhileOnScreen
import com.ltots.app.ui.rememberBatterySaver
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Holds the analyzer and a frame counter that triggers redraws (not recompositions). */
class LiveVisual(val analyzer: Analyzer) {
    var tick by mutableLongStateOf(0L)
    var frame: SpectrumFrame = SpectrumFrame(analyzer.bands)
    /** Frames per second the visual is running at (0 = off). */
    var fps = 30
    /** Reused drawing objects, so drawing a frame allocates nothing. */
    internal val curve = Path()
    internal val fill = Path()
    internal val ghostCurve = Path()
    internal val wave = Path()
    /** True while the EQ leaves the sound unchanged: the "before" equals the "after". */
    var preMirrorsPost = false
}

/**
 * Drives the live spectrum. Battery-conscious by design:
 *  - runs at the chosen rate (120 fps by default, up to the display's refresh);
 *  - sleeps completely while nothing is playing (once the bars have fallen to rest), and
 *    while the phone's audio chip is playing the music directly (there's no signal to show);
 *  - only analyses what is drawn: no waveform where none is shown, and no separate pre-EQ
 *    "ghost" analysis while the EQ leaves the sound unchanged;
 *  - is cancelled completely when the app is in the background or the screen is off;
 *  - drops to 30 fps when the phone's Battery Saver is on.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberLiveVisual(wave: Boolean = true): LiveVisual {
    val settings by AudioEngine.settings.collectAsState()
    val cfg = LocalThemeConfig.current
    val saver by rememberBatterySaver()
    val fps = if (saver) minOf(cfg.visualizerFps, 30) else cfg.visualizerFps
    val lv = remember(cfg.analyzerBands) { LiveVisual(Analyzer(AudioEngine.tap, cfg.analyzerBands)) }
    lv.fps = fps
    val ghost = cfg.showPreEqGhost
    // Everything below runs only while the app is visible. Screen off (or app in the
    // background): the loop is cancelled and the audio thread stops copying audio for it, so
    // nothing to do with the visualizer runs at all — like YouTube Music.
    // The wave comes from a waveform made once per song (like indexing): ask for the playing
    // song's and the next one's while this screen is open; they're ready in a moment.
    val context = androidx.compose.ui.platform.LocalContext.current
    val songUri by AudioEngine.songUri.collectAsState()
    WhileOnScreen(wave, songUri) {
        if (wave && songUri != null) com.ltots.app.audio.WaveIndex.request(context, AudioEngine.upcomingUris())
    }
    WhileOnScreen(lv, fps, ghost, wave) {
        if (fps <= 0) {
            lv.frame = SpectrumFrame(lv.analyzer.bands); lv.tick++
            return@WhileOnScreen
        }
        val release = AudioEngine.tap.watch()
        try {
            runVisual(lv, fps, ghost, wave)
        } finally {
            release()
        }
    }
    return lv
}

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun runVisual(lv: LiveVisual, fps: Int, ghost: Boolean, wave: Boolean): Nothing {
        val intervalNs = 1_000_000_000L / fps
        var last = 0L
        var lastEq: com.ltots.app.audio.EqState? = null
        var eqNeutral = false
        // The pre-made wave of the playing song, if it's ready.
        fun indexed(): com.ltots.app.audio.Wave? =
            if (!wave) null else AudioEngine.songUri.value?.let { com.ltots.app.audio.WaveIndex.peek(it) }
        while (true) {
            val playing = AudioEngine.isPlaying.value
            val live = playing && !AudioEngine.direct.value
            // The pre-made wave also moves while the audio chip plays the song directly.
            val waveMoving = playing && indexed() != null
            if (!live && !waveMoving && lv.frame.isAtRest()) {
                // Nothing moving: no frames at all until music plays again.
                combine(AudioEngine.isPlaying, AudioEngine.direct, com.ltots.app.audio.WaveIndex.ready) { p, d, _ ->
                    p && (!d || indexed() != null)
                }.first { it }
                last = 0L
            }
            // Wait out the rest of this frame's slot without asking for display frames.
            val now = System.nanoTime()
            val wait = (last + intervalNs - now) / 1_000_000L
            if (last != 0L && wait > 1) delay(wait - 1)
            last = withInfiniteAnimationFrameNanos { it }
            val p = AudioEngine.player.value
            val eq = AudioEngine.effectiveEq.value
            if (eq !== lastEq) { lastEq = eq; eqNeutral = EqMath.isNeutral(eq) }
            val pre = ghost && !eqNeutral
            lv.preMirrorsPost = eqNeutral
            val posMs = p?.currentPosition ?: 0L
            val w = indexed()
            lv.frame = lv.analyzer.update(
                posMs * 1000L,
                AudioEngine.isPlaying.value && !AudioEngine.direct.value,
                pre = pre,
                // Only measure the wave live while the song's pre-made one isn't ready yet.
                wave = wave && w == null,
            )
            if (w != null) {
                val f = lv.frame
                w.fill(posMs, (lv.analyzer.waveSeconds * 1000).toLong(), f.waveMin, f.waveMax)
                var peak = 0f
                for (i in f.waveMax.size - 8 until f.waveMax.size) peak = maxOf(peak, -f.waveMin[i], f.waveMax[i])
                f.peak = peak
            }
            lv.tick++
        }
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
    // glass panels on it) to be redrawn. No frame-rate hint: a low one held the whole window
    // (scrolling, animations) at the visualizer's rate.
    Canvas(modifier.graphicsLayer()) {
        @Suppress("UNUSED_VARIABLE") val t = visual.tick // read to redraw on every new frame
        val f = visual.frame
        val alpha = cfg.spectrumOpacity * intensity
        if (showWave) drawWave(f, pal.text.copy(alpha = 0.10f * alpha / 0.85f), visual.wave)
        // The ghost is only worth drawing when the EQ changes something (otherwise it would sit
        // exactly under the main curve).
        if (cfg.showPreEqGhost && !visual.preMirrorsPost) {
            drawSpectrum(f.pre, cfg.spectrumStyle, pal.text.copy(alpha = 0.22f * alpha), ghost = true, visual.ghostCurve, visual.fill)
        }
        drawSpectrum(f.post, cfg.spectrumStyle, pal.accent.copy(alpha = alpha), ghost = false, visual.curve, visual.fill)
    }
}

/**
 * The scrolling waveform: every column is one short vertical stroke, all in a single path drawn
 * once with a left-to-right fade (instead of one draw call per column).
 */
fun DrawScope.drawWave(f: SpectrumFrame, color: Color, path: Path = Path()) {
    val n = SpectrumFrame.WAVE_COLUMNS
    val colW = size.width / n
    val mid = size.height * 0.62f
    val amp = size.height * 0.28f
    path.reset()
    for (i in 0 until n) {
        val x = i * colW + colW / 2
        val y0 = mid - f.waveMax[i] * amp
        val y1 = mid - f.waveMin[i] * amp
        path.moveTo(x, y0)
        path.lineTo(x, maxOf(y1, y0 + 1f))
    }
    // Older columns fade out to the left.
    val brush = Brush.horizontalGradient(
        0f to color.copy(alpha = color.alpha * 0.25f),
        1f to color,
        startX = colW / 2, endX = size.width - colW / 2,
    )
    drawPath(path, brush, style = Stroke(width = colW * 0.7f))
}

fun DrawScope.drawSpectrum(
    levels: FloatArray,
    style: SpectrumStyle,
    color: Color,
    ghost: Boolean,
    path: Path = Path(),
    fill: Path = Path(),
) {
    val n = levels.size
    if (n == 0) return
    // Nothing playing: draw nothing (instead of a flat line along the bottom).
    if (levels.all { it < 0.01f }) return
    val w = size.width
    val h = size.height
    when (style) {
        SpectrumStyle.CURVE -> {
            path.reset()
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
                fill.reset()
                fill.addPath(path); fill.lineTo(w, h); fill.lineTo(0f, h); fill.close()
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
