package com.ltots.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Natural voices when shifting pitch" really keeps a voice's timbre: the harmonics move up, but
 * the formant (the peak in the spectrum's envelope that makes a voice sound like itself) stays.
 */
class FormantTest {
    private val sr = Signals.SR

    /** A sung "ah": 180 Hz with harmonics shaped by a formant peak at [formant] Hz. */
    private fun vowel(seconds: Double, formant: Double = 900.0): ShortArray {
        val n = (sr * seconds).toInt()
        val out = ShortArray(n * 2)
        val f0 = 180.0
        val amps = DoubleArray(40) { h -> val f = f0 * (h + 1); exp(-0.5 * (ln(f / formant) / 0.25).pow(2)) }
        val norm = amps.sum()
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var v = 0.0
            for (h in amps.indices) v += amps[h] * sin(2 * PI * f0 * (h + 1) * t)
            val s = (0.5 * v / norm * 32767).toInt().toShort()
            out[2 * i] = s; out[2 * i + 1] = s
        }
        return out
    }

    /** The spectral centroid of x[from, from+n) between 100 Hz and 6 kHz. */
    private fun centroid(x: FloatArray, from: Int, n: Int = 8192): Double {
        var num = 0.0; var den = 0.0
        var f = 100.0
        while (f < 6000.0) {
            var re = 0.0; var im = 0.0
            for (i in 0 until n) {
                val w = 0.5 - 0.5 * kotlin.math.cos(2 * PI * i / (n - 1))
                val a = 2 * PI * f * i / sr
                re += x[from + i] * w * kotlin.math.cos(a); im += x[from + i] * w * sin(a)
            }
            val mag = sqrt(re * re + im * im)
            num += f * mag; den += mag
            f += 15.0
        }
        return num / den
    }

    private fun shifted(semitones: Double, formants: Boolean, efficient: Boolean = false): FloatArray {
        val src = StaticSoundSource(pitch = 2.0.pow(semitones / 12.0), preserveFormants = formants, efficientPitch = efficient)
        val chain = LToTSProcessorChain(EqProcessor(AnalyzerTap(), src), TimePitchProcessor(src), OutputStage())
        val sink = SinkSimulator(chain)
        sink.start(); sink.write(vowel(3.0)); sink.end()
        return sink.left()
    }

    private fun check(efficient: Boolean) {
        val original = run {
            val s = vowel(3.0); FloatArray(s.size / 2) { s[2 * it] / 32768f }
        }
        val c0 = centroid(original, sr)
        val plain = centroid(shifted(7.0, formants = false, efficient), sr)
        val natural = centroid(shifted(7.0, formants = true, efficient), sr)
        println("centroid: original %.0f Hz, +7 st plain %.0f Hz, +7 st natural %.0f Hz (efficient=$efficient)".format(c0, plain, natural))
        // A plain key change moves the whole spectrum up by 1.5×.
        assertTrue("plain shift moves the timbre up", plain > c0 * 1.3)
        // Natural voices keeps it close to where it was.
        assertTrue("natural voices keeps the timbre: $natural vs $c0", natural < c0 * 1.15)
    }

    @Test fun naturalVoicesKeepsTheFormant() = check(efficient = false)

    /** Switched on while a song plays (the usual way: it's a switch in Sound). */
    @Test fun switchingItOnWhilePlayingTakesEffect() {
        for (efficient in listOf(false, true)) {
            val src = StaticSoundSource(pitch = 2.0.pow(7 / 12.0), preserveFormants = false, efficientPitch = efficient)
            val chain = LToTSProcessorChain(EqProcessor(AnalyzerTap(), src), TimePitchProcessor(src), OutputStage())
            val sink = SinkSimulator(chain)
            val pcm = vowel(6.0)
            sink.start(); sink.write(pcm.copyOfRange(0, sr * 2 * 3))
            src.preserveFormants = true; src.bump()
            sink.write(pcm.copyOfRange(sr * 2 * 3, pcm.size)); sink.end()
            val l = sink.left()
            val before = centroid(l, sr); val after = centroid(l, 4 * sr)
            println("live: before %.0f Hz, after switching on %.0f Hz (efficient=$efficient)".format(before, after))
            assertTrue("switching on takes effect (efficient=$efficient): $after vs $before", after < before * 0.8)
        }
    }

    /** With Battery-saving pitch on, natural voices still uses the full engine (R2 only half keeps formants). */
    @Test fun naturalVoicesKeepsTheFormantWithBatterySavingOn() = check(efficient = true)
}
