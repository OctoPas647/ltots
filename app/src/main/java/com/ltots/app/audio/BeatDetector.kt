package com.ltots.app.audio

import kotlin.math.exp

/**
 * Finds the moments the cover should move to: bass hits, and above all drops and other big bass
 * moments. It follows the bass level of the live spectrum and compares it with what the bass has
 * been doing over the last second or so: a kick that stands out gives a pulse, and bass that
 * comes back after a quiet build-up (a drop) stands out by far the most, so it gives the biggest.
 * Steady bass gives nothing (it's only the jumps that count).
 *
 * [update] once per visual frame; [pulse] is 0..1: jumps up on a hit, then falls away.
 */
class BeatDetector {
    /** Bass bands: up to this frequency (kick drums, 808s, sub-bass). */
    private val maxHz = 140.0

    /** What the bass has been doing lately (about the last second). */
    private var recent = -1f
    /** How loud the bass was a moment ago, to see it rising. */
    private var last = 0f
    var pulse = 0f
        private set

    fun reset() { recent = -1f; last = 0f; pulse = 0f }

    /**
     * @param levels the spectrum's bands (0..1, log-spaced 20 Hz–20 kHz) as shown right now.
     * @param dt seconds since the last update.
     */
    fun update(levels: FloatArray, dt: Float): Float {
        val n = levels.size
        if (n == 0) return decay(dt)
        var sum = 0f; var count = 0
        for (i in 0 until n) {
            if (com.ltots.app.audio.SpectrumFrame.bandFreq(i, n) > maxHz) break
            sum += levels[i]; count++
        }
        val bass = if (count == 0) levels[0] else sum / count
        if (recent < 0f) recent = bass
        val rising = bass > last + 0.004f
        last = bass
        // How far above the recent bass this is: small for ordinary kicks, large for a drop.
        val lift = bass - recent
        recent += (bass - recent) * (1f - exp(-dt / 1.1f))
        // Very quiet bass never counts (no pulses in near-silence).
        val hit = if (rising && bass > 0.25f) smoothstep(0.04f, 0.45f, lift) else 0f
        decay(dt)
        if (hit > pulse) pulse = hit
        return pulse
    }

    private fun decay(dt: Float): Float {
        pulse *= exp(-dt / 0.16f)
        if (pulse < 0.002f) pulse = 0f
        return pulse
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
