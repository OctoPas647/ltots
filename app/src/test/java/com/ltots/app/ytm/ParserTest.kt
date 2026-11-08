package com.ltots.app.ytm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser checks against real YouTube Music responses captured on 2026-10-15. */
class ParserTest {
    private fun fixture(name: String) =
        JSONObject(javaClass.classLoader!!.getResource("ytm/$name")!!.readText())

    @Test fun topSearchHasCardChipsAndArtists() {
        val r = Parser.parseSearch(fixture("search_top.json"))
        assertTrue(r.chips.any { it.text == "Videos" && it.params != null })
        val all = r.sections.flatMap { it.items }
        val card = r.sections.first().items.first() as YtSong
        assertEquals("CCHdMIEGaaM", card.videoId)
        assertTrue(card.isVideo)
        val daft = all.filterIsInstance<YtArtist>().first()
        assertEquals("Daft Punk", daft.title)
        assertEquals("UCRr1xG_2WIDs18a6cIiCxeA", daft.browseId)
    }

    @Test fun videoSearchParsesArtistAndDuration() {
        val r = Parser.parseSearch(fixture("search_videos.json"))
        val songs = r.sections.flatMap { it.items }.filterIsInstance<YtSong>()
        assertEquals(20, songs.size)
        val voyager = songs.first { it.title == "Voyager" }
        assertEquals("INbgG9M0WYE", voyager.videoId)
        assertEquals("Daft Punk", voyager.artistText)
        assertEquals(3 * 60 + 48, voyager.durationSec)
        assertNotNull(r.continuation)
    }

    @Test fun searchContinuationGivesMoreItems() {
        val r = Parser.parseSearch(fixture("search_videos_continuation.json"))
        assertEquals(20, r.sections.flatMap { it.items }.size)
    }

    @Test fun playlistSearchSkipsPodcasts() {
        val r = Parser.parseSearch(fixture("search_playlists.json"))
        val items = r.sections.flatMap { it.items }
        assertTrue(items.all { it is YtPlaylist })
        val p = items.first() as YtPlaylist
        assertTrue(p.browseId.startsWith("VL"))
        assertEquals(p.browseId.removePrefix("VL"), p.playlistId)
    }

    @Test fun playlistPageHasAllTracks() {
        val c = Parser.parseCollection("VLPLSdoVPM5WnndSQEXRz704yQkKwx76GvPV", fixture("playlist_small.json"))
        assertEquals("Daft Punk - Discovery (Full Album)", c.title)
        assertFalse(c.isAlbum)
        assertEquals(14, c.tracks.size)
        val first = c.tracks.first()
        assertEquals("A2VpR8HahKc", first.videoId)
        assertEquals("One More Time", first.title)
        assertEquals("Daft Punk", first.artistText)
        assertEquals(5 * 60 + 21, first.durationSec)
        assertTrue(c.thumbUrl!!.contains("googleusercontent"))
    }

    @Test fun bigPlaylistPagesThrough() {
        val c = Parser.parseCollection("VLPLQCXKl33aBijHlP4kjmQNP1TTeBlnKPoc", fixture("playlist_big.json"))
        assertEquals(100, c.tracks.size)
        assertNotNull(c.continuation)
        val (more, _) = Parser.parseContinuationTracks(fixture("playlist_big_continuation.json"))
        assertEquals(100, more.size)
        assertTrue(more.none { m -> c.tracks.any { it.videoId == m.videoId } })
    }

    @Test fun albumPageGivesTrackNumbersAndAlbumArtist() {
        val c = Parser.parseCollection("MPREb_test", fixture("album.json"))
        assertTrue(c.isAlbum)
        assertEquals("Random Access Memories", c.title)
        assertEquals("Daft Punk", c.artist)
        assertEquals("2013", c.year)
        assertEquals(3, c.tracks.size)
        val t1 = c.tracks[0]
        assertEquals(1, t1.trackNumber)
        assertEquals("Daft Punk", t1.artistText) // falls back to the album artist
        assertEquals("Random Access Memories", t1.album?.name)
        assertFalse(t1.isVideo)
        val lucky = c.tracks[2]
        assertEquals(8, lucky.trackNumber)
        assertEquals("Daft Punk, Pharrell Williams, Nile Rodgers", lucky.artistText)
        assertEquals(6 * 60 + 9, lucky.durationSec)
    }

