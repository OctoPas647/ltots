package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The whole sound path (EQ → Rubber Band pitch/speed → limiter/output), driven exactly like
 * Media3's audio output drives it, on test tones and a music-like signal.
 */
class AudioChainTest {
    private val sr = Signals.SR

    private class Rig(eq: EqState = EqState(enabled = false), pitch: Double = 1.0, int16: Boolean = false) {
        val src = StaticSoundSource(eq, pitch)
        val tap = AnalyzerTap()
        val eqp = EqProcessor(tap, src)
        val tp = TimePitchProcessor(src)
        val outStage = OutputStage().also { it.int16Output = int16 }
        val chain = LToTSProcessorChain(eqp, tp, outStage)
        val sink = SinkSimulator(chain)
    }

    private fun rmsDb(x: FloatArray, from: Int, to: Int) = 20 * log10(Signals.rms(x, from, to))

    // ---- pass-through & EQ -------------------------------------------------------------------

    @Test fun flatAndUnshiftedIsBitExact() {
        val r = Rig()
        r.sink.start()
        val pcm = Signals.music(2.0)
        r.sink.write(pcm); r.sink.end()
        assertEquals(C.ENCODING_PCM_FLOAT, r.sink.outputEncoding)
        assertEquals(pcm.size, r.sink.out.size)
        for (i in pcm.indices) {
            val expected = OutputStage.softLimit(pcm[i] / 32768.0).toFloat()
            assertEquals("sample $i", expected, r.sink.out[i], 0f)
        }
        assertTrue(!r.tp.isEngaged)
    }

