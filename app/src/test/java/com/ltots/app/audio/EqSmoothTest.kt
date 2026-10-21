package com.ltots.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The smooth EQ curve: neighbouring knobs blend into one line that goes through every knob, with
 * no dip between two raised knobs and no peak above them, and the filters really play that line.
 */
class EqSmoothTest {
    private val fs = 48000.0

    private fun tenBand(vararg gains: Float) = EqState(bands = EqDefaults.tenBand().mapIndexed { i, b -> b.copy(gainDb = gains[i]) })

    /** What you hear, in dB at [f] Hz. */
    private fun heard(s: EqState, f: Double): Double =
        EqMath.responseDb(EqSmooth.filters(s).map { BiquadCoefs.design(it, fs) }, f, fs)

    /** What the old maths (every band's bell simply added up) gave. */
    private fun summed(s: EqState, f: Double): Double =
        EqMath.responseDb(s.bands.map { BiquadCoefs.design(it, fs) }, f, fs)

    private fun logRange(a: Double, b: Double, n: Int = 40) = (0..n).map { a * (b / a).pow(it / n.toDouble()) }

    @Test fun twoRaisedNeighboursMakeAFlatTopNoDipNoPeak() {
        val s = tenBand(0f, 0f, 0f, 6f, 6f, 0f, 0f, 0f, 0f, 0f)
        val k3 = s.bands[3].freqHz.toDouble(); val k4 = s.bands[4].freqHz.toDouble()
        // The old way peaked above both knobs and dipped between them.
        assertTrue("old curve overshot the knob", summed(s, k3) > 6.8)
        for (f in logRange(k3, k4)) {
            assertEquals("flat between the two knobs at $f Hz", 6.0, heard(s, f), 0.3)
        }
        assertEquals(6.0, heard(s, k3), 0.2)
        assertEquals(6.0, heard(s, k4), 0.2)
    }

    @Test fun curveGoesThroughEveryKnobOfTheGraphicBands() {
        val shapes = listOf(
            floatArrayOf(6f, 5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f), // Bass boost
            floatArrayOf(5f, 4f, 2f, 0f, -2f, -2f, 0f, 2f, 4f, 5f),   // V-shape
            floatArrayOf(6f, 6f, 6f, 6f, 6f, 6f, 6f, 6f, 6f, 6f),
            floatArrayOf(0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f, 0f),
        )
        for (g in shapes) {
            val s = tenBand(*g)
            for (b in s.bands) assertEquals("knob ${b.freqHz} Hz of ${g.toList()}", b.gainDb.toDouble(), heard(s, b.freqHz.toDouble()), 0.3)
        }
    }

    @Test fun noOvershootBetweenRisingKnobs() {
        val s = tenBand(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        for (i in 0 until 9) {
            val a = s.bands[i]; val b = s.bands[i + 1]
            for (f in logRange(a.freqHz.toDouble(), b.freqHz.toDouble(), 12)) {
                val v = heard(s, f)
                assertTrue("between ${a.gainDb} and ${b.gainDb} dB at $f Hz: $v", v > a.gainDb - 0.35 && v < b.gainDb + 0.35)
            }
        }
    }

    @Test fun aKnobOnItsOwnKeepsItsOwnBell() {
        // De-ess: one narrow cut. Clarity: two bells far apart. Nothing to blend: exactly as before.
        val deEss = EqState(bands = listOf(EqBand(1, FilterType.PEAK, 7000f, -4f, 4f)))
        assertEquals(deEss.bands, EqSmooth.filters(deEss))
        val clarity = EqState(bands = listOf(
            EqBand(2, FilterType.PEAK, 250f, -2.5f, 1f), EqBand(3, FilterType.PEAK, 3000f, 2f, 1f),
            EqBand(4, FilterType.HIGH_SHELF, 10000f, 1.5f, 0.7f),
        ))
        assertEquals(clarity.bands.toSet(), EqSmooth.filters(clarity).toSet())
        for (f in logRange(20.0, 20000.0)) assertEquals(summed(clarity, f), heard(clarity, f), 1e-9)
    }

    @Test fun aNarrowKnobStandsApartFromItsNeighbours() {
        val s = tenBand(0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f, 0f)
        val narrow = s.copy(bands = s.bands.mapIndexed { i, b -> if (i == 4) b.copy(q = 5f) else b })
        assertTrue(EqSmooth.isBlended(s, s.bands[4].id))
        assertTrue(!EqSmooth.isBlended(narrow, narrow.bands[4].id))
    }

    @Test fun flatStaysNeutral() {
        val s = tenBand(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertTrue(EqMath.isNeutral(s))
        for (f in logRange(20.0, 20000.0)) assertEquals(0.0, heard(s, f), 1e-9)
    }

    @Test fun headroomFollowsWhatIsHeard() {
        val s = tenBand(0f, 0f, 0f, 6f, 6f, 0f, 0f, 0f, 0f, 0f)
        // The old sum peaked at ~7 dB; the smooth line tops out at the knobs' 6 dB.
        assertEquals(6.0, EqMath.peakBoostDb(s), 0.3)
    }

    @Test fun fitIsQuick() {
        val s = tenBand(5f, -3f, 2f, 6f, -6f, 1f, 4f, -2f, 3f, 0f)
        // Warm up (the JIT), then time fresh fits (every one a new EQ, so nothing comes from the cache).
        repeat(200) { i -> EqSmooth.filters(s.copy(bands = s.bands.map { it.copy(gainDb = it.gainDb - 5f + i * 0.01f) })) }
        val t0 = System.nanoTime()
        repeat(100) { i -> EqSmooth.filters(s.copy(bands = s.bands.map { it.copy(gainDb = it.gainDb + i * 0.01f) })) }
        val ms = (System.nanoTime() - t0) / 1e6 / 100
        println("EQ smooth fit: %.2f ms each".format(ms))
        assertTrue("fit took $ms ms", ms < 25)
        assertTrue(abs(heard(s, 1000.0)) < 30)
    }
}
