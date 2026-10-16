package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Last stage: a transparent soft limiter just below 0 dBFS (catches any peaks the EQ or pitch
 * shifting create), then either 32-bit float out, or TPDF-dithered 16-bit for bit-perfect USB.
 */
@UnstableApi
class OutputStage : BaseAudioProcessor() {
    /** Output dithered 16-bit PCM (bit-perfect USB). Takes effect at the next reconfigure. */
    @Volatile var int16Output: Boolean = false
    private var outInt16 = false
    private var seed = 0x2545F491

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        outInt16 = int16Output
        return AudioFormat(
            inputAudioFormat.sampleRate, inputAudioFormat.channelCount,
            if (outInt16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT,
        )
    }

    private var floats = FloatArray(0)
    private var shorts = ShortArray(0)

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val n = inputBuffer.remaining() / 4
        if (floats.size < n) floats = FloatArray(n)
        val f = floats
        inputBuffer.asFloatBuffer().get(f, 0, n)
        inputBuffer.position(inputBuffer.limit())
        val out = replaceOutputBuffer(n * if (outInt16) 2 else 4).order(ByteOrder.nativeOrder())
        if (outInt16) {
            if (shorts.size < n) shorts = ShortArray(n)
            val o = shorts
            for (i in 0 until n) {
                var v = f[i].toDouble()
                if (!v.isFinite()) v = 0.0
                o[i] = toInt16Dithered(softLimit(v))
            }
            out.asShortBuffer().put(o, 0, n)
            out.position(0).limit(n * 2)
        } else {
            for (i in 0 until n) {
                val v = f[i]
                // Almost every sample is well below the knee: those pass through untouched.
                if (v > -KNEE_F && v < KNEE_F) continue
                val d = v.toDouble()
                f[i] = if (d.isFinite()) softLimit(d).toFloat() else 0f
            }
            out.asFloatBuffer().put(f, 0, n)
            out.position(0).limit(n * 4)
        }
    }

    private fun toInt16Dithered(y: Double): Short {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        val r1 = (seed and 0xFFFF) / 65536.0
        val r2 = ((seed ushr 16) and 0xFFFF) / 65536.0
        return Math.round(y * 32767.0 + (r1 - r2)).coerceIn(-32768L, 32767L).toInt().toShort()
    }

    companion object {
        private const val KNEE = 0.89 // ≈ -1 dBFS: everything below passes untouched
        private const val CEILING = 0.98 // ≈ -0.2 dBFS: nothing ever goes above this
        private const val KNEE_F = KNEE.toFloat() // ≤ KNEE, so the shortcut never skips a sample that softLimit would change

        fun softLimit(x: Double): Double {
            val a = abs(x)
            if (a <= KNEE) return x
            val range = CEILING - KNEE
            val y = KNEE + range * tanh((a - KNEE) / range)
            return if (x < 0) -y else y
        }
    }
}

/**
 * EQ → pitch/speed → limiter/output. Replaces Media3's default chain (whose silence skipper only
 * accepts 16-bit audio and whose Sonic pitch shifter is built for speech).
 */
@UnstableApi
class LToTSProcessorChain(
    val eq: EqProcessor,
    val timePitch: TimePitchProcessor,
    val output: OutputStage,
) : AudioProcessorChain {
    private val processors: Array<AudioProcessor> = arrayOf(eq, timePitch, output)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        // Only speed travels through the player; pitch is applied live inside timePitch.
        timePitch.setSpeed(playbackParameters.speed.toDouble())
        return PlaybackParameters(playbackParameters.speed, 1f)
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long = timePitch.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = 0
}
