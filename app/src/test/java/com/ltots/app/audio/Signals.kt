package com.ltots.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Test signals and measurements for the audio tests. */
object Signals {
    const val SR = 48000

    fun sine(freq: Double, seconds: Double, amp: Double = 0.4, sr: Int = SR): ShortArray {
        val n = (sr * seconds).toInt()
        return ShortArray(n * 2) { i ->
            val f = i / 2
            (amp * sin(2 * PI * freq * f / sr) * 32767).toInt().toShort()
        }
    }

    /**
     * A music-like signal: a chord with rich harmonics, a "voice" with formants and vibrato,
     * and percussive hits — the kind of material that exposes pitch-shifter artefacts.
     */
    fun music(seconds: Double, sr: Int = SR): ShortArray {
        val n = (sr * seconds).toInt()
        val out = ShortArray(n * 2)
        val chord = doubleArrayOf(220.0, 277.18, 329.63, 440.0)
        var noise = 12345
        for (f in 0 until n) {
            val t = f.toDouble() / sr
            var l = 0.0
            var r = 0.0
            for ((k, fr) in chord.withIndex()) {
                for (h in 1..6) {
                    val v = sin(2 * PI * fr * h * t + k) / (h * h) * 0.06
                    l += v * (1.0 - 0.15 * k)
                    r += v * (0.55 + 0.15 * k)
                }
            }
            // "Voice": 300 Hz with vibrato and two formant-ish partials.
            val vib = 300.0 * (1 + 0.01 * sin(2 * PI * 5.5 * t))
            val voice = 0.10 * sin(2 * PI * vib * t) + 0.05 * sin(2 * PI * vib * 3 * t) + 0.03 * sin(2 * PI * vib * 8 * t)
            l += voice; r += voice
            // Kick + hat every 0.5 s / 0.25 s.
            val beat = t % 0.5
            l += 0.35 * exp(-beat * 30) * sin(2 * PI * (60 + 80 * exp(-beat * 40)) * beat)
            r += 0.35 * exp(-beat * 30) * sin(2 * PI * (60 + 80 * exp(-beat * 40)) * beat)
            val hat = t % 0.25
            noise = noise * 1103515245 + 12345
            val nz = ((noise ushr 16) and 0x7FFF) / 16384.0 - 1.0
            l += 0.05 * exp(-hat * 120) * nz
            r += 0.05 * exp(-hat * 120) * nz
            out[f * 2] = (l.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            out[f * 2 + 1] = (r.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
        return out
    }

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / max(1, to - from))
    }

    fun db(v: Double) = 20 * log10(max(v, 1e-12))

    /** Dominant frequency in x[from, from+n) using a Hann-windowed DFT with peak interpolation. */
    fun dominantFreq(x: FloatArray, from: Int, n: Int = 16384, sr: Int = SR): Double {
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until n) re[i] = x[from + i] * (0.5 - 0.5 * kotlin.math.cos(2 * PI * i / (n - 1)))
        fft(re, im)
        var best = 1; var bestMag = 0.0
        val mags = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        for (k in 2 until n / 2 - 1) if (mags[k] > bestMag) { bestMag = mags[k]; best = k }
        val a = ln(mags[best - 1] + 1e-12); val b = ln(mags[best] + 1e-12); val c = ln(mags[best + 1] + 1e-12)
        val delta = 0.5 * (a - c) / (a - 2 * b + c)
        return (best + delta) * sr / n
    }

    /** Longest stretch (in ms) where 5 ms RMS falls below [ratio] of the median level. */
    fun longestDropoutMs(x: FloatArray, from: Int, to: Int, ratio: Double = 0.2, sr: Int = SR): Double {
        val win = sr / 200
        val levels = mutableListOf<Double>()
        var i = from
        while (i + win <= to) { levels += rms(x, i, i + win); i += win }
        if (levels.isEmpty()) return 0.0
        val median = levels.sorted()[levels.size / 2]
        var run = 0; var worst = 0
        for (l in levels) { if (l < median * ratio) { run++; worst = max(worst, run) } else run = 0 }
        return worst * 5.0
    }

    /** Largest jump between neighbouring samples, relative to the signal's peak (clicks show up as spikes). */
    fun worstJump(x: FloatArray, from: Int, to: Int): Double {
        var peak = 1e-9; var jump = 0.0
        for (i in from + 1 until to) { peak = max(peak, abs(x[i].toDouble())); jump = max(jump, abs((x[i] - x[i - 1]).toDouble())) }
        return jump / peak
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = kotlin.math.cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }
}
