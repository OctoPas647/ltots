package com.ltots.app.audio

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A stand-in for the phone's AudioTrack that behaves like real hardware: it has a fixed-size
 * buffer and plays it out at exactly the sample rate, following the player's clock. (Robolectric's
 * own AudioTrack "plays" everything instantly, which hides timing bugs.) Everything played is
 * recorded so tests can check the sound itself.
 */
class RealtimeOutput(private val clock: Clock, private val sampleRate: Int, private val channels: Int, private val encoding: Int, bufferBytes: Int) : AudioOutput {
    private val frameBytes = channels * if (encoding == C.ENCODING_PCM_16BIT) 2 else 4
    private val capacity = (bufferBytes / frameBytes).toLong()
    private var written = 0L
    private var basePlayed = 0L
    private var playingSince = -1L
    private val listeners = mutableListOf<AudioOutput.Listener>()
    /** Everything written, left channel, as floats. */
    val left = FloatList()
    var underruns = 0; private set
    private var starved = false

    private fun played(): Long {
        if (playingSince < 0) return basePlayed
        val p = basePlayed + (clock.elapsedRealtime() - playingSince) * sampleRate / 1000
        if (p >= written) {
            if (written > 0) starved = true
            return written
        }
        return p
    }

    override fun play() {
        if (playingSince < 0) { playingSince = clock.elapsedRealtime(); listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis()) } }
    }

    override fun pause() {
        basePlayed = played(); playingSince = -1
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val free = capacity - (written - played())
        val n = minOf(free, (buffer.remaining() / frameBytes).toLong()).toInt()
        if (n <= 0) return !buffer.hasRemaining()
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        for (f in 0 until n) {
            val v = if (encoding == C.ENCODING_PCM_16BIT) b.short / 32768f else b.float
            left.add(v)
            repeat(channels - 1) { if (encoding == C.ENCODING_PCM_16BIT) b.short else b.float }
        }
        buffer.position(buffer.position() + n * frameBytes)
        played()
        if (starved) { underruns++; starved = false } // ran dry, then more audio arrived: a gap
        written += n
        return !buffer.hasRemaining()
    }

    override fun flush() { written = 0; basePlayed = 0; starved = false; if (playingSince >= 0) playingSince = clock.elapsedRealtime() }
    override fun stop() {}
    override fun release() { listeners.toList().forEach { it.onReleased() } }
    override fun setVolume(volume: Float) {}
    override fun isOffloadedPlayback() = false
    override fun getAudioSessionId() = 1
    override fun getSampleRate() = sampleRate
    override fun getBufferSizeInFrames() = capacity
    override fun getPositionUs(): Long = played() * 1_000_000L / sampleRate
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
    override fun isStalled() = false
    override fun addListener(listener: AudioOutput.Listener) { listeners += listener }
    override fun removeListener(listener: AudioOutput.Listener) { listeners -= listener }
    override fun setPlaybackParameters(playbackParams: PlaybackParameters) {}
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
    override fun setOffloadEndOfStream() {}
    override fun attachAuxEffect(effectId: Int) {}
    override fun setAuxEffectSendLevel(level: Float) {}
    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}

    companion object {
        /** Output provider that hands out [RealtimeOutput]s; [outputs] keeps every one created. */
        class Provider(context: android.content.Context, private val clock: Clock) :
            ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
            val outputs = mutableListOf<RealtimeOutput>()
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
                RealtimeOutput(clock, config.sampleRate, Integer.bitCount(config.channelMask).coerceAtLeast(1), config.encoding, config.bufferSize)
                    .also { outputs += it }
        }
    }
}
