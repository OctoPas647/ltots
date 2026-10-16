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
    private var preCursor = 0L
    private var postCursor = 0L
    @Volatile var published = 0L; private set
    @Volatile private var flushMediaUs = 0L
    @Volatile private var framesSinceFlush = 0L
    @Volatile var sampleRate = 48000; private set

    /**
     * Whether a live view is on screen. While nobody watches, the audio thread skips filling
     * the ring entirely (it still keeps time, so the view lines up the moment it reappears).
     */
    @Volatile var watching = false; private set
    private var watchers = 0
    @Volatile private var clearPending = false

    /** A live view appeared; returns the function to call when it goes away. */
    fun watch(): () -> Unit {
        synchronized(this) {
            if (watchers++ == 0) {
                clearPending = true // old audio in the ring is stale; start from silence
                watching = true
            }
        }
        var released = false
        return {
            synchronized(this) {
                if (!released) {
                    released = true
                    if (--watchers == 0) watching = false
                }
            }
        }
    }

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

    /** Audio thread: call before writing a block. */
    fun prepareWrite() {
        if (clearPending) {
            clearPending = false
            pre.fill(0f)
            post.fill(0f)
        }
        preCursor = writeIdx
        postCursor = writeIdx
    }

    fun writePre(v: Float) { pre[(preCursor++ and mask.toLong()).toInt()] = v }
    fun writePost(v: Float) { post[(postCursor++ and mask.toLong()).toInt()] = v }

    fun write(preSample: Float, postSample: Float) {
        writePre(preSample)
        writePost(postSample)
    }

    fun commit(frames: Int) {
        writeIdx += frames
        preCursor = writeIdx
        postCursor = writeIdx
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
 * player's position. Runs on the UI side, once per display frame (up to 120 Hz).
 *
 * Two FFTs per frame: a long one (85 ms) for fine detail in the bass, and a short one (21 ms)
 * for everything above [SPLIT_HZ], so drums and transients show up instantly. Both windows are
 * centred on the sample you're hearing right now, and smoothing is time-based so it feels the
 * same at 60 or 120 fps.
 */
class Analyzer(private val tap: AnalyzerTap, val bands: Int = 96) {
    private class Resolution(val size: Int) {
        val window = FloatArray(size) { (0.5 - 0.5 * cos(2.0 * PI * it / (size - 1))).toFloat() }
        val fft = Fft(size)
        val re = FloatArray(size)
        val im = FloatArray(size)
        val mags = FloatArray(size / 2)
    }

    private val long = Resolution(4096)
    private val short = Resolution(1024)
    private val frame = SpectrumFrame(bands)
    private val smoothPost = FloatArray(bands)
    private val smoothPre = FloatArray(bands)
    private var delayFrames = -1L
    private var lastNanos = 0L

    /** Seconds of history shown in the scrolling waveform. */
    var waveSeconds = 2.5f

    /**
     * @param positionUs where the player says playback currently is.
     * @param playing whether audio is moving; when paused the bars fall to rest.
     */
    fun update(positionUs: Long, playing: Boolean): SpectrumFrame {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastNanos = now
        val sr = tap.sampleRate
        val head = tap.published
        if (head < long.size || !playing) {
            decay(dt)
            return frame
        }

        // How far the audio thread is ahead of what you hear. Smoothed, and kept sane when the
        // estimate jumps (e.g. right after a track change, before the next flush).
        val aheadUs = tap.headMediaTimeUs() - positionUs
        val rawDelay = aheadUs * sr / 1_000_000L
        val maxDelay = (tap.size - long.size - waveFrames(sr)).toLong()
        if (rawDelay in 0..maxDelay) {
            delayFrames = if (delayFrames < 0) rawDelay else (delayFrames * 3 + rawDelay) / 4
        } else if (delayFrames < 0) {
            delayFrames = (0.25 * sr).toLong()
        }
        val now0 = head - delayFrames.coerceIn(0, maxDelay)

        analyse(tap.post, now0, head, sr, smoothPost, frame.post, dt)
        analyse(tap.pre, now0, head, sr, smoothPre, frame.pre, dt)
        waveform(now0, sr)
        return frame
    }

    private fun waveFrames(sr: Int) = (waveSeconds * sr).toInt()

    private fun runFft(r: Resolution, src: FloatArray, centre: Long, head: Long) {
        // Centre the window on "now", but never read past what the audio thread has written.
        val end = minOf(centre + r.size / 2, head)
        val start = end - r.size
        for (i in 0 until r.size) {
            r.re[i] = src[tap.indexOf(start + i)] * r.window[i]
            r.im[i] = 0f
        }
        r.fft.transform(r.re, r.im)
        val norm = 2f / (r.size * 0.5f) // Hann coherent gain
        for (k in 0 until r.size / 2) r.mags[k] = kotlin.math.sqrt(r.re[k] * r.re[k] + r.im[k] * r.im[k]) * norm
    }

    private fun bandLevel(r: Resolution, f0: Double, f1: Double, sr: Int): Float {
        val binHz = sr.toDouble() / r.size
        val mags = r.mags
        val k0 = (f0 / binHz).toInt().coerceIn(1, mags.size - 1)
        val k1 = (f1 / binHz).toInt().coerceIn(k0, mags.size - 1)
        return if (k1 == k0) {
            // Band narrower than one FFT bin: interpolate.
            val fc = (f0 + f1) / 2 / binHz
            val kk = fc.toInt().coerceIn(0, mags.size - 2)
            val t = (fc - kk).toFloat().coerceIn(0f, 1f)
            mags[kk] * (1 - t) + mags[kk + 1] * t
        } else {
            var level = 0f
            for (k in k0..k1) level = max(level, mags[k])
            level
        }
    }

    private fun analyse(src: FloatArray, centre: Long, head: Long, sr: Int, smooth: FloatArray, out: FloatArray, dt: Float) {
        runFft(long, src, centre, head)
        runFft(short, src, centre, head)
        // Time-based smoothing: rises in ~12 ms, falls in ~70 ms.
        val attack = 1f - kotlin.math.exp(-dt / 0.012f)
        val release = 1f - kotlin.math.exp(-dt / 0.070f)
        for (b in 0 until bands) {
            val f0 = SpectrumFrame.MIN_FREQ * (SpectrumFrame.MAX_FREQ / SpectrumFrame.MIN_FREQ).pow(b.toDouble() / bands)
            val f1 = SpectrumFrame.MIN_FREQ * (SpectrumFrame.MAX_FREQ / SpectrumFrame.MIN_FREQ).pow((b + 1.0) / bands)
            val fc = kotlin.math.sqrt(f0 * f1)
            val level = if (fc < SPLIT_HZ) bandLevel(long, f0, f1, sr) else bandLevel(short, f0, f1, sr)
            // Gentle tilt (+3 dB/oct) so music looks balanced rather than bass-heavy.
            val tilt = (3.0 * ln(fc / 1000.0) / ln(2.0)).toFloat()
            val db = 20f * log10(max(level, 1e-9f)) + tilt
            val v = ((db + 72f) / 72f).coerceIn(0f, 1f)
            val s = smooth[b]
            smooth[b] = s + (v - s) * (if (v > s) attack else release)
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

    private fun decay(dt: Float) {
        val k = kotlin.math.exp(-dt / 0.09f)
        for (b in 0 until bands) {
            smoothPost[b] *= k; frame.post[b] = smoothPost[b]
            smoothPre[b] *= k; frame.pre[b] = smoothPre[b]
        }
        for (c in 0 until SpectrumFrame.WAVE_COLUMNS) {
            frame.waveMin[c] *= k; frame.waveMax[c] *= k
        }
        frame.peak *= k
        delayFrames = -1
        if (dt >= 0.1f) lastNanos = 0L
    }

    companion object {
        const val SPLIT_HZ = 300.0
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

