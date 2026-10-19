package com.ltots.app.ui.components

import androidx.compose.ui.graphics.Path
import com.ltots.app.data.RingLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Points along one or more closed outlines that the live spectrum grows from: round the cover
 * ([CoverRing]) or along the dark parts of an icon ([IconRing]). Each point has the direction
 * pointing straight out of the shape. Everything here is worked out once; drawing a frame only
 * reads these arrays.
 */
abstract class SpectrumOutline(
    /** Number of points along all outlines together. */
    val count: Int,
) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    /** Unit direction out of the shape, into the background. */
    val nx = FloatArray(count)
    val ny = FloatArray(count)

    /** Index of the first point of each closed outline; each runs to the next start (or [count]). */
    open val contourStarts: IntArray get() = SINGLE

    /** How far a full-level bar reaches out of the shape from point [k]. */
    abstract fun reachOut(k: Int): Float

    /** How far a full-level bar reaches into the shape from point [k] (0 = never inwards). */
    open fun reachIn(k: Int): Float = 0f

    /** The spectrum level at point [k], following [layout] along the whole outline. */
    abstract fun levelAt(k: Int, levels: FloatArray, layout: RingLayout = RingLayout.LOOP): Float

    /** Where the closed outline [c] ends (exclusive). */
    fun contourEnd(c: Int): Int = if (c + 1 < contourStarts.size) contourStarts[c + 1] else count

    companion object {
        private val SINGLE = intArrayOf(0)

        /**
         * The level for position [u] (0..1 along the outline): one loop runs the spectrum once from
         * start to end; mirrored puts the bass at both ends' middle (or, flipped, at the start).
         */
        fun levelAlong(u: Float, levels: FloatArray, layout: RingLayout): Float {
            val n = levels.size
            if (n == 0) return 0f
            val d = if (u <= 0.5f) u * 2 else (1 - u) * 2 // 0 at the start, 1 halfway
            val f = when (layout) {
                RingLayout.LOOP -> u * (n - 1)
                RingLayout.MIRRORED -> (1 - d) * (n - 1)
                RingLayout.MIRRORED_FLIPPED -> d * (n - 1)
            }
            val i = f.toInt().coerceIn(0, n - 1)
            val j = min(i + 1, n - 1)
            val w = f - i
            return levels[i] * (1 - w) + levels[j] * w
        }
    }
}

/**
 * Adds a smooth closed curve per outline, "level × reach" away from each point: outwards, or
 * into the shape when [inward]. Allocation-free.
 */
fun Path.addOutlineCurve(o: SpectrumOutline, levels: FloatArray, layout: RingLayout, inward: Boolean = false) {
    val starts = o.contourStarts
    for (c in starts.indices) {
        val s = starts[c]
        val e = o.contourEnd(c)
        val n = e - s
        if (n < 3) continue
        fun len(k: Int) = o.levelAt(k, levels, layout) * if (inward) -o.reachIn(k) else o.reachOut(k)
        fun qx(k: Int) = o.x[k] + o.nx[k] * len(k)
        fun qy(k: Int) = o.y[k] + o.ny[k] * len(k)
        moveTo((qx(e - 1) + qx(s)) / 2, (qy(e - 1) + qy(s)) / 2)
        for (k in s until e) {
            val nk = if (k + 1 == e) s else k + 1
            quadraticTo(qx(k), qy(k), (qx(k) + qx(nk)) / 2, (qy(k) + qy(nk)) / 2)
        }
        close()
    }
}

/** Adds each outline itself as a closed polygon (used to fill the band between it and a curve). */
fun Path.addOutline(o: SpectrumOutline) {
    val starts = o.contourStarts
    for (c in starts.indices) {
        val s = starts[c]
        val e = o.contourEnd(c)
        if (e - s < 3) continue
        moveTo(o.x[s], o.y[s])
        for (k in s + 1 until e) lineTo(o.x[k], o.y[k])
        close()
    }
}

/**
 * Adds one straight line per point, out of the shape (to be stroked). [mirror]: centred halfway
 * along the reach, so they pulse both ways.
 */
fun Path.addOutlineStrokes(o: SpectrumOutline, levels: FloatArray, layout: RingLayout, mirror: Boolean) {
    for (k in 0 until o.count) {
        val reach = o.reachOut(k)
        val len = max(o.levelAt(k, levels, layout) * reach, 1f)
        val from = if (mirror) reach / 2 - len / 2 else 0f
        val x0 = o.x[k] + o.nx[k] * from
        val y0 = o.y[k] + o.ny[k] * from
        moveTo(x0, y0)
        lineTo(x0 + o.nx[k] * len, y0 + o.ny[k] * len)
    }
}
