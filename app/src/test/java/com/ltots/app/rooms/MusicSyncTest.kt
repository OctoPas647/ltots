package com.ltots.app.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class MusicSyncTest {
    private val rate = MusicSync.RATE
    private val maxLag = rate / 4 // ±250 ms

    /** Music-like test signal: noise through a gentle low-pass. */
    private fun music(n: Int, seed: Int): FloatArray {
        val r = Random(seed)
        val out = FloatArray(n)
        var y = 0f
        for (i in 0 until n) { y = 0.6f * y + 0.4f * (r.nextFloat() * 2 - 1); out[i] = y }
        return out
    }

    /** A room: each speaker (delay ms, gain) plays [ref]; the mic hears their sum plus noise. */
    private fun room(ref: FloatArray, recLen: Int, speakers: List<Pair<Double, Float>>, noise: Float = 0.05f): FloatArray {
        val r = Random(99)
        return FloatArray(recLen) { j ->
            var v = (r.nextFloat() * 2 - 1) * noise
            for ((ms, g) in speakers) {
                val i = j + maxLag - Math.round(ms * rate / 1000.0).toInt()
                if (i in ref.indices) v += g * ref[i]
            }
            v
        }
    }

    @Test fun findsEachSpeaker() {
        val recLen = rate * 5 / 2
        val ref = music(recLen + 2 * maxLag, 1)
        val rec = room(ref, recLen, listOf(10.0 to 1f, 24.0 to 0.7f))
        val p = MusicSync.peaks(ref, rec, maxLag)
        assertEquals(2, p.size)
        val lags = p.map { it.lagMs }.sorted()
        assertTrue("$lags", abs(lags[0] - 10.0) < 0.2 && abs(lags[1] - 24.0) < 0.2)
        assertEquals(14.0, MusicSync.spreadMs(p), 0.3)
    }

    @Test fun inSyncSpeakersAreOnePeak() {
        val recLen = rate * 2
        val ref = music(recLen + 2 * maxLag, 2)
        val rec = room(ref, recLen, listOf(5.0 to 1f, 5.5 to 0.8f))
        val p = MusicSync.peaks(ref, rec, maxLag)
        assertTrue(MusicSync.spreadMs(p) < 1.0)
    }

    @Test fun silenceOrOtherSoundFindsNothing() {
        val recLen = rate * 2
        val ref = music(recLen + 2 * maxLag, 3)
        val other = music(recLen, 4) // a different song / talking
        assertTrue(MusicSync.peaks(ref, other, maxLag).isEmpty())
    }

    @Test fun earlySpeakerHasNegativeLag() {
        val recLen = rate * 2
        val ref = music(recLen + 2 * maxLag, 5)
        val p = MusicSync.peaks(ref, room(ref, recLen, listOf(-12.0 to 1f)), maxLag)
        assertEquals(-12.0, p.first().lagMs, 0.2)
    }

    @Test fun driftNeedsTwoMatchingChecksAwayFromBaseline() {
        val d = MusicSync.Drift()
        assertTrue(!d.check(7.0)) // baseline (an echo, say)
        assertTrue(!d.check(8.0)) // same as before
        assertTrue(!d.check(30.0)) // once: could be a cough
        assertTrue(d.check(31.0)) // twice: a speaker drifted
        d.reset()
        assertTrue(!d.check(31.0))
        assertTrue(!d.check(0.0))
        assertTrue(!d.check(31.0)) // back to normal in between: not two in a row
    }

    @Test fun fftRoundTrip() {
        val re = DoubleArray(64) { Math.sin(it * 0.3) }; val im = DoubleArray(64)
        val orig = re.copyOf()
        MusicSync.fft(re, im, false); MusicSync.fft(re, im, true)
        for (i in re.indices) assertEquals(orig[i], re[i], 1e-9)
    }

    @Test fun downsampleKeepsLength() {
        assertEquals(16_000, MusicSync.downsample(FloatArray(48_000) { 1f }, 48_000).size)
        assertEquals(16_000, MusicSync.downsample(FloatArray(44_100) { 1f }, 44_100).size)
    }
}
