package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.breakfastquay.rubberband.RubberBandStretcher
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Pitch and speed, using Rubber Band (the R3 "finer" engine used in studio software) instead
 * of Media3's speech-oriented Sonic.
 *
 * - **Pitch changes apply live**, sample-accurately and smoothly, without restarting the audio
 *   pipeline (previously every slider movement drained and restarted it, which broke playback).
 * - **Speed** comes from the player's playback parameters (so the seek bar and clock stay
 *   right); it is only changed when you let go of the slider.
 * - At original pitch and speed the stage is a plain copy — not a single bit is altered.
 * - The stretcher's start-up latency is compensated so audio stays time-aligned.
 */
@UnstableApi
class TimePitchProcessor(@Volatile var source: SoundSource = StaticSoundSource()) : BaseAudioProcessor() {

    private var rb: RubberBandStretcher? = null
    private var rbRate = 0
    private var rbChannels = 0
    private var channels = 2
    private var sampleRate = 48000

    /** Speed from the player's playback parameters. Applied at the next flush. */
    @Volatile private var pendingSpeed = 1.0
    private var speed = 1.0
    private var pitch = 1.0
    private var formants = false
    private var efficient = false
    private var rbOptions = 0
    private var engaged = false
    private var toDrop = 0
    private var seenVersion = -1
    private var mediaId: String? = null

    private var inBuf = Array(2) { FloatArray(CHUNK) }
    private var outBuf = Array(2) { FloatArray(CHUNK * 4) }
    private var pending = FloatArray(CHUNK * 8) // interleaved output for this call
    private var pendingFrames = 0
    /** Frames fed to / taken from Rubber Band since it was engaged (to keep output exact). */
    private var framesIn = 0L
    private var framesOut = 0L

    /** True while Rubber Band is in the signal path (for tests/diagnostics). */
    val isEngaged: Boolean get() = engaged
    val currentSpeed: Double get() = speed
    /** Frames in / out since creation (diagnostics). */
    @Volatile var totalIn = 0L; private set
    @Volatile var totalOut = 0L; private set

    fun setSpeed(s: Double) { pendingSpeed = s.coerceIn(0.25, 4.0) }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        if (speed == 1.0) durationUs else (durationUs / speed).toLong()

