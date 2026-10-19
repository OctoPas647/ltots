package com.ltots.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filter shapes available for each EQ band. */
enum class FilterType(val label: String, val usesGain: Boolean) {
    PEAK("Peak", true),
    LOW_SHELF("Low shelf", true),
    HIGH_SHELF("High shelf", true),
    HIGH_PASS("Low cut", false),
    LOW_PASS("High cut", false),
}

/** One fully parametric band: any frequency, gain and width (Q). */
data class EqBand(
    val id: Long,
    val type: FilterType = FilterType.PEAK,
    val freqHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
)

/** Complete EQ setting — what gets saved per song, per preset, or as the default. */
data class EqState(
    val enabled: Boolean = true,
    val bands: List<EqBand> = EqDefaults.tenBand(),
    val preampDb: Float = 0f,
    /** Automatically lowers the level by the curve's biggest boost so nothing clips. */
    val autoHeadroom: Boolean = true,
)

object EqLimits {
    const val MIN_FREQ = 16f
    const val MAX_FREQ = 22000f
    const val MIN_GAIN = -24f
    const val MAX_GAIN = 24f
    const val MIN_Q = 0.1f
    const val MAX_Q = 18f
    const val MAX_BANDS = 64
}

object EqDefaults {
    private var nextId = System.nanoTime()
    fun newId(): Long = ++nextId

    /** Log-spaced bands between 31 Hz and 16 kHz, like a classic graphic EQ. */
    fun spaced(count: Int, lo: Float = 31f, hi: Float = 16000f): List<EqBand> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(EqBand(newId(), freqHz = 1000f))
        val ratio = (hi / lo).toDouble()
        val q = bandwidthQ(count, ratio)
        return List(count) { i ->
            val f = lo * ratio.pow(i.toDouble() / (count - 1)).toFloat()
            EqBand(newId(), freqHz = roundFreq(f), q = q)
        }
    }

    /** The standard layout: 10 bands from 31 Hz to 16 kHz. */
    const val DEFAULT_BANDS = 10

    fun tenBand(): List<EqBand> = spaced(DEFAULT_BANDS)

    /** A Q that makes neighbouring bands overlap smoothly for [count] log-spaced bands. */
    private fun bandwidthQ(count: Int, ratio: Double): Float {
        val octaves = log2(ratio) / (count - 1)
        val n = 2.0.pow(octaves)
        return (sqrt(n) / (n - 1)).toFloat().coerceIn(0.3f, 8f)
    }

    private fun log2(x: Double) = kotlin.math.ln(x) / kotlin.math.ln(2.0)

    fun roundFreq(f: Float): Float = when {
        f < 100f -> (f * 10f).toInt() / 10f
        f < 1000f -> f.toInt().toFloat()
        else -> (f / 10f).toInt() * 10f
    }
}

/** Normalised biquad coefficients (a0 = 1). */
class BiquadCoefs(
    @JvmField val b0: Double, @JvmField val b1: Double, @JvmField val b2: Double,
    @JvmField val a1: Double, @JvmField val a2: Double,
) {
    /** Magnitude response in dB at [freqHz] for sample rate [fs]. */
    fun magnitudeDb(freqHz: Double, fs: Double): Double {
        val w = 2.0 * PI * freqHz / fs
        val cw = cos(w); val sw = sin(w)
        val c2w = cos(2 * w); val s2w = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2w
        val ni = -(b1 * sw + b2 * s2w)
        val dr = 1.0 + a1 * cw + a2 * c2w
        val di = -(a1 * sw + a2 * s2w)
        val num = nr * nr + ni * ni
        val den = dr * dr + di * di
        return 10.0 * log10(max(num / den, 1e-20))
    }

    companion object {
        val IDENTITY = BiquadCoefs(1.0, 0.0, 0.0, 0.0, 0.0)

        /** RBJ "Audio EQ Cookbook" designs. */
        fun design(band: EqBand, fs: Double): BiquadCoefs {
            if (!band.enabled) return IDENTITY
            val f = min(band.freqHz.toDouble(), fs * 0.49)
            val q = band.q.toDouble().coerceIn(EqLimits.MIN_Q.toDouble(), EqLimits.MAX_Q.toDouble())
            val w0 = 2.0 * PI * f / fs
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a = 10.0.pow(band.gainDb / 40.0)
            val b0: Double; val b1: Double; val b2: Double
            val a0: Double; val a1: Double; val a2: Double
            when (band.type) {
                FilterType.PEAK -> {
                    if (band.gainDb == 0f) return IDENTITY
                    b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
                }
                FilterType.LOW_SHELF -> {
                    if (band.gainDb == 0f) return IDENTITY
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cw + s)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - s)
                    a0 = (a + 1) + (a - 1) * cw + s
                    a1 = -2 * ((a - 1) + (a + 1) * cw)
                    a2 = (a + 1) + (a - 1) * cw - s
                }
                FilterType.HIGH_SHELF -> {
                    if (band.gainDb == 0f) return IDENTITY
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cw + s)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - s)
                    a0 = (a + 1) - (a - 1) * cw + s
                    a1 = 2 * ((a - 1) - (a + 1) * cw)
                    a2 = (a + 1) - (a - 1) * cw - s
                }
                FilterType.LOW_PASS -> {
                    b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                FilterType.HIGH_PASS -> {
                    b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
            }
            return BiquadCoefs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

object EqMath {
    /** Total response of the whole EQ (bands only, no preamp) in dB at [freqHz]. */
    fun responseDb(bands: List<BiquadCoefs>, freqHz: Double, fs: Double): Double {
        var sum = 0.0
        for (c in bands) if (c !== BiquadCoefs.IDENTITY) sum += c.magnitudeDb(freqHz, fs)
        return sum
    }

    /** The largest boost anywhere in the audible range — used for automatic headroom. */
    fun peakBoostDb(state: EqState, fs: Double = 48000.0): Double {
        if (!state.enabled) return 0.0
        val coefs = state.bands.map { BiquadCoefs.design(it, fs) }
        var peak = 0.0
        val points = 256
        for (i in 0 until points) {
            val f = 20.0 * 1000.0.pow(i / (points - 1.0))
            peak = max(peak, responseDb(coefs, f, fs))
        }
        return peak
    }

    /**
     * True when this EQ leaves the sound exactly as it is (switched off, or every band flat
     * and no preamp) — then music can skip the processor entirely.
     */
    fun isNeutral(state: EqState): Boolean {
        if (!state.enabled) return true
        if (state.preampDb != 0f) return false
        return state.bands.all { BiquadCoefs.design(it, 48000.0) === BiquadCoefs.IDENTITY }
    }

    /** Overall gain in dB applied before the filters (user preamp + auto headroom). */
    fun inputGainDb(state: EqState, fs: Double = 48000.0): Double {
        if (!state.enabled) return 0.0
        val headroom = if (state.autoHeadroom) -peakBoostDb(state, fs) else 0.0
        return state.preampDb + headroom
    }
}
