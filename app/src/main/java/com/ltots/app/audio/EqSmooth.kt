package com.ltots.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh

/**
 * The smooth EQ curve.
 *
 * Peak knobs whose bells overlap (the 10 graphic bands, or any knobs close together) used to add
 * up: the sound rose above each knob and dipped between two raised neighbours. Here those knobs
 * form a *chain*, and the chain's curve is one smooth line that goes exactly through every knob
 * and glides from one to the next (a shape-preserving spline in log frequency: no overshoot, no
 * dips). Beyond the chain's first and last knob the line falls away like those knobs' own bells.
 *
 * The line is then played by ordinary filters: peaking biquads at the knobs and between them,
 * with levels fitted so their combined response follows the line (within a few tenths of a dB
 * for ordinary settings). The processor, the graph, the automatic headroom and Battery EQ all use
 * these fitted filters, so what is drawn is what is heard.
 *
 * Knobs that stand on their own (far from their neighbours, or narrow: a high Q) keep their own
 * bell exactly as before, and shelves and cut filters are untouched.
 */
object EqSmooth {
    /**
     * Two neighbouring knobs blend when their bells' half-widths together cover at least this
     * share of the gap between them (the standard graphic bands cover all of it: 1.0).
     */
    const val LINK = 0.75

    private const val FS = 48000.0
    /** Filters per gap between two knobs (one at the knob, the rest in between). */
    private const val SUB = 2
    /** Width of each fitted filter, in multiples of the spacing to its neighbour. */
    private const val WIDTH = 3.0
    /** Long chains get one filter per knob, to keep the work per sample in check. */
    private const val MAX_SUB_CHAIN = 32
    private const val GRID_STEP_OCT = 1.0 / 12
    private const val OUTSIDE_OCT = 2.5
    private const val OUTSIDE_WEIGHT = 0.15
    private const val KNOB_WEIGHT = 20.0
    private const val LAMBDA = 1e-3