    @Test fun artistPageHasShelves() {
        val a = Parser.parseArtist("UCRr1xG_2WIDs18a6cIiCxeA", fixture("artist.json"))
        assertEquals("Daft Punk", a.name)
        assertTrue(a.sections.any { s -> s.items.any { it is YtSong } })
        assertTrue(a.sections.any { s -> s.items.any { it is YtPlaylist } })
        val top = a.sections.first { it.title == "Top songs" }
        assertTrue(top.items.last() is YtPlaylist) // "All songs" link
    }

    @Test fun nextGivesAlbumYearAndSquareCover() {
        val s = Parser.parseNext("utmHrr31N3g", fixture("next_song.json"))!!
        assertEquals("Solar Sailer", s.title)
        assertEquals("Daft Punk", s.artistText)
        assertEquals("TRON: Legacy", s.album?.name)
        assertEquals("MPREb_juoC2uOEXE1", s.album?.id)
        assertEquals("2010", s.year)
        assertEquals(163, s.durationSec)
        assertFalse(s.isVideo)
    }

    @Test fun links() {
        assertEquals(LinkTarget.Song("lYBUbBu4W08"), Links.parse("https://music.youtube.com/watch?v=lYBUbBu4W08&si=abc"))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ"), Links.parse("Check this out https://youtu.be/dQw4w9WgXcQ?si=x"))
        assertEquals(LinkTarget.Playlist("OLAK5uy_abc"), Links.parse("https://music.youtube.com/playlist?list=OLAK5uy_abc"))
        assertEquals(LinkTarget.Album("MPREb_juoC2uOEXE1"), Links.parse("https://music.youtube.com/browse/MPREb_juoC2uOEXE1"))
        assertEquals(LinkTarget.Artist("UCRr1xG_2WIDs18a6cIiCxeA"), Links.parse("https://music.youtube.com/channel/UCRr1xG_2WIDs18a6cIiCxeA"))
        assertEquals(LinkTarget.Song("dQw4w9WgXcQ"), Links.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLx"))
        assertEquals(LinkTarget.Playlist("PLx"), Links.parse("https://m.youtube.com/watch?list=PLx"))
        assertNull(Links.parse("daft punk get lucky"))
        assertNull(Links.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun covers() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w2400-h2400-l100-rj",
            Covers.highRes("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"),
        )
        assertEquals("https://i.ytimg.com/vi/A2VpR8HahKc/maxresdefault.jpg",
            Covers.highRes("https://i.ytimg.com/vi/A2VpR8HahKc/hqdefault.jpg?sqp=xyz"))
        assertTrue(Covers.needsSquareCrop("https://i.ytimg.com/vi/x/maxresdefault.jpg"))
        assertFalse(Covers.needsSquareCrop("https://lh3.googleusercontent.com/abc=w2400"))
    }

    @Test fun titleNormalisationMatchesVideoAndSong() {
        assertEquals("get lucky", Parser.normalizeTitle("Daft Punk - Get Lucky (Official Video) feat. Pharrell Williams and Nile Rodgers", "Daft Punk"))
        assertEquals("get lucky", Parser.normalizeTitle("Get Lucky", "Daft Punk"))
        assertEquals("never gonna give you up", Parser.normalizeTitle("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)", "Rick Astley"))
        assertEquals("one more time", Parser.normalizeTitle("One More Time [Official Music Video]"))
    }

    @Test fun musicVideoWithoutTwinHasNoCounterpart() {
        assertNull(Parser.parseCounterpart("INbgG9M0WYE", fixture("next_video.json")))
        val v = Parser.parseNext("INbgG9M0WYE", fixture("next_video.json"))!!
        assertTrue(v.isVideo)
        assertEquals("Daft Punk", v.artistText)
    }
}
