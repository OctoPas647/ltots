package com.ltots.app.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GenresAndFiltersTest {
    private fun track(id: Long, title: String, artist: String, genre: String? = null) =
        Track(id, Uri.parse("content://media/external/audio/media/$id"), title, artist, "Album", 1L, 200_000L, null, null, null, genre)

    @Test fun theLastGenreWordDecidesTheFamily() {
        fun fam(g: String) = SongSorter.familyName(g)
        assertEquals("Rock", fam("Pop Rock"))
        assertEquals("Pop", fam("Indie Pop"))
        assertEquals("Metal", fam("Folk Metal"))
        assertEquals("R&B & soul", fam("Alternative R&B"))
        assertEquals("Hip-hop", fam("Hip-Hop/Rap"))
        assertEquals("Hip-hop", fam("Rap/Hip Hop"))
        assertEquals("R&B & soul", fam("R&B/Soul"))
        assertEquals("Pop", fam("Electropop"))
        assertEquals("Dance & electronic", fam("Drum and Bass"))
        assertEquals("Dance & electronic", fam("Electronic"))
        assertEquals("Chill", fam("Trip Hop"))
        assertEquals("Latin, reggae & world", fam("Pop-Folk"))
        assertEquals("Classical & soundtracks", fam("Films/Games"))
        assertEquals("Country & folk", fam("Singer/Songwriter"))
        // "rap" inside "trap" or "therapy" is not rap; "emo" inside "demo" is not emo.
        assertEquals("Hip-hop", fam("Trap"))
        assertEquals(null, fam("Demo Therapy"))
    }

    @Test fun databaseGenreWinsThenTheArtistsUsualGenreThenTheFileTag() {
        val a1 = track(1, "Song One", "Artist A", genre = "Other")
        val a2 = track(2, "Song Two (Official Video)", "Artist A feat. B")
        val a3 = track(3, "Unknown Track", "Artist A", genre = "Classical") // databases don't know it
        val b1 = track(4, "Lone", "Artist B", genre = "Jazz")
        val known = mapOf(
            GenreFinder.key(a1)!! to "Hip-Hop/Rap",
            GenreFinder.key(a2)!! to "Rap/Hip Hop",
            GenreFinder.key(a3)!! to "",
        )
        val picked = GenreFinder.pick(listOf(a1, a2, a3, b1), known)
        assertEquals("Hip-Hop/Rap", picked[1L])
        assertEquals("Rap/Hip Hop", picked[2L])
        assertEquals("Hip-hop", SongSorter.familyName(picked[3L]))
        assertEquals("Jazz", picked[4L])
    }

    @Test fun hiddenArtistsAndPopularSongsAreFilteredOut() {
        val songs = (1L..8L).map { track(it, "S$it", if (it <= 4) "Big Star" else "Small Act") }
        val pop = mapOf(1L to 900_000, 2L to 300_000, 3L to 100_000, 4L to 50_000, 5L to 200_000, 6L to 20_000, 7L to 10_000, 8L to 5_000)
        val hits = LibraryFilter.hits(songs, pop, stillLooking = false)
        // Big Star's huge hit, plus each artist's top quarter.
        assertEquals(setOf(1L, 5L), hits)

        val s = AppSettings(hiddenArtists = listOf("small act"))
        assertEquals((1L..4L).toList(), LibraryFilter.apply(songs, s, pop, emptyMap(), false).map { it.id })

        val plays = mapOf(songs[2].profileKey to 9, songs[3].profileKey to 2, songs[6].profileKey to 5, songs[7].profileKey to 1)
        val most = PlayStats.mostPlayed(songs, plays)
        assertTrue(3L in most); assertFalse(8L in most)

        val both = LibraryFilter.apply(songs, AppSettings(onlyHits = true, onlyMostPlayed = true), pop, plays, false).map { it.id }.toSet()
        assertTrue(1L in both && 3L in both && 5L in both)
    }
}
