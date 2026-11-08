package com.ltots.app.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The simple controls of the EQ tab: Bass, Mids and Treble sliders that work on top of any EQ
 * (10 graphic bands, a precise parametric setup, anything). Each slider moves the bands in its
 * region by the same amount, faded at the region's edges, so the shape you built stays intact.
 */
enum class ToneRegion(val label: String) {
    BASS("Bass"), MIDS("Mids"), TREBLE("Treble");

    /** How strongly a band at [f] Hz belongs to this region (0..1). */
    fun weight(f: Float): Float {
        val x = ln(f.coerceAtLeast(1f))
        fun ramp(f0: Float, f1: Float): Float = ((x - ln(f0)) / (ln(f1) - ln(f0))).coerceIn(0f, 1f)
        return when (this) {
            BASS -> 1f - ramp(150f, 500f)
            MIDS -> minOf(ramp(150f, 500f), 1f - ramp(2000f, 5000f))
            TREBLE -> ramp(2000f, 5000f)
        }
    }

    /** The band added when the EQ has nothing in this region yet. */
    fun newBand(gain: Float): EqBand = when (this) {
        BASS -> EqBand(EqDefaults.newId(), FilterType.LOW_SHELF, 105f, gain, 0.7f)
        MIDS -> EqBand(EqDefaults.newId(), FilterType.PEAK, 1000f, gain, 0.6f)
        TREBLE -> EqBand(EqDefaults.newId(), FilterType.HIGH_SHELF, 8000f, gain, 0.7f)
    }
}

object EqTone {
    const val RANGE = 12f

    private fun movable(b: EqBand) = b.enabled && b.type.usesGain

    /** The region's current level: the weighted average gain of the bands in it. */
    fun level(state: EqState, r: ToneRegion): Float {
        var sw = 0f; var sg = 0f
        for (b in state.bands) if (movable(b)) {
            val w = r.weight(b.freqHz)
            sw += w; sg += w * b.gainDb
        }
        return if (sw <= 1e-4f) 0f else sg / sw
    }

    /**
     * Sets the region to [target] dB. Bands move in proportion to how much they belong to the
     * region, scaled so the region's level lands exactly on [target] (unless a band hits ±24 dB).
     */
    fun set(state: EqState, r: ToneRegion, target: Float): EqState {
        val t = target.coerceIn(-RANGE, RANGE)
        var sw = 0f; var sw2 = 0f
        for (b in state.bands) if (movable(b)) { val w = r.weight(b.freqHz); sw += w; sw2 += w * w }
        if (sw <= 1e-4f) {
            if (abs(t) < 0.05f) return state
            return state.copy(bands = state.bands + r.newBand(round1(t)))
        }
        val delta = t - level(state, r)
        val k = sw / sw2
        return state.copy(bands = state.bands.map { b ->
            if (!movable(b)) return@map b
            val w = r.weight(b.freqHz)
            if (w <= 0f) b else b.copy(gainDb = round1((b.gainDb + delta * k * w).coerceIn(EqLimits.MIN_GAIN, EqLimits.MAX_GAIN)))
        })
    }

    /** Every gain back to 0 dB and no preamp — band layout, types and widths stay. */
    fun flatten(state: EqState): EqState =
        state.copy(preampDb = 0f, bands = state.bands.map { if (it.type.usesGain) it.copy(gainDb = 0f) else it })

    /** True when [a] and [b] sound the same (ignoring band ids and order). */
    fun sameShape(a: EqState, b: EqState): Boolean {
        if (EqMath.isNeutral(a) && EqMath.isNeutral(b)) return true
        if (a.preampDb != b.preampDb) return false
        fun key(s: EqState) = s.bands.filter { it.enabled }.map { Triple(it.type, (it.freqHz * 10).roundToInt(), (it.gainDb * 10).roundToInt() * 1000 + (it.q * 100).roundToInt()) }
            .sortedWith(compareBy({ it.second }, { it.first.ordinal }, { it.third }))
        return key(a) == key(b)
    }

    private fun round1(v: Float) = (v * 10f).roundToInt() / 10f
}
