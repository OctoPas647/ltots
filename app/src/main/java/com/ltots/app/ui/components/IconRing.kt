package com.ltots.app.ui.components

import com.ltots.app.data.RingLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The icon's outlines placed in the drawing area, ready to draw the spectrum along: evenly spaced
 * points, the direction out of the dark parts at each, how far a bar may reach and how wide it
 * may be. Built once per icon, size and setting (never per frame).
 *
 * All outlines together are one sequence, longest first, and the spectrum is spread over its
 * whole length like [RingLayout.LOOP] (or mirrored) — the same as round the cover.
 *
 * A bar never covers a dark part and never enters the safe margin at the area's edge. Where the
 * white space narrows (a gap between two dark parts, a curl, a corner) bars stop before they meet
 * the bars growing from the other side, and get thinner.
 */
class IconRing private constructor(
    count: Int,
    private val starts: IntArray,
    /** Position of each point along the whole sequence, 0..1. */
    val u: FloatArray,
    /** Full-level bar length out of the shape, in px. */
    val reachOutPx: FloatArray,
    /** Full-level bar length into the shape, in px. */
    val reachInPx: FloatArray,
    /** Half the bar width for bars out of / into the shape, in px. */
    val halfWidthOut: FloatArray,
    val halfWidthIn: FloatArray,
    /** Where the icon's bounding box sits in the area, in px. */
    val iconLeft: Float,
    val iconTop: Float,
    val iconWidth: Float,
    val iconHeight: Float,
    /** The dark parts' outlines at full detail in px (x/y interleaved), for filling them. */
    val shapeLoops: List<FloatArray>,
) : SpectrumOutline(count) {
    override val contourStarts: IntArray get() = starts
    override fun reachOut(k: Int): Float = reachOutPx[k]
    override fun reachIn(k: Int): Float = reachInPx[k]
    override fun levelAt(k: Int, levels: FloatArray, layout: RingLayout): Float = levelAlong(u[k], levels, layout)

    companion object {
        /** Share of the safe area the icon's shape takes; the rest is room for the bars round it. */
        const val ICON_FRACTION = 0.72f
        /** The most points along all outlines (keeps every frame cheap on long outlines). */
        const val MAX_POINTS = 1200
        /** Bars stop this share of the way to where the white space is shared with another wall. */
        private const val SHARE = 0.85f
        /** …and at this share of the distance where neighbouring bars would converge. */
        private const val CONVERGE = 0.55f
        /** Ray-marching step, in mask pixels. */
        private const val STEP = 0.5f
        /**
         * The outline is softened (it runs up to a pixel inside sharp corners), so what is within
         * this many mask pixels of it counts as the outline itself, not as something in the way.
         */
        private const val NEAR = 2f
        /** How much longer a bar may be than its neighbour, per point spacing. */
        private const val SLOPE = 1.2f

        /** Shortens bars so each is at most [step] longer than its neighbours (closed outline st..en). */
        internal fun limitSlope(r: FloatArray, st: Int, en: Int, step: Float) {
            val n = en - st
            if (n < 3) return
            repeat(2) {
                for (i in 1..n) { val k = st + i % n; val p = st + (i - 1) % n; if (r[k] > r[p] + step) r[k] = r[p] + step }
                for (i in n - 1 downTo -1) { val k = st + (i + n) % n; val p = st + (i + 1 + n) % n; if (r[k] > r[p] + step) r[k] = r[p] + step }
            }
        }

        /**
         * @param areaWidth, areaHeight the drawing area in px.
         * @param marginPx safe space kept free at the area's edges (no bar reaches into it).
         * @param spacingPx distance between neighbouring points along the outline.
         * @param minPoints at least this many points (two per spectrum band shows every band).
         * @param maxBarWidthPx the widest a bar gets.
         */
        fun build(
            shape: IconShape,
            areaWidth: Float,
            areaHeight: Float,
            marginPx: Float,
            spacingPx: Float,
            minPoints: Int,
            maxBarWidthPx: Float,
            iconFraction: Float = ICON_FRACTION,
        ): IconRing? {
            val contours = shape.contours
            val mask = shape.mask
            if (shape.isEmpty) return null
            val safeW = areaWidth - 2 * marginPx
            val safeH = areaHeight - 2 * marginPx
            if (safeW <= 0f || safeH <= 0f) return null
            // Fit the icon's bounding box in the middle, keeping its aspect ratio.
            val s = iconFraction * min(safeW / mask.width, safeH / mask.height)
            val ox = (areaWidth - mask.width * s) / 2
            val oy = (areaHeight - mask.height * s) / 2
            // Bars out of the shape: at most as long as the room round the icon, and never into
            // the margin.
            val maxLen = min(safeW, safeH) * (1 - iconFraction) / 2
            val bounds = floatArrayOf((marginPx - ox) / s, (areaWidth - marginPx - ox) / s, (marginPx - oy) / s, (areaHeight - marginPx - oy) / s)

            // Points per outline, evenly spaced along the whole sequence.
            val total = contours.totalLength * s
            val wanted = (total / spacingPx).roundToInt().coerceIn(minPoints, MAX_POINTS)
            val per = IntArray(contours.loops.size) { i ->
                (contours.lengths[i] * s / total * wanted).roundToInt().let { if (it < 3) 0 else it }
            }
            val count = per.sum()
            if (count < 3) return null
            val spacing = total / count
            val starts = IntList()
            val mx = FloatArray(count); val my = FloatArray(count)
            val nx = FloatArray(count); val ny = FloatArray(count)
            var k0 = 0
            for ((li, loop) in contours.loops.withIndex()) {
                val n = per[li]
                if (n == 0) continue
                starts.add(k0)
                val pts = orient(resample(loop, n), mask)
                for (k in 0 until n) { mx[k0 + k] = pts[2 * k]; my[k0 + k] = pts[2 * k + 1] }
                // Normals: perpendicular to the outline, towards the white side ([orient] walks
                // each outline with the dark parts on its right, so white is on the left).
                for (k in 0 until n) {
                    val a = k0 + (k + n - 1) % n; val b = k0 + (k + 1) % n
                    var tx = mx[b] - mx[a]; var ty = my[b] - my[a]
                    val l = sqrt(tx * tx + ty * ty).coerceAtLeast(1e-6f)
                    tx /= l; ty /= l
                    nx[k0 + k] = ty; ny[k0 + k] = -tx
                }
                k0 += n
            }
            val startArr = IntArray(starts.size) { starts[it] }
            val u = FloatArray(count) { (it + 0.5f) / count }
            val reachOut = FloatArray(count); val reachIn = FloatArray(count)
            val halfOut = FloatArray(count); val halfIn = FloatArray(count)
            // Half the space each bar has sideways, from the point spacing.
            val baseHalf = min(maxBarWidthPx / 2, spacing * 0.55f / 2).coerceAtLeast(0.75f)
            val conv = FloatArray(2)
            for ((c, st) in startArr.withIndex()) {
                val en = if (c + 1 < startArr.size) startArr[c + 1] else count
                val n = en - st
                // Neighbours a few points away on either side, to see where bars converge.
                val w = (8f * spacingPx / spacing).roundToInt().coerceIn(1, max(1, n / 4))
                val convOut = FloatArray(n); val convIn = FloatArray(n)
                for (k in st until en) {
                    val a = st + (k - st - w + n) % n; val b = st + (k - st + w) % n
                    convergence(mx, my, nx, ny, a, k, b, conv)
                    convOut[k - st] = conv[0]; convIn[k - st] = conv[1]
                    reachOut[k] = min(reachOut(shape.field, mx[k], my[k], nx[k], ny[k], maxLen / s, bounds), conv[0] * CONVERGE) * s
                    reachIn[k] = min(reachIn(shape.field, mx[k], my[k], nx[k], ny[k], maxLen / s), conv[1] * CONVERGE) * s
                }
                // Neighbouring bars change length gradually (only ever shortening), so where the
                // room changes abruptly the bars and the curve follow a smooth edge, not steps.
                limitSlope(reachOut, st, en, spacing * SLOPE)
                limitSlope(reachIn, st, en, spacing * SLOPE)
                for (k in st until en) {
                    // Short bars (narrow gaps) and converging bars are thinner.
                    halfOut[k] = min(baseHalf * taper(reachOut[k] / s, convOut[k - st]), max(0.5f, reachOut[k] * 0.3f))
                    halfIn[k] = min(baseHalf * taper(reachIn[k] / s, convIn[k - st]), max(0.5f, reachIn[k] * 0.3f))
                }
            }
            val ring = IconRing(
                count, startArr, u, reachOut, reachIn, halfOut, halfIn,
                ox, oy, mask.width * s, mask.height * s,
                contours.loops.map { l -> FloatArray(l.size) { i -> if (i % 2 == 0) ox + l[i] * s else oy + l[i] * s } },
            )
            for (k in 0 until count) {
                ring.x[k] = ox + mx[k] * s; ring.y[k] = oy + my[k] * s
                ring.nx[k] = nx[k]; ring.ny[k] = ny[k]
            }
            return ring
        }

        private const val FAR = 1e6f

        /** How much a bar of [len] narrows when bars meet [conv] away (1 = not at all). */
        private fun taper(len: Float, conv: Float): Float = if (conv >= FAR) 1f else (1f - len / conv).coerceIn(0.35f, 1f)

        /**
         * Where the bars near point [k] meet ([out] 0: outside the shape, [out] 1: inside it): the
         * radius of the circle through [a], [k] and [b], on the side its centre lies; [FAR] on
         * the other side and along straight stretches.
         */
        private fun convergence(mx: FloatArray, my: FloatArray, nx: FloatArray, ny: FloatArray, a: Int, k: Int, b: Int, out: FloatArray) {
            out[0] = FAR; out[1] = FAR
            val ax = mx[a] - mx[k]; val ay = my[a] - my[k]
            val bx = mx[b] - mx[k]; val by = my[b] - my[k]
            val d = 2 * (ax * by - ay * bx)
            if (kotlin.math.abs(d) < 1e-6f) return
            val a2 = ax * ax + ay * ay; val b2 = bx * bx + by * by
            // Centre of the circle through the three points, relative to point k.
            val cx = (by * a2 - ay * b2) / d
            val cy = (ax * b2 - bx * a2) / d
            val radius = sqrt(cx * cx + cy * cy)
            if (cx * nx[k] + cy * ny[k] > 0) out[0] = radius else out[1] = radius
        }

        /**
         * How far a bar from (x, y) along (nx, ny) may reach out of the shape, in mask px: until a
         * closer dark wall takes over the white space (bars from both sides then meet with a small
         * gap left), up to the edge of the safe area, at most [maxLen].
         *
         * @param bounds left, right, top, bottom of the safe area in mask coordinates.
         */
        internal fun reachOut(field: IconField, x: Float, y: Float, nx: Float, ny: Float, maxLen: Float, bounds: FloatArray): Float {
            val shared = { t: Float ->
                val px = x + nx * t; val py = y + ny * t
                field.mask.darkAt(px, py) || field.outside(px, py) < t * 0.92f - 0.35f
            }
            var t = STEP
            while (t <= maxLen) {
                val px = x + nx * t; val py = y + ny * t
                if (px < bounds[0] || px > bounds[1] || py < bounds[2] || py > bounds[3]) return max(0f, t - STEP)
                if (t >= NEAR && shared(t)) return edge(t - STEP, t, shared) * SHARE
                t += STEP
            }
            return maxLen
        }

        /** How far a bar may reach into the shape: the same rule, inside the dark part. */
        internal fun reachIn(field: IconField, x: Float, y: Float, nx: Float, ny: Float, maxLen: Float): Float {
            val shared = { t: Float ->
                val px = x - nx * t; val py = y - ny * t
                !field.mask.darkAt(px, py) || field.inside(px, py) < t * 0.92f - 0.35f
            }
            var t = STEP
            while (t <= maxLen) {
                if (t >= NEAR && shared(t)) return edge(t - STEP, t, shared) * SHARE
                t += STEP
            }
            return maxLen
        }

        /** Where between [a] (not yet) and [b] (already) [past] turns true, to a small fraction of a pixel. */
        private fun edge(a: Float, b: Float, past: (Float) -> Boolean): Float {
            var lo = a; var hi = b
            repeat(5) {
                val m = (lo + hi) / 2
                if (past(m)) hi = m else lo = m
            }
            return (lo + hi) / 2
        }

        /** [n] points evenly spaced along the closed outline [loop]. */
        internal fun resample(loop: FloatArray, n: Int): FloatArray {
            val m = loop.size / 2
            val total = IconContours.length(loop)
            val out = FloatArray(n * 2)
            val step = total / n
            var seg = 0
            var segStart = 0f
            fun segLen(i: Int): Float {
                val j = (i + 1) % m
                val dx = loop[2 * j] - loop[2 * i]; val dy = loop[2 * j + 1] - loop[2 * i + 1]
                return sqrt(dx * dx + dy * dy)
            }
            var curLen = segLen(0)
            for (k in 0 until n) {
                val target = k * step
                while (segStart + curLen < target && seg < m - 1) { segStart += curLen; seg++; curLen = segLen(seg) }
                val t = if (curLen > 0f) ((target - segStart) / curLen).coerceIn(0f, 1f) else 0f
                val j = (seg + 1) % m
                out[2 * k] = loop[2 * seg] + (loop[2 * j] - loop[2 * seg]) * t
                out[2 * k + 1] = loop[2 * seg + 1] + (loop[2 * j + 1] - loop[2 * seg + 1]) * t
            }
            return out
        }

        /**
         * Walks the outline with the dark parts on its right (clockwise round a dark piece on
         * screen, like the ring round the cover), starting from its top.
         */
        internal fun orient(pts: FloatArray, mask: IconMask): FloatArray {
            val n = pts.size / 2
            // Vote: is the left side (n = (ty, -tx)) white at most points?
            var whiteLeft = 0; var whiteRight = 0
            for (k in 0 until n) {
                val a = (k + n - 1) % n; val b = (k + 1) % n
                var tx = pts[2 * b] - pts[2 * a]; var ty = pts[2 * b + 1] - pts[2 * a + 1]
                val l = sqrt(tx * tx + ty * ty)
                if (l < 1e-6f) continue
                tx /= l; ty /= l
                val lx = pts[2 * k] + ty * 2f; val ly = pts[2 * k + 1] - tx * 2f
                val rx = pts[2 * k] - ty * 2f; val ry = pts[2 * k + 1] + tx * 2f
                val lw = !mask.darkAt(lx, ly); val rw = !mask.darkAt(rx, ry)
                if (lw && !rw) whiteLeft++ else if (rw && !lw) whiteRight++
            }
            val reverse = whiteRight > whiteLeft
            // Start at the topmost point (then leftmost).
            var top = 0
            for (k in 1 until n) {
                val y = pts[2 * k + 1]; val ty = pts[2 * top + 1]
                if (y < ty - 1e-3f || (y < ty + 1e-3f && pts[2 * k] < pts[2 * top])) top = k
            }
            val out = FloatArray(pts.size)
            for (k in 0 until n) {
                val src = if (reverse) (top - k + n) % n else (top + k) % n
                out[2 * k] = pts[2 * src]; out[2 * k + 1] = pts[2 * src + 1]
            }
            return out
        }
    }
}
