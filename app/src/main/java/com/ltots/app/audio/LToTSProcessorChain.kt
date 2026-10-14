package com.ltots.app.audio

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * EQ → pitch/speed. Replaces Media3's default chain, whose silence-skipping stage only accepts
 * 16-bit audio and would reject the EQ's 32-bit float output. (LToTS never skips silence.)
 */
@UnstableApi
class LToTSProcessorChain(eq: EqProcessor) : AudioProcessorChain {
    private val sonic = SonicAudioProcessor()
    private val processors: Array<AudioProcessor> = arrayOf(eq, sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = 0
}
