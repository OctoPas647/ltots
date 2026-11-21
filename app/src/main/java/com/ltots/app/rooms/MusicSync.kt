package com.ltots.app.rooms

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Checks whether the room's speakers are lined up by listening to the music itself (no whistle).
 *
 * One phone records a few seconds of the room with its mic. Every phone has the music it played,
 * stamped with the group time it was due ([RoomRenderer.reference]). Cross-correlating the
 * recording with that music (GCC-PHAT: only the phase counts, so every frequency weighs the
 * same and each speaker shows up as one sharp peak) gives one peak per speaker, at the delay
 * with which its sound reached the mic. Speakers that are in sync give peaks a few
 * milliseconds apart at most (their distance to the mic); peaks further apart mean a speaker
 * has drifted (its Bluetooth delay changed, it reconnected…) and the room calibrates again.
 *
 * Which peak is which speaker can't be told from the music alone; the whistle calibration does
 * that. So this only *detects*, cheaply and silently; the whistle *corrects*.
 *
 * Pure maths, tested with synthetic rooms.
 */
object MusicSync {
    /** The rate both signals are brought to before comparing (Hz). */
    const val RATE = 16_000

    /** One speaker heard in the recording. */
    data class Peak(val lagMs: Double, val strength: Double)

    /**
     * The speakers heard in [rec] (at [RATE], starting at group time T0) given the music [ref]
     * (at [RATE], starting at T0 − [maxLag] samples, [rec].size + 2·[maxLag] long). Strongest
     * first; empty when the recording doesn't contain the music clearly enough.
     */
    fun peaks(ref: FloatArray, rec: FloatArray, maxLag: Int, minRelative: Double = 0.3, nearMs: Double = 80.0): List<Peak> {
        require(ref.size >= rec.size + 2 * maxLag) { "reference too short" }
        val n = Integer.highestOneBit(ref.size + rec.size - 1).let { if (it < ref.size + rec.size) it * 2 else it }
        val xr = DoubleArray(n); val xi = DoubleArray(n)
        val yr = DoubleArray(n); val yi = DoubleArray(n)
        val refMean = ref.average(); val recMean = rec.average()
        for (i in ref.indices) xr[i] = ref[i] - refMean
        for (i in rec.indices) yr[i] = rec[i] - recMean
        fft(xr, xi, false)
        fft(yr, yi, false)
        // X · conj(Y), whitened (PHAT), only the band where speakers and phone mics are reliable.
        val lo = (200.0 * n / RATE).toInt()
        val hi = (6000.0 * n / RATE).toInt()
        for (k in 0 until n) {
            val f = if (k <= n / 2) k else n - k
            if (f < lo || f > hi) { xr[k] = 0.0; xi[k] = 0.0; continue }
            val re = xr[k] * yr[k] + xi[k] * yi[k]
            val im = xi[k] * yr[k] - xr[k] * yi[k]
            val m = hypot(re, im)
            if (m < 1e-12) { xr[k] = 0.0; xi[k] = 0.0 } else { xr[k] = re / m; xi[k] = im / m }
        }
        fft(xr, xi, true)
        // corr[k] = Σ_j ref[j + k]·rec[j]; the speaker's lag (samples) is maxLag − k.
        val span = 2 * maxLag + 1
        val c = DoubleArray(span) { abs(xr[it]) }
        val floor = c.sorted()[span / 2] // the typical level away from any peak
        val best = c.indices.maxBy { c[it] }
        if (c[best] < floor * 12.0) return emptyList() // nothing stands out: the music wasn't heard
        val minSep = (0.0015 * RATE).toInt().coerceAtLeast(1)
        val near = (nearMs / 1000.0 * RATE).toInt()
        val found = ArrayList<Pair<Int, Double>>()
        for (k in 1 until span - 1) {
            if (c[k] < c[best] * minRelative || c[k] < c[k - 1] || c[k] < c[k + 1]) continue
            if (abs(k - best) > near) continue
            found += k to c[k]
        }
        // Keep the strongest of peaks closer than minSep (one speaker's peak is a few samples wide).
        val kept = ArrayList<Pair<Int, Double>>()
        for (p in found.sortedByDescending { it.second }) if (kept.none { abs(it.first - p.first) < minSep }) kept += p
        return kept.map { (k, v) ->
            // Parabolic interpolation: a fraction of a sample.
            val a = c[k - 1]; val b = c[k]; val d = c[k + 1]
            val den = a - 2 * b + d
            val frac = if (abs(den) < 1e-12) 0.0 else 0.5 * (a - d) / den
            Peak((maxLag - (k + frac)) * 1000.0 / RATE, v / c[best])
        }
    }

    /** How far apart the speakers heard are (ms): 0 for one speaker (or all lined up). */
    fun spreadMs(peaks: List<Peak>): Double =
        if (peaks.size < 2) 0.0 else peaks.maxOf { it.lagMs } - peaks.minOf { it.lagMs }

    /**
     * Decides from successive checks whether the speakers drifted apart. A room's echoes (a wall
     * a metre away) show up as peaks too, so the spread is compared with the one measured right
     * after the last calibration, and only a change seen twice in a row counts.
     */
    class Drift(private val toleranceMs: Double = 6.0) {
        private var baseline: Double? = null
        private var suspect: Double? = null

        /** After a calibration: the next check is the new normal. */
        fun reset() { baseline = null; suspect = null }

        /** One check's spread; true when the room should calibrate again. */
        fun check(spread: Double): Boolean {
            val base = baseline ?: run { baseline = spread; return false }
            if (abs(spread - base) <= toleranceMs) { suspect = null; return false }
            val prev = suspect
            suspect = spread
            return prev != null && abs(prev - spread) <= toleranceMs
        }
    }

    /** In-place radix-2 FFT (inverse scaled by 1/n). */
    internal fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /**
     * [src] (mono, [srcRate]) brought to [RATE] by averaging the source samples each output
     * sample covers (a crude low-pass, the same for both signals so it cancels out).
     */
    fun downsample(src: FloatArray, srcRate: Int): FloatArray {
        val step = srcRate.toDouble() / RATE
        val out = FloatArray((src.size / step).toInt())
        for (j in out.indices) {
            val a = (j * step).toInt(); val b = ((j + 1) * step).toInt().coerceAtMost(src.size).coerceAtLeast(a + 1)
            var s = 0f
            for (i in a until b) s += src[i]
            out[j] = s / (b - a)
        }
        return out
    }
}
