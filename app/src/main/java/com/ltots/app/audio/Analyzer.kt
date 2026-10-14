package com.ltots.app.audio

import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What the audio path is currently doing — shown on the output/info screen. */
data class StreamInfo(
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val inputEncoding: Int = C.ENCODING_INVALID,
    val outputEncoding: Int = C.ENCODING_INVALID,
) {
    fun encodingName(e: Int) = when (e) {
        C.ENCODING_PCM_16BIT -> "16-bit"
        C.ENCODING_PCM_24BIT -> "24-bit"
        C.ENCODING_PCM_32BIT -> "32-bit"
        C.ENCODING_PCM_FLOAT -> "32-bit float"
        else -> "—"
    }
}

/**
 * Lock-free tap written by the audio thread. Keeps the last ~2.7 s of the signal before and
 * after the EQ, plus the media time of the newest sample so the visuals line up with what's
 * actually coming out of the headphones (the audio thread runs ahead of playback).
 */
class AnalyzerTap {
    val size = 1 shl 17
    private val mask = size - 1
    val pre = FloatArray(size)
    val post = FloatArray(size)

    private var writeIdx = 0L
    @Volatile var published = 0L; private set
    @Volatile private var flushMediaUs = 0L
    @Volatile private var framesSinceFlush = 0L
    @Volatile var sampleRate = 48000; private set

    private val _info = MutableStateFlow(StreamInfo())
    val info: StateFlow<StreamInfo> = _info

    fun onFormat(sampleRate: Int, channels: Int, inEnc: Int, outEnc: Int) {
        this.sampleRate = sampleRate
        _info.value = StreamInfo(sampleRate, channels, inEnc, outEnc)
    }

    fun onFlush(positionOffsetUs: Long) {
        flushMediaUs = positionOffsetUs
        framesSinceFlush = 0
    }

    fun write(preSample: Float, postSample: Float) {
        val i = (writeIdx and mask.toLong()).toInt()
        pre[i] = preSample
        post[i] = postSample
        writeIdx++
    }

    fun commit(frames: Int) {
        framesSinceFlush += frames
        published = writeIdx
    }

    /** Media time (µs) of the newest sample in the ring. */
    fun headMediaTimeUs(): Long = flushMediaUs + framesSinceFlush * 1_000_000L / max(1, sampleRate)

    fun indexOf(frame: Long): Int = (frame and mask.toLong()).toInt()
}

/** One frame of visual data for the UI. */
class SpectrumFrame(
    val bands: Int,
    /** Post-EQ level per log-frequency band, 0..1. */
    val post: FloatArray = FloatArray(bands),
    /** Pre-EQ level per band, 0..1 — drawn as a faint ghost so you can see what the EQ changed. */
    val pre: FloatArray = FloatArray(bands),
    /** Scrolling waveform envelope (min/max pairs), oldest first. */
    val waveMin: FloatArray = FloatArray(WAVE_COLUMNS),
    val waveMax: FloatArray = FloatArray(WAVE_COLUMNS),
    /** Peak level of the newest audio (0..1), for the meter. */
    var peak: Float = 0f,
) {
    companion object {
        const val WAVE_COLUMNS = 240
        const val MIN_FREQ = 20.0
        const val MAX_FREQ = 20000.0

        /** Frequency at the centre of band [i] of [n] (log spacing, 20 Hz – 20 kHz). */
        fun bandFreq(i: Int, n: Int): Double = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow((i + 0.5) / n)
    }
}

/**
 * Turns the tap's ring buffer into smoothed spectrum bars and a waveform, synced to the
 * player's position. Runs on the UI side, once per frame.
 */
