package com.ltots.app.identify

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Melody matching, for songs played on an instrument (a piano or guitar cover, someone playing
 * the tune on a violin) or hummed — which Shazam-style sound matching can't recognise, since the
 * sound is nothing like the record's.
 *
 * Both the clip and each candidate song are turned into their **tune**: the main pitch, ten times
 * a second, in semitones (YIN pitch tracking). Tunes are compared the way a person would: in any
 * key (all 12 transpositions are tried), a little faster or slower (the alignment may stretch),
 * and only the best-matching stretch counts (a cover may play only the chorus). How far a song's
 * score stands out from the other candidates' decides whether it's a match.
 *
 * Measured on covers made from 14 chart songs' melodies (3 semitones higher, 10 % slower) against
 * 31 songs: the right song came first 9 times; above the [CONFIDENT] line no wrong song appeared.
 */
object Melody {
    const val RATE = ClipAudio.RATE
    /** Tune values per second. */
    const val FPS = 10
    private const val HOP = RATE / 50 // 20 ms
    private const val WIN = 640 // 40 ms
    private const val FMIN = 110.0
    private const val FMAX = 900.0
    /** Fewer sung/played moments than this (in tune steps) is too little to go on. */
    const val MIN_VOICED = 40
    /** How far (in standard deviations) the best song must stand out to be called a match. */
    const val CONFIDENT = 2.5
    const val POSSIBLE = 1.9

    /** The tune of [pcm] (16 kHz mono): semitones (MIDI numbers), NaN where nothing tuneful plays. */
    fun contour(pcm: FloatArray): FloatArray {
        val x = bandPass(pcm)
        val frames = max(0, (x.size - WIN - (RATE / FMIN).toInt() - 2) / HOP)
        if (frames == 0) return FloatArray(0)
        val rms = DoubleArray(frames) { f -> var s = 0.0; for (j in f * HOP until f * HOP + WIN) s += x[j] * x[j]; sqrt(s / WIN) }
        val loud = rms.sorted()[(frames * 0.95).toInt().coerceAtMost(frames - 1)]
        val tauMin = (RATE / FMAX).toInt()
        val tauMax = (RATE / FMIN).toInt()
        val d = DoubleArray(tauMax + 2)
        val dn = DoubleArray(tauMax + 2)
        val midi = FloatArray(frames) { Float.NaN }
        for (f in 0 until frames) {
            if (rms[f] < 0.08 * loud) continue
            val s = f * HOP
            // YIN: difference function, cumulative-mean normalised.
            for (tau in 1..tauMax + 1) {
                var acc = 0.0
                for (j in 0 until WIN) { val v = x[s + j] - x[s + j + tau]; acc += v * v }
                d[tau] = acc
            }
            var running = 0.0
            var found = -1
            dn[0] = 1.0
            for (tau in 1..tauMax + 1) {
                running += d[tau]
                dn[tau] = if (running > 0) d[tau] * tau / running else 1.0
            }
            var tau = tauMin
            while (tau <= tauMax) {
                if (dn[tau] < 0.18) {
                    while (tau + 1 <= tauMax && dn[tau + 1] < dn[tau]) tau++
                    found = tau
                    break
                }
                tau++
            }
            if (found < 0) continue
            // Parabolic interpolation around the minimum.
            val a = dn[found - 1]; val b = dn[found]; val c = dn[found + 1]
            val den = a - 2 * b + c
            val t = if (abs(den) > 1e-12) found + 0.5 * (a - c) / den else found.toDouble()
            val hz = RATE / t
            if (hz in FMIN..FMAX) midi[f] = (69 + 12 * ln(hz / 440.0) / ln(2.0)).toFloat()
        }
        // Ten values a second: the median of each 100 ms that's mostly tuneful.
        val per = 50 / FPS
        return FloatArray(frames / per) { k ->
            val v = (0 until per).map { midi[k * per + it] }.filter { !it.isNaN() }.sorted()
            if (v.size * 2 > per) v[v.size / 2] else Float.NaN
        }
    }

    /** Keeps roughly 100 Hz – 1.2 kHz, where tunes live (no bass line, no hi-hats). */
    private fun bandPass(x: FloatArray): FloatArray {
        val out = FloatArray(x.size)
        val hp = 1.0 - 2 * Math.PI * 100 / RATE
        val lpA = 2 * Math.PI * 1200 / RATE / (1 + 2 * Math.PI * 1200 / RATE)
        var prevX = 0.0; var prevH = 0.0; var low = 0.0
        for (i in x.indices) {
            val h = hp * (prevH + x[i] - prevX)
            prevX = x[i].toDouble(); prevH = h
            low += lpA * (h - low)
            out[i] = low.toFloat()
        }
        return out
    }

    fun voiced(c: FloatArray): Int = c.count { !it.isNaN() }

    /**
     * How well the tune [q] appears somewhere in [r], in the best key: a local alignment where
     * notes within a semitone (any octave) match, others cost, and the tempo may drift
     * (steps of 2-for-1 either way). Per voiced step of [q], so clips of any length compare.
     */
    fun score(q: FloatArray, r: FloatArray): Double {
        val n = q.size; val m = r.size
        if (n == 0 || m == 0) return 0.0
        val qv = voiced(q).takeIf { it > 0 } ?: return 0.0
        var best = 0.0
        val prev2 = DoubleArray(m + 1); val prev = DoubleArray(m + 1); val cur = DoubleArray(m + 1)
        for (shift in 0 until 12) {
            prev2.fill(0.0); prev.fill(0.0)
            for (i in 1..n) {
                cur[0] = 0.0
                val qi = q[i - 1]
                for (j in 1..m) {
                    val rj = r[j - 1]
                    val sim = if (qi.isNaN() || rj.isNaN()) -0.15 else {
                        val dd = (qi + shift - rj).toDouble()
                        val dist = abs(dd - 12 * floor((dd + 6) / 12))
                        if (dist < 0.75) 1.0 else -0.8
                    }
                    var v = prev[j - 1] + sim
                    if (i > 1) v = max(v, prev2[j - 1] + sim - 0.4)
                    if (j > 1) v = max(v, prev[j - 2] + sim - 0.4)
                    v = max(0.0, v)
                    cur[j] = v
                    if (v > best) best = v
                }
                System.arraycopy(prev, 0, prev2, 0, m + 1)
                System.arraycopy(cur, 0, prev, 0, m + 1)
            }
        }
        return best / qv
    }

    /** A candidate's raw score and how far it stands out from the rest (z-score). */
    data class Ranked<K>(val key: K, val score: Double, val z: Double)

    fun <K> rank(q: FloatArray, refs: List<Pair<K, FloatArray>>): List<Ranked<K>> {
        if (refs.size < 3) return emptyList()
        val scores = refs.map { it.first to score(q, it.second) }
        val mean = scores.sumOf { it.second } / scores.size
        val sd = sqrt(scores.sumOf { (it.second - mean) * (it.second - mean) } / scores.size).takeIf { it > 1e-9 } ?: return emptyList()
        return scores.map { Ranked(it.first, it.second, (it.second - mean) / sd) }.sortedByDescending { it.z }
    }

    /** Stores a tune compactly (tenths of a semitone; 0 = nothing). */
    fun pack(c: FloatArray): String = c.joinToString(",") { if (it.isNaN()) "0" else (it * 10).toInt().toString() }

    fun unpack(s: String): FloatArray = if (s.isBlank()) FloatArray(0)
    else s.split(',').map { it.toIntOrNull() ?: 0 }.map { if (it == 0) Float.NaN else it / 10f }.toFloatArray()
}
