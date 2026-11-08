package com.ltots.app.ytm

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Talks to the real YouTube Music. Only runs with -Dltots.live=true (not in CI). */
class LiveYtmTest {
    @Test fun endToEndLookups() = runBlocking {
        assumeTrue(System.getProperty("ltots.live") == "true")
        val r = InnerTube.search("daft punk one more time")
        val songs = r.sections.flatMap { it.items }.filterIsInstance<YtSong>()
        println("LIVE search: ${r.sections.size} sections, ${songs.size} songs; chips=${r.chips.map { it.text }}")
        songs.take(5).forEach { println("LIVE   ${it.videoId} ${it.title} — ${it.artistText} video=${it.isVideo} album=${it.album?.name}") }
        val first = songs.first()
        val s = InnerTube.song(first.videoId)
        println("LIVE song: ${s?.title} / ${s?.artistText} / album=${s?.album?.name} ${s?.album?.id} year=${s?.year} thumb=${s?.thumbUrl}")
        val video = songs.firstOrNull { it.isVideo }
        if (video != null) println("LIVE officialAudio(${video.title}) = ${InnerTube.officialAudio(video)?.let { it.videoId + " " + it.title }}")
        s?.album?.id?.let { id -> val a = InnerTube.album(id); println("LIVE album: ${a.title} by ${a.artist} ${a.year} tracks=${a.tracks.size} thumb=${a.thumbUrl}") }
        val pl = InnerTube.search("daft punk discovery playlist").sections.flatMap { it.items }.filterIsInstance<YtPlaylist>().firstOrNull()
        if (pl != null) { val c = InnerTube.playlist(pl.playlistId); println("LIVE playlist ${c.title}: ${c.tracks.size} tracks") }
    }
}