    private val cache = object : LinkedHashMap<List<EqBand>, List<EqBand>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<EqBand>, List<EqBand>>?) = size > 8
    }

    /**
     * The filters that actually play [state]'s bands: enabled bands only (switching the whole EQ
     * off is left to the caller), with every chain of overlapping peak knobs replaced by the
     * filters fitted to its smooth line. Cached, so the graph, the processor and the headroom
     * share one fit per change.
     */
    fun filters(state: EqState): List<EqBand> {
        val key = state.bands
        synchronized(cache) { cache[key]?.let { return it } }
        val result = compute(key)
        synchronized(cache) { cache[key] = result }
        return result
    }

    /** True when band [id] blends into its neighbours (its own width then doesn't shape the line). */
    fun isBlended(state: EqState, id: Long): Boolean {
        val peaks = peaksOf(state.bands)
        return chains(peaks).any { c -> c.size > 1 && c.any { it.id == id } }
    }

    /** The bell's half-width in octaves for [q] (half of the RBJ bandwidth). */
    fun halfWidthOct(q: Float): Double = kotlin.math.asinh(1.0 / (2.0 * q.coerceAtLeast(EqLimits.MIN_Q))) / ln(2.0)

    private fun qForWidthOct(bw: Double): Float = (1.0 / (2.0 * sinh(bw * ln(2.0) / 2.0))).toFloat()
        .coerceIn(EqLimits.MIN_Q, EqLimits.MAX_Q)

    private fun peaksOf(bands: List<EqBand>) = bands.filter { it.enabled && it.type == FilterType.PEAK }.sortedBy { it.freqHz }

    /** Runs of neighbouring peak knobs that blend into one line (single knobs are runs of one). */
    internal fun chains(peaks: List<EqBand>): List<List<EqBand>> {
        if (peaks.isEmpty()) return emptyList()
        val out = mutableListOf(mutableListOf(peaks[0]))
        for (i in 1 until peaks.size) {
            val a = peaks[i - 1]; val b = peaks[i]
            val d = log2(b.freqHz.toDouble() / a.freqHz)
            val linked = d > 0.02 && (halfWidthOct(a.q) + halfWidthOct(b.q)) / d >= LINK
            if (linked) out.last().add(b) else out.add(mutableListOf(b))
        }
        return out
    }

    private fun compute(bands: List<EqBand>): List<EqBand> {
        val out = ArrayList<EqBand>(bands.size * 2)
        for (b in bands) if (b.enabled && b.type != FilterType.PEAK) out.add(b)
        for (chain in chains(peaksOf(bands))) {
            if (chain.size == 1 || chain.all { it.gainDb == 0f }) out.addAll(chain)
            else out.addAll(fit(chain))
        }
        return out
    }

    // ---- the smooth line through a chain --------------------------------------------------

    /** The chain's line in dB at [x] (log2 Hz): the spline between the knobs, the end bells outside. */
    internal class ChainLine(private val chain: List<EqBand>) {
        val xs = DoubleArray(chain.size) { log2(chain[it].freqHz.toDouble()) }
        val ys = DoubleArray(chain.size) { chain[it].gainDb.toDouble() }
        private val slopes = pchipSlopes(xs, ys)
        private val first = BiquadCoefs.design(chain.first(), FS)
        private val last = BiquadCoefs.design(chain.last(), FS)

        fun at(x: Double): Double = when {
            x <= xs[0] -> first.magnitudeDb(2.0.pow(x), FS)
            x >= xs[xs.size - 1] -> last.magnitudeDb(2.0.pow(x), FS)
            else -> hermite(xs, ys, slopes, x)
        }
    }

    private fun fit(chain: List<EqBand>): List<EqBand> {
        val line = ChainLine(chain)
        val xs = line.xs
        val sub = if (chain.size > MAX_SUB_CHAIN) 1 else SUB

        // Filter centres: at every knob, and evenly between neighbouring knobs.
        val centres = ArrayList<Double>()
        val ids = ArrayList<Long>()
        for (i in chain.indices) {
            centres.add(xs[i]); ids.add(chain[i].id)
            if (i < chain.size - 1) for (k in 1 until sub) {
                centres.add(xs[i] + (xs[i + 1] - xs[i]) * k / sub)
                ids.add(java.lang.Long.rotateLeft(chain[i].id, 21) xor (0x5ABL + k))
            }
        }
        val n = centres.size
        val qs = FloatArray(n) { j ->
            val left = if (j > 0) centres[j] - centres[j - 1] else Double.MAX_VALUE
            val right = if (j < n - 1) centres[j + 1] - centres[j] else Double.MAX_VALUE
            qForWidthOct(min(left, right) * WIDTH)
        }
        val freqs = FloatArray(n) { 2.0.pow(centres[it]).toFloat().coerceIn(EqLimits.MIN_FREQ, EqLimits.MAX_FREQ) }

        // Where the fit is measured: the chain and a margin either side, plus the knobs themselves.
        val lo = max(log2(EqLimits.MIN_FREQ.toDouble()), xs[0] - OUTSIDE_OCT)
        val hi = min(log2(21000.0), xs[xs.size - 1] + OUTSIDE_OCT)
        val steps = ((hi - lo) / GRID_STEP_OCT).toInt().coerceAtLeast(2)
        val m = steps + 1 + xs.size
        val gx = DoubleArray(m) { k -> if (k <= steps) lo + (hi - lo) * k / steps else xs[k - steps - 1] }
        val target = DoubleArray(m) { k -> if (k <= steps) line.at(gx[k]) else line.ys[k - steps - 1] }
        val weight = DoubleArray(m) { k ->
            when {
                k > steps -> KNOB_WEIGHT
                gx[k] < xs[0] || gx[k] > xs[xs.size - 1] -> OUTSIDE_WEIGHT
                else -> 1.0
            }
        }
        val grid = Grid(gx)

        fun response(j: Int, gain: Double, outRow: DoubleArray) {
            val c = BiquadCoefs.design(EqBand(0, FilterType.PEAK, freqs[j], gain.toFloat(), qs[j]), FS)
            for (k in 0 until m) outRow[k] = grid.magnitudeDb(c, k)
        }

        // First guess: each filter's shape at +1 dB, scaled (bells add up in dB, near enough).
        val shapes = Array(n) { DoubleArray(m) }
        for (j in 0 until n) response(j, 1.0, shapes[j])
        val g = solve(shapes, DoubleArray(m) { target[it] }, weight, n, m)

        // Then a few Gauss-Newton steps on the real responses (bells aren't exactly proportional).
        val resp = Array(n) { DoubleArray(m) }
        val jac = Array(n) { DoubleArray(m) }
        val residual = DoubleArray(m)
        repeat(3) {
            for (j in 0 until n) {
                response(j, g[j], resp[j])
                response(j, g[j] + 0.05, jac[j])
                for (k in 0 until m) jac[j][k] = (jac[j][k] - resp[j][k]) / 0.05
            }
            for (k in 0 until m) {
                var s = 0.0
                for (j in 0 until n) s += resp[j][k]
                residual[k] = target[k] - s
            }
            val dg = solve(jac, residual, weight, n, m)
            for (j in 0 until n) g[j] = (g[j] + dg[j]).coerceIn(-30.0, 30.0)
        }

        val out = ArrayList<EqBand>(n)
        for (j in 0 until n) {
            if (abs(g[j]) < 0.005) continue
            out.add(EqBand(ids[j], FilterType.PEAK, freqs[j], g[j].toFloat(), qs[j], true))
        }
        return out
    }

    /** Weighted, lightly regularised least squares: the gains for [basis] that best make [rhs]. */
    private fun solve(basis: Array<DoubleArray>, rhs: DoubleArray, w: DoubleArray, n: Int, m: Int): DoubleArray {
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (i in 0 until n) {
            val bi = basis[i]
            for (j in i until n) {
                val bj = basis[j]
                var s = 0.0
                for (k in 0 until m) s += w[k] * bi[k] * bj[k]
                a[i][j] = s; a[j][i] = s
            }
            var s = 0.0
            for (k in 0 until m) s += w[k] * bi[k] * rhs[k]
            b[i] = s
            a[i][i] += LAMBDA
        }
        return gauss(a, b)
    }

    /** Solves a·x = b (Gaussian elimination with partial pivoting). */
    private fun gauss(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (piv != col) { val t = a[piv]; a[piv] = a[col]; a[col] = t; val tb = b[piv]; b[piv] = b[col]; b[col] = tb }
            val d = a[col][col]
            if (abs(d) < 1e-12) continue
            for (r in col + 1 until n) {
                val f = a[r][col] / d
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = if (abs(a[r][r]) < 1e-12) 0.0 else s / a[r][r]
        }
        return x
    }

    /** Frequencies (as log2 Hz) with their sines and cosines worked out once. */
    private class Grid(gx: DoubleArray) {
        val c1 = DoubleArray(gx.size); val s1 = DoubleArray(gx.size)
        val c2 = DoubleArray(gx.size); val s2 = DoubleArray(gx.size)

        init {
            for (k in gx.indices) {
                val w = 2.0 * PI * 2.0.pow(gx[k]) / FS
                c1[k] = cos(w); s1[k] = sin(w); c2[k] = cos(2 * w); s2[k] = sin(2 * w)
            }
        }

        fun magnitudeDb(c: BiquadCoefs, k: Int): Double {
            if (c === BiquadCoefs.IDENTITY) return 0.0
            val nr = c.b0 + c.b1 * c1[k] + c.b2 * c2[k]
            val ni = -(c.b1 * s1[k] + c.b2 * s2[k])
            val dr = 1.0 + c.a1 * c1[k] + c.a2 * c2[k]
            val di = -(c.a1 * s1[k] + c.a2 * s2[k])
            return 10.0 * log10(max((nr * nr + ni * ni) / (dr * dr + di * di), 1e-20))
        }
    }

    private fun log2(v: Double) = ln(v) / ln(2.0)

    /** Fritsch-Carlson slopes (no overshoot), flat at both ends so the end bells join smoothly. */
    private fun pchipSlopes(x: DoubleArray, y: DoubleArray): DoubleArray {
        val n = x.size
        val m = DoubleArray(n)
        for (i in 1 until n - 1) {
            val h0 = x[i] - x[i - 1]; val h1 = x[i + 1] - x[i]
            val d0 = (y[i] - y[i - 1]) / h0; val d1 = (y[i + 1] - y[i]) / h1
            m[i] = if (d0 * d1 <= 0.0) 0.0 else {
                val w1 = 2 * h1 + h0; val w2 = h1 + 2 * h0
                (w1 + w2) / (w1 / d0 + w2 / d1)
            }
        }
        return m
    }

    private fun hermite(x: DoubleArray, y: DoubleArray, m: DoubleArray, t: Double): Double {
        var i = 0
        while (i < x.size - 2 && t > x[i + 1]) i++
        val h = x[i + 1] - x[i]
        val s = (t - x[i]) / h
        val s2 = s * s; val s3 = s2 * s
        return (2 * s3 - 3 * s2 + 1) * y[i] + (s3 - 2 * s2 + s) * h * m[i] +
            (-2 * s3 + 3 * s2) * y[i + 1] + (s3 - s2) * h * m[i + 1]
    }
}
