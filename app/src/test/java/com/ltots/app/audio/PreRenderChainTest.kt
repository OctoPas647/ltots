package com.ltots.app.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.data.Store
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pre-render's processing: the same EQ and pitch engine as live playback, run over a whole song,
 * gives exactly the live sound and keeps the song's length.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PreRenderChainTest {
    private val sr = 48000
    private val rock get() = Store.builtInPresets.first { it.name == "Rock" }.eq

    private fun pcm16(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply { asShortBuffer().put(samples) }

    /** Interleaved stereo [st] run through [job] in blocks, like a decoder delivers them. */
    private fun render(st: ShortArray, job: PreRender.Job, block: Int = 4096): FloatArray {
        val chain = PreRender.OfflineChain(sr, 2, C.ENCODING_PCM_16BIT, job)
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i < st.size) {
            val n = minOf(block * 2, st.size - i)
            chain.process(pcm16(st.copyOfRange(i, i + n))) { out += it }
            i += n
        }
        chain.finish { out += it }
        val all = FloatArray(out.sumOf { it.size })
        var p = 0
        for (f in out) { f.copyInto(all, p); p += f.size }
        return all
    }

    @Test fun eqMatchesLivePlaybackExactly() {
        val music = Signals.music(6.0)
        val job = PreRender.Job(Uri.EMPTY, rock, 1.0, false, false)
        val offline = render(music, job)

        // The live processor, fed the same way the player would.
        val live = EqProcessor(AnalyzerTap(), StaticSoundSource(eq = rock))
        live.configure(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        live.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val st = music
        val got = ArrayList<Float>()
        var i = 0
        while (i < st.size) {
            val n = minOf(4096 * 2, st.size - i)
            live.queueInput(pcm16(st.copyOfRange(i, i + n)))
            val o = live.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
            while (o.hasRemaining()) got += o.get()
            i += n
        }
        assertEquals(st.size, offline.size)
        assertArrayEquals(got.toFloatArray(), offline, 0f)
        // And the EQ really did something.
        val dry = FloatArray(st.size) { st[it] / 32768f }
        assertTrue(offline.indices.any { abs(offline[it] - dry[it]) > 1e-3f })
    }

    @Test fun pitchKeepsLengthAndMovesTheKey() {
        val tone = Signals.sine(440.0, 6.0)
        val semis = 3
        val job = PreRender.Job(Uri.EMPTY, EqState(enabled = false), 2.0.pow(semis / 12.0), false, false)
        val out = render(tone, job)
        val frames = out.size / 2
        val inFrames = tone.size / 2
        assertTrue("length kept: $frames vs $inFrames", abs(frames - inFrames) <= inFrames / 100)
        val left = FloatArray(frames) { out[it * 2] }
        val f = Signals.dominantFreq(left, frames / 3)
        val want = 440.0 * 2.0.pow(semis / 12.0)
        assertTrue("dominant $f Hz, wanted $want Hz", abs(f - want) < 6.0)
    }
}
