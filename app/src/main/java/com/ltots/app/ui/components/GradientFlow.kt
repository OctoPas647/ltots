package com.ltots.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Gradient flow: light round the cover in the colours of its edges, like a TV's backlight on the
 * wall behind it. Every spot along each edge takes the average colour of the cover's outermost
 * [GradientFlow.DEPTH_PX] pixels there (going inwards), and shines outwards from that spot. On
 * AMOLED it melts into your own colour further out; on the other themes it fades into the
 * background.
 */
object GradientFlow {
    /** Spots along each side of the cover. */
    const val ZONES = 16
    /** How deep into the cover each spot's colour is measured, in the cover's own pixels. */
    const val DEPTH_PX = 10
    /** How far the light reaches beyond the cover, as a share of the cover's side. */
    const val REACH = 0.6f
    /** Size of the light map (it's drawn scaled up, smoothly). */
    private const val MAP = 96

    /** Average colours along the four edges, clockwise from the top-left corner. */
    class Edges(val top: IntArray, val right: IntArray, val bottom: IntArray, val left: IntArray)

    private fun toLinear(c: Int): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f) }
    private fun toSrgb(l: Float): Int {
        val v = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
        return (v * 255f).roundToInt().coerceIn(0, 255)
    }

    /**
     * The edges' colours of [bmp]: for each of [ZONES] spots per side, the average (in linear
     * light, as the eye mixes colours) of the strip [DEPTH_PX] pixels deep under that spot.
     */
    fun edges(bmp: Bitmap): Edges {
        val src = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val w = src.width; val h = src.height
        val depth = DEPTH_PX.coerceAtMost(maxOf(1, minOf(w, h) / 8))
        val rows = IntArray(w * depth)
        val cols = IntArray(h * depth)
        fun zoneAvg(px: IntArray, stride: Int, lines: Int, from: Int, to: Int): Int {
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            for (l in 0 until lines) for (i in from until to) {
                val p = px[l * stride + i]
                if ((p ushr 24) < 16) continue
                r += toLinear((p shr 16) and 0xFF); g += toLinear((p shr 8) and 0xFF); b += toLinear(p and 0xFF); n++
            }
            if (n == 0) return 0
            return (0xFF shl 24) or (toSrgb(r / n) shl 16) or (toSrgb(g / n) shl 8) or toSrgb(b / n)
        }
        fun side(px: IntArray, stride: Int) = IntArray(ZONES) { z ->
            val from = stride * z / ZONES
            val to = maxOf(from + 1, stride * (z + 1) / ZONES)
            zoneAvg(px, stride, depth, from, to)
        }
        // Top and bottom: rows straight from the bitmap. Left and right: columns, laid out as rows.
        src.getPixels(rows, 0, w, 0, 0, w, depth)
        val top = side(rows, w)
        src.getPixels(rows, 0, w, 0, h - depth, w, depth)
        val bottom = side(rows, w)
        val colBuf = IntArray(depth * h)
        fun columns(x0: Int): IntArray {
            src.getPixels(colBuf, 0, depth, x0, 0, depth, h)
            // colBuf is h rows of [depth]; transpose to [depth] rows of h.
            for (y in 0 until h) for (d in 0 until depth) cols[d * h + y] = colBuf[y * depth + d]
            return side(cols, h)
        }
        val left = columns(0)
        val right = columns(w - depth)
        if (src !== bmp) src.recycle()
        return Edges(top, right, bottom, left)
    }

    /**
     * The light round the cover as a small picture: the cover sits in the middle third-ish
     * (its side is 1 / (1 + 2·[REACH]) of the map), the light spreads out from its edges.
     * [into]: your colour to melt into (AMOLED), or null to fade out into the background.
     */
    fun lightMap(e: Edges, into: Int?): Bitmap {
        val n = MAP
        val a = n * REACH / (1 + 2 * REACH) // cover's top-left, in map pixels
        val b = n - a                         // cover's bottom-right
        val side = b - a
        val reach = a
        // Each side's spots, softened a little into their neighbours along the outline.
        val ring = (e.top.toList() + e.right.toList() + e.bottom.reversed() + e.left.reversed()).toIntArray()
        val soft = IntArray(ring.size) { i ->
            val p = ring[(i - 1 + ring.size) % ring.size]; val c = ring[i]; val q = ring[(i + 1) % ring.size]
            mix(mix(p, q, 0.5f), c, 0.5f)
        }
        // Colour at distance [u] (0..4·side) along the outline, clockwise from the top-left corner.
        fun along(u: Float): Int {
            val per = side / ZONES
            val f = (u / per - 0.5f).let { ((it % ring.size) + ring.size) % ring.size }
            val i = f.toInt() % ring.size
            return mix(soft[i], soft[(i + 1) % ring.size], f - f.toInt())
        }
        val out = IntArray(n * n)
        val accent = into
        for (y in 0 until n) for (x in 0 until n) {
            val px = x + 0.5f; val py = y + 0.5f
            var cx = px.coerceIn(a, b); var cy = py.coerceIn(a, b)
            val dx = px - cx; val dy = py - cy
            val t = (sqrt(dx * dx + dy * dy) / reach).coerceIn(0f, 1f)
            if (cx == px && cy == py) {
                // Under the cover: the nearest edge's light, so the map has no seam at the edge.
                val m = minOf(py - a, b - py, px - a, b - px)
                when (m) {
                    py - a -> cy = a
                    b - px -> cx = b
                    b - py -> cy = b
                    else -> cx = a
                }
            }
            // Where on the outline this light comes from.
            val u = when {
                cy <= a && cx < b -> cx - a                       // top, going right
                cx >= b && cy < b -> side + (cy - a)              // right, going down
                cy >= b && cx > a -> 2 * side + (b - cx)          // bottom, going left
                else -> 3 * side + (b - cy)                       // left, going up
            }
            val edge = along(u)
            val color: Int; val alpha: Float
            if (accent != null) {
                // AMOLED: the edge's colour near the cover, your colour further out, then black.
                color = mix(edge, accent, smoothstep(0.1f, 0.85f, t))
                alpha = 0.8f * (1f - smoothstep(0.45f, 1f, t))
            } else {
                color = edge
                alpha = 0.85f * (1f - t).pow(1.6f)
            }
            out[y * n + x] = (((alpha * 255f).roundToInt().coerceIn(0, 255)) shl 24) or (color and 0xFFFFFF)
        }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun mix(c0: Int, c1: Int, t: Float): Int {
        fun ch(s: Int) = (((c0 shr s) and 0xFF) * (1 - t) + ((c1 shr s) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Draws the Gradient flow light for [artwork] round a cover of side [coverFraction] of this
 * area's width, centred in it (the light reaches past the area's edges). [into]: your colour on
 * AMOLED, else null.
 *
 * [reachDown]: how far the light reaches below the cover compared with its other sides (the part
 * of the light under the cover is stretched downwards). [scale]: the cover's size right now (it
 * moves with the beat), read while drawing. On the Playing tab the light also fades into black
 * at the top of the screen ([TopFade]): a still mask fixed to the screen, not to the light, so
 * the light pumps under it while the fade stays where it is.
 */
@Composable
fun GradientFlowLight(
    artwork: Bitmap?,
    coverFraction: Float,
    into: Int?,
    modifier: Modifier = Modifier,
    reachDown: Float = 1f,
    scale: () -> Float = { 1f },
) {
    val map by produceState<Bitmap?>(null, artwork, into) {
        value = if (artwork == null) null else withContext(Dispatchers.Default) {
            runCatching { GradientFlow.lightMap(GradientFlow.edges(artwork), into) }.getOrNull()
        }
    }
    val img = map?.asImageBitmap() ?: return
    val cfg = com.ltots.app.ui.theme.LocalThemeConfig.current
    val fade = com.ltots.app.ui.TopFade.on(cfg)
    val screenHeight = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toFloat()
    // Where this area is on the screen (it scrolls with the page), read while drawing.
    val windowY = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // The map row at the cover's bottom edge: everything below it is stretched by [reachDown].
    val n = img.height
    val splitRow = (n * (1 + GradientFlow.REACH) / (1 + 2 * GradientFlow.REACH)).roundToInt().coerceIn(1, n - 1)
    Canvas(modifier.then(if (fade) Modifier.onGloballyPositioned { windowY.floatValue = it.positionInWindow().y } else Modifier)) {
        val s = scale()
        val cover = size.width * coverFraction * s
        val reach = cover * GradientFlow.REACH
        val c = Offset(size.width / 2, size.height / 2)
        val left = c.x - cover / 2 - reach
        val right = c.x + cover / 2 + reach
        val top = c.y - cover / 2 - reach
        val coverBottom = c.y + cover / 2
        val bottom = coverBottom + reach * reachDown.coerceAtLeast(1f)
        val w = (right - left).roundToInt()
        fun light() {
            // Above and beside the cover, and under it, as is.
            drawImage(
                img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, splitRow),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(w, (coverBottom - top).roundToInt()),
                filterQuality = FilterQuality.High,
            )
            // Below the cover, stretched to reach further down.
            drawImage(
                img, srcOffset = IntOffset(0, splitRow), srcSize = IntSize(img.width, n - splitRow),
                dstOffset = IntOffset(left.roundToInt(), coverBottom.roundToInt()),
                dstSize = IntSize(w, (bottom - coverBottom).roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
        if (!fade) { light(); return@Canvas }
        // Drawn on its own layer (as big as the light), then the fade cuts it away at the top.
        val bounds = androidx.compose.ui.geometry.Rect(left, top, right, bottom)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, Paint())
            light()
            drawRect(
                com.ltots.app.ui.TopFade.mask(cfg, -windowY.floatValue, if (screenHeight > 0f) screenHeight else size.height * 2f),
                topLeft = bounds.topLeft, size = bounds.size, blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }
}
