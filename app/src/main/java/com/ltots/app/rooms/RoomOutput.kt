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

    /** How far ahead of being heard the music is sent (ns). */
    @Volatile var delayNs: Long = 450_000_000L

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
 */
@UnstableApi
internal class RoomAudioOutput(config: AudioOutputProvider.OutputConfig) : AudioOutput {
    private val rate = config.sampleRate
    private val channels = Integer.bitCount(config.channelMask).coerceAtLeast(1)
    private val float = config.encoding == C.ENCODING_PCM_FLOAT
    private val bytesPerFrame = channels * (if (float) 4 else 2)
    private val sessionId = config.audioSessionId
    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()

    /** Room for 4 s of music (it's never more than delay + 0.6 s ahead). */
    private val capacity = rate * 4
    private val ring = FloatArray(capacity * channels)

    private val lock = Object()
    private var written = 0L
    private var sent = 0L
    private var playing = false
    private var anchored = false
    private var anchorFrame = 0L
    private var anchorNs = 0L
    private var pausedHeard = 0L
    private var epoch = RoomStream.newEpoch()
    private var everSent = false
    private var everAnchored = false
    private var stopped = false
    private var volume = 1f
    private var params = PlaybackParameters.DEFAULT
    private var advancingReported = false

    private fun frameNs(frames: Long): Long = frames * 1_000_000_000L / rate

    /** Frames heard by group time [now]. */
    private fun heard(now: Long): Long {
        if (!anchored) return pausedHeard
        if (now <= anchorNs) return anchorFrame
        return min(written, anchorFrame + (now - anchorNs) * rate / 1_000_000_000L)
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
        anchorFrame = pausedHeard
        sent = anchorFrame
        val handoff = RoomStream.pendingAnchorNs
        val cont = RoomStream.continueAtNs
        anchorNs = when {
            handoff > now + MIN_LEAD_NS -> { RoomStream.pendingAnchorNs = 0L; handoff }
            cont > now + MIN_LEAD_NS && !everAnchored -> cont
            else -> now + RoomStream.delayNs
        }
        RoomStream.continueAtNs = 0L
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
            val heardNow = heard(now)
            val ahead = written - heardNow
            val limit = ((RoomStream.delayNs + AHEAD_NS) * rate / 1_000_000_000L).coerceAtMost(capacity.toLong() - 1)
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
        if (sent < written && anchorNs + frameNs(sent - anchorFrame) < now + MIN_LEAD_NS) {
            pausedHeard = sent
            val old = epoch
            anchor(now)
            listeners.forEach { it.onUnderrun() }
            RoomStream.listener?.onCut(old, now)
        }
        val chunk = rate / 50 // 20 ms pieces
        while (sent < written) {
            val n = min(chunk.toLong(), written - sent).toInt()
            val at = anchorNs + frameNs(sent - anchorFrame)
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
            val startsInMs = (anchorNs - now) / 1_000_000L
            listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis() + max(0L, startsInMs)) }
        }
    }

    override fun flush() {
        synchronized(lock) {
            if (everSent) RoomStream.listener?.onCut(epoch, GroupClock.now())
            written = 0; sent = 0; pausedHeard = 0; anchored = false; stopped = false
            epoch = RoomStream.newEpoch()
        }
    }

    override fun stop() {
        synchronized(lock) {
            // No more music for this output: everything written counts as handed over, so
            // ExoPlayer moves on at once and the next song can carry straight on from here.
            stopped = true
            if (anchored) RoomStream.continueAtNs = anchorNs + frameNs(written - anchorFrame)
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
    override fun getAudioSessionId(): Int = sessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: C.AUDIO_SESSION_ID_UNSET
    override fun getSampleRate(): Int = rate
    override fun getBufferSizeInFrames(): Long = ((RoomStream.delayNs + AHEAD_NS) * rate / 1_000_000_000L)
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

    private companion object {
        /** A frame is sent at least this long before it's heard, or the timeline waits. */
        const val MIN_LEAD_NS = 150_000_000L
        /** How much further ahead than [RoomStream.delayNs] ExoPlayer may write. */
        const val AHEAD_NS = 600_000_000L
    }
}
