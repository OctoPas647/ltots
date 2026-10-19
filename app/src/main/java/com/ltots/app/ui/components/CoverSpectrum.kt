package com.ltots.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ltots.app.data.SpectrumStyle
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Points all the way around a rounded square (the cover's shape, pushed out by a small gap), each
 * with the direction pointing straight out of the shape. Worked out once per size; the live
 * spectrum is then just "how far out from each point".
 *
 * The spectrum is mirrored left/right: the bass sits at the bottom centre, the treble at the top
 * centre, so the ring is symmetrical like the cover.
 */
class CoverRing(
    /** Size of the drawing area (square). */
    val size: Float,
    /** Side of the cover, centred in the area. */
    val coverSide: Float,
    val cornerRadius: Float,
    val gap: Float,
    /** Number of points around the ring. */
    val count: Int,
) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    val nx = FloatArray(count)
    val ny = FloatArray(count)
    /** Spacing between neighbouring points along the ring. */
    val spacing: Float
    /** The longest a bar can be before it leaves the drawing area. */
    val maxLength: Float

    init {
        val c = size / 2
        val a = coverSide / 2 + gap // half side of the ring's square
        val r = (cornerRadius + gap).coerceAtMost(a)
        val s = 2 * a - 2 * r // straight part of each side
        val arc = (PI * r / 2).toFloat()
        val perimeter = 4 * s + 4 * arc
        spacing = perimeter / count
        maxLength = max(0f, c - a - size * 0.02f)
        for (k in 0 until count) {
            // Start at the top centre and go clockwise.
            var t = (k + 0.5f) * spacing
            val seg = floatArrayOf(s / 2, arc, s, arc, s, arc, s, arc, s / 2)
            var i = 0
            while (i < seg.size - 1 && t > seg[i]) { t -= seg[i]; i++ }
            var px: Float; var py: Float; var ox: Float; var oy: Float
            when (i) {
                0 -> { px = c + t; py = c - a; ox = 0f; oy = -1f }
                2 -> { px = c + a; py = c - a + r + t; ox = 1f; oy = 0f }
                4 -> { px = c + a - r - t; py = c + a; ox = 0f; oy = 1f }
                6 -> { px = c - a; py = c + a - r - t; ox = -1f; oy = 0f }
                8 -> { px = c - s / 2 + t; py = c - a; ox = 0f; oy = -1f }
                else -> {
                    // Corner arcs: top-right, bottom-right, bottom-left, top-left.
                    val corner = (i - 1) / 2
                    val (cx, cy) = when (corner) {
                        0 -> (c + a - r) to (c - a + r)
                        1 -> (c + a - r) to (c + a - r)
                        2 -> (c - a + r) to (c + a - r)
                        else -> (c - a + r) to (c - a + r)
                    }
                    val start = (-PI / 2 + corner * PI / 2).toFloat()
                    val ang = start + if (r > 0f) t / r else 0f
                    ox = cos(ang); oy = sin(ang)
                    px = cx + r * ox; py = cy + r * oy
                }
            }
            x[k] = px; y[k] = py; nx[k] = ox; ny[k] = oy
        }
    }

    /** The spectrum level for ring point [k]: bass at the bottom, treble at the top, mirrored. */
    fun levelAt(k: Int, levels: FloatArray): Float {
        val n = levels.size
        if (n == 0) return 0f
        val u = (k + 0.5f) / count
        val d = if (u <= 0.5f) u * 2 else (1 - u) * 2 // 0 at the top, 1 at the bottom
        val f = (1 - d) * (n - 1)
        val i = f.toInt().coerceIn(0, n - 1)
        val j = min(i + 1, n - 1)
        val w = f - i
        return levels[i] * (1 - w) + levels[j] * w
    }

    fun matches(size: Float, coverSide: Float, cornerRadius: Float, gap: Float, count: Int) =
        this.size == size && this.coverSide == coverSide && this.cornerRadius == cornerRadius && this.gap == gap && this.count == count
}

/**
 * The live spectrum wrapped around the song's cover. Same visualizer as before (same analysis,
 * frame rate, style, strength and "before EQ" outline from the Look tab), drawn as a ring.
 *
 * @param coverFraction the cover's side as a share of this area's width.
 */
