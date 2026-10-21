package com.ltots.app.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The phone's built-in EQ as Android describes it: each band's centre and range, and how far a
 * band can be raised or lowered. Usually 5 bands (about 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz).
 */
data class PhoneEqLayout(
    val centersHz: List<Float>,
    val lowHz: List<Float>,
    val highHz: List<Float>,
    val minDb: Float,
    val maxDb: Float,
) {
    val bandCount: Int get() = centersHz.size

    companion object {
        /**
         * A layout from what a phone reports, made safe: a band with a missing or impossible
         * centre gets one from its range (or a standard spot), ranges are put in order, and
         * missing level limits fall back to ±15 dB.
         */
        fun sanitized(centersHz: List<Float>, lowHz: List<Float>, highHz: List<Float>, minDb: Float, maxDb: Float): PhoneEqLayout {
            val n = centersHz.size
            fun ok(f: Float) = f.isFinite() && f in 10f..24000f
            val c = ArrayList<Float>(n); val lo = ArrayList<Float>(n); val hi = ArrayList<Float>(n)
            for (i in 0 until n) {
                val l = lowHz.getOrNull(i) ?: Float.NaN
                val h = highHz.getOrNull(i) ?: Float.NaN
                val centre = when {
                    ok(centersHz[i]) -> centersHz[i]
                    ok(l) && ok(h) && h > l -> kotlin.math.sqrt(l * h)
                    else -> 60f * 4f.pow(i) // the standard 60, 240, 960 Hz, … spacing
                }.coerceIn(20f, 20000f)
                c += centre
                lo += if (ok(l) && l < centre) l else centre / 2f
                hi += if (ok(h) && h > centre) h else centre * 2f
            }
            val okRange = minDb.isFinite() && maxDb.isFinite() && minDb < maxDb && maxDb - minDb >= 2f
            return PhoneEqLayout(c, lo, hi, if (okRange) minDb else -15f, if (okRange) maxDb else 15f)
        }

        /** Android's standard 5-band layout (what most phones report). For tests and previews. */
        val STANDARD = PhoneEqLayout(
            centersHz = listOf(60f, 230f, 910f, 3600f, 14000f),
            lowHz = listOf(30f, 120f, 460f, 1800f, 7000f),
            highHz = listOf(120f, 460f, 1800f, 7000f, 20000f),
            minDb = -15f, maxDb = 15f,
        )
    }
}

/**
 * Battery EQ: LToTS's EQ curve played by the phone's own EQ instead of by LToTS.
 *
 * The phone's EQ has only a few fixed bands, so this finds the levels for those bands that come
 * closest to the whole curve (not just the curve's value at each band's centre): every band is
 * modelled as a bell over its range, and the levels are fitted across the audible range. The
 * result is a simplified version of the curve: broad shapes (more bass, brighter, a mid scoop)
 * carry over well; narrow notches and cuts don't.
 */
object BatteryEqFit {
    private const val FS = 48000.0

    /** Where the fit is measured: 25 Hz to 16 kHz, evenly on a log scale. */
    private val grid = DoubleArray(96) { i -> 25.0 * (16000.0 / 25.0).pow(i / 95.0) }

    /** The bell that stands in for phone band [i], designed at [gainDb]. */
    private fun bandFilter(layout: PhoneEqLayout, i: Int, gainDb: Float): BiquadCoefs {
        val fc = layout.centersHz[i]
        val width = (layout.highHz[i] - layout.lowHz[i]).coerceAtLeast(fc * 0.2f)
        val q = (fc / width).coerceIn(0.3f, 4f)
        return BiquadCoefs.design(EqBand(0, FilterType.PEAK, fc, gainDb, q), FS)
    }

    /** The curve to aim for, in dB on [grid]: the EQ's shape plus its preamp / automatic headroom. */
    private fun target(state: EqState): DoubleArray {
        val coefs = EqSmooth.filters(state).map { BiquadCoefs.design(it, FS) }
        val gain = EqMath.inputGainDb(state, FS)
        return DoubleArray(grid.size) { k -> EqMath.responseDb(coefs, grid[k], FS) + gain }
    }

