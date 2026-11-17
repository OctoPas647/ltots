package com.ltots.app.rooms

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutputProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The main's output: ExoPlayer writes into it like an AudioTrack, and the room gets the music
 * stamped with when it's to be heard; pausing, seeking and songs ending behave as they should.
 */
class RoomOutputTest {
    private val chunks = ArrayList<AudioChunk>()
    private val cuts = ArrayList<Pair<Int, Long>>()

    @Before fun setUp() {
        GroupClock.lead()
        RoomStream.active = true
        RoomStream.delayNs = 300_000_000L
        RoomStream.pendingAnchorNs = 0L
        RoomStream.continueAtNs = 0L
        RoomStream.stopAtNs = Long.MAX_VALUE
        RoomStream.listener = object : RoomStream.Listener {
            override fun onChunk(chunk: AudioChunk, packet: ByteArray) { synchronized(chunks) { chunks += chunk } }
            override fun onCut(epoch: Int, atNs: Long) { synchronized(cuts) { cuts += epoch to atNs } }
        }
    }

    @After fun tearDown() {
        RoomStream.active = false
        RoomStream.listener = null
    }

    private fun output() = RoomAudioOutput(
        AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT).setSampleRate(48000).setChannelMask(12).build()
    )

    private fun frames(n: Int, start: Int = 0): ByteBuffer {
        val b = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
        for (i in 0 until n) { val v = ((start + i) % 1000) / 1000f; b.putFloat(v); b.putFloat(-v) }
        b.flip()
        return b
    }

    @Test fun musicIsStampedAheadAndContiguous() {
        val out = output()
        assertTrue(out.write(frames(24000), 1, 0))
        val before = System.nanoTime()
        out.play()
        assertTrue(chunks.isNotEmpty())
        val first = chunks.first()
        val lead = first.groupNs - before
        assertTrue("first frame ${lead / 1e6} ms ahead", lead in 290_000_000L..330_000_000L)
        for (i in 1 until chunks.size) {
            val a = chunks[i - 1]; val b = chunks[i]
            assertEquals(a.startFrame + a.frames, b.startFrame)
            assertTrue(abs(b.groupNs - a.endNs) <= 1)
        }
        assertEquals(24000, chunks.sumOf { it.frames })
        // The samples arrive intact (frame 1234, left and right).
        val c = chunks.first { 1234 in it.startFrame until it.startFrame + it.frames }
        val k = (1234 - c.startFrame).toInt() * 2
        assertEquals(0.234f, c.samples[k], 1e-6f)
        assertEquals(-0.234f, c.samples[k + 1], 1e-6f)
        // Nothing is "played" until its moment comes.
        assertTrue(out.positionUs < 20_000)
        Thread.sleep(420)
        val pos = out.positionUs
        assertTrue("position $pos µs", pos in 60_000L..200_000L)
    }

    @Test fun neverMoreThanDelayPlusALittleAhead() {
        val out = output()
        out.play()
        var accepted = 0
        repeat(20) {
            val b = frames(4800)
            out.write(b, 1, 0)
            accepted += (4800 - b.remaining() / 8)
        }
        // 0.3 s delay + 0.25 s headroom: about 26,400 frames, not the 96,000 offered.
        assertTrue("accepted $accepted", accepted in 25_000..28_000)
        val b = frames(4800)
        assertFalse(out.write(b, 1, 0))
    }

    @Test fun pauseCutsAndResumeCarriesOnFromWhereItWasHeard() {
        val out = output()
        out.write(frames(48000 / 2), 1, 0)
        out.play()
        val epoch = chunks.first().epoch
        Thread.sleep(400) // ~100 ms heard
        out.pause()
        assertEquals(1, cuts.size)
        assertEquals(epoch, cuts[0].first)
        val heard = out.positionUs * 48 / 1000
        chunks.clear()
        out.play()
        val resumed = chunks.first()
        assertTrue(resumed.epoch != epoch)
        assertTrue("resumes at ${resumed.startFrame}, heard $heard", abs(resumed.startFrame - heard) < 500)
    }

    @Test fun nextSongCarriesOnWithoutAGap_seekDoesNot() {
        val a = output()
        a.write(frames(4800), 1, 0)
        a.play()
        val end = chunks.last().endNs
        a.stop()
        // Ran out: everything counts as handed over, so ExoPlayer moves on straight away.
        assertEquals(100_000L, a.positionUs)
        a.release()
        assertTrue("no cut when a song simply ends", cuts.isEmpty())
        chunks.clear()
        val b = output()
        b.write(frames(4800), 1, 0)
        b.play()
        assertEquals("the next song starts where the last one ended", end, chunks.first().groupNs)
        // A seek releases without stopping: what's queued is cut.
        b.release()
        assertEquals(1, cuts.size)
    }

    @Test fun handoffStartsAtTheAgreedMoment() {
        val at = GroupClock.now() + 2_000_000_000L
        RoomStream.pendingAnchorNs = at
        val out = output()
        out.write(frames(4800), 1, 0)
        out.play()
        assertEquals(at, chunks.first().groupNs)
        assertEquals(0L, RoomStream.pendingAnchorNs)
    }

    /**
     * ExoPlayer topping up every 0.1 s (what the short reported buffer makes it do): every piece
     * of music leaves at least the full delay before it's due — never in bursts that leave a
     * phone with Bluetooth's 0.3 s waiting (the "music, nothing, music" bug).
     */
    @Test fun everyPieceLeavesAtLeastTheDelayAhead() {
        val leads = ArrayList<Long>()
        RoomStream.listener = object : RoomStream.Listener {
            override fun onChunk(chunk: AudioChunk, packet: ByteArray) { leads += chunk.groupNs - GroupClock.now() }
            override fun onCut(epoch: Int, atNs: Long) {}
        }
        val out = output()
        assertEquals(200_000L * 48, out.bufferSizeInFrames * 1000)
        out.play()
        val end = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < end) {
            out.write(frames(48000), 1, 0)
            Thread.sleep(100)
        }
        val min = leads.minOrNull()!!
        assertTrue("tightest lead ${min / 1e6} ms", min >= RoomStream.delayNs - 15_000_000L)
    }

    @Test fun moreDelayMeansMoreSentAhead() {
        val out = output()
        out.play()
        RoomStream.extendDelay(900_000_000L)
        var accepted = 0
        repeat(30) { val b = frames(4800); out.write(b, 1, 0); accepted += (4800 - b.remaining() / 8) }
        // 0.9 s + 0.25 s.
        assertTrue("accepted $accepted", accepted in 54_000..56_500)
        RoomStream.extendDelay(500_000_000L) // never lowers
        assertEquals(900_000_000L, RoomStream.delayNs)
    }
}
