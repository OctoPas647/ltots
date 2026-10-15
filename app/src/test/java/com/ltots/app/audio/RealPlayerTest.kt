package com.ltots.app.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * The real ExoPlayer and Media3 audio output (DefaultAudioSink) with LToTS' processing chain and
 * the real Rubber Band engine — the same objects the phone uses — playing a song through an
 * output that plays in real time like the phone's ([RealtimeOutput]), while speed and pitch are
 * changed the way the Playing screen changes them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class RealPlayerTest {
    private lateinit var ctx: Context
    private lateinit var player: ExoPlayer
    private lateinit var clock: FakeClock
    private lateinit var source: StaticSoundSource
    private lateinit var chain: LToTSProcessorChain
    private lateinit var outputs: RealtimeOutput.Companion.Provider
    private val errors = mutableListOf<Throwable>()
    private var stockSink = false

    @Before fun setUp() = build()

    private fun build() {
        ctx = ApplicationProvider.getApplicationContext()
        clock = FakeClock(/* isAutoAdvancing = */ true)
        source = StaticSoundSource()
        chain = LToTSProcessorChain(EqProcessor(AnalyzerTap(), source), TimePitchProcessor(source), OutputStage())
        outputs = RealtimeOutput.Companion.Provider(ctx, clock)
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            val sink = if (stockSink) DefaultAudioSink.Builder(ctx).setAudioOutputProvider(outputs).build()
            else AudioEngine.buildSink(ctx, chain, outputs)
            arrayOf(MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, audioListener, sink))
        }
        player = TestExoPlayerBuilder(ctx).setClock(clock).setRenderersFactory(renderers).build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { errors += error }
        })
    }

    @After fun tearDown() = player.release()

    private fun song(seconds: Int): Uri {
        val f = File(ctx.cacheDir, "song_$seconds.wav")
        if (!f.exists()) writeWav(f, seconds)
        return Uri.fromFile(f)
    }

    private fun startSong(seconds: Int) {
        player.setMediaItem(MediaItem.Builder().setMediaId("song:test").setUri(song(seconds)).build())
        player.prepare()
        player.play()
    }

    private val trace = mutableListOf<Triple<Long, Long, Float>>() // clock ms, position ms, speed

    /** Advances the clock in small steps, recording where the player says it is. */
    private fun runFor(ms: Long) {
        val end = clock.elapsedRealtime() + ms
        while (clock.elapsedRealtime() < end && player.playbackState != Player.STATE_ENDED) {
            TestPlayerRunHelper.advance(player).untilPendingCommandsAreFullyHandled()
            clock.advanceTime(50)
            trace += Triple(clock.elapsedRealtime(), player.currentPosition, player.playbackParameters.speed)
            assertTrue("player error: $errors", errors.isEmpty())
        }
    }

    /** The position must move at [speed]× real time (±8 %), never freeze and never jump. */
    private fun assertMovesAtSpeed(fromIndex: Int, speed: Float) {
        val seg = trace.subList(fromIndex, trace.size)
        // Ignore the first half second while audio processed at the old speed plays out.
        val settled = seg.dropWhile { it.first - seg.first().first < 600 }
        if (settled.size < 6) return
        val dt = settled.last().first - settled.first().first
        val dp = settled.last().second - settled.first().second
        val rate = dp.toFloat() / dt
        assertTrue("position moved at ${"%.2f".format(rate)}× while speed was $speed× (trace ${settled.take(3)} … ${settled.takeLast(3)})", abs(rate - speed) <= 0.08f * speed)
        var still = 0
        for (i in 1 until settled.size) {
            val step = settled[i].second - settled[i - 1].second
            assertTrue("position jumped by $step ms", step <= 50 * speed * 3 + 20)
            still = if (step <= 0) still + 1 else 0
            assertTrue("position froze", still < 6)
        }
    }

    private fun setSpeed(s: Float) {
        player.playbackParameters = PlaybackParameters(s, 1f)
    }

    private fun output() = outputs.outputs.last()

    @Test fun plainPlaybackKeepsRealTime() {
        startSong(12)
        val i = trace.size
        runFor(6_000)
        assertMovesAtSpeed(i, 1f)
        runFor(10_000)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertEquals("underruns", 0, output().underruns)
    }

    @Test fun speedChangesWhilePlayingKeepTimeAndSound() {
        startSong(80)
        runFor(2_000)
        for (s in listOf(1.25f, 0.8f, 1.5f, 0.5f, 2f, 1f, 1.1f)) {
            setSpeed(s)
            val i = trace.size
            runFor(4_000)
            assertMovesAtSpeed(i, s)
        }
        runFor(120_000)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        val out = output().left
        val x = FloatArray(out.size) { out[it] }
        assertEquals("underruns", 0, output().underruns)
        assertTrue("dropout ${Signals.longestDropoutMs(x, 4800, x.size - 4800)} ms", Signals.longestDropoutMs(x, 4800, x.size - 4800) < 30)
    }

    @Test fun rapidSpeedTapsNeverStall() {
        // The ±0.01 / ±0.05 buttons tapped quickly: many changes before any drain finishes.
        startSong(40)
        runFor(1_000)
        var s = 1f
        repeat(30) { k ->
            s = (s + if (k % 3 == 0) -0.05f else 0.01f).coerceIn(0.5f, 2f)
            setSpeed(s)
            runFor(100)
        }
        val i = trace.size
        runFor(5_000)
        assertMovesAtSpeed(i, s)
        runFor(120_000)
        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    @Test fun pitchDragWhileSpedUpAndSeeking() {
        startSong(40)
        setSpeed(1.3f)
        runFor(3_000)
        for (st in -6..6) { source.pitch = Math.pow(2.0, st / 12.0); source.bump(); runFor(60) }
        val i = trace.size
        runFor(3_000)
        assertMovesAtSpeed(i, 1.3f)
        player.seekTo(20_000)
        runFor(3_000)
        setSpeed(1f)
        source.pitch = 1.0; source.bump()
        runFor(120_000)
        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    @Test fun stockMedia3SpeedForComparison() {
        player.release(); stockSink = true; build()
        startSong(30)
        runFor(2_000)
        setSpeed(1.25f)
        val i = trace.size
        runFor(4_000)
        assertMovesAtSpeed(i, 1.25f)
    }

    companion object {
        /** A music-like test song: a chord with a beat, 48 kHz stereo 16-bit WAV. */
        fun writeWav(f: File, seconds: Int, sr: Int = 48000) {
            val frames = seconds * sr
            val data = ByteArray(frames * 4)
            var p = 0
            for (i in 0 until frames) {
                val t = i.toDouble() / sr
                val beat = exp(-((t % 0.5) * 18))
                val v = 0.12 * (sin(2 * PI * 220 * t) + sin(2 * PI * 277.18 * t) + sin(2 * PI * 329.63 * t)) +
                    0.25 * beat * sin(2 * PI * 60 * t)
                val s = (v.coerceIn(-1.0, 1.0) * 32767).toInt()
                repeat(2) { data[p++] = (s and 0xFF).toByte(); data[p++] = (s shr 8).toByte() }
            }
            RandomAccessFile(f, "rw").use { r ->
                r.setLength(0)
                fun i32(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
                fun i16(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte())) }
                r.write("RIFF".toByteArray()); i32(36 + data.size); r.write("WAVE".toByteArray())
                r.write("fmt ".toByteArray()); i32(16); i16(1); i16(2); i32(sr); i32(sr * 4); i16(4); i16(16)
                r.write("data".toByteArray()); i32(data.size); r.write(data)
            }
        }
    }
}