class Analyzer(private val tap: AnalyzerTap, val bands: Int = 96, private val fftSize: Int = 4096) {
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }
    private val fft = Fft(fftSize)
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val mags = FloatArray(fftSize / 2)
    private val frame = SpectrumFrame(bands)
    private val smoothPost = FloatArray(bands)
    private val smoothPre = FloatArray(bands)
    private var delayFrames = -1L

    /** Seconds of history shown in the scrolling waveform. */
    var waveSeconds = 2.5f

    /**
     * @param positionUs where the player says playback currently is.
     * @param playing whether audio is moving; when paused the bars fall to rest.
     */
    fun update(positionUs: Long, playing: Boolean): SpectrumFrame {
        val sr = tap.sampleRate
        val head = tap.published
        if (head < fftSize || !playing) {
            decay()
            return frame
        }

        // How far the audio thread is ahead of what you hear. Smoothed, and kept sane when the
        // estimate jumps (e.g. right after a track change, before the next flush).
        val aheadUs = tap.headMediaTimeUs() - positionUs
        val rawDelay = aheadUs * sr / 1_000_000L
        val maxDelay = (tap.size - fftSize - waveFrames(sr)).toLong()
        if (rawDelay in 0..maxDelay) {
            delayFrames = if (delayFrames < 0) rawDelay else (delayFrames * 7 + rawDelay) / 8
        } else if (delayFrames < 0) {
            delayFrames = (0.25 * sr).toLong()
        }
        val end = head - delayFrames.coerceIn(0, maxDelay)

        spectrum(tap.post, end, sr, smoothPost, frame.post)
        spectrum(tap.pre, end, sr, smoothPre, frame.pre)
        waveform(end, sr)
        return frame
    }

    private fun waveFrames(sr: Int) = (waveSeconds * sr).toInt()

    private fun spectrum(src: FloatArray, end: Long, sr: Int, smooth: FloatArray, out: FloatArray) {
        val start = end - fftSize
        for (i in 0 until fftSize) {
            re[i] = src[tap.indexOf(start + i)] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        val norm = 2f / (fftSize * 0.5f) // Hann coherent gain
        for (k in 0 until fftSize / 2) {
            val m = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]) * norm
            mags[k] = m
        }
        val binHz = sr.toDouble() / fftSize
        for (b in 0 until bands) {
            val f0 = SpectrumFrame.MIN_FREQ * (SpectrumFrame.MAX_FREQ / SpectrumFrame.MIN_FREQ).pow(b.toDouble() / bands)
            val f1 = SpectrumFrame.MIN_FREQ * (SpectrumFrame.MAX_FREQ / SpectrumFrame.MIN_FREQ).pow((b + 1.0) / bands)
            var k0 = (f0 / binHz).toInt()
            var k1 = (f1 / binHz).toInt()
            k0 = k0.coerceIn(1, mags.size - 1)
            k1 = k1.coerceIn(k0, mags.size - 1)
            var level: Float
            if (k1 == k0) {
                // Low bands narrower than one FFT bin: interpolate.
                val fc = (f0 + f1) / 2 / binHz
                val kk = fc.toInt().coerceIn(0, mags.size - 2)
                val t = (fc - kk).toFloat().coerceIn(0f, 1f)
                level = mags[kk] * (1 - t) + mags[kk + 1] * t
            } else {
                level = 0f
                for (k in k0..k1) level = max(level, mags[k])
            }
            // Gentle tilt (+3 dB/oct) so music looks balanced rather than bass-heavy.
            val fc = (f0 * f1).let { kotlin.math.sqrt(it) }
            val tilt = (3.0 * ln(fc / 1000.0) / ln(2.0)).toFloat()
            val db = 20f * log10(max(level, 1e-9f)) + tilt
            val v = ((db + 72f) / 72f).coerceIn(0f, 1f)
            val s = smooth[b]
            smooth[b] = if (v > s) s + (v - s) * 0.6f else s + (v - s) * 0.18f
            out[b] = smooth[b]
        }
    }

    private fun waveform(end: Long, sr: Int) {
        val total = waveFrames(sr)
        val cols = SpectrumFrame.WAVE_COLUMNS
        val per = max(1, total / cols)
        val start = end - per.toLong() * cols
        var peak = 0f
        for (c in 0 until cols) {
            var lo = 0f; var hi = 0f
            val s = start + c.toLong() * per
            var i = 0
            while (i < per) {
                val v = tap.post[tap.indexOf(s + i)]
                if (v < lo) lo = v
                if (v > hi) hi = v
                i += 4 // sub-sample for speed; visually identical
            }
            frame.waveMin[c] = lo
            frame.waveMax[c] = hi
            if (c >= cols - 8) peak = max(peak, max(-lo, hi))
        }
        frame.peak = peak
    }

    private fun decay() {
        for (b in 0 until bands) {
            smoothPost[b] *= 0.86f; frame.post[b] = smoothPost[b]
            smoothPre[b] *= 0.86f; frame.pre[b] = smoothPre[b]
        }
        for (c in 0 until SpectrumFrame.WAVE_COLUMNS) {
            frame.waveMin[c] *= 0.9f; frame.waveMax[c] *= 0.9f
        }
        frame.peak *= 0.9f
        delayFrames = -1
    }
}

/** Plain iterative radix-2 FFT. */
class Fft(private val n: Int) {
    private val levels = 31 - Integer.numberOfLeadingZeros(n)
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(1 shl levels == n) { "FFT size must be a power of two" }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val tre = re[l] * cosT[k] + im[l] * sinT[k]
                    val tim = -re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

