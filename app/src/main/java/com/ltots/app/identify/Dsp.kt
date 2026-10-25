package com.ltots.app.identify

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Complex FFT for sizes made of factors 2 and 3 (2048 for the fingerprint, 6144 for the music
 * separator), in double precision. Recursive decimation in time with precomputed twiddles.
 */
class MixedFft(val n: Int) {
    private val factors: IntArray
    private val cosT = DoubleArray(n) { cos(-2 * PI * it / n) }
    private val sinT = DoubleArray(n) { sin(-2 * PI * it / n) }
    private val scratchRe = DoubleArray(n)
    private val scratchIm = DoubleArray(n)

    init {
        val f = ArrayList<Int>()
        var m = n
        while (m % 2 == 0) { f += 2; m /= 2 }
        while (m % 3 == 0) { f += 3; m /= 3 }
        require(m == 1 && n > 1) { "FFT size must be made of 2s and 3s" }
        // Radix-3 stages first keeps the inner loops on radix 2.
        factors = (f.filter { it == 3 } + f.filter { it == 2 }).toIntArray()
    }

    /** In-place forward transform (no scaling). */
    fun forward(re: DoubleArray, im: DoubleArray) {
        rec(re, im, 0, 1, scratchRe, scratchIm, 0, n, 0)
        System.arraycopy(scratchRe, 0, re, 0, n)
        System.arraycopy(scratchIm, 0, im, 0, n)
    }

    /** In-place inverse transform, scaled by 1/n. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) im[i] = -im[i]
        forward(re, im)
        val s = 1.0 / n
        for (i in 0 until n) { re[i] *= s; im[i] = -im[i] * s }
    }

    // out[o + k*?] = DFT of in[i0 + j*stride], length len
    private fun rec(
        inRe: DoubleArray, inIm: DoubleArray, i0: Int, stride: Int,
        outRe: DoubleArray, outIm: DoubleArray, o0: Int, len: Int, fi: Int,
    ) {
        if (len == 1) { outRe[o0] = inRe[i0]; outIm[o0] = inIm[i0]; return }
        val p = factors[fi]
        val m = len / p
        // Sub-transforms of the p decimated sequences, stored consecutively.
        for (q in 0 until p) rec(inRe, inIm, i0 + q * stride, stride * p, outRe, outIm, o0 + q * m, m, fi + 1)
        val tw = n / len
        if (p == 2) {
            for (k in 0 until m) {
                val a = o0 + k; val b = a + m
                val c = cosT[k * tw]; val s = sinT[k * tw]
                val br = outRe[b] * c - outIm[b] * s
                val bi = outRe[b] * s + outIm[b] * c
                val ar = outRe[a]; val ai = outIm[a]
                outRe[a] = ar + br; outIm[a] = ai + bi
                outRe[b] = ar - br; outIm[b] = ai - bi
            }
        } else {
            val c3 = -0.5
            val s3 = -sin(2 * PI / 3) // forward direction
            for (k in 0 until m) {
                val i0k = o0 + k; val i1 = i0k + m; val i2 = i1 + m
                val w1 = k * tw; val w2 = 2 * k * tw
                val x1r = outRe[i1] * cosT[w1] - outIm[i1] * sinT[w1]
                val x1i = outRe[i1] * sinT[w1] + outIm[i1] * cosT[w1]
                val x2r = outRe[i2] * cosT[w2] - outIm[i2] * sinT[w2]
                val x2i = outRe[i2] * sinT[w2] + outIm[i2] * cosT[w2]
                val x0r = outRe[i0k]; val x0i = outIm[i0k]
                val sr = x1r + x2r; val si = x1i + x2i
                val dr = x1r - x2r; val di = x1i - x2i
                val mr = x0r + c3 * sr; val mi = x0i + c3 * si
                // multiply (dr + i di) by i*s3
                val rr = -s3 * di; val ri = s3 * dr
                outRe[i0k] = x0r + sr; outIm[i0k] = x0i + si
                outRe[i1] = mr + rr; outIm[i1] = mi + ri
                outRe[i2] = mr - rr; outIm[i2] = mi - ri
            }
        }
    }
}

object Dsp {
    /**
     * Resamples [x] by [ratio] (= output rate / input rate) with a windowed-sinc filter.
     * A ratio above 1 makes the clip longer (slower and lower when played at the old rate).
     */
    fun resample(x: FloatArray, ratio: Double, taps: Int = 16): FloatArray {
        if (abs(ratio - 1.0) < 1e-9) return x.copyOf()
        val outLen = floor(x.size * ratio).toInt()
        val out = FloatArray(outLen)
        val cutoff = min(1.0, ratio) // lowpass when shrinking, so nothing folds back
        val half = taps / cutoff
        // The kernel, tabulated finely and read with linear interpolation (no trig per tap).
        val res = 512
        val tableLen = (half * res).toInt() + 2
        val table = DoubleArray(tableLen) { i ->
            val d = i.toDouble() / res
            if (d >= half) 0.0 else {
                val u = d * cutoff
                val sinc = if (u < 1e-9) 1.0 else sin(PI * u) / (PI * u)
                sinc * (0.5 + 0.5 * cos(PI * d / half)) // Hann window over the kernel
            }
        }
        for (i in 0 until outLen) {
            val t = i / ratio
            val lo = max(0, kotlin.math.ceil(t - half).toInt())
            val hi = min(x.size - 1, floor(t + half).toInt())
            var acc = 0.0
            var wsum = 0.0
            for (j in lo..hi) {
                val pos = abs(j - t) * res
                val k0 = pos.toInt()
                if (k0 + 1 >= tableLen) continue
                val f = pos - k0
                val k = table[k0] + (table[k0 + 1] - table[k0]) * f
                acc += x[j] * k
                wsum += k
            }
            out[i] = if (wsum != 0.0) (acc / wsum).toFloat() else 0f
        }
        return out
    }

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        if (to <= from) return 0.0
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return kotlin.math.sqrt(s / (to - from))
    }
}
