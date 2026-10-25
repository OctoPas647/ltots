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
 * player's position. Runs on the UI side, once per visual frame.
 *
 * Two FFTs per frame: a long one (85 ms) for fine detail in the bass, and a short one (21 ms)
 * for everything above [SPLIT_HZ], so drums and transients show up instantly. Both windows are
 * centred on the sample you're hearing right now, and smoothing is time-based so it feels the
 * same at any frame rate.
 *
 * Kept cheap because it runs many times a second while visible:
 *  - each band's frequency range, FFT bins and tilt are worked out once, not every frame;
 *  - the long FFT only keeps the bass bins it's used for, the short one only the rest;
 *  - the pre-EQ "ghost" is only analysed when it's drawn and actually differs from the result;
 *  - the scrolling waveform only measures the newly arrived audio each frame and scrolls the
 *    rest, instead of re-reading 2.5 s of samples every frame.
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

    // Per-band plan for the current sample rate.
    private var planRate = 0
    private val useLong = BooleanArray(bands)
    private val k0 = IntArray(bands)
    private val k1 = IntArray(bands)
    private val interpK = IntArray(bands)
    private val interpT = FloatArray(bands)
    private val tiltDb = FloatArray(bands)
    private var longBins = 0  // highest long-FFT bin any band reads (+1)
    private var shortFrom = 0 // lowest short-FFT bin any band reads

    // Scrolling waveform: one min/max per column, kept in a ring and filled incrementally.
    private val cols = SpectrumFrame.WAVE_COLUMNS
    private val colMin = FloatArray(cols)
    private val colMax = FloatArray(cols)
    private var lastCol = Long.MIN_VALUE
    private var colFrames = 0

    /** Seconds of history shown in the scrolling waveform. */
    var waveSeconds = 2.5f

    /**
     * @param positionUs where the player says playback currently is.
     * @param playing whether audio is moving; when paused the bars fall to rest.
     * @param pre analyse the signal before the EQ separately (otherwise it mirrors the result).
     * @param wave keep the scrolling waveform up to date.
     */
    /** The time source for smoothing (tests drive it frame by frame). */
    internal var clock: () -> Long = System::nanoTime

    fun update(positionUs: Long, playing: Boolean, pre: Boolean = true, wave: Boolean = true): SpectrumFrame {
        val now = clock()
        val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastNanos = now
        val sr = tap.sampleRate
        val head = tap.published
        if (head < long.size || !playing) {
            decay(dt)
            return frame
        }
        if (sr != planRate) plan(sr)

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

        analyse(tap.post, now0, head, smoothPost, frame.post, dt)
        if (pre) analyse(tap.pre, now0, head, smoothPre, frame.pre, dt)
        else { smoothPost.copyInto(smoothPre); frame.post.copyInto(frame.pre) }
        if (wave) waveform(now0, sr)
        return frame
    }

    private fun waveFrames(sr: Int) = (waveSeconds * sr).toInt()

    /**
     * Starts from [f], a frozen picture (Look > Freeze when music stops): it shows at once, and
     * the bars move on from it when the music plays again instead of jumping up from nothing.
     */
    fun seed(f: SpectrumFrame): SpectrumFrame {
        if (f.bands == bands) {
            f.post.copyInto(frame.post); f.pre.copyInto(frame.pre)
            f.post.copyInto(smoothPost); f.pre.copyInto(smoothPre)
        }
        f.waveMin.copyInto(frame.waveMin); f.waveMax.copyInto(frame.waveMax)
        frame.peak = f.peak
        return frame
    }

    private fun plan(sr: Int) {
        planRate = sr
        val longHz = sr.toDouble() / long.size
        val shortHz = sr.toDouble() / short.size
        longBins = 0
        shortFrom = short.size / 2
        for (b in 0 until bands) {
            val f0 = SpectrumFrame.MIN_FREQ * (SpectrumFrame.MAX_FREQ / SpectrumFrame.MIN_FREQ).pow(b.toDouble() / bands)
            val f1 = SpectrumFrame.MIN_FREQ * (SpectrumFrame.MAX_FREQ / SpectrumFrame.MIN_FREQ).pow((b + 1.0) / bands)
            val fc = kotlin.math.sqrt(f0 * f1)
            // Gentle tilt (+3 dB/oct) so music looks balanced rather than bass-heavy.
            tiltDb[b] = (3.0 * ln(fc / 1000.0) / ln(2.0)).toFloat()
            val lng = fc < SPLIT_HZ
            useLong[b] = lng
            val binHz = if (lng) longHz else shortHz
            val nMags = (if (lng) long.size else short.size) / 2
            val a = (f0 / binHz).toInt().coerceIn(1, nMags - 1)
            val z = (f1 / binHz).toInt().coerceIn(a, nMags - 1)
            k0[b] = a; k1[b] = z
            // Band narrower than one FFT bin: interpolate between the two nearest bins.
            val fcb = (f0 + f1) / 2 / binHz
            interpK[b] = fcb.toInt().coerceIn(0, nMags - 2)
            interpT[b] = (fcb - interpK[b]).toFloat().coerceIn(0f, 1f)
            val lo = if (z == a) interpK[b] else a
            val hi = if (z == a) interpK[b] + 1 else z
            if (lng) longBins = max(longBins, hi + 1) else shortFrom = min(shortFrom, lo)
        }
        lastCol = Long.MIN_VALUE
    }

    private fun runFft(r: Resolution, src: FloatArray, centre: Long, head: Long, binFrom: Int, binTo: Int) {
        // Centre the window on "now", but never read past what the audio thread has written.
        val end = minOf(centre + r.size / 2, head)
        val start = end - r.size
        for (i in 0 until r.size) {
            r.re[i] = src[tap.indexOf(start + i)] * r.window[i]
            r.im[i] = 0f
        }
        r.fft.transform(r.re, r.im)
        val norm = 2f / (r.size * 0.5f) // Hann coherent gain
        for (k in binFrom until binTo) r.mags[k] = kotlin.math.sqrt(r.re[k] * r.re[k] + r.im[k] * r.im[k]) * norm
    }

    private fun bandLevel(r: Resolution, b: Int): Float {
        val mags = r.mags
        val a = k0[b]
        val z = k1[b]
        return if (z == a) {
            val kk = interpK[b]
            val t = interpT[b]
            mags[kk] * (1 - t) + mags[kk + 1] * t
        } else {
            var level = 0f
            for (k in a..z) level = max(level, mags[k])
            level
        }
    }

    private fun analyse(src: FloatArray, centre: Long, head: Long, smooth: FloatArray, out: FloatArray, dt: Float) {
        if (longBins > 0) runFft(long, src, centre, head, 0, longBins)
        if (shortFrom < short.size / 2) runFft(short, src, centre, head, shortFrom, short.size / 2)
        // Time-based smoothing: rises in ~12 ms, falls in ~70 ms.
        val attack = 1f - kotlin.math.exp(-dt / 0.012f)
        val release = 1f - kotlin.math.exp(-dt / 0.070f)
        for (b in 0 until bands) {
            val level = bandLevel(if (useLong[b]) long else short, b)
            val db = 20f * log10(max(level, 1e-9f)) + tiltDb[b]
            val v = ((db + 72f) / 72f).coerceIn(0f, 1f)
            val s = smooth[b]
            smooth[b] = s + (v - s) * (if (v > s) attack else release)
            out[b] = smooth[b]
        }
    }

    private fun waveform(end: Long, sr: Int) {
        val per = max(1, waveFrames(sr) / cols)
        if (per != colFrames) { colFrames = per; lastCol = Long.MIN_VALUE }
        // The newest complete column, and how many columns are new since the last frame.
        val newest = end / per - 1
        val fresh = when {
            lastCol == Long.MIN_VALUE || newest - lastCol >= cols || lastCol - newest >= cols -> cols.toLong()
            // The "now" estimate can wobble back a little: nothing new to measure.
            newest <= lastCol -> 0L
            else -> newest - lastCol
        }
        for (c in newest - fresh + 1..newest) {
            var lo = 0f; var hi = 0f
            val s = c * per
            var i = 0
            while (i < per) {
                val v = tap.post[tap.indexOf(s + i)]
                if (v < lo) lo = v
                if (v > hi) hi = v
                i += 4 // sub-sample for speed; visually identical
            }
            val slot = Math.floorMod(c, cols.toLong()).toInt()
            colMin[slot] = lo
            colMax[slot] = hi
        }
        if (fresh > 0) lastCol = newest
        // Lay the ring out oldest first for drawing.
        var peak = 0f
        for (i in 0 until cols) {
            val slot = Math.floorMod(newest - (cols - 1) + i, cols.toLong()).toInt()
            frame.waveMin[i] = colMin[slot]
            frame.waveMax[i] = colMax[slot]
            if (i >= cols - 8) peak = max(peak, max(-colMin[slot], colMax[slot]))
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
        lastCol = Long.MIN_VALUE
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

