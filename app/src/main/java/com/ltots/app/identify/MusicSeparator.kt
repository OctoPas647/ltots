package com.ltots.app.identify

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos

/**
 * The "ears" of the song finder: an AI music-source-separation model (UVR MDX-Net,
 * Inst HQ 3) that splits a clip into the music and everything voice-like on top of it —
 * someone talking over the song, narration, a TikTok voice-over. Sound effects and crowd noise
 * are weakened too.
 *
 * Works on 44.1 kHz spectrograms (n_fft 6144, hop 1024), 256 frames at a time, as the model
 * was trained. Input and output here are 16 kHz mono, which is all fingerprinting needs.
 */
class MusicSeparator(model: File, threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)) : Closeable {
    class Stems(
        /** The music on its own (16 kHz mono). */
        val music: FloatArray,
        /** Everything the model took out: voices, speech, part of the noise (16 kHz mono). */
        val voice: FloatArray,
    )

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        model.absolutePath,
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
        },
    )
    private val inputName = session.inputNames.first()

    private val nFft = 6144
    private val hop = 1024
    private val dimF = 3072
    private val dimT = 256
    private val compensate = 1.022f
    private val trim = nFft / 2
    private val chunk = hop * (dimT - 1)
    private val gen = chunk - 2 * trim
    private val bins = nFft / 2 + 1
    private val window = DoubleArray(nFft) { 0.5 - 0.5 * cos(2 * PI * it / nFft) } // periodic Hann
    private val fft = MixedFft(nFft)

    /** Splits [mono16k]; [onProgress] goes 0..1. */
    fun separate(mono16k: FloatArray, onProgress: (Float) -> Unit = {}): Stems {
        val x = Dsp.resample(mono16k, RATE_HI / 16000.0)
        val len = x.size
        val pad = gen - len % gen
        val padded = FloatArray(trim + len + pad + trim)
        System.arraycopy(x, 0, padded, trim, len)
        val out = FloatArray(len + pad)

        val steps = (padded.size - 2 * trim + gen - 1) / gen
        var step = 0
        var i = 0
        val input = FloatArray(4 * dimF * dimT)
        while (i < padded.size - 2 * trim) {
            // STFT of this chunk (centered, reflect padding), first dimF bins.
            val seg = FloatArray(chunk)
            System.arraycopy(padded, i, seg, 0, minOf(chunk, padded.size - i))
            val (re, im) = stft(seg)
            // Both channels are the same mono signal: [L.re, L.im, R.re, R.im].
            for (f in 0 until dimF) for (t in 0 until dimT) {
                val r = re[t][f].toFloat(); val m = im[t][f].toFloat()
                val o = f * dimT + t
                input[o] = r; input[dimF * dimT + o] = m
                input[2 * dimF * dimT + o] = r; input[3 * dimF * dimT + o] = m
            }
            val y = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, dimF.toLong(), dimT.toLong())).use { t ->
                session.run(mapOf(inputName to t)).use { res ->
                    val buf = (res[0] as OnnxTensor).floatBuffer
                    FloatArray(buf.remaining()).also { buf.get(it) }
                }
            }
            val oRe = Array(dimT) { DoubleArray(bins) }
            val oIm = Array(dimT) { DoubleArray(bins) }
            for (f in 0 until dimF) for (t in 0 until dimT) {
                val o = f * dimT + t
                oRe[t][f] = (y[o] + y[2 * dimF * dimT + o]) / 2.0
                oIm[t][f] = (y[dimF * dimT + o] + y[3 * dimF * dimT + o]) / 2.0
            }
            val wave = istft(oRe, oIm, chunk)
            val n = minOf(gen, out.size - i)
            for (k in 0 until n) out[i + k] = wave[trim + k] * compensate
            i += gen
            onProgress(++step / steps.toFloat())
        }

        val music44 = out.copyOf(len)
        val voice44 = FloatArray(len) { x[it] - music44[it] }
        val back = 16000.0 / RATE_HI
        val music = Dsp.resample(music44, back)
        val voice = Dsp.resample(voice44, back)
        val n = minOf(mono16k.size, music.size, voice.size)
        return Stems(music.copyOf(n), voice.copyOf(n))
    }

    private fun stft(x: FloatArray): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val half = nFft / 2
        val frames = 1 + x.size / hop
        val re = Array(frames) { DoubleArray(bins) }
        val im = Array(frames) { DoubleArray(bins) }
        val bre = DoubleArray(nFft)
        val bim = DoubleArray(nFft)
        for (t in 0 until frames) {
            val start = t * hop - half
            for (k in 0 until nFft) {
                var idx = start + k
                // reflect padding
                if (idx < 0) idx = -idx
                if (idx >= x.size) idx = 2 * (x.size - 1) - idx
                bre[k] = x[idx.coerceIn(0, x.size - 1)] * window[k]
                bim[k] = 0.0
            }
            fft.forward(bre, bim)
            System.arraycopy(bre, 0, re[t], 0, bins)
            System.arraycopy(bim, 0, im[t], 0, bins)
        }
        return re to im
    }

    private fun istft(re: Array<DoubleArray>, im: Array<DoubleArray>, length: Int): FloatArray {
        val frames = re.size
        val half = nFft / 2
        val total = nFft + hop * (frames - 1)
        val acc = DoubleArray(total)
        val wsum = DoubleArray(total)
        val bre = DoubleArray(nFft)
        val bim = DoubleArray(nFft)
        for (t in 0 until frames) {
            // Hermitian spectrum → real frame
            for (k in 0 until bins) { bre[k] = re[t][k]; bim[k] = im[t][k] }
            for (k in bins until nFft) { bre[k] = re[t][nFft - k]; bim[k] = -im[t][nFft - k] }
            fft.inverse(bre, bim)
            val off = t * hop
            for (k in 0 until nFft) {
                acc[off + k] += bre[k] * window[k]
                wsum[off + k] += window[k] * window[k]
            }
        }
        return FloatArray(length) { val j = it + half; if (j < total && wsum[j] > 1e-8) (acc[j] / wsum[j]).toFloat() else 0f }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val RATE_HI = 44100
    }
}
