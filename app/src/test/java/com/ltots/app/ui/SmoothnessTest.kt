package com.ltots.app.ui

import com.ltots.app.data.Effects
import com.ltots.app.data.EffectsMode
import com.ltots.app.data.ThemeConfig
import com.ltots.app.ui.components.visualizerFps
import com.ltots.app.ui.glass.backgroundMoves
import com.ltots.app.ui.glass.motionPeriodNs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smoothness: when Automatic steps down, and what each level turns down. */
class SmoothnessTest {
    private val frame = 8_333_333L // 120 Hz

    /** Feeds [n] frames from [start], [lateEvery]-th of them late (0 = none); returns the time after. */
    private fun JankMeter.feed(start: Long, n: Int, lateEvery: Int, trips: MutableList<Long>): Long {
        var t = start
        repeat(n) { i ->
            val late = lateEvery > 0 && i % lateEvery == 0
            if (add(t, if (late) frame * 2 else frame / 2, frame)) trips += t
            t += frame
        }
        return t
    }

    @Test fun smoothFramesNeverTurnEffectsDown() {
        val m = JankMeter()
        val trips = mutableListOf<Long>()
        m.feed(0L, 120 * 60, lateEvery = 10, trips) // a minute at 120 fps, 10% of frames late
        assertTrue(trips.isEmpty())
    }

    @Test fun sustainedStutterTurnsEffectsDownOnceThenWaits() {
        val m = JankMeter()
        val trips = mutableListOf<Long>()
        var t = m.feed(0L, 400, lateEvery = 0, trips) // the start (ignored) and some smooth frames
        t = m.feed(t, 240, lateEvery = 2, trips) // half the frames late, two windows
        assertEquals(1, trips.size)
        // Right after stepping down it waits for the lighter level to take effect.
        m.feed(t, 240, lateEvery = 2, trips)
        assertEquals(1, trips.size)
    }

    @Test fun oneSlowMomentDoesNotCount() {
        val m = JankMeter()
        val trips = mutableListOf<Long>()
        var t = m.feed(0L, 480, lateEvery = 0, trips) // the start (ignored), then one smooth window
        t = m.feed(t, 120, lateEvery = 1, trips) // one bad window (a heavy tab switch)
        m.feed(t, 120 * 10, lateEvery = 0, trips)
        assertTrue(trips.isEmpty())
    }

    @Test fun theFirstSecondsAreIgnored() {
        val m = JankMeter()
        val trips = mutableListOf<Long>()
        m.feed(0L, 300, lateEvery = 1, trips) // everything late, but within the first 3 seconds
        assertTrue(trips.isEmpty())
    }

    @Test fun pickedLevelsWinOverAutomatic() {
        assertEquals(Effects.BALANCED, Smoothness.effective(EffectsMode.AUTO, Effects.BALANCED))
        assertEquals(Effects.FULL, Smoothness.effective(EffectsMode.FULL, Effects.LIGHT))
        assertEquals(Effects.LIGHT, Smoothness.effective(EffectsMode.LIGHT, Effects.FULL))
    }

    @Test fun visualizerRateFollowsSmoothnessAndBatterySaver() {
        assertEquals(120, visualizerFps(120, false, Effects.FULL))
        assertEquals(60, visualizerFps(120, false, Effects.BALANCED))
        assertEquals(30, visualizerFps(120, false, Effects.LIGHT))
        assertEquals(30, visualizerFps(120, true, Effects.FULL))
        assertEquals(30, visualizerFps(30, false, Effects.BALANCED))
        assertEquals(0, visualizerFps(0, false, Effects.FULL))
    }

    @Test fun movingColoursOnlyWhereTheyCanMove() {
        val colourful = ThemeConfig(animateBackground = true, pureBlack = false)
        assertTrue(backgroundMoves(colourful, false, Effects.FULL))
        assertTrue(backgroundMoves(colourful, false, Effects.BALANCED))
        assertFalse(backgroundMoves(colourful, false, Effects.LIGHT))
        assertFalse(backgroundMoves(colourful, true, Effects.FULL))
        assertFalse(backgroundMoves(colourful.copy(pureBlack = true), false, Effects.FULL))
        assertFalse(backgroundMoves(colourful.copy(animateBackground = false), false, Effects.FULL))
    }

    @Test fun movingColoursAreVisiblyMovingAtTheDefaultSpeed() {
        // One loop every 40 s at the default 0.5× (it was 2 minutes, which looked still).
        assertEquals(40e9f, motionPeriodNs(ThemeConfig().animationSpeed), 1e6f)
        assertTrue(motionPeriodNs(3f) < 8e9f)
    }
}
