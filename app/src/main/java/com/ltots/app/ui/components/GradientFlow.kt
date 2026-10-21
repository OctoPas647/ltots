package com.ltots.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
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
 * Draws the Gradient flow light for [artwork] round a cover of side [coverSidePx] centred in this
 * area (the light reaches past the area's edges). [into]: your colour on AMOLED, else null.
 */
@Composable
fun GradientFlowLight(artwork: Bitmap?, coverFraction: Float, into: Int?, modifier: Modifier = Modifier) {
    val map by produceState<Bitmap?>(null, artwork, into) {
        value = if (artwork == null) null else withContext(Dispatchers.Default) {
            runCatching { GradientFlow.lightMap(GradientFlow.edges(artwork), into) }.getOrNull()
        }
    }
    val img = map?.asImageBitmap() ?: return
    Canvas(modifier) {
        val cover = size.width * coverFraction
        val full = cover * (1 + 2 * GradientFlow.REACH)
        val c = Offset(size.width / 2, size.height / 2)
        drawImage(
            img,
            srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset((c.x - full / 2).roundToInt(), (c.y - full / 2).roundToInt()),
            dstSize = IntSize(full.roundToInt(), full.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}
