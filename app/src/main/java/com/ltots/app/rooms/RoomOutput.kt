package com.ltots.app.rooms

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * The main phone's side of the room: where LToTS's processed music (after your EQ, pitch and
 * speed) goes instead of this phone's own speaker.
 *
 * ExoPlayer writes into a [RoomAudioOutput] exactly as it would into an AudioTrack. Each frame
 * is stamped with the group time it's to be heard at — [delayNs] from now, which leaves every
 * phone time to receive it and get it through its Bluetooth — and sent to the room. The output
 * reports its position by that same timeline, so the seek bar, lyrics and song changes follow
 * what you actually hear.
 */
@UnstableApi
object RoomStream {
    interface Listener {
        fun onChunk(chunk: AudioChunk, packet: ByteArray)
        fun onCut(epoch: Int, atNs: Long)
    }

    /** True while the music goes to the room instead of this phone's own output. */
    @Volatile var active: Boolean = false

    /**
     * How far ahead of being heard the music is sent (ns): enough for the slowest phone's
     * network (Wi-Fi or Tailscale) plus its output (Bluetooth can add a third of a second).
     * The room raises it by itself when a phone reports music arriving too late ([extendDelay]).
     */
    @Volatile var delayNs: Long = 700_000_000L

    /**
     * A phone needs more time: send further ahead from now on. ExoPlayer tops the output up
     * every ~0.1 s, so within a moment every piece of music leaves this phone the new delay
     * before it's due (no pause needed: the timeline itself doesn't move).
     */
    fun extendDelay(newDelayNs: Long) {
        if (newDelayNs > delayNs) delayNs = newDelayNs
    }

    /** The first play after a handoff starts at exactly this group time. */
    @Volatile var pendingAnchorNs: Long = 0L

    /** Nothing at or after this group time is sent (the old main's last moment). */
    @Volatile var stopAtNs: Long = Long.MAX_VALUE

    /** Where the previous output's music ends, when it ran out naturally (no gap between songs). */
    @Volatile internal var continueAtNs: Long = 0L

    @Volatile var listener: Listener? = null

    private val epochs = AtomicInteger(RoomCrypto.randomLong().toInt() and 0x3FFF0000)
    internal fun newEpoch(): Int = epochs.incrementAndGet()

    /** Wraps the usual output provider: room outputs while [active], the phone's own otherwise. */
    fun provider(inner: AudioOutputProvider): AudioOutputProvider = object : ForwardingAudioOutputProvider(inner) {
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            if (active && !config.isOffload && (config.encoding == C.ENCODING_PCM_FLOAT || config.encoding == C.ENCODING_PCM_16BIT)) {
                return RoomAudioOutput(config)
            }
            return super.getAudioOutput(config)
        }
    }
}

/**
 * An [AudioOutput] that sends to the room. Its clock is the room's: a frame counts as played
 * once its group time has come.
 *
 * The timeline is set when playback starts (a start frame and the group time it's heard at).
 *
 * It always keeps [RoomStream.delayNs] (+ a little) of music sent ahead, and tells ExoPlayer
 * its buffer is short ([REPORTED_BUFFER_NS]) so ExoPlayer tops it up every ~0.1 s: the music
 * then always reaches the other phones the full delay before it's due, never in bursts that
 * leave a phone waiting.
 */
@UnstableApi
internal class RoomAudioOutput(config: AudioOutputProvider.OutputConfig) : AudioOutput {
    private val rate = config.sampleRate
    private val channels = Integer.bitCount(config.channelMask).coerceAtLeast(1)
    private val float = config.encoding == C.ENCODING_PCM_FLOAT
    private val bytesPerFrame = channels * (if (float) 4 else 2)
    private val sessionId = config.audioSessionId
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()

    /** Room for 5 s of music (the most delay the room ever asks for is 3 s). */
    private val capacity = rate * 5
    private val ring = FloatArray(capacity * channels)

    private class Seg(val frame: Long, val ns: Long)

    private val lock = Object()
    private var written = 0L
    private var sent = 0L
    private var playing = false
    private var anchored = false
    private val segs = ArrayList<Seg>()
    private var pausedHeard = 0L
    private var epoch = RoomStream.newEpoch()
    private var everSent = false
    private var everAnchored = false
    private var stopped = false
    private var volume = 1f
    private var params = PlaybackParameters.DEFAULT
    private var advancingReported = false

    private fun frameNs(frames: Long): Long = frames * 1_000_000_000L / rate

    /** Group time at which frame [f] is heard. */
    private fun timeOf(f: Long): Long {
        var s = segs[0]
        for (x in segs) if (x.frame <= f) s = x
        return s.ns + frameNs(f - s.frame)
    }

    /** Frames heard by group time [now]. */
    private fun heard(now: Long): Long {
        if (!anchored || segs.isEmpty()) return pausedHeard
        var i = 0
        while (i + 1 < segs.size && segs[i + 1].ns <= now) i++
        val s = segs[i]
        if (now <= s.ns) return s.frame
        val end = if (i + 1 < segs.size) segs[i + 1].frame else written
        return min(end, s.frame + (now - s.ns) * rate / 1_000_000_000L)
    }

    override fun play() {
        synchronized(lock) {
            if (playing) return
            playing = true
            anchor(GroupClock.now())
            flushToRoom()
        }
    }

