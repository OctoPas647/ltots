package com.ltots.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    // ---- LRC ------------------------------------------------------------------------------

    @Test fun plainLyricsGetTimesSpreadOverTheSong() {
        val plain = "So the last time that you guys saw\nAn AK-50 video was about\n\nBig russian gun\nPart of my personality\n"
        val l = Lrc.estimate(plain, 130_000)!!
        assertEquals(listOf("So the last time that you guys saw", "An AK-50 video was about", "Big russian gun", "Part of my personality"), l.map { it.text })
        // After a short intro, in order, and done before the end.
        assertEquals(7_800L, l.first().timeMs)
        assertTrue(l.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
        assertTrue(l.last().timeMs < 130_000 - 7_800)
        // The verse break adds a pause: the gap before "Big russian gun" is the biggest.
        val gaps = l.zipWithNext { a, b -> b.timeMs - a.timeMs }
        assertEquals(1, gaps.indexOf(gaps.max()))
        assertEquals(2, Lrc.lineAt(l, l[2].timeMs + 1))
        assertNull(Lrc.estimate("  \n ", 130_000))
        assertNull(Lrc.estimate(plain, 0))
    }

    @Test fun parsesTimesFractionsAndRepeats() {
        val l = Lrc.parse(
            """
            [ar:Someone]
            [00:30.64] Like the legend
            [00:35.1]Our ends
            [01:02.345][02:10.00] Chorus
            [03:00]
            plain line without time
            """.trimIndent()
        )
        assertEquals(listOf(30_640L, 35_100L, 62_345L, 130_000L, 180_000L), l.map { it.timeMs })
        assertEquals("Like the legend", l[0].text)
        assertEquals("Chorus", l[2].text)
        assertEquals("Chorus", l[3].text)
        assertEquals("", l[4].text)
    }

    @Test fun offsetTagShiftsLines() {
        val l = Lrc.parse("[offset:+500]\n[00:10.00] a")
        assertEquals(9_500L, l[0].timeMs)
    }

    @Test fun formatRoundTrips() {
        val l = Lrc.parse("[00:30.64] a\n[01:02.34] b")
        assertEquals(l, Lrc.parse(Lrc.format(l)))
    }

    @Test fun findsTheLineBeingSung() {
        val d = LyricsDoc(Lrc.parse("[00:10.00] a\n[00:20.00] b\n[00:30.00] c"), null)
        assertEquals(-1, d.lineAt(5_000))
        assertEquals(0, d.lineAt(10_000))
        assertEquals(1, d.lineAt(29_999))
        assertEquals(2, d.lineAt(99_000))
    }

    @Test fun docSurvivesSaving() {
        val d = LyricsDoc(Lrc.parse("[00:10.00] a"), "a", false, "LRCLIB", "X — Y")
        assertEquals(d, LyricsDoc.fromJson(d.toJson()))
    }

    // ---- matching ------------------------------------------------------------------------

    @Test fun cleansTitles() {
        assertEquals("Get Lucky", SongMatch.cleanTitle("Get Lucky (Official Video) [HD]"))
        assertEquals("Get Lucky", SongMatch.cleanTitle("Get Lucky (feat. Pharrell Williams)"))
        assertEquals("Get Lucky", SongMatch.cleanTitle("Get Lucky ft. Pharrell Williams.mp3"))
        assertEquals("Song", SongMatch.cleanTitle("Song (Remastered 2011)"))
        assertEquals("Linkin Park - Numb", SongMatch.cleanTitle("Linkin Park - Numb (Official Music Video) [4K UPGRADE]"))
        assertEquals("Song (Acoustic)", SongMatch.cleanTitle("Song (Acoustic) (Lyric Video)"))
        assertEquals("Daft Punk", SongMatch.mainArtist("Daft Punk feat. Pharrell Williams"))
        assertEquals("Daft Punk", SongMatch.mainArtist("Daft Punk - Topic"))
        assertEquals("Beyonce", SongMatch.mainArtist("Beyonce, JAY-Z"))
    }

    @Test fun readsArtistFromTheFileNameWhenUntagged() {
        val q = SongQuery("k", "Daft Punk - Get Lucky (Official Audio)", null, null, 248_000)
        val tries = SongMatch.interpretations(q)
        assertEquals("Daft Punk" to "Get Lucky", tries.first())
        assertTrue(tries.any { it.first == null })
    }

    private fun cand(title: String, artist: String, dur: Double, synced: Boolean = true) =
        Candidate(title, artist, "", dur, if (synced) "[00:01.00] x" else null, "x", false)

    @Test fun rejectsWrongLengthOrWrongSong() {
        val q = SongQuery("k", "Get Lucky", "Daft Punk", null, 248_000)
        assertEquals(0.0, SongMatch.score(q, "Daft Punk", "Get Lucky", cand("Get Lucky (Daft Punk remix)", "Daft Punk", 630.0)), 0.0)
        assertEquals(0.0, SongMatch.score(q, "Daft Punk", "Get Lucky", cand("One More Time", "Daft Punk", 248.0)), 0.0)
        assertEquals(0.0, SongMatch.score(q, "Daft Punk", "Get Lucky", cand("Get Lucky", "Someone Else", 248.0)), 0.0)
        assertTrue(SongMatch.score(q, "Daft Punk", "Get Lucky", cand("Get Lucky", "Daft Punk", 249.5)) > 0.8)
    }

    @Test fun prefersSyncedAndCloserLength() {
        val q = SongQuery("k", "Get Lucky", "Daft Punk", null, 248_000)
        val a = cand("Get Lucky", "Daft Punk", 248.0, synced = false)
        val b = cand("Get Lucky", "Daft Punk", 249.0, synced = true)
        val c = cand("Get Lucky (Radio Edit)", "Daft Punk", 248.0 + 120, synced = true)
        assertEquals(b, SongMatch.best(q, "Daft Punk", "Get Lucky", listOf(a, b, c)))
        assertNull(SongMatch.best(q, "Daft Punk", "Get Lucky", listOf(c)))
    }

    /** Real lookup against LRCLIB (opt-in: -Dltots.live=true). */
    @Test fun liveLookup() {
        if (System.getProperty("ltots.live") != "true") return
        val api = LrcLib()
        val hit = api.find(SongQuery("k", "Get Lucky (Official Audio)", "Daft Punk feat. Pharrell Williams & Nile Rodgers", null, 248_000))
        assertNotNull(hit)
        assertTrue(hit!!.first.synced!!.contains("phoenix"))
        val untagged = api.find(SongQuery("k2", "Daft Punk - Get Lucky", "<unknown>", null, 248_000))
        assertNotNull(untagged)
        // A made-up song must not match anything.
        assertNull(api.find(SongQuery("k3", "Zzqx Nonexistent Track 9921", "Nobody Qqv", null, 201_000)))
        println("LIVE OK: ${hit.second} / ${untagged!!.second}")
    }
}
