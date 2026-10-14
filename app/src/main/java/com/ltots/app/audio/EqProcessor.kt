package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The heart of LToTS' sound path. Sits in front of Media3's pitch/speed stage (Sonic) and:
 *  - runs every EQ band as a double-precision biquad (transposed direct form II),
 *  - applies preamp + automatic headroom so boosts never clip,
 *  - catches any remaining overs with a soft limiter just under 0 dBFS,
 *  - outputs 32-bit float, or TPDF-dithered 16-bit when bit-perfect USB output needs it,
 *  - copies the signal before and after the EQ into ring buffers for the live spectrum.
 */
@UnstableApi
class EqProcessor(private val analyzerTap: AnalyzerTap) : BaseAudioProcessor() {

    /** Latest EQ requested by the UI. Swapped atomically; picked up on the audio thread. */
    @Volatile private var requested: EqState = EqState()

    /** When true, output dithered 16-bit PCM (needed for bit-perfect USB). Takes effect on reconfigure. */
    @Volatile var int16Output: Boolean = false

    private var applied: EqState? = null
    private var appliedSampleRate = 0
    private var sections: Array<Section> = emptyArray()
    private var inputGain = 1.0
    private var channels = 2
    private var sampleRate = 48000
    private var inEncoding = C.ENCODING_PCM_16BIT
    private var outInt16 = false
    private var ditherSeed = 0x2545F491

    fun setState(state: EqState) {
        requested = state
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT &&
            enc != C.ENCODING_PCM_24BIT && enc != C.ENCODING_PCM_32BIT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        inEncoding = enc
        outInt16 = int16Output
        applied = null
        analyzerTap.onFormat(sampleRate, channels, enc, if (outInt16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT)
        return AudioFormat(
            sampleRate,
            channels,
            if (outInt16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT,
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        refreshFiltersIfNeeded()

        val inBytesPerSample = when (inEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val frames = inputBuffer.remaining() / (inBytesPerSample * channels)
        val outBytesPerSample = if (outInt16) 2 else 4
        val out = replaceOutputBuffer(frames * channels * outBytesPerSample)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.nativeOrder())

        val secs = sections
        val nSec = secs.size
        val gain = inputGain
        val ch = channels
        val tap = analyzerTap

        for (f in 0 until frames) {
            var monoIn = 0f
            var monoOut = 0f
            for (c in 0 until ch) {
                val x: Double = readSample(inputBuffer)
                monoIn += x.toFloat()
                var y = x * gain
                for (s in 0 until nSec) y = secs[s].process(y, c)
                y = softLimit(y)
                monoOut += y.toFloat()
                if (outInt16) {
                    out.putShort(toInt16Dithered(y))
                } else {
                    out.putFloat(y.toFloat())
                }
            }
            tap.write(monoIn / ch, monoOut / ch)
        }
        tap.commit(frames)
        out.flip()
    }

    private fun readSample(b: ByteBuffer): Double = when (inEncoding) {
        C.ENCODING_PCM_16BIT -> b.short / 32768.0
        C.ENCODING_PCM_FLOAT -> b.float.toDouble()
        C.ENCODING_PCM_24BIT -> {
            val lo = b.get().toInt() and 0xFF
            val mid = b.get().toInt() and 0xFF
            val hi = b.get().toInt()
            ((hi shl 16) or (mid shl 8) or lo) / 8388608.0
        }
        else -> b.int / 2147483648.0
    }

    private fun toInt16Dithered(y: Double): Short {
        // Triangular (TPDF) dither of ±1 LSB keeps quantisation noise benign and signal-independent.
        ditherSeed = ditherSeed xor (ditherSeed shl 13)
        ditherSeed = ditherSeed xor (ditherSeed ushr 17)
        ditherSeed = ditherSeed xor (ditherSeed shl 5)
        val r1 = (ditherSeed and 0xFFFF) / 65536.0
        val r2 = ((ditherSeed ushr 16) and 0xFFFF) / 65536.0
        val v = y * 32767.0 + (r1 - r2)
        val rounded = Math.round(v).coerceIn(-32768L, 32767L)
        return rounded.toInt().toShort()
    }

    private fun refreshFiltersIfNeeded() {
        val want = requested
        if (want === applied && appliedSampleRate == sampleRate) return
        val fs = sampleRate.toDouble()
        val old = sections.associateBy { it.id }
        val active = if (want.enabled) want.bands.filter { it.enabled } else emptyList()
        sections = active.map { band ->
            val coefs = BiquadCoefs.design(band, fs)
            // Keep filter memory for bands that already existed so dragging a band is click-free.
            val sec = old[band.id]?.takeIf { it.channels == channels } ?: Section(band.id, channels)
            sec.coefs = coefs
            sec
        }.filter { it.coefs !== BiquadCoefs.IDENTITY }.toTypedArray()
        inputGain = 10.0.pow(EqMath.inputGainDb(want, fs) / 20.0)
        applied = want
        appliedSampleRate = sampleRate
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        for (s in sections) s.reset()
        analyzerTap.onFlush(streamMetadata.positionOffsetUs)
    }

    override fun onReset() {
        sections = emptyArray()
        applied = null
    }

    private class Section(val id: Long, val channels: Int) {
        var coefs: BiquadCoefs = BiquadCoefs.IDENTITY
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)

        fun process(x: Double, c: Int): Double {
            val k = coefs
            val y = k.b0 * x + z1[c]
            z1[c] = k.b1 * x - k.a1 * y + z2[c]
            z2[c] = k.b2 * x - k.a2 * y
            return y
        }

        fun reset() {
            z1.fill(0.0); z2.fill(0.0)
        }
    }

    companion object {
        private const val KNEE = 0.89 // ≈ -1 dBFS: everything below passes untouched
        private const val CEILING = 0.98 // ≈ -0.2 dBFS: nothing ever goes above this

        /** Transparent below -1 dBFS; above it, rounds peaks off smoothly instead of hard clipping. */
        fun softLimit(x: Double): Double {
            val a = abs(x)
            if (a <= KNEE) return x
            val range = CEILING - KNEE
            val y = KNEE + range * tanh((a - KNEE) / range)
            return if (x < 0) -y else y
        }
    }
}
