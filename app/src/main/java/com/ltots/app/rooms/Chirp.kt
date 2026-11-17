package com.ltots.app.rooms

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The calibration chirp and finding it in a recording.
 *
 * A short sweep (a rising whistle, 600 Hz → 7 kHz in 120 ms) is easy to pick out of a room's
 * echoes and noise with a matched filter: cross-correlating the recording with the sweep
 * gives one sharp peak where it arrived, to a fraction of a millisecond. Every speaker plays
 * it at its own scheduled moment; the phone that listens measures how early or late each one
 * really was, and the room corrects them so they all line up where you're standing.
 */
object Chirp {
    const val F0 = 600.0
    const val F1 = 7000.0
    const val DURATION_S = 0.12

    /** The sweep at [rate], with soft ends (no clicks). */
    fun make(rate: Int, amplitude: Float = 0.5f): FloatArray {
        val n = (rate * DURATION_S).toInt()
        val k = (F1 - F0) / DURATION_S
        val fade = n / 10
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val w = when {
                i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
                i > n - fade -> 0.5 - 0.5 * cos(PI * (n - i) / fade)
                else -> 1.0
            }
            (amplitude * w * sin(2 * PI * (F0 * t + 0.5 * k * t * t))).toFloat()
        }
    }

    /** Where the sweep was found: the sample index of its start (fractional) and how clearly. */
    data class Hit(val index: Double, val clarity: Double)

    /**
     * Finds [chirp] in [rec] between [from] and [to] (sample indexes where the chirp may start).
     * [Hit.clarity] is the peak over the typical correlation level around it: above ~6 it's
     * a real arrival, below that it's noise.
     */
    fun find(rec: FloatArray, chirp: FloatArray, from: Int, to: Int): Hit? {
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(rec.size - chirp.size)
        if (end <= start) return null
        val span = end - start
        val n = Integer.highestOneBit((span + chirp.size) * 2 - 1).coerceAtLeast(64)
        // Correlation by FFT: X · conj(C).
        val xr = DoubleArray(n); val xi = DoubleArray(n)
        val len = minOf(n, rec.size - start)
        for (i in 0 until len) xr[i] = rec[start + i].toDouble()
        val cr = DoubleArray(n); val ci = DoubleArray(n)
        for (i in chirp.indices) cr[i] = chirp[i].toDouble()
        fft(xr, xi, false)
        fft(cr, ci, false)
        for (i in 0 until n) {
            val r = xr[i] * cr[i] + xi[i] * ci[i]
            val im = xi[i] * cr[i] - xr[i] * ci[i]
            xr[i] = r; xi[i] = im
        }
        fft(xr, xi, true)
        // The envelope: |correlation| smoothed over about one period of the sweep's middle.
        var best = -1
        var bestV = 0.0
        val mag = DoubleArray(span + 1) { abs(xr[it]) }
        for (i in 0..span) if (mag[i] > bestV) { bestV = mag[i]; best = i }
        if (best < 0 || bestV <= 0.0) return null
        // The earliest strong arrival wins over a later, louder echo off a wall.
        val earliest = (0..best).firstOrNull { mag[it] >= bestV * 0.7 && isLocalMax(mag, it) } ?: best
        val sorted = mag.sorted()
        val typical = sorted[sorted.size / 2].coerceAtLeast(1e-12)
        val clarity = mag[earliest] / (typical * 4.0)
        // Parabolic fit through the peak and its neighbours: a fraction of a sample.
        val p = earliest
        val frac = if (p in 1 until span) {
            val a = mag[p - 1]; val b = mag[p]; val c = mag[p + 1]
            val d = a - 2 * b + c
            if (d != 0.0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
        } else 0.0
        return Hit(start + p + frac, clarity)
    }

    private fun isLocalMax(m: DoubleArray, i: Int): Boolean =
        (i == 0 || m[i] >= m[i - 1]) && (i == m.lastIndex || m[i] >= m[i + 1])

    /** In-place radix-2 FFT (n a power of two). [inverse] also divides by n. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
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
        var size = 2
        while (size <= n) {
            val ang = 2 * PI / size * (if (inverse) 1 else -1)
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until size / 2) {
                    val a = i + k; val b = a + size / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += size
            }
            size = size shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    /** Root mean square of a stretch of samples (to tell a silent recording). */
    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from).coerceAtLeast(1))
    }
}
