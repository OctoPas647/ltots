package com.ltots.app.ui.components

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A picture made ready for icon mode, once per picture: how light ([lum]) and how opaque
 * ([alpha]) every pixel is, scaled up smoothly so the long side is at least [IconMask.MIN_SIDE]
 * (small icons would otherwise give jagged outlines).
 */
class IconSource(
    val width: Int,
    val height: Int,
    val lum: FloatArray,
    val alpha: FloatArray,
    /** Working pixels per pixel of the original picture. */
    val scale: Float,
)

/**
 * The icon as black and white: [dark] is true for the parts the spectrum grows out of. Cropped to
 * the dark parts' bounding box.
 */
class IconMask(
    val width: Int,
    val height: Int,
    val dark: BooleanArray,
) {
    val isEmpty get() = width == 0 || height == 0

    /** Dark at continuous mask coordinates (pixel i covers i..i+1); outside the mask is white. */
    fun darkAt(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f) return false
        val i = x.toInt(); val j = y.toInt()
        if (i >= width || j >= height) return false
        return dark[j * width + i]
    }

    companion object {
        /** The working size's long side is at least this (smaller pictures are scaled up). */
        const val MIN_SIDE = 1024
        /** …and at most this (big photos are scaled down: plenty of detail, less work). */
        const val MAX_SIDE = 1536
        /** Dark specks smaller than this many pixels of the original picture are dropped (JPEG noise). */
        const val SPECK_PX = 20
        /** Default smallest white hole that is kept (share of the picture's area). */
        const val DEFAULT_MIN_HOLE = 0.001f

        /** Reads a decoded picture. Any [Bitmap] works (an SVG can be rasterised first). */
        fun prepare(bitmap: Bitmap): IconSource {
            val w = bitmap.width; val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            return prepare(px, w, h)
        }

        /**
         * Brightness and opacity of [argb] (w×h pixels), resampled smoothly (bilinear) to the
         * working size.
         */
        fun prepare(argb: IntArray, w: Int, h: Int): IconSource {
            require(w > 0 && h > 0 && argb.size >= w * h)
            val lum = FloatArray(w * h)
            val alpha = FloatArray(w * h)
            for (i in 0 until w * h) {
                val p = argb[i]
                alpha[i] = (p ushr 24) / 255f
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                lum[i] = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            }
            val long = max(w, h)
            val target = long.coerceIn(MIN_SIDE, MAX_SIDE)
            val scale = target.toFloat() / long
            if (target == long) return IconSource(w, h, lum, alpha, 1f)
            val nw = max(1, (w * scale).roundToInt()); val nh = max(1, (h * scale).roundToInt())
            return IconSource(nw, nh, resample(lum, w, h, nw, nh), resample(alpha, w, h, nw, nh), scale)
        }

        /** Bilinear resampling (smooth, so the threshold gives smooth edges). */
        internal fun resample(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
            val out = FloatArray(nw * nh)
            val sx = w.toFloat() / nw; val sy = h.toFloat() / nh
            for (j in 0 until nh) {
                val fy = ((j + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
                val y0 = fy.toInt(); val y1 = min(y0 + 1, h - 1); val wy = fy - y0
                for (i in 0 until nw) {
                    val fx = ((i + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                    val x0 = fx.toInt(); val x1 = min(x0 + 1, w - 1); val wx = fx - x0
                    val top = src[y0 * w + x0] * (1 - wx) + src[y0 * w + x1] * wx
                    val bot = src[y1 * w + x0] * (1 - wx) + src[y1 * w + x1] * wx
                    out[j * nw + i] = top * (1 - wy) + bot * wy
                }
            }
            return out
        }

        /**
         * Black and white: a pixel is dark when its brightness is below [threshold] (0..1).
         * Transparent counts as white, also when [invert]ed (inverting swaps the colours, not the
         * see-through background).
         */
        fun threshold(src: IconSource, threshold: Float, invert: Boolean): BooleanArray {
            val n = src.width * src.height
            val dark = BooleanArray(n)
            for (i in 0 until n) {
                val a = src.alpha[i]
                val l = if (invert) 1f - src.lum[i] else src.lum[i]
                // Composited over white: half-transparent edges count as half white.
                val v = l * a + (1f - a)
                dark[i] = v < threshold
            }
            return dark
        }

        /** Removes dark pieces (8-connected) smaller than [minArea] pixels. */
        fun removeSpecks(dark: BooleanArray, w: Int, h: Int, minArea: Int) {
            if (minArea <= 1) return
            components(dark, w, h, value = true, eight = true) { pixels, size, _ ->
                if (size < minArea) for (i in 0 until size) dark[pixels[i]] = false
            }
        }

        /**
         * Fills enclosed white holes (4-connected, not touching the edge) smaller than [minArea]
         * pixels: they count as solid dark, so they get no outline and no bars.
         */
        fun fillHoles(dark: BooleanArray, w: Int, h: Int, minArea: Int) {
            if (minArea <= 0) return
            components(dark, w, h, value = false, eight = false) { pixels, size, touchesEdge ->
                if (!touchesEdge && size < minArea) for (i in 0 until size) dark[pixels[i]] = true
            }
        }

        /**
         * The whole step from a prepared picture to the cropped mask: threshold, invert, drop
         * specks, fill small holes.
         *
         * @param minHole smallest kept hole, as a share of the picture's area.
         */
        fun build(src: IconSource, threshold: Float, invert: Boolean, minHole: Float = DEFAULT_MIN_HOLE): IconMask {
            val w = src.width; val h = src.height
            val dark = threshold(src, threshold, invert)
            removeSpecks(dark, w, h, (SPECK_PX * src.scale * src.scale).roundToInt())
            fillHoles(dark, w, h, (minHole * w * h).roundToInt())
            return crop(dark, w, h)
        }

        /** Crops to the dark parts (an empty mask when nothing is dark). */
        fun crop(dark: BooleanArray, w: Int, h: Int): IconMask {
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (j in 0 until h) for (i in 0 until w) if (dark[j * w + i]) {
                if (i < x0) x0 = i; if (i > x1) x1 = i
                if (j < y0) y0 = j; if (j > y1) y1 = j
            }
            if (x1 < 0) return IconMask(0, 0, BooleanArray(0))
            val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
            val out = BooleanArray(cw * ch)
            for (j in 0 until ch) System.arraycopy(dark, (y0 + j) * w + x0, out, j * cw, cw)
            return IconMask(cw, ch, out)
        }

        /**
         * Visits every connected group of pixels equal to [value]: [onGroup] gets the group's pixel
         * indices (valid for [size] entries) and whether it touches the edge of the picture.
         */
        private inline fun components(
            px: BooleanArray, w: Int, h: Int, value: Boolean, eight: Boolean,
            onGroup: (pixels: IntArray, size: Int, touchesEdge: Boolean) -> Unit,
        ) {
            val seen = BooleanArray(w * h)
            val stack = IntArray(w * h)
            val group = IntArray(w * h)
            for (start in 0 until w * h) {
                if (seen[start] || px[start] != value) continue
                var sp = 0; var size = 0; var edge = false
                stack[sp++] = start; seen[start] = true
                while (sp > 0) {
                    val p = stack[--sp]
                    group[size++] = p
                    val x = p % w; val y = p / w
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) edge = true
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (!eight && dx != 0 && dy != 0) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val q = ny * w + nx
                        if (!seen[q] && px[q] == value) { seen[q] = true; stack[sp++] = q }
                    }
                }
                onGroup(group, size, edge)
            }
        }
    }
}

/**
 * The closed outlines of an [IconMask]'s dark parts, in mask coordinates, longest first. Traced
 * once (marching squares on a lightly softened mask, then smoothed), so they follow the shape
 * smoothly instead of in pixel steps.
 */
class IconContours(
    val maskWidth: Int,
    val maskHeight: Int,
    /** Each outline as x0, y0, x1, y1, … (closed: the last point joins the first). */
    val loops: List<FloatArray>,
    val lengths: FloatArray,
) {
    val totalLength: Float get() = lengths.sum()

    companion object {
        private const val PAD = 3
        /** Outlines shorter than this (mask pixels) are leftovers of the softening, not shapes. */
        private const val MIN_LENGTH = 8f

        fun trace(mask: IconMask): IconContours {
            if (mask.isEmpty) return IconContours(0, 0, emptyList(), FloatArray(0))
            val fw = mask.width + 2 * PAD; val fh = mask.height + 2 * PAD
            val f = FloatArray(fw * fh)
            for (j in 0 until mask.height) for (i in 0 until mask.width) {
                if (mask.dark[j * mask.width + i]) f[(j + PAD) * fw + i + PAD] = 1f
            }
            // Soften a little (two box passes ≈ a small Gaussian): the outline then runs smoothly
            // between pixels instead of along their edges.
            boxBlur(f, fw, fh)
            boxBlur(f, fw, fh)
            val raw = marchingSquares(f, fw, fh)
            val loops = ArrayList<FloatArray>()
            for (pts in raw) {
                // Field coordinates → mask coordinates (pixel i covers i..i+1).
                for (k in pts.indices) pts[k] = pts[k] - PAD + 0.5f
                smooth(pts, passes = 2)
                if (length(pts) >= MIN_LENGTH) loops += pts
            }
            loops.sortByDescending { length(it) }
            return IconContours(mask.width, mask.height, loops, FloatArray(loops.size) { length(loops[it]) })
        }

        fun length(pts: FloatArray): Float {
            val n = pts.size / 2
            var s = 0f
            for (k in 0 until n) {
                val j = (k + 1) % n
                val dx = pts[2 * j] - pts[2 * k]; val dy = pts[2 * j + 1] - pts[2 * k + 1]
                s += sqrt(dx * dx + dy * dy)
            }
            return s
        }

        /** [1, 2, 1] / 4 along the closed outline, [passes] times. */
        internal fun smooth(pts: FloatArray, passes: Int) {
            val n = pts.size / 2
            if (n < 3) return
            val tmp = FloatArray(pts.size)
            repeat(passes) {
                for (k in 0 until n) {
                    val a = (k + n - 1) % n; val b = (k + 1) % n
                    tmp[2 * k] = (pts[2 * a] + 2 * pts[2 * k] + pts[2 * b]) / 4
                    tmp[2 * k + 1] = (pts[2 * a + 1] + 2 * pts[2 * k + 1] + pts[2 * b + 1]) / 4
                }
                System.arraycopy(tmp, 0, pts, 0, pts.size)
            }
        }

        private fun boxBlur(f: FloatArray, w: Int, h: Int) {
            val tmp = FloatArray(f.size)
            for (j in 0 until h) for (i in 0 until w) {
                var s = 0f
                for (d in -1..1) s += f[j * w + (i + d).coerceIn(0, w - 1)]
                tmp[j * w + i] = s / 3
            }
            for (j in 0 until h) for (i in 0 until w) {
                var s = 0f
                for (d in -1..1) s += tmp[(j + d).coerceIn(0, h - 1) * w + i]
                f[j * w + i] = s / 3
            }
        }

        /**
         * The 0.5 lines of [f] (w×h, zero all round the edge) as closed loops, x/y interleaved, in
         * field coordinates (sample (i, j) at i, j). Saddles are decided by the cell's centre value.
         */
        private fun marchingSquares(f: FloatArray, w: Int, h: Int): List<FloatArray> {
            fun hId(x: Int, y: Int) = (y * w + x) * 2
            fun vId(x: Int, y: Int) = (y * w + x) * 2 + 1
            val segA = IntList(); val segB = IntList()
            for (cy in 0 until h - 1) for (cx in 0 until w - 1) {
                val tl = f[cy * w + cx]; val tr = f[cy * w + cx + 1]
                val br = f[(cy + 1) * w + cx + 1]; val bl = f[(cy + 1) * w + cx]
                val a = tl >= 0.5f; val b = tr >= 0.5f; val c = br >= 0.5f; val d = bl >= 0.5f
                if (a == b && b == c && c == d) continue
                val top = hId(cx, cy); val right = vId(cx + 1, cy); val bottom = hId(cx, cy + 1); val left = vId(cx, cy)
                if (a == c && b == d && a != b) {
                    // Saddle: two separate lines through this cell.
                    val centre = (tl + tr + br + bl) / 4 >= 0.5f
                    if (a == centre) {
                        segA.add(top); segB.add(right); segA.add(bottom); segB.add(left)
                    } else {
                        segA.add(left); segB.add(top); segA.add(right); segB.add(bottom)
                    }
                    continue
                }
                var e1 = -1; var e2 = -1
                fun cross(e: Int) { if (e1 < 0) e1 = e else e2 = e }
                if (a != b) cross(top)
                if (b != c) cross(right)
                if (d != c) cross(bottom)
                if (a != d) cross(left)
                segA.add(e1); segB.add(e2)
            }
            // Every crossing point is shared by exactly two segments: link them into loops.
            val n = segA.size
            val first = HashMap<Int, Int>(n * 2)
            val second = HashMap<Int, Int>(n * 2)
            for (s in 0 until n) for (e in intArrayOf(segA[s], segB[s])) {
                if (first.containsKey(e)) second[e] = s else first[e] = s
            }
            fun point(e: Int, out: FloatList) {
                val idx = e / 2; val x = idx % w; val y = idx / w
                val va = f[idx]
                if (e % 2 == 0) {
                    val vb = f[idx + 1]
                    out.add(x + (0.5f - va) / (vb - va)); out.add(y.toFloat())
                } else {
                    val vb = f[idx + w]
                    out.add(x.toFloat()); out.add(y + (0.5f - va) / (vb - va))
                }
            }
            val used = BooleanArray(n)
            val loops = ArrayList<FloatArray>()
            for (s0 in 0 until n) {
                if (used[s0]) continue
                used[s0] = true
                val pts = FloatList()
                val startEdge = segA[s0]
                point(startEdge, pts)
                var cur = s0
                var at = segB[s0]
                var guard = 0
                while (at != startEdge && guard++ < n) {
                    point(at, pts)
                    val next = if (first[at] == cur) second[at] else first[at]
                    if (next == null || used[next]) break
                    used[next] = true
                    at = if (segA[next] == at) segB[next] else segA[next]
                    cur = next
                }
                if (pts.size >= 6) loops += pts.toArray()
            }
            return loops
        }
    }
}

/**
 * Distances, for limiting the bars: from every point of the white space to the nearest dark part
 * ([outside], over the mask plus [pad] pixels of white all round, so bars reaching past the
 * icon's box are covered too), and from every dark point to the nearest white ([inside]).
 * Exact Euclidean distance transforms, made once per mask.
 */
class IconField(val mask: IconMask, val pad: Int) {
    private val w = mask.width + 2 * pad
    private val h = mask.height + 2 * pad
    private val toDark: FloatArray
    private val toWhite: FloatArray

    init {
        toDark = FloatArray(w * h) { INF }
        for (j in 0 until mask.height) for (i in 0 until mask.width) {
            if (mask.dark[j * mask.width + i]) toDark[(j + pad) * w + i + pad] = 0f
        }
        edt(toDark, w, h)
        val mw = mask.width; val mh = mask.height
        // Outside the mask is white, so pad by one white pixel for the inside distances.
        val iw = mw + 2; val ih = mh + 2
        val t = FloatArray(iw * ih)
        for (j in 0 until mh) for (i in 0 until mw) if (mask.dark[j * mw + i]) t[(j + 1) * iw + i + 1] = INF
        edt(t, iw, ih)
        toWhite = FloatArray(mw * mh) { k -> t[(k / mw + 1) * iw + k % mw + 1] }
    }

    /** Distance from (x, y) (mask coordinates) to the nearest dark edge; large beyond the field. */
    fun outside(x: Float, y: Float): Float = sample(toDark, w, h, x - 0.5f + pad, y - 0.5f + pad) - 0.5f

    /** Distance from (x, y) to the nearest white edge (0 outside the dark parts). */
    fun inside(x: Float, y: Float): Float = sample(toWhite, mask.width, mask.height, x - 0.5f, y - 0.5f) - 0.5f

    private fun sample(f: FloatArray, w: Int, h: Int, fx: Float, fy: Float): Float {
        if (fx < 0f || fy < 0f || fx > w - 1f || fy > h - 1f) return if (f === toDark) FAR else 0.5f
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val ax = fx - x0; val ay = fy - y0
        val top = f[y0 * w + x0] * (1 - ax) + f[y0 * w + x1] * ax
        val bot = f[y1 * w + x0] * (1 - ax) + f[y1 * w + x1] * ax
        return top * (1 - ay) + bot * ay
    }

    companion object {
        private const val INF = 1e10f
        private const val FAR = 1e6f

        private fun parabolaCut(f: FloatArray, q: Int, p: Int): Float =
            ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * q - 2f * p)

        /** In place: 0 / [INF] in, Euclidean distance to the nearest 0 out (Felzenszwalb & Huttenlocher). */
        private fun edt(f: FloatArray, w: Int, h: Int) {
            val n = max(w, h)
            val line = FloatArray(n); val d = FloatArray(n)
            val v = IntArray(n); val z = FloatArray(n + 1)
            for (i in 0 until w) {
                for (j in 0 until h) line[j] = f[j * w + i]
                edt1(line, h, d, v, z)
                for (j in 0 until h) f[j * w + i] = d[j]
            }
            for (j in 0 until h) {
                System.arraycopy(f, j * w, line, 0, w)
                edt1(line, w, d, v, z)
                for (i in 0 until w) f[j * w + i] = sqrt(d[i])
            }
        }

        private fun edt1(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
            var k = 0
            v[0] = 0; z[0] = -INF; z[1] = INF
            for (q in 1 until n) {
                var s = parabolaCut(f, q, v[k])
                while (s <= z[k]) { k--; s = parabolaCut(f, q, v[k]) }
                k++
                v[k] = q; z[k] = s; z[k + 1] = INF
            }
            k = 0
            for (q in 0 until n) {
                while (z[k + 1] < q) k++
                val dq = (q - v[k]).toFloat()
                d[q] = dq * dq + f[v[k]]
            }
        }
    }
}

/**
 * Everything worked out from an icon at one threshold / invert / hole setting, independent of
 * the screen size: the mask, its outlines and its distance field.
 */
class IconShape(val mask: IconMask, val contours: IconContours, val field: IconField) {
    val isEmpty get() = contours.loops.isEmpty()

    companion object {
        fun build(
            src: IconSource, threshold: Float, invert: Boolean, minHole: Float = IconMask.DEFAULT_MIN_HOLE,
            iconFraction: Float = IconRing.ICON_FRACTION,
        ): IconShape = of(IconMask.build(src, threshold, invert, minHole), iconFraction)

        fun of(mask: IconMask, iconFraction: Float = IconRing.ICON_FRACTION): IconShape {
            // Enough white round the icon for bars as long as the room round it (square area).
            val pad = (max(mask.width, mask.height) * (1 - iconFraction) / (2 * iconFraction)).toInt() + 4
            return IconShape(mask, IconContours.trace(mask), IconField(mask, pad))
        }
    }
}

/** A growable IntArray without boxing. */
internal class IntList {
    private var a = IntArray(256)
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
}

/** A growable FloatArray without boxing. */
internal class FloatList {
    private var a = FloatArray(256)
    var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun toArray(): FloatArray = a.copyOf(size)
}