@Composable
fun CoverSpectrum(
    visual: LiveVisual,
    coverFraction: Float,
    coverCorner: Dp,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
) {
    val cfg = LocalThemeConfig.current
    val pal = LocalPalette.current
    if (visual.fps <= 0) return
    Canvas(modifier.graphicsLayer()) {
        @Suppress("UNUSED_VARIABLE") val t = visual.tick // redraw on every new frame
        val f = visual.frame
        val side = min(size.width, size.height)
        val coverSide = size.width * coverFraction
        val corner = coverCorner.toPx()
        val gap = 8.dp.toPx()
        // Two points per spectrum band (one each side), so every bar of the spectrum shows.
        val count = (f.post.size * 2).coerceIn(48, 360)
        val ring = visual.ring?.takeIf { it.matches(side, coverSide, corner, gap, count) }
            ?: CoverRing(side, coverSide, corner, gap, count).also { visual.ring = it }
        val alpha = cfg.spectrumOpacity * intensity
        if (cfg.showPreEqGhost && !visual.preMirrorsPost) {
            drawRing(ring, f.pre, SpectrumStyle.CURVE, pal.text.copy(alpha = 0.25f * alpha), ghost = true, visual.ghostCurve)
        }
        drawRing(ring, f.post, cfg.spectrumStyle, pal.accent.copy(alpha = alpha), ghost = false, visual.curve)
    }
}

/** Draws one spectrum around [ring] in the chosen style. */
fun DrawScope.drawRing(ring: CoverRing, levels: FloatArray, style: SpectrumStyle, color: Color, ghost: Boolean, path: Path) {
    if (levels.isEmpty() || levels.all { it < 0.01f }) return
    val c = Offset(size.width / 2, size.height / 2)
    val inner = ring.coverSide / 2 + ring.gap
    val outer = inner + ring.maxLength
    // Brightest at the cover, fading outwards.
    val brush = Brush.radialGradient(
        0f to color, (inner / outer).coerceIn(0f, 1f) to color, 1f to color.copy(alpha = color.alpha * 0.25f),
        center = c, radius = outer * 1.42f,
    )
    val n = ring.count
    path.reset()
    if (ghost || style == SpectrumStyle.CURVE) {
        // A smooth closed outline, "level" away from the cover all the way round.
        fun qx(k: Int) = ring.x[k] + ring.nx[k] * ring.levelAt(k, levels) * ring.maxLength
        fun qy(k: Int) = ring.y[k] + ring.ny[k] * ring.levelAt(k, levels) * ring.maxLength
        path.moveTo((qx(n - 1) + qx(0)) / 2, (qy(n - 1) + qy(0)) / 2)
        for (k in 0 until n) {
            val nk = (k + 1) % n
            path.quadraticTo(qx(k), qy(k), (qx(k) + qx(nk)) / 2, (qy(k) + qy(nk)) / 2)
        }
        path.close()
        if (ghost) {
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        } else {
            drawPath(path, Brush.radialGradient(
                0f to color.copy(alpha = color.alpha * 0.4f), (inner / outer).coerceIn(0f, 1f) to color.copy(alpha = color.alpha * 0.32f),
                1f to color.copy(alpha = 0f), center = c, radius = outer * 1.42f,
            ))
            drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
        }
        return
    }
    // Bars: one stroke per point, straight out from the cover. Mirror: centred on a ring halfway
    // out, so they pulse both ways.
    val mid = style == SpectrumStyle.MIRROR
    for (k in 0 until n) {
        val len = max(ring.levelAt(k, levels) * ring.maxLength, 1f)
        val from = if (mid) ring.maxLength / 2 - len / 2 else 0f
        val x0 = ring.x[k] + ring.nx[k] * from
        val y0 = ring.y[k] + ring.ny[k] * from
        path.moveTo(x0, y0)
        path.lineTo(x0 + ring.nx[k] * len, y0 + ring.ny[k] * len)
    }
    val width = (ring.spacing * 0.55f).coerceIn(1.5f, 6.dp.toPx())
    drawPath(path, brush, style = Stroke(width = width, cap = StrokeCap.Round))
}
