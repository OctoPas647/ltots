package com.ltots.app.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SongSortTest {
    private fun track(id: Long, title: String, genre: String? = null, artist: String = "Artist") =
        Track(id, Uri.parse("content://media/external/audio/media/$id"), title, artist, "Album", 1L, 200_000L, null, null, null, genre)

    @Test fun sameGenrePlaysTogetherAndRelatedGenresSitSideBySide() {
        val songs = listOf(
            track(1, "A", "Pop"), track(2, "B", "Metal"), track(3, "C", null), track(4, "D", "Rock"),
            track(5, "E", "Pop"), track(6, "F", "Hard Rock"), track(7, "G", "Soul"), track(8, "H", "R&B"),
        )
        val genres = SongSorter.sort(songs, SongSort.GENRE).map { SongSorter.genreName(it.genre) }
        assertEquals(listOf("Metal", "Hard Rock", "Rock", "Pop", "Pop", "R&B", "Soul", null), genres)
    }

    @Test fun oldNumberedId3GenresAreNamed() {
        assertEquals("Rock", SongSorter.genreName("(17)"))
        assertEquals("Rock", SongSorter.genreName("17"))
        assertEquals("Rock", SongSorter.genreName("(17)Rock"))
        assertEquals(null, SongSorter.genreName("  "))
    }

    @Test fun coversGoRoundTheRainbowThenGreysThenNone() {
        val red = 0xFFE01010.toInt(); val pinkRed = 0xFFE0103A.toInt(); val yellow = 0xFFE0D010.toInt()
        val blue = 0xFF1030E0.toInt(); val black = 0xFF050505.toInt(); val white = 0xFFF5F5F5.toInt()
        val songs = listOf(track(1, "white"), track(2, "blue"), track(3, "none"), track(4, "black"), track(5, "yellow"), track(6, "red"), track(7, "pinkRed"))
        val colors = mapOf(1L to white, 2L to blue, 3L to SongSorter.NO_COVER, 4L to black, 5L to yellow, 6L to red, 7L to pinkRed)
        val order = SongSorter.sort(songs, SongSort.COLOR, colors).map { it.title }
        assertEquals(listOf("pinkRed", "red", "yellow", "blue", "black", "white", "none"), order)
    }

    @Test fun mainColourIsTheHueMostOfTheCoverHasNotAMuddyAverage() {
        // 60% blue, 40% red: blue, not purple.
        val px = IntArray(100) { if (it < 60) 0xFF2040E0.toInt() else 0xFFE02020.toInt() }
        val c = SongSorter.mainColor(px)
        assertTrue((c and 0xFF) > 0xC0 && ((c shr 16) and 0xFF) < 0x40)
        // Hardly any colour: a grey of the cover's brightness.
        val grey = SongSorter.mainColor(IntArray(100) { 0xFF808080.toInt() })
        assertEquals((grey shr 16) and 0xFF, grey and 0xFF)
        assertNotEquals(SongSorter.NO_COVER, SongSorter.mainColor(IntArray(100) { 0xFF000000.toInt() }))
    }

    @Test fun randomIsAPermutationThatStaysPutForTheSameSeed() {
        val songs = (1L..50L).map { track(it, "Song $it") }
        val a = SongSorter.sort(songs, SongSort.RANDOM, seed = 42)
        assertEquals(songs.toSet(), a.toSet())
        assertNotEquals(songs, a)
        assertEquals(a, SongSorter.sort(songs, SongSort.RANDOM, seed = 42))
        // Searching keeps the remaining songs in the same order.
        assertEquals(a.filter { it.id % 2 == 0L }, SongSorter.sort(songs.filter { it.id % 2 == 0L }, SongSort.RANDOM, seed = 42))
    }

    @Test fun pickingAButtonDoesNotChangeTheSetting() {
        val settings = AppSettings(songSort = SongSort.GENRE)
        SongSorter.setQuick(SongSort.COLOR)
        assertEquals(SongSort.COLOR, SongSorter.quick.value ?: settings.songSort)
        assertEquals(SongSort.GENRE, settings.songSort)
        assertEquals(SongSort.GENRE, Json.settings(Json.settings(settings)).songSort)
        SongSorter.setQuick(null)
        assertEquals(SongSort.GENRE, SongSorter.quick.value ?: settings.songSort)
    }
}
