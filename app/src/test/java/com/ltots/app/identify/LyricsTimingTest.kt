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
}
