package com.ltots.app.audio

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import org.junit.Test
import kotlin.math.pow

/**
 * Before/after comparison for the bug "changing pitch breaks the song": a finger dragging the
 * pitch slider sends a new value every ~16 ms. Prints measurements for both engines.
 */
class PitchReproTest {
    /** The old path: pitch sent through PlaybackParameters to Media3's Sonic. */
    private class OldChain : AudioProcessorChain {
        val sonic = SonicAudioProcessor()
        override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(sonic)
        override fun applyPlaybackParameters(p: PlaybackParameters): PlaybackParameters { sonic.setSpeed(p.speed); sonic.setPitch(p.pitch); return p }
        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean) = false
        override fun getMediaDuration(playoutDuration: Long) = if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration
        override fun getSkippedOutputFrameCount() = 0L
    }

    private fun drag(cb: (Int, Double) -> Unit) {
        // 6 s of dragging between -5 and +5 semitones, a new value every 768 frames (16 ms).
        val steps = (6.0 * 48000 / 768).toInt()
        for (i in 0 until steps) {
            val semis = 5.0 * kotlin.math.sin(i / 25.0)
            cb(i, 2.0.pow(semis / 12.0))
        }
    }

    @Test fun compareDragging() {
        val music = Signals.music(7.0)

        // Old engine.
        val oldChain = OldChain()
        val old = SinkSimulator(oldChain)
        old.start()
        old.write(music.copyOfRange(0, 48000), 768)
        drag { i, scale ->
            old.setPlaybackParameters(PlaybackParameters(1f, scale.toFloat()))
            val o = 48000 * 2 + i * 768 * 2
            old.write(music.copyOfRange(o, minOf(o + 768 * 2, music.size)), 768)
        }
        old.end()
        val ol = old.left()

        // New engine.
        val src = StaticSoundSource()
        val chain = LToTSProcessorChain(EqProcessor(AnalyzerTap(), src), TimePitchProcessor(src), OutputStage())
        val new = SinkSimulator(chain)
        new.start()
        new.write(music.copyOfRange(0, 48000), 768)
        drag { i, scale ->
            src.pitch = scale; src.bump()
            val o = 48000 * 2 + i * 768 * 2
            new.write(music.copyOfRange(o, minOf(o + 768 * 2, music.size)), 768)
        }
        new.end()
        val nl = new.left()

        val inputFrames = 48000 + (6.0 * 48000 / 768).toInt() * 768
        println("OLD: output ${ol.size} of $inputFrames frames, restarts=${old.flushPoints.size}, " +
            "longest dropout=${Signals.longestDropoutMs(ol, 48000, ol.size - 4800)} ms, worst jump=${"%.2f".format(Signals.worstJump(ol, 48000, ol.size - 4800))}")
        println("NEW: output ${nl.size} of $inputFrames frames, restarts=${new.flushPoints.size}, " +
            "longest dropout=${Signals.longestDropoutMs(nl, 48000, nl.size - 4800)} ms, worst jump=${"%.2f".format(Signals.worstJump(nl, 48000, nl.size - 4800))}")
    }

    /** Share of energy that lands on the expected (shifted) notes — higher = cleaner. */
    private fun purity(x: FloatArray, from: Int, freqs: DoubleArray, n: Int = 32768): Double {
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until n) re[i] = x[from + i] * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (n - 1)))
        // naive DFT only at needed bins would miss noise; use full FFT via Signals helper
        val mags = spectrum(re, im)
        var total = 0.0; var hit = 0.0
        for (k in 1 until n / 2) {
            val e = mags[k] * mags[k]
            total += e
            val f = k * 48000.0 / n
            if (freqs.any { kotlin.math.abs(it - f) < 6.0 }) hit += e
        }
        return hit / total
    }

    private fun spectrum(re: DoubleArray, im: DoubleArray): DoubleArray {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = kotlin.math.cos(ang * k); val wi = kotlin.math.sin(ang * k)
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k + len / 2] = re[i + k] - vr; im[i + k + len / 2] = im[i + k] - vi
                re[i + k] += vr; im[i + k] += vi
            }
            len = len shl 1
        }
        return DoubleArray(n / 2) { kotlin.math.sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    @Test fun compareQualityAtFixedPitch() {
        // A chord (A3, C#4, E4, A4) shifted up 3 semitones.
        val notes = doubleArrayOf(220.0, 277.18, 329.63, 440.0)
        val n = 48000 * 4
        val pcm = ShortArray(n * 2) { i ->
            val t = (i / 2).toDouble() / 48000
            (notes.sumOf { 0.18 * kotlin.math.sin(2 * Math.PI * it * t) } * 32767).toInt().toShort()
        }
        val scale = 2.0.pow(3 / 12.0)
        val target = notes.map { it * scale }.toDoubleArray()

        val oldChain = OldChain()
        val old = SinkSimulator(oldChain)
        old.setPlaybackParameters(PlaybackParameters(1f, scale.toFloat()))
        old.start(); old.write(pcm); old.end()

        val src = StaticSoundSource(pitch = scale)
        val new = SinkSimulator(LToTSProcessorChain(EqProcessor(AnalyzerTap(), src), TimePitchProcessor(src), OutputStage()))
        new.start(); new.write(pcm); new.end()

        val po = purity(old.left(), 48000, target)
        val pn = purity(new.left(), 48000, target)
        println("QUALITY +3 st: old engine ${"%.1f".format(po * 100)}% of energy on the right notes, new engine ${"%.1f".format(pn * 100)}%")
        println("QUALITY noise: old ${"%.1f".format(10 * kotlin.math.log10(po / (1 - po)))} dB, new ${"%.1f".format(10 * kotlin.math.log10(pn / (1 - pn)))} dB (notes vs everything else)")
    }
}
