package com.ltots.app.moods

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
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CLAP (LAION's audio–text model, HTSAT-unfused): turns 10 s of music into a 512-number
 * "meaning" that sits close to the meaning of words that describe it ("sad slow song",
 * "party dance music"). The words' side is computed once, off the phone, and shipped with the
 * app (assets/clap_text.json), so only the small audio half runs here.
 *
 * Input: 48 kHz mono, exactly as the model was trained — 10 s, 64 log-mel bands (Slaney,
 * 50 Hz–14 kHz), n_fft 1024, hop 480.
 */
class Clap(model: File, threads: Int = 2) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        model.absolutePath,
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
        },
    )

    /** The unit-length embedding of [audio48k] (10 s; shorter is repeated, longer cut). */
    fun embed(audio48k: FloatArray): FloatArray {
        val mel = features(audio48k)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, 1, FRAMES.toLong(), MELS.toLong())).use { t ->
            session.run(mapOf("input_features" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val out = (r[0].value as Array<FloatArray>)[0]
                return normalize(out)
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val RATE = 48_000
        const val SAMPLES = 480_000
        const val N_FFT = 1024
        const val HOP = 480
        const val MELS = 64
        const val BINS = N_FFT / 2 + 1
        const val FRAMES = SAMPLES / HOP + 1 // 1001

        fun normalize(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x * x
            val n = sqrt(s).toFloat().takeIf { it > 0f } ?: return v
            return FloatArray(v.size) { v[it] / n }
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        // ---- log-mel features, as transformers' ClapFeatureExtractor (rand_trunc/repeatpad) ----

        /** Slaney mel scale (librosa's default). */
        private fun hzToMel(f: Double): Double {
            val logStep = 27.0 / ln(6.4)
            return if (f >= 1000.0) 15.0 + ln(f / 1000.0) * logStep else 3.0 * f / 200.0
        }

        private fun melToHz(m: Double): Double {
            val logStep = ln(6.4) / 27.0
            return if (m >= 15.0) 1000.0 * exp(logStep * (m - 15.0)) else 200.0 * m / 3.0
        }

        /** [BINS] × [MELS] triangular filters, Slaney-normalised. */
        internal val FILTERS: Array<DoubleArray> by lazy {
            val melMin = hzToMel(50.0); val melMax = hzToMel(14_000.0)
            val f = DoubleArray(MELS + 2) { melToHz(melMin + (melMax - melMin) * it / (MELS + 1)) }
            val fft = DoubleArray(BINS) { (RATE / 2).toDouble() * it / (BINS - 1) }
            Array(BINS) { b ->
                DoubleArray(MELS) { m ->
                    val down = (fft[b] - f[m]) / (f[m + 1] - f[m])
                    val up = (f[m + 2] - fft[b]) / (f[m + 2] - f[m + 1])
                    max(0.0, min(down, up)) * (2.0 / (f[m + 2] - f[m]))
                }
            }
        }

        private val WINDOW by lazy { DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) } }

        /** Log-mel spectrogram, frames × mels, row-major (1001 × 64). */
        fun features(audio: FloatArray): FloatArray {
            // repeatpad: repeat a short clip as many whole times as fit, then zeros; cut a long one.
            val x = FloatArray(SAMPLES)
            if (audio.isNotEmpty()) {
                if (audio.size >= SAMPLES) System.arraycopy(audio, 0, x, 0, SAMPLES)
                else {
                    val reps = SAMPLES / audio.size
                    for (r in 0 until reps) System.arraycopy(audio, 0, x, r * audio.size, audio.size)
                }
            }
            // centre = true: reflect-pad n_fft/2 on both sides.
            val pad = N_FFT / 2
            val padded = DoubleArray(SAMPLES + 2 * pad)
            for (i in padded.indices) {
                var j = i - pad
                if (j < 0) j = -j
                if (j >= SAMPLES) j = 2 * (SAMPLES - 1) - j
                padded[i] = x[j].toDouble()
            }
            val fft = MixedFft(N_FFT)
            val re = DoubleArray(N_FFT); val im = DoubleArray(N_FFT)
            val power = DoubleArray(BINS)
            val out = FloatArray(FRAMES * MELS)
            val filters = FILTERS
            for (t in 0 until FRAMES) {
                val s = t * HOP
                for (i in 0 until N_FFT) { re[i] = padded[s + i] * WINDOW[i]; im[i] = 0.0 }
                fft.forward(re, im)
                for (b in 0 until BINS) power[b] = re[b] * re[b] + im[b] * im[b]
                for (m in 0 until MELS) {
                    var e = 0.0
                    for (b in 0 until BINS) { val w = filters[b][m]; if (w != 0.0) e += w * power[b] }
                    out[t * MELS + m] = (10.0 * log10(max(1e-10, e))).toFloat()
                }
            }
            return out
        }
    }
}
