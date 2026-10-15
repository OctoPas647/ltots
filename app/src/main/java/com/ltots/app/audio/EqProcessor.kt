package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * First stage of LToTS' sound path:
 *  - reads any PCM the decoder gives (16/24/32-bit or float) and outputs 32-bit float;
 *  - runs every EQ band as a double-precision biquad;
 *  - applies preamp + automatic headroom;
 *  - when the EQ changes mid-song, crossfades from the old settings to the new ones over
 *    ~20 ms so dragging a band never clicks;
 *  - knows which song it is processing (from Media3's stream metadata) and uses that song's
 *    own EQ from its first sample;
 *  - copies the signal before and after the EQ to the analyzer for the live view.
 */
@UnstableApi
class EqProcessor(
    private val analyzerTap: AnalyzerTap,
    @Volatile var source: SoundSource = StaticSoundSource(),
) : BaseAudioProcessor() {

    private class Plan(val sections: Array<Section>, val gain: Double, val state: EqState)

    private var plan: Plan? = null
    /** Previous plan while a crossfade is running. */
    private var fadingFrom: Plan? = null
    private var fadePos = 0
    private val fadeLen get() = (sampleRate / 50).coerceAtLeast(64) // 20 ms

    private var seenVersion = -1
    private var mediaId: String? = null
    private var channels = 2
    private var sampleRate = 48000
    private var inEncoding = C.ENCODING_PCM_16BIT
    private var scratch = DoubleArray(0)

    /** The song currently flowing through (for tests and diagnostics). */
    val currentMediaId: String? get() = mediaId

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT &&
            enc != C.ENCODING_PCM_24BIT && enc != C.ENCODING_PCM_32BIT
        ) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, C.ENCODING_PCM_FLOAT)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val fmt = inputAudioFormat
        val formatChanged = fmt.sampleRate != sampleRate || fmt.channelCount != channels
        sampleRate = fmt.sampleRate
        channels = fmt.channelCount
        inEncoding = fmt.encoding
        val newId = StreamIds.mediaIdOf(streamMetadata) ?: mediaId
        // A new song starting right after the previous one (gapless): keep the filters'
        // memory so the join stays seamless. A seek or format change starts clean.
        val gapless = newId != mediaId && streamMetadata.positionOffsetUs == 0L && !formatChanged
        mediaId = newId
        val newPlan = buildPlan(source.eqFor(mediaId), previous = if (gapless) plan else null)
        if (!gapless) newPlan.sections.forEach { it.reset() }
        plan = newPlan
        fadingFrom = null
        seenVersion = source.version
        analyzerTap.onFormat(sampleRate, channels, inEncoding, C.ENCODING_PCM_FLOAT)
        analyzerTap.onFlush(streamMetadata.positionOffsetUs)
    }

    override fun onReset() {
        plan = null
        fadingFrom = null
        mediaId = null
        seenVersion = -1
    }

    /** Settings changed while playing: switch with a short crossfade. */
    private fun maybeUpdate() {
        val v = source.version
        if (v == seenVersion) return
        seenVersion = v
        val want = source.eqFor(mediaId)
        val cur = plan
        if (cur != null && cur.state == want) return
        val next = buildPlan(want, previous = cur)
        if (cur != null) {
            fadingFrom = cur
            fadePos = 0
        }
        plan = next
    }

    private fun buildPlan(state: EqState, previous: Plan?): Plan {
        val fs = sampleRate.toDouble()
        val old = previous?.sections?.associateBy { it.id }
        val active = if (state.enabled) state.bands.filter { it.enabled } else emptyList()
        val sections = active.mapNotNull { band ->
            val coefs = BiquadCoefs.design(band, fs)
            if (coefs === BiquadCoefs.IDENTITY) return@mapNotNull null
            // New filter objects, but starting from the old ones' memory, so the faded-in
            // path is already "warmed up" and doesn't ring.
            Section(band.id, channels).also { s ->
                s.coefs = coefs
                old?.get(band.id)?.let { o -> if (o.channels == channels) s.copyStateFrom(o) }
            }
        }.toTypedArray()
        val gain = 10.0.pow(EqMath.inputGainDb(state, fs) / 20.0)
        return Plan(sections, gain, state)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (plan == null) plan = buildPlan(source.eqFor(mediaId), null)
        maybeUpdate()

        val inBytes = when (inEncoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val ch = channels
        val frames = inputBuffer.remaining() / (inBytes * ch)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val n = frames * ch
        if (scratch.size < n) scratch = DoubleArray(n)
        val x = scratch
        for (i in 0 until n) x[i] = readSample(inputBuffer)
        // Skip any stray partial frame so the buffer is fully consumed.
        inputBuffer.position(inputBuffer.limit())

        val out = replaceOutputBuffer(n * 4).order(ByteOrder.nativeOrder())
        val p = plan!!
        val from = fadingFrom
        val tap = analyzerTap
        val fl = fadeLen
        for (f in 0 until frames) {
            var monoIn = 0.0
            var monoOut = 0.0
            val base = f * ch
            for (c in 0 until ch) {
                val s = x[base + c]
                monoIn += s
                var y = run(p, s, c)
                if (from != null) {
                    val yOld = run(from, s, c)
                    val t = (fadePos + 1).toDouble() / fl
                    y = yOld + (y - yOld) * t
                }
                monoOut += y
                out.putFloat(y.toFloat())
            }
            if (from != null && ++fadePos >= fl) fadingFrom = null
            tap.write((monoIn / ch).toFloat(), (monoOut / ch).toFloat())
        }
        tap.commit(frames)
        out.flip()
        guardStability(p)
    }

    private fun run(p: Plan, x: Double, c: Int): Double {
        var y = x * p.gain
        val secs = p.sections
        for (s in secs.indices) y = secs[s].process(y, c)
        return y
    }

    /** A filter must never get stuck on NaN/infinity (that would silence the song for good). */
    private fun guardStability(p: Plan) {
        for (s in p.sections) if (!s.isFinite()) s.reset()
    }

    private fun readSample(b: ByteBuffer): Double = when (inEncoding) {
        C.ENCODING_PCM_16BIT -> b.short / 32768.0
        C.ENCODING_PCM_FLOAT -> b.float.toDouble().let { if (it.isFinite()) it else 0.0 }
        C.ENCODING_PCM_24BIT -> {
            val lo = b.get().toInt() and 0xFF
            val mid = b.get().toInt() and 0xFF
            val hi = b.get().toInt()
            ((hi shl 16) or (mid shl 8) or lo) / 8388608.0
        }
        else -> b.int / 2147483648.0
    }

    internal class Section(val id: Long, val channels: Int) {
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

        fun copyStateFrom(o: Section) {
            o.z1.copyInto(z1); o.z2.copyInto(z2)
        }

        fun isFinite(): Boolean {
            for (c in 0 until channels) if (!z1[c].isFinite() || !z2[c].isFinite()) return false
            return true
        }

        fun reset() {
            z1.fill(0.0); z2.fill(0.0)
        }
    }
}
