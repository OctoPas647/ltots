package com.ltots.app.audio

import com.ltots.app.data.BeatSource
import kotlin.math.exp

/**
 * Every beat of a song, worked out before it plays (see [BeatTracker]), so the cover can move
 * exactly on the beat instead of reacting a moment after hearing it.
 *
 * Each beat has a strength per part of the sound (whole song, lows, mids, highs): 0..1, biggest
 * on drops and downbeats.
 */
class BeatMap(
    /** Beat times in seconds, ascending. */
    val times: FloatArray,
    val song: FloatArray,
    val lows: FloatArray,
    val mids: FloatArray,
    val highs: FloatArray,
    val downbeat: BooleanArray,
    /** Which tracker found the beats. */
    val byAi: Boolean,
) {
    val size: Int get() = times.size

    fun strengths(source: BeatSource): FloatArray = when (source) {
        BeatSource.SONG -> song
        BeatSource.LOWS -> lows
        BeatSource.MIDS -> mids
        BeatSource.HIGHS -> highs
    }

    /** Index of the last beat at or before [t] seconds; -1 when none. */
    fun lastAtOrBefore(t: Double): Int {
        var lo = 0; var hi = times.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /**
     * How far the cover is pushed out at song time [t] (seconds), 0..1: it jumps on each beat
     * and falls away over [releaseS] of real time ([speed] = playback speed). [leadS] starts
     * the jump that much early, to make up for the time a frame takes to reach the screen.
     */
    fun pulse(t: Double, source: BeatSource, speed: Float = 1f, leadS: Double = LEAD_S, releaseS: Double = RELEASE_S): Float {
        val i = lastAtOrBefore(t + leadS * speed)
        if (i < 0) return 0f
        val since = ((t + leadS * speed) - times[i]) / speed.coerceAtLeast(0.05f)
        val s = strengths(source)[i]
        // Released a little faster when the next beat comes quickly, so fast songs still breathe.
        val gap = if (i + 1 < times.size) (times[i + 1] - times[i]) / speed else 1f
        val rel = minOf(releaseS, gap * 0.45)
        return (s * exp(-since / rel)).toFloat().let { if (it < 0.002f) 0f else it }
    }

    companion object {
        const val LEAD_S = 0.020
        const val RELEASE_S = 0.16
    }
}
