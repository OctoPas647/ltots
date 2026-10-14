package com.ltots.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.media3.common.util.UnstableApi
import com.ltots.app.audio.Analyzer
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.SpectrumFrame
import com.ltots.app.data.SpectrumStyle
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig

/** Holds the analyzer and a frame counter that triggers redraws (not recompositions). */
class LiveVisual(val analyzer: Analyzer) {
    var tick by mutableLongStateOf(0L)
    var frame: SpectrumFrame = SpectrumFrame(analyzer.bands)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberLiveVisual(): LiveVisual {
    val settings by AudioEngine.settings.collectAsState()
    val lv = remember(settings.analyzerBands) { LiveVisual(Analyzer(AudioEngine.tap, settings.analyzerBands)) }
    LaunchedEffect(lv) {
        while (true) {
            withFrameNanos { }
            val p = AudioEngine.player.value
            lv.frame = lv.analyzer.update((p?.currentPosition ?: 0L) * 1000L, p?.isPlaying == true)
            lv.tick++
        }
    }
    return lv
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
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val t = visual.tick // read to redraw every frame
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
