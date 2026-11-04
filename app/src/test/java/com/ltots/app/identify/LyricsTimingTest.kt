package com.ltots.app.identify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTimingTest {
    @Test fun wholeSongUsesTheLyricsOwnTimeline() {
        // Shazam's copy starts 0.9 s later than the lyrics' record: the old mapping ran 0.9 s late.
        val t = LyricsMatch.Timing(rate = 1.0005, intercept = 0.9)
        val fixed = LyricsMatch.wholeSong(t, recordSec = 241.4, fileSec = 241.4)
        assertEquals(0.0, fixed.intercept, 1e-9)
        assertEquals(1.0, fixed.rate, 1e-9)
    }

    @Test fun cutEditsKeepShazamsTiming() {
        val t = LyricsMatch.Timing(rate = 1.26, intercept = 35.0) // a sped-up edit that starts at the chorus
        assertEquals(t, LyricsMatch.wholeSong(t, recordSec = 200.0, fileSec = 70.0))
    }

    @Test fun shiftFromHeardLines() {
        val placed = listOf(10_000L, 14_000L, 18_000L, 22_000L, 26_000L, 30_000L, 34_000L)
        val heard = placed.map { it - 800 }
        assertEquals(-800L, LyricsMatch.shiftFrom(placed, heard, placed.indices.toList()))
        // Too few heard lines, or they disagree: no change.
        assertNull(LyricsMatch.shiftFrom(placed, heard, listOf(0, 1, 2)))
        val messy = placed.mapIndexed { i, p -> p + (if (i % 2 == 0) 3_000 else -3_000) }
        assertNull(LyricsMatch.shiftFrom(placed, messy, placed.indices.toList()))
    }

    @Test fun normalSpeedNeverStretchesWhenLengthsDiffer() {
        // The file has a 6 s longer outro: the old code stretched the lyrics by 2.5 % (≈5 s late
        // by the end). At normal speed the rate must stay exactly 1.
        val t = LyricsMatch.Timing(rate = 1.004, intercept = 0.4)
        val fixed = LyricsMatch.wholeSong(t, recordSec = 236.0, fileSec = 242.0)
        assertEquals(1.0, fixed.rate, 1e-9)
        assertEquals(0.4, fixed.intercept, 1e-9) // not the same length: keep where Shazam put it
    }

    @Test fun driftIsStraightenedOut() {
        // Lyrics placed at 1.02× the song's pace: 0 s off at the start, ~4 s by the end.
        val placed = (0 until 40).map { 10_000L + it * 5_000L }
        val heard = placed.map { (it / 1.02).toLong() }
        val (slope, offset) = LyricsMatch.driftFrom(placed, heard, placed.indices.toList())!!
        assertEquals(1 / 1.02, slope, 0.002)
        assertEquals(heard.last().toDouble(), placed.last() * slope + offset, 300.0)
        // Plain offset, no drift: not a drift (the shift handles it).
        assertNull(LyricsMatch.driftFrom(placed, placed.map { it - 800 }, placed.indices.toList()))
        // A few misheard lines don't bend it.
        val noisy = heard.mapIndexed { i, h -> if (i % 9 == 4) h + 6_000 else h }
        val (s2, _) = LyricsMatch.driftFrom(placed, noisy, placed.indices.toList())!!
        assertEquals(1 / 1.02, s2, 0.004)
    }
}
