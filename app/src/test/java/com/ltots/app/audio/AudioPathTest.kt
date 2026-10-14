package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Pushes real test tones through EQ → Sonic exactly like the player does, and measures them. */
class AudioPathTest {
    private val sr = 44100

    private fun sine16(freq: Double, seconds: Double, amp: Double): ByteBuffer {
        val frames = (sr * seconds).toInt()
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (amp * sin(2 * PI * freq * i / sr) * 32767).toInt().toShort()
            b.putShort(v); b.putShort(v)
        }
        b.flip()
        return b
    }

    private class Result(val floats: FloatArray?, val shorts: ShortArray?, val encoding: Int, val frames: Int) {
        fun left(i: Int): Double = floats?.get(i * 2)?.toDouble() ?: (shorts!![i * 2] / 32768.0)
    }

    private fun run(eq: EqState, input: ByteBuffer, int16: Boolean = false, params: PlaybackParameters? = null): Result {
        val tap = AnalyzerTap()
        val proc = EqProcessor(tap).apply { setState(eq); int16Output = int16 }
        val chain = LToTSProcessorChain(proc)
        params?.let { chain.applyPlaybackParameters(it) }
        val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(chain.audioProcessors))
        val out = pipeline.configure(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val collected = java.io.ByteArrayOutputStream()
        fun drain() {
            val o = pipeline.output
            val bytes = ByteArray(o.remaining()); o.get(bytes); collected.write(bytes)
        }
        while (input.hasRemaining()) { pipeline.queueInput(input); drain() }
        pipeline.queueEndOfStream()
        var guard = 0
        while (!pipeline.isEnded && guard++ < 1000) drain()
        val raw = ByteBuffer.wrap(collected.toByteArray()).order(ByteOrder.nativeOrder())
        return if (out.encoding == C.ENCODING_PCM_FLOAT) {
            val f = FloatArray(raw.remaining() / 4) { raw.float }
            Result(f, null, out.encoding, f.size / 2)
        } else {
            val s = ShortArray(raw.remaining() / 2) { raw.short }
            Result(null, s, out.encoding, s.size / 2)
        }
    }

    private fun rmsDb(r: Result, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) { val v = r.left(i); sum += v * v }
        return 20 * log10(sqrt(sum / (to - from)) + 1e-12)
    }

    private fun peak(r: Result): Double { var p = 0.0; for (i in 0 until r.frames) p = max(p, abs(r.left(i))); return p }

    private val flat = EqState(enabled = true, bands = emptyList(), autoHeadroom = false)

    @Test fun flatEqIsTransparentAndOutputsFloat() {
        val r = run(flat, sine16(1000.0, 0.5, 0.5))
        assertEquals(C.ENCODING_PCM_FLOAT, r.encoding)
        val db = rmsDb(r, 2000, r.frames - 2000)
        assertEquals(20 * log10(0.5 / sqrt(2.0)), db, 0.05)
    }

    @Test fun peakBandBoostsItsFrequencyOnly() {
        val eq = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 1000f, 6f, 1f)), autoHeadroom = false)
        val at = rmsDb(run(eq, sine16(1000.0, 0.5, 0.25)), 4000, 18000) - rmsDb(run(flat, sine16(1000.0, 0.5, 0.25)), 4000, 18000)
        val far = rmsDb(run(eq, sine16(100.0, 0.5, 0.25)), 4000, 18000) - rmsDb(run(flat, sine16(100.0, 0.5, 0.25)), 4000, 18000)
        assertEquals(6.0, at, 0.2)
        assertTrue("100 Hz should be nearly untouched, was $far", abs(far) < 0.5)
    }

    @Test fun autoHeadroomCancelsTheBoost() {
        val eq = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 1000f, 9f, 1f)), autoHeadroom = true)
        val gain = rmsDb(run(eq, sine16(1000.0, 0.5, 0.25)), 4000, 18000) - rmsDb(run(flat, sine16(1000.0, 0.5, 0.25)), 4000, 18000)
        assertEquals(0.0, gain, 0.3)
    }

    @Test fun limiterStopsClipping() {
        val eq = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 1000f, 12f, 1f)), autoHeadroom = false)
        val r = run(eq, sine16(1000.0, 0.5, 0.9))
        assertTrue("peak ${peak(r)} must stay below full scale", peak(r) < 1.0)
    }

    @Test fun shelvesAndCutsBehave() {
        val low = EqState(bands = listOf(EqBand(1, FilterType.LOW_SHELF, 200f, 6f, 0.7f)), autoHeadroom = false)
        assertEquals(6.0, rmsDb(run(low, sine16(40.0, 1.0, 0.25)), 8000, 40000) - rmsDb(run(flat, sine16(40.0, 1.0, 0.25)), 8000, 40000), 0.4)
        val hp = EqState(bands = listOf(EqBand(1, FilterType.HIGH_PASS, 200f, 0f, 0.71f)), autoHeadroom = false)
        assertTrue(rmsDb(run(hp, sine16(40.0, 1.0, 0.25)), 8000, 40000) < rmsDb(run(flat, sine16(40.0, 1.0, 0.25)), 8000, 40000) - 20)
    }

    @Test fun bitPerfectModeOutputs16Bit() {
        val r = run(flat, sine16(1000.0, 0.3, 0.5), int16 = true)
        assertEquals(C.ENCODING_PCM_16BIT, r.encoding)
        assertEquals(20 * log10(0.5 / sqrt(2.0)), rmsDb(r, 2000, r.frames - 2000), 0.1)
    }

    @Test fun pitchAndSpeedWorkOnFloatAudio() {
        val input = sine16(1000.0, 1.0, 0.4)
        val fast = run(flat, input, params = PlaybackParameters(2f, 1f))
        // Twice the speed → about half the samples out.
        assertEquals(sr / 2.0, fast.frames.toDouble(), sr * 0.05)
        val pitched = run(flat, sine16(1000.0, 1.0, 0.4), params = PlaybackParameters(1f, 2f))
        // One octave up → zero crossings double.
        var crossings = 0
        for (i in 5000 until 25000) if ((pitched.left(i - 1) < 0) != (pitched.left(i) < 0)) crossings++
        val freq = crossings / 2.0 / (20000.0 / sr)
        assertEquals(2000.0, freq, 60.0)
    }

    @Test fun analyzerFindsTheTone() {
        val tap = AnalyzerTap()
        tap.onFormat(sr, 2, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
        tap.onFlush(0)
        for (i in 0 until sr) { val v = (0.5 * sin(2 * PI * 2000.0 * i / sr)).toFloat(); tap.write(v, v) }
        tap.commit(sr)
        val a = Analyzer(tap, bands = 96)
        var f = a.update(positionUs = 1_000_000L, playing = true)
        repeat(10) { f = a.update(1_000_000L, true) }
        val loudest = f.post.indices.maxBy { f.post[it] }
        val centre = SpectrumFrame.bandFreq(loudest, 96)
        assertTrue("loudest band at $centre Hz", centre in 1800.0..2250.0)
    }

    @Test fun graphicPresetsHaveSaneQ() {
        val bands = EqDefaults.spaced(10)
        assertEquals(10, bands.size)
        assertEquals(31f, bands.first().freqHz, 0.5f)
        assertTrue(bands.all { it.q in 0.5f..3f })
    }
}