    /** Puts the not-yet-heard music on the timeline, starting at the right moment. */
    private fun anchor(now: Long) {
        val first = pausedHeard
        sent = first
        val handoff = RoomStream.pendingAnchorNs
        val cont = RoomStream.continueAtNs
        val at = when {
            handoff > now + MIN_LEAD_NS -> { RoomStream.pendingAnchorNs = 0L; handoff }
            cont > now + MIN_LEAD_NS && !everAnchored -> cont
            else -> now + RoomStream.delayNs
        }
        RoomStream.continueAtNs = 0L
        segs.clear()
        segs += Seg(first, at)
        anchored = true
        everAnchored = true
        epoch = RoomStream.newEpoch()
        advancingReported = false
    }

    override fun pause() {
        synchronized(lock) {
            if (!playing) return
            val now = GroupClock.now()
            pausedHeard = heard(now)
            playing = false
            anchored = false
            if (everSent) RoomStream.listener?.onCut(epoch, now)
        }
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        synchronized(lock) {
            val now = GroupClock.now()
            val ahead = written - heard(now)
            val limit = ((RoomStream.delayNs + HEADROOM_NS) * rate / 1_000_000_000L).coerceAtMost(capacity.toLong() - 1)
            val room = (limit - ahead).coerceAtLeast(0)
            val available = buffer.remaining() / bytesPerFrame
            val n = min(available.toLong(), room).toInt()
            if (n <= 0) return false
            buffer.order(ByteOrder.nativeOrder())
            var pos = buffer.position()
            for (f in 0 until n) {
                val base = (((written + f) % capacity) * channels).toInt()
                for (c in 0 until channels) {
                    ring[base + c] = if (float) buffer.getFloat(pos) else buffer.getShort(pos) / 32768f
                    pos += if (float) 4 else 2
                }
            }
            buffer.position(pos)
            written += n
            if (playing) flushToRoom()
            return n == available
        }
    }

    /** Sends what's been written and not sent yet. */
    private fun flushToRoom() {
        if (!anchored) return
        val now = GroupClock.now()
        // ExoPlayer fell behind (a stream buffering): the timeline waits for it.
        if (sent < written && timeOf(sent) < now + MIN_LEAD_NS) {
            pausedHeard = sent
            val old = epoch
            anchor(now)
            listeners.forEach { it.onUnderrun() }
            RoomStream.listener?.onCut(old, now)
        }
        val chunk = rate / 50 // 20 ms pieces
        while (sent < written) {
            // Never across a segment boundary (the times jump there).
            val nextSeg = segs.firstOrNull { it.frame > sent }?.frame ?: Long.MAX_VALUE
            val n = min(min(chunk.toLong(), written - sent), nextSeg - sent).toInt()
            val at = timeOf(sent)
            if (at >= RoomStream.stopAtNs) { sent = written; break }
            val samples = FloatArray(n * channels)
            for (f in 0 until n) {
                val base = (((sent + f) % capacity) * channels).toInt()
                System.arraycopy(ring, base, samples, f * channels, channels)
            }
            if (volume != 1f) for (i in samples.indices) samples[i] *= volume
            val c = AudioChunk(epoch, sent, at, rate, channels, samples)
            val packet = AudioPacket.encode(epoch, sent, at, rate, channels, samples, 0, n)
            RoomStream.listener?.onChunk(c, packet)
            everSent = true
            sent += n
        }
        if (!advancingReported) {
            advancingReported = true
            val startsInMs = (segs[0].ns - now) / 1_000_000L
            listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis() + max(0L, startsInMs)) }
        }
    }

    override fun flush() {
        synchronized(lock) {
            if (everSent) RoomStream.listener?.onCut(epoch, GroupClock.now())
            written = 0; sent = 0; pausedHeard = 0; anchored = false; stopped = false
            segs.clear()
            epoch = RoomStream.newEpoch()
        }
    }

    override fun stop() {
        synchronized(lock) {
            // No more music for this output: everything written counts as handed over, so
            // ExoPlayer moves on at once and the next song can carry straight on from here.
            stopped = true
            if (anchored) RoomStream.continueAtNs = timeOf(written)
        }
    }

    override fun release() {
        synchronized(lock) {
            // Released without running out (a seek, a skip, a stop): what's queued is stale.
            if (!stopped && everSent) {
                RoomStream.continueAtNs = 0L
                RoomStream.listener?.onCut(epoch, GroupClock.now())
            }
        }
        listeners.forEach { it.onReleased() }
    }

    override fun getPositionUs(): Long = synchronized(lock) {
        val frames = if (stopped) written else heard(GroupClock.now())
        frames * 1_000_000L / rate
    }

    override fun setVolume(volume: Float) { this.volume = volume }
    override fun isOffloadedPlayback(): Boolean = false
    override fun getAudioSessionId(): Int = sessionId
    override fun getSampleRate(): Int = rate
    override fun getBufferSizeInFrames(): Long = REPORTED_BUFFER_NS * rate / 1_000_000_000L
    override fun getPlaybackParameters(): PlaybackParameters = params
    override fun isStalled(): Boolean = false
    override fun addListener(listener: AudioOutput.Listener) { listeners.add(listener) }
    override fun removeListener(listener: AudioOutput.Listener) { listeners.remove(listener) }
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) { params = playbackParams }
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}

    internal companion object {
        /** A frame is sent at least this long before it's heard, or the timeline waits. */
        const val MIN_LEAD_NS = 150_000_000L
        /** How much more than [RoomStream.delayNs] may be sent ahead. */
        const val HEADROOM_NS = 250_000_000L
        /**
         * The buffer size ExoPlayer is told: it sleeps for half of it between top-ups, so the
         * lead never sags more than ~0.1 s below delay + headroom.
         */
        const val REPORTED_BUFFER_NS = 200_000_000L
    }
}