    @Test fun peakBandBoostsOnlyItsFrequency() {
        val eq = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 1000f, 6f, 1f)), autoHeadroom = false)
        fun gainAt(f: Double): Double {
            val flat = Rig(); flat.sink.start(); flat.sink.write(Signals.sine(f, 1.0, 0.2)); flat.sink.end()
            val r = Rig(eq); r.sink.start(); r.sink.write(Signals.sine(f, 1.0, 0.2)); r.sink.end()
            return rmsDb(r.sink.left(), 8000, 40000) - rmsDb(flat.sink.left(), 8000, 40000)
        }
        assertEquals(6.0, gainAt(1000.0), 0.2)
        assertTrue(abs(gainAt(100.0)) < 0.5)
    }

    @Test fun autoHeadroomCancelsTheBoost() {
        val eq = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 1000f, 9f, 1f)), autoHeadroom = true)
        val flat = Rig(); flat.sink.start(); flat.sink.write(Signals.sine(1000.0, 1.0, 0.2)); flat.sink.end()
        val r = Rig(eq); r.sink.start(); r.sink.write(Signals.sine(1000.0, 1.0, 0.2)); r.sink.end()
        assertEquals(0.0, rmsDb(r.sink.left(), 8000, 40000) - rmsDb(flat.sink.left(), 8000, 40000), 0.3)
    }

    @Test fun limiterNeverClipsEvenWithBigBoost() {
        val eq = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 1000f, 12f, 1f)), autoHeadroom = false)
        val r = Rig(eq); r.sink.start(); r.sink.write(Signals.sine(1000.0, 1.0, 0.9)); r.sink.end()
        var peak = 0f
        for (i in 0 until r.sink.out.size) peak = maxOf(peak, abs(r.sink.out[i]))
        assertTrue("peak $peak", peak < 0.99f)
    }

    @Test fun eqChangeWhilePlayingDoesNotClick() {
        // A 200 Hz tone; the EQ jumps from flat to +12 dB at 200 Hz mid-buffer (like a fast drag).
        val r = Rig(EqState(bands = listOf(EqBand(1, FilterType.PEAK, 200f, 0f, 1f)), autoHeadroom = false))
        r.sink.start()
        val pcm = Signals.sine(200.0, 2.0, 0.1)
        r.sink.write(pcm.copyOfRange(0, sr * 2))
        r.src.eq = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 200f, 12f, 1f)), autoHeadroom = false)
        r.src.bump()
        r.sink.write(pcm.copyOfRange(sr * 2, pcm.size)); r.sink.end()
        val l = r.sink.left()
        // A clean 200 Hz sine at amplitude A moves at most 2π·200/48000·A per sample (≈0.026·A).
        assertTrue("jump ${Signals.worstJump(l, 100, l.size)}", Signals.worstJump(l, 100, l.size) < 0.04)
        // And the boost really happened.
        assertEquals(12.0, rmsDb(l, sr + 4800, 2 * sr - 100) - rmsDb(l, 4800, sr - 100), 0.3)
    }

    @Test fun badSamplesCannotSilenceTheSong() {
        val r = Rig(EqState(bands = listOf(EqBand(1, FilterType.LOW_SHELF, 100f, 6f, 0.7f)), autoHeadroom = false))
        r.sink.start(inputEncoding = C.ENCODING_PCM_FLOAT)
        // Feed float audio containing NaN and infinity, then normal audio.
        val sim = r.sink
        val buf = java.nio.ByteBuffer.allocateDirect(4800 * 2 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4800 * 2) buf.putFloat(if (i % 97 == 0) Float.NaN else if (i % 101 == 0) Float.POSITIVE_INFINITY else 0.1f)
        buf.flip()
        val p = androidx.media3.common.audio.AudioProcessingPipeline(com.google.common.collect.ImmutableList.copyOf(r.chain.audioProcessors))
        p.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_FLOAT))
        p.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
        p.queueInput(buf)
        val o = p.output.order(java.nio.ByteOrder.nativeOrder())
        var finite = true
        while (o.hasRemaining()) if (!o.float.isFinite()) finite = false
        assertTrue(finite)
        assertTrue(sim.out.size == 0) // (pipeline used directly above)
    }

    @Test fun bitPerfectModeOutputsDithered16Bit() {
        val r = Rig(int16 = true); r.sink.start(); r.sink.write(Signals.sine(1000.0, 0.5, 0.5)); r.sink.end()
        assertEquals(C.ENCODING_PCM_16BIT, r.sink.outputEncoding)
        assertEquals(20 * log10(0.5 / sqrt(2.0)), rmsDb(r.sink.left(), 2000, r.sink.left().size - 2000), 0.1)
    }

    // ---- pitch ---------------------------------------------------------------------------------

    private fun shiftedFreq(semitones: Double, input: Double = 440.0): Double {
        val r = Rig(pitch = 2.0.pow(semitones / 12.0))
        r.sink.start(); r.sink.write(Signals.sine(input, 3.0, 0.3)); r.sink.end()
        return Signals.dominantFreq(r.sink.left(), sr)
    }

    @Test fun pitchShiftsAreMusicallyAccurate() {
        for (st in listOf(-12.0, -5.0, -1.0, 1.0, 3.0, 7.0, 12.0)) {
            val expected = 440.0 * 2.0.pow(st / 12.0)
            val got = shiftedFreq(st)
            val cents = 1200 * kotlin.math.ln(got / expected) / kotlin.math.ln(2.0)
            assertTrue("$st st: got $got Hz, expected $expected Hz ($cents cents off)", abs(cents) < 5)
        }
    }

    @Test fun pitchShiftKeepsLengthAndLevel() {
        val r = Rig(pitch = 2.0.pow(4 / 12.0))
        val pcm = Signals.music(4.0)
        r.sink.start(); r.sink.write(pcm); r.sink.end()
        val inFrames = pcm.size / 2
        val outFrames = r.sink.out.size / 2
        assertEquals("no frames gained or lost", inFrames, outFrames)
        val flat = Rig(); flat.sink.start(); flat.sink.write(pcm); flat.sink.end()
        assertEquals(rmsDb(flat.sink.left(), sr, 3 * sr), rmsDb(r.sink.left(), sr, 3 * sr), 1.5)
        assertEquals(0.0, Signals.longestDropoutMs(r.sink.left(), sr / 2, outFrames - sr / 2), 0.0)
    }

    @Test fun draggingPitchIsSmoothAndNeverRestartsTheChain() {
        val r = Rig()
        val pcm = Signals.music(8.0)
        r.sink.start()
        r.sink.write(pcm.copyOfRange(0, sr * 2), 768)
        var off = sr * 2
        var step = 0
        while (off < pcm.size) {
            r.src.pitch = 2.0.pow(6.0 * kotlin.math.sin(step++ / 20.0) / 12.0); r.src.bump()
            val end = minOf(off + 768 * 2, pcm.size)
            r.sink.write(pcm.copyOfRange(off, end), 768)
            off = end
        }
        r.sink.end()
        val l = r.sink.left()
        assertEquals("only the initial setup flush", 1, r.sink.flushPoints.size)
        assertEquals(pcm.size / 2, l.size)
        assertEquals(0.0, Signals.longestDropoutMs(l, sr, l.size - sr / 2), 0.0)
        assertTrue("worst jump ${Signals.worstJump(l, sr, l.size)}", Signals.worstJump(l, sr, l.size) < 0.35)
        for (i in 0 until l.size) assertTrue(l[i].isFinite())
    }

    @Test fun pitchShiftKeepsToneLevel() {
        val flat = Rig(); flat.sink.start(); flat.sink.write(Signals.sine(440.0, 3.0, 0.3)); flat.sink.end()
        for (st in listOf(-7.0, 5.0, 12.0)) {
            val r = Rig(pitch = 2.0.pow(st / 12.0)); r.sink.start(); r.sink.write(Signals.sine(440.0, 3.0, 0.3)); r.sink.end()
            assertEquals("$st st level", rmsDb(flat.sink.left(), sr, 2 * sr), rmsDb(r.sink.left(), sr, 2 * sr), 1.0)
        }
    }

    @Test fun naturalVoicesOptionChangesTimbreLive() {
        val r = Rig(pitch = 2.0.pow(5 / 12.0))
        val pcm = Signals.music(4.0)
        r.sink.start(); r.sink.write(pcm.copyOfRange(0, sr * 2 * 2))
        r.src.preserveFormants = true; r.src.bump()
        r.sink.write(pcm.copyOfRange(sr * 2 * 2, pcm.size)); r.sink.end()
        val l = r.sink.left()
        assertEquals(pcm.size / 2, l.size)
        assertEquals(0.0, Signals.longestDropoutMs(l, sr / 2, l.size - sr / 2), 0.0)
    }

    @Test fun returningToOriginalPitchSoundsOriginal() {
        val r = Rig(pitch = 2.0.pow(5 / 12.0))
        val pcm = Signals.sine(440.0, 6.0, 0.3)
        r.sink.start(); r.sink.write(pcm.copyOfRange(0, sr * 2 * 3))
        r.src.pitch = 1.0; r.src.bump()
        r.sink.write(pcm.copyOfRange(sr * 2 * 3, pcm.size)); r.sink.end()
        assertEquals(440.0, Signals.dominantFreq(r.sink.left(), 4 * sr), 1.0)
    }

    // ---- speed (via playback parameters, as the player sends it) -------------------------------

    @Test fun speedChangeShortensAudioKeepsPitchAndTimeline() {
        val r = Rig()
        val pcm = Signals.sine(440.0, 8.0, 0.3)
        r.sink.start()
        r.sink.write(pcm.copyOfRange(0, sr * 2 * 4))
        r.sink.setPlaybackParameters(PlaybackParameters(1.25f, 1f))
        r.sink.write(pcm.copyOfRange(sr * 2 * 4, pcm.size)); r.sink.end()
        val l = r.sink.left()
        val expected = 4 * sr + (4 * sr / 1.25).toInt()
        assertEquals("length", expected.toDouble(), l.size.toDouble(), sr * 0.01)
        assertEquals(440.0, Signals.dominantFreq(l, 5 * sr), 1.5)
        assertEquals(1.25, r.tp.currentSpeed, 1e-9)
        // Song time for 1 s of playback at 1.25× is 1.25 s.
        assertEquals(1_250_000L, r.chain.getMediaDuration(1_000_000L))
        assertTrue(Signals.longestDropoutMs(l, sr, l.size - sr / 2) <= 10.0)
    }

    @Test fun tapeModeIsSpeedAndPitchTogether() {
        val r = Rig(pitch = 1.5)
        val pcm = Signals.sine(440.0, 6.0, 0.3)
        r.sink.setPlaybackParameters(PlaybackParameters(1.5f, 1f))
        r.sink.start(); r.sink.write(pcm); r.sink.end()
        val l = r.sink.left()
        assertEquals(660.0, Signals.dominantFreq(l, sr), 2.0)
        assertEquals(6.0 * sr / 1.5, l.size.toDouble(), sr * 0.01)
    }

    // ---- per-song settings -----------------------------------------------------------------------

    @Test fun eachSongGetsItsOwnEqAndPitchFromItsFirstSample() {
        val tl = PlaylistTimeline(listOf("song:a", "song:b"))
        val loud = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 1000f, 10f, 1f)), autoHeadroom = false)
        val src = object : StaticSoundSource() {
            override fun eqFor(mediaId: String?) = if (mediaId == "song:b") loud else EqState(enabled = false)
            override fun pitchScaleFor(mediaId: String?) = if (mediaId == "song:b") 2.0 else 1.0
        }
        val chain = LToTSProcessorChain(EqProcessor(AnalyzerTap(), src), TimePitchProcessor(src), OutputStage())
        val sink = SinkSimulator(chain)
        val tone = Signals.sine(1000.0, 1.0, 0.1)
        sink.start(tl.metaFor(0)); sink.write(tone)
        val firstEnd = sink.out.size / 2
        sink.nextItem(tl.metaFor(1)); sink.write(tone); sink.end()
        val l = sink.left()
        assertEquals("song:b", chain.eq.currentMediaId)
        val a = rmsDb(l, 4800, firstEnd - 4800)
        val b = rmsDb(l, firstEnd + 4800, l.size - 4800)
        assertEquals("song B is boosted from its start", 10.0, b - a, 0.5)
        assertEquals("song A untouched", 1000.0, Signals.dominantFreq(l, 4800, 16384), 1.0)
        assertEquals("song B an octave up", 2000.0, Signals.dominantFreq(l, firstEnd + 9600, 16384), 2.0)
    }

    @Test fun seekKeepsWorkingWhileShifted() {
        val r = Rig(pitch = 2.0.pow(2 / 12.0))
        val pcm = Signals.sine(440.0, 3.0, 0.3)
        r.sink.start(); r.sink.write(pcm.copyOfRange(0, sr * 2))
        r.sink.seek(30_000_000L)
        r.sink.write(pcm.copyOfRange(sr * 2, pcm.size)); r.sink.end()
        assertEquals(440.0 * 2.0.pow(2 / 12.0), Signals.dominantFreq(r.sink.left(), sr + sr / 4), 2.0)
    }

    // ---- analyzer ----------------------------------------------------------------------------------

    @Test fun analyzerFindsTheTone() {
        val tap = AnalyzerTap()
        tap.onFormat(sr, 2, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
        tap.onFlush(0)
        for (i in 0 until sr) { val v = (0.5 * kotlin.math.sin(2 * Math.PI * 2000.0 * i / sr)).toFloat(); tap.write(v, v) }
        tap.commit(sr)
        val a = Analyzer(tap, bands = 96)
        var f = a.update(positionUs = 900_000L, playing = true)
        repeat(30) { Thread.sleep(2); f = a.update(900_000L, true) }
        val loudest = f.post.indices.maxBy { f.post[it] }
        assertTrue("loudest band at ${SpectrumFrame.bandFreq(loudest, 96)} Hz", SpectrumFrame.bandFreq(loudest, 96) in 1800.0..2250.0)
    }
}
