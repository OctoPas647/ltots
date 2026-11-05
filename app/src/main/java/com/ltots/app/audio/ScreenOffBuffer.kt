package com.ltots.app.audio

import android.content.Context
import android.media.AudioTrack
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.lang.ref.WeakReference

/**
 * The audio output, with a buffer that grows while the screen is off.
 *
 * When LToTS has to process the sound itself (your EQ, pitch or speed), the processor wakes up
 * each time the phone's audio buffer runs low. With the screen on that buffer holds about half
 * a second, so EQ and pitch changes are heard right away. With the screen off nobody moves a
 * slider, so it's let out to [LONG_MS]: the processor fills it in one go and then sleeps several
 * times longer between wake-ups. Pausing, skipping and seeking still act at once (they clear the
 * buffer), and the volume and headphone unplugging aren't affected at all.
 *
 * The buffer is made big once, when the output is created, and only the part that's used
 * changes, so switching never interrupts the music. Direct playback (the audio chip playing by
 * itself) has its own, much larger buffer and is left alone.
 */
@UnstableApi
object ScreenOffBuffer {
    /** What the buffer holds with the screen on (Media3's usual size). */
    const val SHORT_MS = 500
    /** What it holds with the screen off. */
    const val LONG_MS = 4000

    private var track = WeakReference<AudioTrack>(null)
    private var sampleRate = 48000
    @Volatile private var long = false

    /** Media3's audio output, with room for the long buffer. */
    fun provider(context: Context): AudioOutputProvider {
        val sizes = DefaultAudioTrackBufferSizeProvider.Builder()
            .setTargetPcmBufferDurationUs(LONG_MS * 1000)
            .build()
        val inner = AudioTrackAudioOutputProvider.Builder(context)
            .setAudioTrackBufferSizeProvider(sizes)
            .build()
        return object : ForwardingAudioOutputProvider(inner) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val out = super.getAudioOutput(config)
                if (out is AudioTrackAudioOutput && !out.isOffloadedPlayback) {
                    track = WeakReference(out.audioTrack)
                    sampleRate = out.sampleRate.takeIf { it > 0 } ?: 48000
                    apply()
                } else track = WeakReference(null)
                return out
            }
        }
    }

    /** Screen on: the short buffer; screen off: the long one. */
    fun setLong(on: Boolean) {
        long = on
        apply()
    }

    /** About how much audio is waiting to be heard right now, in ms (0 during direct playback). */
    fun queuedMs(): Long {
        val t = track.get() ?: return 0
        return runCatching { t.bufferSizeInFrames * 1000L / sampleRate }.getOrDefault(0)
    }

    private fun apply() {
        val t = track.get() ?: return
        runCatching {
            val frames = (sampleRate.toLong() * (if (long) LONG_MS else SHORT_MS) / 1000).toInt()
            t.setBufferSizeInFrames(frames.coerceAtMost(t.bufferCapacityInFrames))
        }
    }
}
