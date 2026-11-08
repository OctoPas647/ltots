package com.ltots.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lyrics are only accepted when they really are this song. */
class StrictMatchTest {
    private val goodLrc = (0..40).joinToString("\n") { "[%02d:%02d.00] line number %d of this song".format(it * 5 / 60, it * 5 % 60, it) }
    private fun c(title: String, artist: String, dur: Double = 240.0, lrc: String? = goodLrc) = Candidate(title, artist, "", dur, lrc, null, false)
    private val q = SongQuery("k", "Love", "Some Band", null, 240_000)

    @Test fun aLongerTitleIsAnotherSong() {
        assertEquals(0.0, SongMatch.score(q, "Some Band", "Love", c("Love Story", "Some Band")), 0.0)
    }

    @Test fun aSearchByTitleStillChecksTheArtist() {
        assertEquals(0.0, SongMatch.score(q, null, "Love", c("Love", "Another Band")), 0.0)
        assertTrue(SongMatch.score(q, null, "Love", c("Love", "Some Band")) > 0)
    }

    @Test fun remixesAndLiveVersionsAreDifferentRecordings() {
        assertEquals(0.0, SongMatch.titleMatch("Numb", "Numb (Live)"), 0.0)
        assertEquals(0.0, SongMatch.titleMatch("Numb", "Numb - Remix"), 0.0)
        assertEquals(1.0, SongMatch.titleMatch("Numb", "Numb - 2011 Remaster"), 0.0)
        assertEquals(1.0, SongMatch.titleMatch("Numb", "Numb (Album Version)"), 0.0)
    }

    @Test fun anyCreditedArtistCounts() {
        assertEquals(1.0, SongMatch.artistMatch("Jess Glynne", "Clean Bandit feat. Jess Glynne"), 0.0)
        assertEquals(1.0, SongMatch.artistMatch("The Weeknd", "Weeknd"), 0.0)
        assertTrue(SongMatch.artistMatch("Kid", "Kid Cudi") < 0.8)
    }

    @Test fun lengthMustAgree() {
        assertEquals(0.0, SongMatch.score(q, "Some Band", "Love", c("Love", "Some Band", dur = 244.0)), 0.0)
    }

    @Test fun garbledLyricsAreRefused() {
        val garbled = (0..40).joinToString("\n") { "[00:%02d.00] ÐŸÑ€Ð¸Ð²ÐµÑ‚ Ð¼Ð¸Ñ€".format(it) }
        assertFalse(LyricsQuality.ok(q, c("Love", "Some Band", lrc = garbled)))
        assertFalse(LyricsQuality.ok(q, c("Love", "Some Band", lrc = "[00:01.00] broken � text\n[03:50.00] a\n[03:55.00] b")))
        assertTrue(LyricsQuality.ok(q.copy(durationMs = 210_000), c("Love", "Some Band")))
    }

    @Test fun lyricsThatOutlastTheSongAreRefused() {
        val long = (0..40).joinToString("\n") { "[%02d:%02d.00] words of the song %d".format(it * 10 / 60, it * 10 % 60, it) }
        assertFalse(LyricsQuality.ok(q.copy(durationMs = 200_000), c("Love", "Some Band", lrc = long)))
    }

    @Test fun neteaseCreditsAreRemovedAndChineseWordsForAWesternSongRefused() {
        val lines = LyricsQuality.withoutCredits(listOf(LyricLine(0, "作词 : Someone"), LyricLine(1000, "Hello")))
        assertEquals(listOf("Hello"), lines.map { it.text })
        val han = (0..40).joinToString("\n") { "[%02d:%02d.00] 这是一首完全不同的歌".format(it * 5 / 60, it * 5 % 60) }
        assertFalse(LyricsQuality.ok(q, c("Love", "Some Band", lrc = han), fromNetEase = true))
    }
}
