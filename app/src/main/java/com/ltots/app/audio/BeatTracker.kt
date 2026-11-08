package com.ltots.app.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.ltots.app.identify.MixedFft
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Finds the beats of a whole song ahead of time, from 22.05 kHz mono audio, at 50 frames a
 * second:
 *  - with the **beat AI** (Beat This!, JKU Linz, ISMIR 2024 — on the phone, once downloaded):
 *    a transformer trained on thousands of annotated songs; it keeps the beat through quiet
 *    parts, breakdowns and tricky rhythms;
 *  - without it: a classic beat tracker — onsets from spectral flux, the tempo from their
 *    autocorrelation, and the beat grid that best fits both (dynamic programming, after
 *    D. Ellis 2007).
 * Then each beat gets its strengths (song, lows, mids, highs), largest on drops.
 */
object BeatTracker {
    const val RATE = 22_050
    const val HOP = 441
    const val FPS = 50
    private const val N_FFT = 1024
    private const val BANDS = 40

    /** Spectrum of the song in [BANDS] log-spaced bands (30 Hz–11 kHz), log1p-compressed. */
    class Spectrum(val frames: Int, val bands: Array<FloatArray>, val bandHz: DoubleArray)

    fun spectrum(pcm: FloatArray): Spectrum {
        val n = if (pcm.size < N_FFT) 1 else 1 + (pcm.size - N_FFT) / HOP
        val fft = MixedFft(N_FFT)
        val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) }
        val edges = DoubleArray(BANDS + 1) { 30.0 * (11_000.0 / 30.0).pow(it / BANDS.toDouble()) }
        val binOf = IntArray(N_FFT / 2 + 1) { b ->
            val f = b * RATE.toDouble() / N_FFT
            if (f < edges[0] || f >= edges[BANDS]) -1 else edges.indexOfLast { it <= f }.coerceAtMost(BANDS - 1)
        }
        val re = DoubleArray(N_FFT); val im = DoubleArray(N_FFT)
        val out = Array(n) { FloatArray(BANDS) }
        val sum = DoubleArray(BANDS)
        val norm = sqrt(N_FFT.toDouble())
        for (t in 0 until n) {
            val s = t * HOP
            for (i in 0 until N_FFT) { re[i] = (if (s + i < pcm.size) pcm[s + i] else 0f) * window[i]; im[i] = 0.0 }
            fft.forward(re, im)
            sum.fill(0.0)
            for (b in 0..N_FFT / 2) { val k = binOf[b]; if (k >= 0) sum[k] += sqrt(re[b] * re[b] + im[b] * im[b]) }
            for (k in 0 until BANDS) out[t][k] = ln(1.0 + 1000.0 * sum[k] / norm).toFloat()
        }
        return Spectrum(n, out, DoubleArray(BANDS) { sqrt(edges[it] * edges[it + 1]) })
    }

    // ---- classic tracker -----------------------------------------------------------------

    /** Onset strength per frame: rises in the spectrum, slow changes removed, in SD units. */
    internal fun onsets(sp: Spectrum): FloatArray {
        val n = sp.frames
        val flux = FloatArray(n)
        for (t in 1 until n) {
            var s = 0f
            for (k in 0 until BANDS) s += max(0f, sp.bands[t][k] - sp.bands[t - 1][k])
            flux[t] = s / BANDS
        }
        val w = 12 // half of a 0.5 s moving average
        val out = FloatArray(n)
        var acc = 0.0
        for (i in 0 until min(n, w)) acc += flux[i]
        for (t in 0 until n) {
            if (t + w < n) acc += flux[t + w]
            if (t - w - 1 >= 0) acc -= flux[t - w - 1]
            val cnt = min(n - 1, t + w) - max(0, t - w) + 1
            out[t] = max(0f, flux[t] - (acc / cnt).toFloat())
        }
        val mean = out.average()
        val sd = sqrt(out.sumOf { (it - mean) * (it - mean) } / max(1, n)).takeIf { it > 1e-9 } ?: 1.0
        for (t in 0 until n) out[t] = (out[t] / sd).toFloat()
        return out
    }

    /**
     * The beat period in frames (fractional: 128 BPM is 23.4 frames): autocorrelation of the
     * slightly smoothed onsets with its 2nd and 3rd harmonics, weighted towards 120 BPM.
     */
    internal fun period(env: FloatArray): Double {
        val n = env.size
        val sm = FloatArray(n) { i -> 0.25f * env[maxOf(0, i - 1)] + 0.5f * env[i] + 0.25f * env[minOf(n - 1, i + 1)] }
        val mean = sm.average()
        val maxLag = minOf(n - 1, 3 * FPS * 60 / 55 + 3)
        val ac = DoubleArray(maxLag + 1) { l ->
            var s = 0.0
            for (i in 0 until n - l) s += (sm[i] - mean) * (sm[i + l] - mean)
            s
        }
        val ac0 = ac[0].takeIf { it > 0 } ?: return FPS / 2.0
        fun at(l: Double): Double {
            if (l >= maxLag) return 0.0
            val i = l.toInt(); val f = l - i
            return ac[i] * (1 - f) + ac[i + 1] * f
        }
        var best = FPS / 2.0; var bestS = Double.NEGATIVE_INFINITY
        var l = FPS * 60.0 / 200
        while (l <= FPS * 60.0 / 55) {
            val bpm = 60.0 * FPS / l
            val w = exp(-0.5 * log2(bpm / 120.0).pow(2))
            val s = (at(l) + 0.5 * at(2 * l) + 0.25 * at(3 * l)) / ac0 * w
            if (s > bestS) { bestS = s; best = l }
            l += 0.1
        }
        return best
    }

    /** Beat frames: the grid that hits strong onsets while keeping the tempo steady. */
    internal fun classicBeats(env: FloatArray, tightness: Double = 100.0): IntArray {
        val n = env.size
        if (n < 2 * FPS) return IntArray(0)
        val p = period(env)
        val score = DoubleArray(n) { env[it].toDouble() }
        val back = IntArray(n) { -1 }
        val lo = max(1, (p / 2).toInt()); val hi = (2 * p).toInt()
        val pen = DoubleArray(hi + 1) { d -> if (d == 0) 0.0 else -tightness * ln(d / p).pow(2) }
        for (t in 0 until n) {
            var bestV = 0.0; var bestJ = -1
            for (d in lo until min(hi, t) + 1) {
                val j = t - d
                if (d > hi || j < 0) continue
                val v = score[j] + pen[d]
                if (v > bestV) { bestV = v; bestJ = j }
            }
            if (bestJ >= 0) { score[t] = env[t] + bestV; back[t] = bestJ }
        }
        var t = if (n > hi) (n - hi until n).maxBy { score[it] } else (0 until n).maxBy { score[it] }
        val out = ArrayList<Int>()
        while (t >= 0) { out += t; t = back[t] }
        return out.reversed().toIntArray()
    }

    // ---- strengths ----------------------------------------------------------------------

    /**
     * Strength of each beat in [times] (seconds) for the song and its lows, mids and highs:
     * how much that part jumps on the beat compared with the half second before, scaled to the
     * song, with every beat showing a little and drops (the music getting much louder than the
     * last few seconds) and downbeats the most.
     */
    fun mapFrom(times: FloatArray, downbeat: BooleanArray, sp: Spectrum, byAi: Boolean): BeatMap {
        val lowB = sp.bandHz.indices.filter { sp.bandHz[it] < 150 }
        val midB = sp.bandHz.indices.filter { sp.bandHz[it] in 250.0..4000.0 }
        val highB = sp.bandHz.indices.filter { sp.bandHz[it] > 4000 }
        fun level(t: Int, bands: List<Int>): Float {
            val f = sp.bands[t.coerceIn(0, sp.frames - 1)]
            var s = 0f; for (k in bands) s += f[k]; return s / bands.size
        }
        fun lift(frame: Int, bands: List<Int>): Float {
            var peak = 0f
            for (t in frame - 1..frame + 2) peak = max(peak, level(t, bands))
            var before = 0f; var c = 0
            for (t in frame - 25 until frame - 2) if (t >= 0) { before += level(t, bands); c++ }
            return max(0f, peak - if (c > 0) before / c else peak)
        }
        // How much louder the next 2 s are than the 4 s before, per band (log units), averaged
        // over the quarter of bands that rose most — a drop brings in the bass and kick even
        // when the hats and vocals carry on as before.
        fun bandMeans(from: Int, to: Int): FloatArray {
            val m = FloatArray(sp.bandHz.size); var c = 0
            for (t in max(0, from) until min(sp.frames, to)) { for (k in m.indices) m[k] += sp.bands[t][k]; c++ }
            if (c > 0) for (k in m.indices) m[k] /= c
            return m
        }
        fun rise(f: Int): Float {
            val after = bandMeans(f, f + 2 * FPS); val before = bandMeans(f - 4 * FPS, f)
            val d = FloatArray(after.size) { after[it] - before[it] }.sortedDescending()
            return d.take(max(1, d.size / 4)).average().toFloat()
        }
        val frames = IntArray(times.size) { (times[it] * FPS).toInt() }
        // Drops: where the music gets much louder than the seconds before, compared with how it
        // usually moves in this song — the biggest rises only, each the peak of its own 8 s,
        // at most one every 16 beats. The drop beat and the next 3 hit hardest.
        val rises = FloatArray(times.size) { if (frames[it] > 4 * FPS) rise(frames[it]) else 0f }
        val sortedRises = rises.sorted()
        val usual = if (sortedRises.isEmpty()) 0f else sortedRises[((sortedRises.size - 1) * 0.9).toInt()]
        val bar = max(DROP_LIFT, usual * 1.6f)
        val drop = BooleanArray(times.size)
        var lastDrop = -100
        for (i in times.indices) {
            if (rises[i] < bar || i - lastDrop < 16) continue
            val near = times.indices.filter { kotlin.math.abs(times[it] - times[i]) <= 4f }
            if (near.any { rises[it] > rises[i] }) continue
            lastDrop = i
            for (j in i until min(times.size, i + 4)) drop[j] = true
        }
        fun strengths(bands: List<Int>): FloatArray {
            if (bands.isEmpty() || times.isEmpty()) return FloatArray(times.size) { 0.5f }
            val raw = FloatArray(times.size) { lift(frames[it], bands) }
            val top = raw.sorted()[((raw.size - 1) * 0.9).toInt()].takeIf { it > 1e-4f } ?: 1f
            return FloatArray(times.size) { i ->
                var s = 0.3f + 0.5f * (raw[i] / top).coerceIn(0f, 1f)
                if (downbeat[i]) s += 0.08f
                if (drop[i]) s = max(s, if (i == 0 || !drop[i - 1]) 1f else 0.9f)
                s.coerceIn(0f, 1f)
            }
        }
        val lows = strengths(lowB); val mids = strengths(midB); val highs = strengths(highB)
        // The whole song: whichever part hits hardest on the beat, leaning on the lows.
        val song = FloatArray(times.size) { max(lows[it], max(mids[it] * 0.92f, highs[it] * 0.85f)) }
        return BeatMap(times, song, lows, mids, highs, downbeat, byAi)
    }

    /** A drop (or a big chorus coming in): the rising bands clearly louder over the next 2 s than the 4 s before. */
    private const val DROP_LIFT = 0.35f

    /** The classic tracker, all the way to a [BeatMap]. */
    fun classic(pcm: FloatArray): BeatMap {
        val sp = spectrum(pcm)
        val beats = classicBeats(onsets(sp))
        // A frame's sound is centred half a window after its start.
        val times = FloatArray(beats.size) { beats[it] / FPS.toFloat() + N_FFT / 2f / RATE }
        // Downbeats: every 4th beat, starting where the lows hit hardest.
        val phase = (0 until 4).maxByOrNull { ph -> beats.indices.filter { it % 4 == ph }.sumOf { sp.bands[beats[it]].take(6).sum().toDouble() } } ?: 0
        return mapFrom(times, BooleanArray(beats.size) { it % 4 == phase }, sp, byAi = false)
    }

    // ---- the beat AI --------------------------------------------------------------------

    /** Beat This! (final0) as two ONNX graphs: its log-mel front end and the network. */
    class Ai(mel: File, net: File, private val threads: Int = 2) : Closeable {
        private val env = OrtEnvironment.getEnvironment()
        private fun open(f: File) = env.createSession(f.absolutePath, OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
        })
        private val melSession = open(mel)
        private val netSession = open(net)

        /** Beat and downbeat logits per frame (50 fps) for 22.05 kHz mono [pcm]. */
        fun logits(pcm: FloatArray, onProgress: (Float) -> Unit = {}): Pair<FloatArray, FloatArray> {
            val spect: Array<FloatArray> = OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm), longArrayOf(pcm.size.toLong())).use { a ->
                melSession.run(mapOf("audio" to a)).use { r ->
                    @Suppress("UNCHECKED_CAST") (r[0].value as Array<FloatArray>)
                }
            }
            return splitPredict(spect.size, onProgress) { from, to, padL, padR ->
                val frames = padL + (to - from) + padR
                val buf = FloatArray(frames * 128)
                for (i in from until to) System.arraycopy(spect[i], 0, buf, (padL + i - from) * 128, 128)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, frames.toLong(), 128)).use { x ->
                    netSession.run(mapOf("spect" to x)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val b = (r[0].value as Array<FloatArray>)[0]
                        @Suppress("UNCHECKED_CAST")
                        val d = (r[1].value as Array<FloatArray>)[0]
                        b to d
                    }
                }
            }
        }

        override fun close() { melSession.close(); netSession.close() }
    }

    const val CHUNK = 1500
    const val BORDER = 6

    /**
     * The reference's split_predict_aggregate: windows of [CHUNK] frames overlapping by
     * 2 × [BORDER], the last one moved back to end at the song's end, borders dropped, and where
     * windows overlap the earlier one's prediction kept. [run] gets (from, to, zeros before,
     * zeros after) and returns that window's beat and downbeat logits.
     */
    internal fun splitPredict(
        n: Int,
        onProgress: (Float) -> Unit = {},
        run: (from: Int, to: Int, padL: Int, padR: Int) -> Pair<FloatArray, FloatArray>,
    ): Pair<FloatArray, FloatArray> {
        val starts = ArrayList<Int>()
        var s = -BORDER
        while (s < n - BORDER) { starts += s; s += CHUNK - 2 * BORDER }
        if (n > CHUNK - 2 * BORDER) starts[starts.size - 1] = n - (CHUNK - BORDER)
        val beat = FloatArray(n) { -1000f }; val down = FloatArray(n) { -1000f }
        val preds = starts.mapIndexed { i, st ->
            val from = max(st, 0); val to = min(st + CHUNK, n)
            val padL = max(0, -st); val padR = max(0, min(BORDER, st + CHUNK - n))
            run(from, to, padL, padR).also { onProgress((i + 1f) / starts.size) }
        }
        for (i in starts.indices.reversed()) {
            val st = starts[i]; val (b, d) = preds[i]
            val len = b.size - 2 * BORDER
            for (k in 0 until len) {
                val t = st + BORDER + k
                if (t in 0 until n) { beat[t] = b[BORDER + k]; down[t] = d[BORDER + k] }
            }
        }
        return beat to down
    }

    /** The reference's minimal postprocessing: logit > 0 and the max within ±3 frames, neighbours merged. */
    internal fun peaks(logits: FloatArray): FloatArray {
        val n = logits.size
        val frames = ArrayList<Int>()
        for (t in 0 until n) {
            val v = logits[t]
            if (v <= 0f) continue
            var isMax = true
            for (k in max(0, t - 3)..min(n - 1, t + 3)) if (logits[k] > v) { isMax = false; break }
            if (isMax) frames += t
        }
        if (frames.isEmpty()) return FloatArray(0)
        val out = ArrayList<Float>()
        var peak = frames[0].toFloat(); var count = 1
        for (i in 1 until frames.size) {
            val p = frames[i]
            if (p - peak <= 1f) { count++; peak += (p - peak) / count } else { out += peak; peak = p.toFloat(); count = 1 }
        }
        out += peak
        return FloatArray(out.size) { out[it] / FPS }
    }

    /** The AI, all the way to a [BeatMap]. Falls back to [classic] if it finds too few beats. */
    fun withAi(ai: Ai, pcm: FloatArray, onProgress: (Float) -> Unit = {}): BeatMap {
        val (b, d) = ai.logits(pcm, onProgress)
        val beats = peaks(b)
        if (beats.size < 4) return classic(pcm)
        val downs = peaks(d)
        val isDown = BooleanArray(beats.size)
        for (t in downs) {
            var best = 0; var bestD = Float.MAX_VALUE
            for (i in beats.indices) { val dd = kotlin.math.abs(beats[i] - t); if (dd < bestD) { bestD = dd; best = i } }
            isDown[best] = true
        }
        return mapFrom(beats, isDown, spectrum(pcm), byAi = true)
    }
}
