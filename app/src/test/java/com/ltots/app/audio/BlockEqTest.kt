package com.ltots.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The EQ filters whole blocks (one band and one channel at a time) for speed. This checks it
 * still produces exactly what filtering sample by sample produces, and that the live-view tap
 * only does work while someone is watching.
 */
class BlockEqTest {
    private val sr = Signals.SR

    private fun eq() = EqState(
        bands = listOf(
            EqBand(1, FilterType.HIGH_PASS, 30f, 0f, 0.7f),
            EqBand(2, FilterType.LOW_SHELF, 110f, 4f, 0.8f),
            EqBand(3, FilterType.PEAK, 2500f, -3.5f, 1.4f),
            EqBand(4, FilterType.HIGH_SHELF, 9000f, 2f, 0.7f),
        ),
        preampDb = -1f,
    )

    /** Straightforward sample-by-sample reference: gain, each biquad in turn, then the limiter. */
    private fun reference(pcm: ShortArray, state: EqState): FloatArray {
        val fs = sr.toDouble()
        val coefs = state.bands.map { BiquadCoefs.design(it, fs) }.filter { it !== BiquadCoefs.IDENTITY }
        val gain = 10.0.pow(EqMath.inputGainDb(state, fs) / 20.0)
        val z1 = Array(coefs.size) { DoubleArray(2) }
        val z2 = Array(coefs.size) { DoubleArray(2) }
        return FloatArray(pcm.size) { i ->
            val c = i % 2
            var y = pcm[i] / 32768.0 * gain
            for (s in coefs.indices) {
                val k = coefs[s]
                val x = y
                y = k.b0 * x + z1[s][c]
                z1[s][c] = k.b1 * x - k.a1 * y + z2[s][c]
                z2[s][c] = k.b2 * x - k.a2 * y
            }
            OutputStage.softLimit(y.toFloat().toDouble()).toFloat()
        }
    }

    private fun run(pcm: ShortArray, state: EqState, watch: Boolean): Pair<FloatArray, AnalyzerTap> {
        val src = StaticSoundSource(state)
        val tap = AnalyzerTap()
        if (watch) tap.watch()
        val sink = SinkSimulator(LToTSProcessorChain(EqProcessor(tap, src), TimePitchProcessor(src), OutputStage()))
        sink.start()
        sink.write(pcm, 777) // odd block size on purpose
        sink.end()
        return FloatArray(sink.out.size) { sink.out[it] } to tap
    }

    @Test fun blockFilteringMatchesSampleBySample() {
        val pcm = Signals.music(2.0)
        val want = reference(pcm, eq())
        val (got, _) = run(pcm, eq(), watch = false)
        assertEquals(want.size, got.size)
        for (i in want.indices) assertEquals("sample $i", want[i], got[i], 0f)
    }

    @Test fun liveViewTapDoesNotChangeTheSound() {
        val pcm = Signals.music(1.0)
        val (a, idle) = run(pcm, eq(), watch = false)
        val (b, watched) = run(pcm, eq(), watch = true)
        for (i in a.indices) assertEquals(a[i], b[i], 0f)
        // Unwatched: the ring stays empty. Watched: it holds the signal.
        assertTrue(idle.post.all { it == 0f })
        assertFalse(watched.post.all { it == 0f })
        assertEquals(idle.published, watched.published)
    }

    @Test fun neutralEqIsRecognised() {
        assertTrue(EqMath.isNeutral(EqState(enabled = false)))
        assertTrue(EqMath.isNeutral(EqState())) // default 10 bands at 0 dB
        assertFalse(EqMath.isNeutral(EqState(preampDb = -2f)))
        assertFalse(EqMath.isNeutral(eq()))
        assertFalse(EqMath.isNeutral(EqState(bands = listOf(EqBand(1, FilterType.HIGH_PASS, 30f, 0f, 0.7f)))))
    }
}