    /** Converts time played out of the speakers back into song time (for the seek bar). */
    fun getMediaDuration(playoutDurationUs: Long): Long =
        if (speed == 1.0) playoutDurationUs else (playoutDurationUs * speed).toLong()

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        speed = pendingSpeed
        mediaId = StreamIds.mediaIdOf(streamMetadata) ?: mediaId
        pitch = source.pitchScaleFor(mediaId)
        formants = source.preserveFormants
        efficient = source.efficientPitch
        seenVersion = source.version
        pendingFrames = 0
        endHandled = false
        engaged = false
        if (needsEngine()) engage()
    }

    override fun onReset() {
        rb?.dispose()
        rb = null
        rbRate = 0
        engaged = false
        mediaId = null
        speed = 1.0
    }

    private fun needsEngine() = abs(speed - 1.0) > 1e-6 || abs(pitch - 1.0) > 1e-6

    /**
     * R3 ("finer") is the studio-quality engine; R2 ("faster") needs roughly half the CPU, which
     * matters for battery when pitch or speed stays changed for a long time. R2 only half keeps a
     * voice's formants, so "Natural voices" always uses R3.
     */
    private fun options(): Int =
        RubberBandStretcher.OptionProcessRealTime or
            (if (usesFaster(efficient, formants)) RubberBandStretcher.OptionEngineFaster else RubberBandStretcher.OptionEngineFiner) or
            RubberBandStretcher.OptionPitchHighConsistency or
            RubberBandStretcher.OptionChannelsTogether or
            (if (formants) RubberBandStretcher.OptionFormantPreserved else 0)

    /** If the native engine can't start, keep playing untouched rather than failing. */
    @Volatile var engineError: Throwable? = null; private set

    private fun engage() {
        if (engineError != null) return
        try {
            engageUnsafe()
        } catch (t: Throwable) {
            engineError = t
            rb = null
            engaged = false
        }
    }

    /** Puts Rubber Band in the path, primed so its output lines up with its input. */
    private fun engageUnsafe() {
        val opts = options()
        if (rb == null || rbRate != sampleRate || rbChannels != channels || rbOptions != opts) {
            rb?.dispose()
            rb = RubberBandStretcher(sampleRate, channels, opts, 1.0 / speed, pitch)
            rbOptions = opts
            rbRate = sampleRate
            rbChannels = channels
            rb!!.setMaxProcessSize(CHUNK)
        }
        val s = rb!!
        s.reset()
        s.setTimeRatio(1.0 / speed)
        s.setPitchScale(pitch)
        s.setFormantOption(if (formants) RubberBandStretcher.OptionFormantPreserved else 0)
        if (inBuf.size != channels) {
            inBuf = Array(channels) { FloatArray(CHUNK) }
            outBuf = Array(channels) { FloatArray(CHUNK * 4) }
        }
        // Feed the recommended silent lead-in, then drop the matching delay from the output.
        var pad = s.preferredStartPad
        inBuf.forEach { it.fill(0f) }
        while (pad > 0) {
            val n = minOf(pad, CHUNK)
            s.process(inBuf, 0, n, false)
            pad -= n
        }
        toDrop = s.startDelay
        framesIn = 0
        framesOut = 0
        engaged = true
    }

    private fun applyLiveChanges() {
        val v = source.version
        if (v == seenVersion) return
        seenVersion = v
        val p = source.pitchScaleFor(mediaId)
        val f = source.preserveFormants
        val e = source.efficientPitch
        if (!engaged || usesFaster(e, f) != usesFaster(efficient, formants)) {
            // Not running yet, or switching engines (a fresh start, heard as a tiny skip).
            pitch = p
            formants = f
            efficient = e
            engaged = false
            if (needsEngine()) engage()
            return
        }
        val s = rb ?: return
        if (p != pitch) { pitch = p; s.setPitchScale(p) }
        if (f != formants) {
            formants = f
            s.setFormantOption(if (f) RubberBandStretcher.OptionFormantPreserved else 0)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        applyLiveChanges()
        val ch = channels
        val frames = inputBuffer.remaining() / (4 * ch)
        inputBuffer.order(ByteOrder.nativeOrder())
        totalIn += frames

        if (!engaged) {
            // Bit-exact pass-through.
            val out = replaceOutputBuffer(frames * ch * 4)
            val lim = inputBuffer.position() + frames * ch * 4
            val slice = inputBuffer.duplicate().also { it.limit(lim) }
            out.put(slice)
            totalOut += frames
            inputBuffer.position(inputBuffer.limit())
            out.flip()
            return
        }

        val s = rb!!
        pendingFrames = 0
        var left = frames
        while (left > 0) {
            val n = minOf(left, CHUNK)
            for (f in 0 until n) for (c in 0 until ch) inBuf[c][f] = inputBuffer.float
            s.process(inBuf, 0, n, false)
            framesIn += n
            left -= n
            drain(s)
        }
        inputBuffer.position(inputBuffer.limit())
        emit()
    }

    /**
     * End of stream (end of song, or Media3 draining before a speed change). Media3's pipeline
     * may call this many times; only the first does anything, and nothing is emitted unless
     * there is real audio — otherwise the pipeline never sees this stage as finished and
     * playback freezes (that was the "stuck after changing speed" bug).
     */
    override fun onQueueEndOfStream() {
        if (endHandled) return
        endHandled = true
        val s = rb
        if (!engaged || s == null) return
        pendingFrames = 0
        capOutput = true
        try {
            s.process(inBuf, 0, 0, true)
            var guard = 0
            while (guard++ < 1_000) {
                if ((framesIn / speed).toLong() - framesOut <= 0) break
                if (s.available() <= 0) break
                drain(s)
            }
        } finally {
            capOutput = false
        }
        // The stretcher has seen its final block; it must be reset before it can take more.
        engaged = false
        if (pendingFrames > 0) emit()
    }

    private var endHandled = false

    private fun drain(s: RubberBandStretcher) {
        val ch = channels
        while (true) {
            val a = s.available()
            if (a <= 0) return
            val want = minOf(a, outBuf[0].size)
            val got = s.retrieve(outBuf, 0, want)
            if (got <= 0) return
            var start = 0
            if (toDrop > 0) {
                val d = minOf(toDrop, got)
                toDrop -= d
                start = d
            }
            var keep = got - start
            if (capOutput) {
                // At the end of a stream, never emit more than the input stretched by speed.
                val allowed = (framesIn / speed).toLong() - framesOut
                keep = minOf(keep.toLong(), maxOf(0L, allowed)).toInt()
            }
            if (keep <= 0) continue
            ensurePending((pendingFrames + keep) * ch)
            var idx = pendingFrames * ch
            for (f in start until start + keep) for (c in 0 until ch) {
                val v = outBuf[c][f]
                pending[idx++] = if (v.isFinite()) v else 0f
            }
            pendingFrames += keep
            framesOut += keep
        }
    }

    private var capOutput = false

    private fun ensurePending(size: Int) {
        if (pending.size < size) pending = pending.copyOf(maxOf(size, pending.size * 2))
    }

    private fun emit() {
        if (pendingFrames == 0) return
        totalOut += pendingFrames
        val n = pendingFrames * channels
        val out = replaceOutputBuffer(n * 4)
        out.asFloatBuffer().put(pending, 0, n)
        out.position(n * 4)
        out.flip()
        pendingFrames = 0
    }

    /** True while the R2 engine is in use. */
    val usesFasterEngine: Boolean get() = engaged && usesFaster(efficient, formants)

    companion object {
        const val CHUNK = 1024

        /** The lighter R2 engine: asked for, and not needed for natural voices. */
        fun usesFaster(efficient: Boolean, formants: Boolean) = efficient && !formants
    }
}
