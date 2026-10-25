package com.ltots.app.audio

import com.ltots.app.data.BeatSource
import kotlin.math.exp
import kotlin.math.max

/**
 * Finds the moments the cover should move to (Look > Move with the beat), in the part of the
 * sound you pick: the whole **song** (default), or only its **lows** (kicks, 808s), **mids**
 * (snares, vocals, chords) or **highs** (hi-hats, claps, cymbals).
 *
 * Each part's level (from the same spectrum the visualizer draws) is compared with what it has
 * been doing over the last second or so: a hit that stands out gives a pulse, and one that comes
 * after a quiet build-up (a drop) stands out by far the most, so it gives the biggest. Steady
 * sound gives nothing (only the jumps count). "Song" listens to all three parts at once and
 * follows whichever hits hardest, leaning on the lows.
 *
 * In sync with the bars: a pulse starts in the very frame the bars jump and falls away at the
 * same speed the bars fall, so the cover and the visualizer move together.
 *
 * [update] once per visual frame; [pulse] is 0..1.
 */
class BeatDetector(var source: BeatSource = BeatSource.LOWS) {
    /** One part of the spectrum and what it has been doing lately. */
    private class Part(val loHz: Double, val hiHz: Double, val gate: Float, val liftFrom: Float, val liftTo: Float, val weight: Float) {
        var recent = -1f
        var spread = 0f
        var last = 0f
        var from = -1; var to = -1; var forBands = -1

        fun level(levels: FloatArray): Float {
            val n = levels.size
            if (n != forBands) {
                forBands = n
                from = (0 until n).firstOrNull { SpectrumFrame.bandFreq(it, n) >= loHz } ?: 0
                to = (0 until n).lastOrNull { SpectrumFrame.bandFreq(it, n) <= hiHz } ?: (n - 1)
                if (to < from) to = from
            }
            var sum = 0f
            for (i in from..to) sum += levels[i]
            return sum / (to - from + 1)
        }

        /** 0..1: how much this frame is a hit in this part. */
        fun hit(levels: FloatArray, dt: Float): Float {
            val v = level(levels)
            if (recent < 0f) { recent = v; last = v }
            val rising = v > last + 0.003f
            last = v
            val lift = v - recent
            recent += (v - recent) * (1f - exp(-dt / 1.1f))
            spread += (kotlin.math.abs(lift) - spread) * (1f - exp(-dt / 2.0f))
            // Very quiet never counts (no pulses in near-silence).
            if (!rising || v < gate) return 0f
            // How far above its recent level: in absolute terms (so a drop beats ordinary hits)…
            val absolute = smoothstep(liftFrom, liftTo, lift)
            // …and compared with how much this part usually moves, so quieter parts (mids,
            // highs) still give clear pulses on their hits.
            val relative = smoothstep(1.2f, 3.0f, lift / max(spread, 0.03f))
            return max(absolute, relative * 0.8f) * weight
        }

        fun reset() { recent = -1f; spread = 0f; last = 0f }
    }

    private val lows = Part(20.0, 140.0, gate = 0.25f, liftFrom = 0.03f, liftTo = 0.40f, weight = 1f)
    private val mids = Part(250.0, 4000.0, gate = 0.22f, liftFrom = 0.03f, liftTo = 0.30f, weight = 1f)
    private val highs = Part(4000.0, 20000.0, gate = 0.18f, liftFrom = 0.03f, liftTo = 0.28f, weight = 1f)
    // The whole song: all three, the lows counting most (that's where the beat lives).
    private val songWeights = floatArrayOf(1f, 0.8f, 0.6f)

    var pulse = 0f
        private set

    fun reset() { lows.reset(); mids.reset(); highs.reset(); pulse = 0f }

    /**
     * @param levels the spectrum's bands (0..1, log-spaced 20 Hz–20 kHz) as shown right now.
     * @param dt seconds since the last update.
     */
    fun update(levels: FloatArray, dt: Float): Float {
        if (levels.isEmpty()) return decay(dt)
        val hit = when (source) {
            BeatSource.LOWS -> lows.hit(levels, dt)
            BeatSource.MIDS -> mids.hit(levels, dt)
            BeatSource.HIGHS -> highs.hit(levels, dt)
            BeatSource.SONG -> maxOf(
                lows.hit(levels, dt) * songWeights[0],
                mids.hit(levels, dt) * songWeights[1],
                highs.hit(levels, dt) * songWeights[2],
            )
        }
        decay(dt)
        if (hit > pulse) pulse = hit
        return pulse
    }

    private fun decay(dt: Float): Float {
        // The bars fall in ~70 ms; the cover follows them down at about the same speed.
        pulse *= exp(-dt / RELEASE_S)
        if (pulse < 0.002f) pulse = 0f
        return pulse
    }

    companion object {
        const val RELEASE_S = 0.085f

        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}
