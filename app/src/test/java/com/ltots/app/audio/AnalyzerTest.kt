package com.ltots.app.audio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** The live view's analyser does only the work that's drawn, and still shows the right thing. */
class AnalyzerTest {
    private val sr = Signals.SR

    private fun tapWith(seconds: Double, freq: Double): AnalyzerTap {
        val tap = AnalyzerTap()
        tap.onFormat(sr, 2, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
        tap.onFlush(0)
        val n = (seconds * sr).toInt()
        for (i in 0 until n) {
            val env = 0.2 + 0.8 * ((i / (sr / 4)) % 2) // loud/quiet every 250 ms, so the wave has shape
            val v = (env * 0.5 * kotlin.math.sin(2 * Math.PI * freq * i / sr)).toFloat()
            tap.write(v, v)
        }
        tap.commit(n)
        return tap
    }

    /** Waveform measured incrementally, frame after frame, equals measuring it from scratch. */
    @Test fun incrementalWaveMatchesFullMeasure() {
        val tap = AnalyzerTap()
        tap.onFormat(sr, 2, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
        tap.onFlush(0)
        var written = 0
        fun feed(frames: Int) {
            for (k in 0 until frames) {
                val i = written + k
                val env = 0.2 + 0.8 * ((i / (sr / 4)) % 2) // loud/quiet every 250 ms
                val v = (env * 0.5 * kotlin.math.sin(2 * Math.PI * 440.0 * i / sr)).toFloat()
                tap.write(v, v)
            }
            tap.commit(frames)
            written += frames
        }
        feed(3 * sr)
        val a = Analyzer(tap, bands = 48)
        // Like playback: the audio thread stays ~100 ms ahead of what you hear.
        fun pos() = written * 1_000_000L / sr - 100_000L
        var f = a.update(pos(), true)
        repeat(60) { feed(sr / 30); f = a.update(pos(), true) }
        // From-scratch measurement at the same moment.
        val fresh = Analyzer(tap, bands = 48).update(pos(), true)
        val cols = SpectrumFrame.WAVE_COLUMNS
        var mismatches = 0
        for (i in 0 until cols) {
            if (f.waveMin[i] != fresh.waveMin[i] || f.waveMax[i] != fresh.waveMax[i]) mismatches++
        }
        assertEquals("columns differ", 0, mismatches)
        var lo = 0f; var hi = 0f
        for (i in 0 until cols) { lo = min(lo, f.waveMin[i]); hi = max(hi, f.waveMax[i]) }
        assertEquals(0.5, hi.toDouble(), 0.05)
        assertEquals(-0.5, lo.toDouble(), 0.05)
    }

    @Test fun skippingTheGhostMirrorsTheResult() {
        val tap = tapWith(2.0, 2000.0)
        val a = Analyzer(tap, bands = 96)
        var f = a.update(900_000L, true, pre = false, wave = false)
        repeat(10) { Thread.sleep(2); f = a.update(900_000L, true, pre = false, wave = false) }
        for (b in 0 until 96) assertEquals(f.post[b], f.pre[b], 0f)
        val loudest = f.post.indices.maxBy { f.post[it] }
        assertTrue(SpectrumFrame.bandFreq(loudest, 96) in 1800.0..2250.0)
        // No wave requested: nothing was measured for it.
        assertTrue(f.waveMax.all { it == 0f })
    }
}
