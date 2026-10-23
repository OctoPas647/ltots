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
class MixesTest {
    private var next = 1L
    private fun track(title: String, album: String = "Album", genre: String? = null, year: Int? = null, trackNo: Int? = null) =
        (next++).let { id ->
            Track(id, Uri.parse("content://media/external/audio/media/$id"), title, "Artist", album, 1L, 200_000L, null, null, null,
                genre, year, trackNo)
        }

    @Test fun songsFromOneGameBecomeOnePlaylistAcrossAlbumsAndSingles() {
        val songs = listOf(
            track("Finish the Fight", "Halo 3 (Original Soundtrack) Vol. 1", trackNo = 2),
            track("Never Forget", "Halo 3 (Original Soundtrack) Vol. 1", trackNo = 1),
            track("Released", "Halo 3 OST, Disc 2"),
            track("Halo Theme (From \"Halo 3\")", "Gaming Covers"),
            track("Sweden", "Volume Alpha", genre = "Soundtrack"),
            track("Wet Hands", "Volume Alpha", genre = "Soundtrack"),
            track("Some Pop Song", "Halo 3"), // same name, but nothing says it's a soundtrack
        )
        val mixes = Mixes.build(songs).filter { it.kind == Mix.Kind.SOUNDTRACK }
        assertEquals(listOf("Halo 3"), mixes.map { it.title }) // Volume Alpha has only 2 songs
        assertEquals(listOf("Halo Theme (From \"Halo 3\")", "Never Forget", "Finish the Fight", "Released"), mixes[0].tracks.map { it.title })
    }

    @Test fun soundtrackNamesLoseTheirOstWords() {
        fun name(album: String) = Mixes.soundtrackName(track("x", album))
        assertEquals("The Legend of Zelda: Breath of the Wild", name("The Legend of Zelda: Breath of the Wild Original Soundtrack"))
        assertEquals("UNDERTALE", name("UNDERTALE Soundtrack"))
        assertEquals("Minecraft", name("Minecraft - Volume Beta"))
        assertEquals("Persona 5", name("Persona 5 OST"))
        assertEquals("Metal Gear Solid 3", Mixes.soundtrackName(track("Snake Eater (From \"Metal Gear Solid 3\")", "Covers")))
        assertTrue(Mixes.isSoundtrack(track("Gerudo Valley", "Ocarina of Time", genre = "Video Game")))
        assertFalse(Mixes.isSoundtrack(track("Yellow", "Parachutes", genre = "Rock")))
    }

    @Test fun decadesFromReleaseYears() {
        val songs = listOf(1985, 1989, 1981, 1994, 1999, 2003, 2004, 2005, 2007, 2021, 0).mapIndexed { i, y -> track("S$i", year = y.takeIf { it > 0 }) }
        val mixes = Mixes.build(songs).filter { it.kind == Mix.Kind.DECADE }
        assertEquals(listOf("The 80s", "The 2000s"), mixes.map { it.title })
        assertEquals(listOf(1981, 1985, 1989), mixes[0].tracks.map { it.year })
        assertEquals("The 2010s", Mixes.decadeName(2016))
        assertEquals(null, Mixes.decadeName(0))
    }

    @Test fun genrePlaylistsGatherAFamily() {
        val songs = listOf(track("a", genre = "Rock"), track("b", genre = "Hard Rock"), track("c", genre = "Alternative"),
            track("d", genre = "Pop"), track("e", genre = "Vaporwave"), track("f", genre = "Vaporwave"), track("g", genre = "Vaporwave"))
        val mixes = Mixes.build(songs).filter { it.kind == Mix.Kind.GENRE }
        assertEquals(listOf("Rock", "Vaporwave"), mixes.map { it.title })
        assertEquals(3, mixes[0].tracks.size)
    }
}
