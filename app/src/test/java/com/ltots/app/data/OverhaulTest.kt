package com.ltots.app.data

import android.net.Uri
import com.ltots.app.ui.ScreenRest
import com.ltots.app.ui.Springs
import com.ltots.app.ui.components.visualizerFps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Duplicates, rolls, the Wrapped stat screens and the screen-on battery rules. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OverhaulTest {
    private fun track(
        id: Long, title: String, artist: String = "Artist", durationMs: Long = 200_000L, genre: String? = null,
        mime: String? = "audio/mpeg", size: Long = 5_000_000L, year: Int? = null,
    ) = Track(id, Uri.parse("content://media/external/audio/media/$id"), title, artist, "Album", 1L, durationMs, 44_100, 16, mime, genre, year, sizeBytes = size)

    // ---- duplicates ------------------------------------------------------------------------

    @Test fun sameRecordingTwiceIsOneGroupBestCopyFirst() {
        val mp3 = track(1, "Blinding Lights", "The Weeknd", 200_000, mime = "audio/mpeg")
        val flac = track(2, "Blinding Lights (Official Audio)", "The Weeknd", 201_500, mime = "audio/flac", size = 30_000_000)
        val other = track(3, "Save Your Tears", "The Weeknd")
        val groups = Duplicates.groups(listOf(mp3, flac, other))
        assertEquals(1, groups.size)
        assertEquals(listOf(2L, 1L), groups[0].map { it.id }) // lossless is the one to keep
    }

    @Test fun otherVersionsAreNotDuplicates() {
        val base = track(1, "Blinding Lights", "The Weeknd")
        val versions = listOf(
            "Blinding Lights (Remix)", "Blinding Lights - Live at the Super Bowl", "Blinding Lights (sped up)",
            "Blinding Lights (Slowed + Reverb)", "Blinding Lights (Acoustic)", "Blinding Lights (Instrumental)",
            "Blinding Lights [Chromatics Remix]", "Blinding Lights (Remastered 2022)",
        ).mapIndexed { i, t -> track(10L + i, t, "The Weeknd") }
        assertTrue(Duplicates.groups(listOf(base) + versions).isEmpty())
        // A version named without brackets is still a version (and the same one as with them).
        assertTrue(Duplicates.groups(listOf(base, track(30, "Blinding Lights sped up", "The Weeknd"))).isEmpty())
        assertEquals(1, Duplicates.groups(listOf(track(30, "Blinding Lights sped up", "The Weeknd"), track(31, "Blinding Lights (Sped Up)", "The Weeknd"))).size)
    }

    @Test fun differentLengthOrArtistIsNotADuplicate() {
        val a = track(1, "Intro", "Artist A", 60_000)
        assertTrue(Duplicates.groups(listOf(a, track(2, "Intro", "Artist A", 95_000))).isEmpty())
        assertTrue(Duplicates.groups(listOf(a, track(3, "Intro", "Artist B", 60_000))).isEmpty())
        // Featured artists and decoration in brackets don't make it a different recording.
        assertEquals(1, Duplicates.groups(listOf(a, track(4, "Intro (feat. Someone) [Lyrics]", "Artist A feat. Someone", 61_000))).size)
    }

    @Test fun twoCopiesOfARemixAreDuplicatesOfEachOther() {
        val g = Duplicates.groups(listOf(track(1, "Song (Remix)"), track(2, "Song [Remix]"), track(3, "Song")))
        assertEquals(1, g.size)
        assertEquals(setOf(1L, 2L), g[0].map { it.id }.toSet())
    }

    @Test fun findSpotsASongAlreadyInTheLibrary() {
        val lib = listOf(track(1, "Starboy", "The Weeknd, Daft Punk", 230_000))
        assertNotNull(Duplicates.find(lib, "Starboy (Official Video)", "The Weeknd", 231_000))
        assertNull(Duplicates.find(lib, "Starboy (Kygo Remix)", "The Weeknd", 231_000))
        assertNull(Duplicates.find(lib, "Starboy", "The Weeknd", 400_000))
        assertNotNull(Duplicates.find(lib, "Starboy", "The Weeknd", 0)) // length unknown: name decides
    }

    // ---- rolls -----------------------------------------------------------------------------

    private fun library(): List<Track> {
        val genres = listOf("Rock", "Hip-Hop", "Jazz", "Electronic", "Classical")
        return (0 until 100).map { i -> track(i.toLong(), "Song $i", "Artist ${i % 30}", genre = genres[i % genres.size], year = 1980 + i % 40) }
    }

    @Test fun rollsStayTheSameUntilRerolled() {
        val lib = library()
        val a = Rolls.deal(lib, 7L).map { r -> r.tracks.map { it.id } }
        assertEquals(a, Rolls.deal(lib, 7L).map { r -> r.tracks.map { it.id } })
        assertNotEquals(a, Rolls.deal(lib, 8L).map { r -> r.tracks.map { it.id } })
    }

    @Test fun rollsSpreadOverGenresAndArtists() {
        val rolls = Rolls.deal(library(), 3L)
        assertTrue(rolls.size >= 4)
        val everything = rolls.first { it.id.startsWith("all") }
        assertEquals(5, Rolls.genreCount(everything.tracks))
        // Dealt one genre at a time: never the same genre twice running.
        everything.tracks.zipWithNext().forEach { (x, y) -> assertNotEquals(x.genre, y.genre) }
        rolls.forEach { r ->
            assertTrue(r.tracks.size >= 8)
            assertEquals(r.tracks.size, r.tracks.map { it.id }.toSet().size)
            if (!r.id.startsWith("remix")) assertTrue(r.tracks.groupingBy { it.artist }.eachCount().values.max() <= 2)
        }
        // The two clash rolls use four different genres between them.
        val clashes = rolls.filter { it.id.startsWith("clash") }
        assertEquals(2, clashes.size)
        assertEquals(4, clashes.flatMap { it.title.split(" × ") }.toSet().size)
    }

    @Test fun aTinyLibraryGetsNoRolls() {
        assertTrue(Rolls.deal(library().take(5), 1L).isEmpty())
    }

    // ---- wrapped ---------------------------------------------------------------------------

    private fun summary(songs: List<SongStat>, streak: Streak? = null) =
        StatsSummary(2026, songs.sumOf { it.ms }, songs.sumOf { it.plays }, songs, emptyList(), LongArray(12), LongArray(24), 10, null, 0.0, streak)

    @Test fun wrappedLinesSayWhatTheNumbersSay() {
        val top = SongStat("a", "Top Song", "A", "", 300, 45 * 3_600_000L, skips = 1, night = 6)
        val second = SongStat("b", "Second Song", "B", "", 200, 45 * 3_600_000L - 9 * 60_000L, skips = 12)
        val s = summary(listOf(top, second), Streak("Top Song", "A", 14, 2, 9))
        val lines = WrappedStats.lines(s, WrappedTone.SNARKY, SleepRule.NIGHT, neverPlayed = 87, librarySize = 400).associateBy { it.id }
        assertEquals(setOf("cost", "sleep", "second", "skip", "grave", "streak"), lines.keys)
        // 45 hours: 6% of Spanish (750 h), 5% of German (900 h), 4.1% of Russian (1100 h).
        assertTrue(lines.getValue("cost").sub, lines.getValue("cost").sub.contains("6.0% of Spanish, 5.0% of German or 4.1% of Russian"))
        assertEquals("Top Song", lines.getValue("sleep").big)
        // 9 minutes behind at about 13.4 minutes a play: one more play.
        assertTrue(lines.getValue("second").sub, lines.getValue("second").sub.contains("just 9 minutes") && lines.getValue("second").sub.contains("1 more time "))
        assertEquals("Second Song", lines.getValue("skip").big)
        assertEquals("87 songs", lines.getValue("grave").big)
        assertEquals("14× in a row", lines.getValue("streak").big)
        assertTrue(lines.getValue("streak").sub.contains("March 9"))
    }

    @Test fun wrappedStaysQuietWithoutEnoughToSay() {
        val s = summary(listOf(SongStat("a", "Only Song", "A", "", 2, 5 * 60_000L)))
        assertTrue(WrappedStats.lines(s, WrappedTone.BRUTAL, SleepRule.EITHER).isEmpty())
    }

    @Test fun sleepRuleAndToneChangeTheLine() {
        val songs = listOf(SongStat("a", "Night Song", "A", "", 50, 3_600_000L, night = 5), SongStat("b", "Timer Song", "B", "", 40, 3_000_000L, timer = 9))
        val s = summary(songs)
        fun sleep(rule: SleepRule, tone: WrappedTone = WrappedTone.MILD) = WrappedStats.lines(s, tone, rule).first { it.id == "sleep" }
        assertEquals("Night Song", sleep(SleepRule.NIGHT).big)
        assertEquals("Timer Song", sleep(SleepRule.TIMER).big)
        assertEquals("Timer Song", sleep(SleepRule.EITHER).big)
        assertNotEquals(sleep(SleepRule.NIGHT, WrappedTone.MILD).sub, sleep(SleepRule.NIGHT, WrappedTone.BRUTAL).sub)
    }

    // ---- screen on -------------------------------------------------------------------------

    @Test fun restingCapsTheVisualizerAndTheBackground() {
        assertEquals(120, visualizerFps(120, false, Effects.FULL))
        assertEquals(60, visualizerFps(120, false, Effects.FULL, restCap = 60))
        assertEquals(30, visualizerFps(120, false, Effects.LIGHT, restCap = 60))
        assertEquals(15, visualizerFps(30, false, Effects.FULL, restCap = 15))
        assertEquals(0L, ScreenRest.minFrameNs(0L, 0))
        assertEquals(33_333_333L, ScreenRest.minFrameNs(0L, 30))
        assertEquals(33_000_000L.coerceAtLeast(16_666_666L), ScreenRest.minFrameNs(33_000_000L, 60))
    }

    @Test fun aFlickIsJudgedByWhereItIsGoing() {
        // A slow drag stays where it is; a quick flick from the same place carries well past half-way.
        assertEquals(100f, Springs.project(100f, 0f), 0.01f)
        assertTrue(Springs.project(100f, 2_000f) > 540f)
        assertTrue(Springs.project(400f, -2_000f) < 0f)
        assertFalse(Springs.project(100f, 200f) > 540f)
    }

    @Test fun newSettingsSurviveSaving() {
        val s = AppSettings(restAfterSeconds = 30, restBackground = false, restVisualizerFps = 15, backgroundFps = 60,
            motion = MotionFeel.SNAPPY, rollSeed = 42L, wrappedTone = WrappedTone.BRUTAL, sleepRule = SleepRule.TIMER, wrappedSlides = WrappedSlides.STATS)
        assertEquals(s, Json.settings(Json.settings(s)))
    }
}
