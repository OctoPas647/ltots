package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/**
 * The native side of the screen-off power governor: bigger processing blocks give the same
 * sound, suspended visual FFTs do no work, beat FFTs stop only on non-essential threads, and
 * moving threads between cores never breaks anything.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ScreenOffPowerTest {
    @After fun tearDown() {
        NativeDsp.suspendNonEssential(0)
        NativeDsp.preferBlockFrames(NativeDsp.SHORT_BLOCK_FRAMES)
    }

    private fun resample(block: Int, speed: Double, input: FloatArray, callFrames: Int): FloatArray {
        NativeDsp.preferBlockFrames(block)
        val h = NativeDsp.resamplerCreate(2, speed)
        val out = ArrayList<Float>()
        val o = FloatArray(2 * (callFrames * 2 + 1024))
        var i = 0
        val frames = input.size / 2
        while (i < frames) {
            val n = minOf(callFrames, frames - i)
            val got = NativeDsp.resamplerProcess(h, input.copyOfRange(i * 2, (i + n) * 2), n, o, o.size / 2, false)
            for (k in 0 until got * 2) out += o[k]
            i += n
        }
        val tail = FloatArray(2 * 65536)
        val got = NativeDsp.resamplerProcess(h, FloatArray(0), 0, tail, 65536, true)
        for (k in 0 until got * 2) out += tail[k]
        NativeDsp.resamplerDestroy(h)
        return out.toFloatArray()
    }

    @Test fun tapeResamplerSoundsTheSameWithTheScreenOffBlock() {
        val tone = Signals.sine(440.0, 4.0)
        val input = FloatArray(tone.size) { tone[it] / 32768f }
        for (speed in listOf(0.8, 1.25)) {
            val short = resample(NativeDsp.SHORT_BLOCK_FRAMES, speed, input, 1152)
            val long = resample(NativeDsp.LONG_BLOCK_FRAMES, speed, input, 1152)
            // Compacting less often changes nothing about the result: bit for bit the same.
            assertArrayEquals("speed $speed", short, long, 0f)
        }
    }

    private fun rubberBand(block: Int): FloatArray {
        NativeDsp.preferBlockFrames(block)
        val tp = TimePitchProcessor(StaticSoundSource(pitch = 1.12))
        tp.setSpeed(1.0)
        tp.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        tp.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals("rubberband", tp.engineName)
        val tone = Signals.sine(330.0, 3.0)
        val out = ArrayList<Float>()
        fun take(b: ByteBuffer) {
            val f = b.order(ByteOrder.nativeOrder()).asFloatBuffer()
            while (f.hasRemaining()) out += f.get()
            b.position(b.limit())
        }
        var i = 0
        while (i < tone.size) {
            // Decoder-sized buffers (1152 frames), like an MP3.
            val n = minOf(1152 * 2, tone.size - i)
            val b = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (j in 0 until n) b.putFloat(tone[i + j] / 32768f)
            b.flip()
            tp.queueInput(b)
            take(tp.output)
            i += n
        }
        tp.queueEndOfStream()
        take(tp.output)
        tp.reset()
        return out.toFloatArray()
    }

    private fun rms(x: FloatArray, from: Int): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i].toDouble() * x[i]
        return sqrt(s / maxOf(1, x.size - from))
    }

    @Test fun rubberBandTakesTheBigBlockAndSoundsTheSame() {
        val short = rubberBand(NativeDsp.SHORT_BLOCK_FRAMES)
        val long = rubberBand(NativeDsp.LONG_BLOCK_FRAMES)
        assertTrue(long.all { it.isFinite() })
        // Same length (pitch only, so the song's length doesn't change) and the same loudness.
        assertEquals(short.size.toDouble(), long.size.toDouble(), short.size * 0.01)
        assertEquals(3 * 48000 * 2.0, long.size.toDouble(), 3 * 48000 * 2.0 * 0.03)
        val skip = 48000 // past the start-up
        assertEquals(rms(short, skip), rms(long, skip), rms(short, skip) * 0.05)
    }

    @Test fun visualSpectrumDoesNoWorkWhileSuspended() {
        val (plan, lock) = NativeDsp.plan(1024)!!
        val ring = FloatArray(1 shl 12) { kotlin.math.sin(it * 0.3).toFloat() }
        val win = FloatArray(1024) { 1f }
        val mags = FloatArray(512)
        synchronized(lock) { NativeDsp.spectrum(plan, ring, ring.size - 1, 0, win, mags, 0, 512, 1f) }
        assertTrue("live: the bars move", mags.any { it > 0.01f })

        val before = NativeDsp.skippedCount()
        NativeDsp.suspendNonEssential(NativeDsp.SUSPEND_VISUALS)
        assertTrue(NativeDsp.visualsSuspended)
        assertEquals(NativeDsp.SUSPEND_VISUALS, NativeDsp.suspendMask())
        synchronized(lock) { NativeDsp.spectrum(plan, ring, ring.size - 1, 0, win, mags, 0, 512, 1f) }
        assertTrue("suspended: silence, no FFT", mags.all { it == 0f })
        assertEquals(before + 1, NativeDsp.skippedCount())

        // The analyzer doesn't even ask: its bars just fall.
        val tap = AnalyzerTap()
        val release = tap.watch()
        assertTrue(tap.watching)
        tap.suspended = true
        assertFalse("the audio thread copies nothing for the view", tap.watching)
        tap.suspended = false
        assertTrue(tap.watching)
        release()
    }

    @Test fun beatFftsStopOnlyOnNonEssentialThreads() {
        val fft = com.ltots.app.identify.MixedFft(1536)
        val re = DoubleArray(1536) { kotlin.math.sin(it * 0.05) }
        val im = DoubleArray(1536)
        NativeDsp.suspendNonEssential(NativeDsp.SUSPEND_BEATS)
        // Song identification, the EQ and everything else on an ordinary thread keep working.
        fft.forward(re.copyOf(), im.copyOf())

        var thrown: Throwable? = null
        var after: Throwable? = null
        val t = Thread {
            NativeDsp.markCurrentThread(NativeDsp.THREAD_BACKGROUND or NativeDsp.THREAD_NON_ESSENTIAL)
            thrown = runCatching { fft.forward(re.copyOf(), im.copyOf()) }.exceptionOrNull()
            NativeDsp.suspendNonEssential(0)
            after = runCatching { fft.forward(re.copyOf(), im.copyOf()) }.exceptionOrNull()
        }
        t.start(); t.join()
        assertTrue("a beat FFT is refused: $thrown", thrown is CancellationException)
        assertEquals("and works again once resumed", null, after)
    }

    @Test fun movingThreadsBetweenCoresIsSafeEverywhere() {
        val cores = NativeDsp.efficiencyCoreCount()
        assertTrue(cores >= 0)
        val pinned = NativeDsp.setThreadEfficiency(true)
        val freed = NativeDsp.setThreadEfficiency(false)
        if (cores == 0) {
            assertEquals("one kind of core: nothing to do", 0, pinned)
            assertEquals(0, freed)
        } else {
            assertTrue(pinned == cores || pinned < 0)
            assertTrue(freed > cores || freed < 0)
        }
        // The audio thread's hook: applies once per change, then costs nothing.
        EfficiencyCores.setEfficient(true)
        EfficiencyCores.onAudioThread()
        EfficiencyCores.onAudioThread()
        EfficiencyCores.setEfficient(false)
        EfficiencyCores.onAudioThread()
        assertFalse(EfficiencyCores.audioThreadEfficient)
    }
}
