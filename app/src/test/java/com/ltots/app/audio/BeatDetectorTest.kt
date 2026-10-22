package com.ltots.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Move with the beat: kicks pulse, drops pulse hardest, steady bass and quiet don't. */
class BeatDetectorTest {
    private val n = 96
    private val dt = 1f / 120f

    /** A spectrum frame with the bass at [bass] and everything else at 0.4. */
    private fun frame(bass: Float) = FloatArray(n) { i -> if (SpectrumFrame.bandFreq(i, n) < 140.0) bass else 0.4f }

    /** Runs [seconds] of frames; [bassAt] gives the bass level at time t. Returns the pulses. */
    private fun run(d: BeatDetector, seconds: Float, bassAt: (Float) -> Float): FloatArray {
        val frames = (seconds / dt).toInt()
        return FloatArray(frames) { k -> d.update(frame(bassAt(k * dt)), dt) }
    }

    /** A kick at 128 bpm: the bass jumps to [peak] and falls back to [floor] (smoothed like the analyzer). */
    private fun kicks(peak: Float, floor: Float): (Float) -> Float = { t ->
        val phase = t % (60f / 128f)
        floor + (peak - floor) * kotlin.math.exp(-phase / 0.07f)
    }

    @Test fun steadyBassStaysStill() {
        val d = BeatDetector()
        val p = run(d, 4f) { 0.7f }
        assertEquals(0f, p.drop(60).maxOrNull()!!, 0.001f)
    }

    @Test fun quietMakesNothing() {
        val d = BeatDetector()
        val p = run(d, 3f, kicks(0.2f, 0.05f))
        assertEquals(0f, p.maxOrNull()!!, 0.001f)
    }

    @Test fun kicksPulseAndFallBetween() {
        val d = BeatDetector()
        val p = run(d, 6f, kicks(0.75f, 0.45f)).drop(240) // after it has settled
        val max = p.maxOrNull()!!
        val min = p.minOrNull()!!
        println("kicks: max %.2f min %.2f".format(max, min))
        assertTrue("kicks pulse: $max", max > 0.3f)
        assertTrue("and fall back between them: $min", min < 0.1f)
    }

    @Test fun aDropHitsHardestOfAll() {
        val d = BeatDetector()
        // Build-up: almost no bass for 4 s, then the drop with full kicks.
        val build = run(d, 4f) { 0.3f }
        val drop = run(d, 0.5f, kicks(0.95f, 0.6f))
        val later = run(d, 6f, kicks(0.95f, 0.6f)).drop(400)
        val dropPeak = drop.maxOrNull()!!
        val laterPeak = later.maxOrNull()!!
        println("drop %.2f, kicks later %.2f".format(dropPeak, laterPeak))
        assertEquals(0f, build.drop(60).maxOrNull()!!, 0.001f)
        assertTrue("the drop is a full hit: $dropPeak", dropPeak > 0.95f)
        assertTrue("bigger than the kicks once the drop goes on: $dropPeak vs $laterPeak", dropPeak > laterPeak + 0.1f)
    }
}