    /** What the phone's EQ does with [levels], in dB at each of [freqs]. */
    private fun phoneResponse(layout: PhoneEqLayout, levels: FloatArray, freqs: DoubleArray): DoubleArray {
        val filters = levels.indices.map { bandFilter(layout, it, levels[it]) }
        return DoubleArray(freqs.size) { k -> EqMath.responseDb(filters, freqs[k], FS) }
    }

    /**
     * Levels in dB for each of the phone's bands, so its EQ comes as close as it can to [state].
     * All zeros when the EQ is off or flat (then the phone's EQ is switched off entirely).
     */
    fun fit(state: EqState, layout: PhoneEqLayout): FloatArray {
        val n = layout.bandCount
        if (n == 0 || !state.enabled || EqMath.isNeutral(state)) return FloatArray(n)
        val t = target(state)
        // Each band's shape at +1 dB (bells add up in dB, near enough, for these gentle widths).
        val shape = Array(n) { i ->
            val c = bandFilter(layout, i, 6f)
            DoubleArray(grid.size) { k -> c.magnitudeDb(grid[k], FS) / 6.0 }
        }
        val levels = FloatArray(n)
        // Fit, then measure what the real bells do at those levels and correct for the difference
        // (bells aren't perfectly proportional to their gain), a few times over.
        var aim = t
        repeat(4) {
            val g = leastSquares(shape, aim, layout.minDb.toDouble(), layout.maxDb.toDouble())
            for (i in 0 until n) levels[i] = g[i].toFloat()
            val got = phoneResponse(layout, levels, grid)
            aim = DoubleArray(grid.size) { k -> aim[k] + (t[k] - got[k]) }
        }
        return FloatArray(n) { i -> if (levels[i].isFinite()) (levels[i] * 10f).roundToInt() / 10f else 0f }
    }

    /**
     * Best band levels for [target] within [lo]..[hi]: least squares with the limits respected
     * (coordinate descent, which handles the limits simply and converges quickly for a few bands).
     */
    private fun leastSquares(shape: Array<DoubleArray>, target: DoubleArray, lo: Double, hi: Double): DoubleArray {
        val n = shape.size
        val m = target.size
        val g = DoubleArray(n)
        val model = DoubleArray(m) // sum of shape[j] * g[j]
        val norm = DoubleArray(n) { i -> shape[i].sumOf { it * it } + 1e-3 * m } // a touch of damping
        repeat(300) {
            var change = 0.0
            for (i in 0 until n) {
                val s = shape[i]
                var dot = 0.0
                for (k in 0 until m) dot += s[k] * (target[k] - model[k] + s[k] * g[i])
                val v = (dot / norm[i]).coerceIn(lo, hi)
                val d = v - g[i]
                if (d != 0.0) {
                    for (k in 0 until m) model[k] += s[k] * d
                    g[i] = v
                    change = maxOf(change, abs(d))
                }
            }
            if (change < 1e-4) return g
        }
        return g
    }

    /**
     * The curve the phone's EQ will roughly play with [levels], with the overall level shift
     * of [state] taken out, so it lines up with LToTS's own curve on the EQ graph. [points]
     * samples from 20 Hz to 20 kHz, like the graph.
     */
    fun phoneCurve(state: EqState, layout: PhoneEqLayout, levels: FloatArray, points: Int = 240): FloatArray {
        val freqs = DoubleArray(points) { i -> 20.0 * 1000.0.pow(i / (points - 1.0)) }
        val shift = if (state.enabled) EqMath.inputGainDb(state, FS) else 0.0
        val r = phoneResponse(layout, levels, freqs)
        return FloatArray(points) { i -> (r[i] - shift).toFloat() }
    }

    /** How far apart the phone's version and the real curve are (RMS dB over the audible range). */
    fun errorDb(state: EqState, layout: PhoneEqLayout, levels: FloatArray): Double {
        val t = target(state)
        val got = phoneResponse(layout, levels, grid)
        return kotlin.math.sqrt(t.indices.sumOf { (t[it] - got[it]).let { d -> d * d } } / t.size)
    }
}
