package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.data.Store
import com.ltots.app.identify.MixedFft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The native DSP gives the same results as the Kotlin it replaces: the EQ cascade, the FFTs,
 * and tape-mode resampling (right length, right pitch, clean).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class NativeDspTest {
    @Test fun libraryLoads() = assertTrue(NativeDsp.available)

    @Test fun cascadeMatchesKotlin() {
        val r = Random(5)
        val ch = 2; val frames = 4096; val nsec = 9
        val bands = List(nsec) { i -> EqBand(i.toLong(), FilterType.entries[i % 3], 40f * (1 shl i), (r.nextFloat() - 0.5f) * 18f, 0.5f + r.nextFloat() * 3f) }
        val coefs = bands.map { BiquadCoefs.design(it, 48000.0) }
        val k = DoubleArray(nsec * 5).also { a -> coefs.forEachIndexed { i, c -> a[i * 5] = c.b0; a[i * 5 + 1] = c.b1; a[i * 5 + 2] = c.b2; a[i * 5 + 3] = c.a1; a[i * 5 + 4] = c.a2 } }
        val x = DoubleArray(frames * ch) { r.nextDouble() * 2 - 1 }
        // Kotlin reference (transposed direct form II, gain first).
        val ref = x.copyOf()
        val z1 = Array(nsec) { DoubleArray(ch) }; val z2 = Array(nsec) { DoubleArray(ch) }
        for (f in 0 until frames) for (c in 0 until ch) {
            var y = ref[f * ch + c] * 0.7
            for (s in 0 until nsec) {
                val q = coefs[s]; val inp = y
                y = q.b0 * inp + z1[s][c]
                z1[s][c] = q.b1 * inp - q.a1 * y + z2[s][c]
                z2[s][c] = q.b2 * inp - q.a2 * y
            }
            ref[f * ch + c] = y
        }
        // Native, in two blocks (the memory must carry over).
        val st = DoubleArray(nsec * 2 * ch)
        val a = x.copyOfRange(0, 1000 * ch); val b = x.copyOfRange(1000 * ch, x.size)
        NativeDsp.cascade(a, 1000, ch, 0.7, k, st, nsec)
        NativeDsp.cascade(b, frames - 1000, ch, 0.7, k, st, nsec)
        val got = a + b
        for (i in ref.indices) assertEquals("sample $i", ref[i], got[i], 1e-9)
    }

    @Test fun eqProcessorStillMatchesItsResponse() {
        // A peak at 1 kHz, +9 dB: a 1 kHz tone comes out 9 dB louder.
        val eq = EqState(enabled = true, autoHeadroom = false, bands = listOf(EqBand(1, FilterType.PEAK, 1000f, 9f, 1f)))
        val p = EqProcessor(AnalyzerTap(), StaticSoundSource(eq = eq))
        p.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val tone = Signals.sine(1000.0, 1.0, amp = 0.1)
        val buf = ByteBuffer.allocateDirect(tone.size * 2).order(ByteOrder.nativeOrder()).apply { asShortBuffer().put(tone) }
        p.queueInput(buf)
        val out = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val y = FloatArray(out.remaining()).also { out.get(it) }
        val inRms = Signals.rms(FloatArray(tone.size) { tone[it] / 32768f }, tone.size / 2, tone.size)
        val outRms = Signals.rms(y, y.size / 2, y.size)
        assertEquals(9.0, Signals.db(outRms / inRms), 0.2)
    }

    @Test fun fftsMatchKotlin() {
        val r = Random(9)
        // Mixed radix (double) against a plain DFT.
        for (n in listOf(48, 2048, 6144)) {
            val re = DoubleArray(n) { r.nextDouble() - 0.5 }; val im = DoubleArray(n) { r.nextDouble() - 0.5 }
            val fre = re.copyOf(); val fim = im.copyOf()
            MixedFft(n).forward(fre, fim)
            for (k in listOf(0, 1, n / 3, n / 2, n - 1)) {
                var sr = 0.0; var si = 0.0
                for (t in 0 until n) { val a = -2 * PI * k * t / n; sr += re[t] * cos(a) - im[t] * sin(a); si += re[t] * sin(a) + im[t] * cos(a) }
                assertEquals("n=$n k=$k re", sr, fre[k], 1e-6 * n); assertEquals("n=$n k=$k im", si, fim[k], 1e-6 * n)
            }
        }
        // The spectrum's float FFT against a plain DFT.
        val n = 1024
        val re = FloatArray(n) { r.nextFloat() - 0.5f }; val im = FloatArray(n)
        val orig = re.copyOf()
        Fft(n).transform(re, im)
        for (k in listOf(1, 100, 511)) {
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) { val a = 2 * PI * k * t / n; sr += orig[t] * cos(a); si -= orig[t] * sin(a) }
            assertEquals(sr, re[k].toDouble(), 1e-2); assertEquals(si, im[k].toDouble(), 1e-2)
        }
    }

    @Test fun tapeResamplingHasTheRightLengthAndPitch() {
        for (speed in listOf(0.8, 1.25, 1.5)) {
            val tone = Signals.sine(440.0, 4.0)
            val inFrames = tone.size / 2
            val input = FloatArray(tone.size) { tone[it] / 32768f }
            val h = NativeDsp.resamplerCreate(2, speed)
            val out = ArrayList<Float>()
            val o = FloatArray(1 shl 17)
            var i = 0
            while (i < inFrames) {
                val n = minOf(3000, inFrames - i)
                val got = NativeDsp.resamplerProcess(h, input.copyOfRange(i * 2, (i + n) * 2), n, o, o.size / 2, false)
                for (j in 0 until got * 2) out += o[j]
                i += n
            }
            val got = NativeDsp.resamplerProcess(h, FloatArray(0), 0, o, o.size / 2, true)
            for (j in 0 until got * 2) out += o[j]
            NativeDsp.resamplerDestroy(h)
            val frames = out.size / 2
            assertEquals("speed $speed length", (inFrames / speed).toInt().toDouble(), frames.toDouble(), 1.0)
            val left = FloatArray(frames) { out[it * 2] }
            val f = Signals.dominantFreq(left, frames / 4)
            assertEquals("speed $speed pitch", 440.0 * speed, f, 1.5)
            // Level kept, no clicks.
            assertEquals(Signals.rms(input.filterIndexed { k, _ -> k % 2 == 0 }.toFloatArray(), 0, inFrames / 2),
                Signals.rms(left, frames / 4, frames / 2), 0.01)
            // A sine's own step at this pitch is 2π·f/48000 of its peak; anything well above is a click.
            assertTrue("smooth", Signals.worstJump(left, 100, frames - 100) < 2 * PI * 440 * speed / 48000 * 1.3)
        }
    }

    @Test fun pitchEnginesAreChosenByWhatChanges() {
        fun run(pitch: Double, speed: Double, lightest: Boolean): Pair<String, Int> {
            val src = StaticSoundSource(pitch = pitch, lightestSpeed = lightest)
            val tp = TimePitchProcessor(src)
            tp.setSpeed(speed)
            tp.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
            tp.flush(AudioProcessor.StreamMetadata.DEFAULT)
            val engine = tp.engineName
            val tone = Signals.sine(440.0, 3.0)
            var outFrames = 0
            var i = 0
            while (i < tone.size) {
                val n = minOf(4096 * 2, tone.size - i)
                val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
                for (j in 0 until n) b.putFloat(tone[i + j] / 32768f)
                b.flip()
                tp.queueInput(b)
                outFrames += tp.output.remaining() / 8
                i += n
            }
            tp.queueEndOfStream()
            outFrames += tp.output.remaining() / 8
            return engine to outFrames
        }
        val inFrames = 3 * 48000
        val tape = run(1.25, 1.25, false)
        assertEquals("tape", tape.first)
        assertEquals(inFrames / 1.25, tape.second.toDouble(), 2.0)
        val sonic = run(1.0, 1.25, true)
        assertEquals("sonic", sonic.first)
        assertEquals(inFrames / 1.25, sonic.second.toDouble(), inFrames * 0.01)
        assertEquals("rubberband", run(1.0, 1.25, false).first)
        assertEquals("rubberband", run(1.1, 1.0, true).first)
        assertEquals("none", run(1.0, 1.0, true).first)
    }

    @Test fun bassBoostHelpsBassHeavyCurves() {
        val layout = PhoneEqLayout.STANDARD
        val bass = EqState(enabled = true, bands = listOf(EqBand(1, FilterType.LOW_SHELF, 60f, 12f, 0.7f)))
        val (levels, db) = BatteryEqFit.fitWithBass(bass, layout)
        assertTrue("bass boost used: $db", db > 1f)
        assertTrue(BatteryEqFit.errorDb(bass, layout, levels, db) < BatteryEqFit.errorDb(bass, layout, BatteryEqFit.fit(bass, layout)))
        val treble = EqState(enabled = true, bands = listOf(EqBand(1, FilterType.HIGH_SHELF, 6000f, 6f, 0.7f)))
        assertEquals("not for treble", 0f, BatteryEqFit.fitWithBass(treble, layout).second, 0f)
        val rock = Store.builtInPresets.first { it.name == "Rock" }.eq
        val (l2, d2) = BatteryEqFit.fitWithBass(rock, layout)
        assertTrue(BatteryEqFit.errorDb(rock, layout, l2, d2) <= BatteryEqFit.errorDb(rock, layout, BatteryEqFit.fit(rock, layout)) + 1e-9)
    }
}
