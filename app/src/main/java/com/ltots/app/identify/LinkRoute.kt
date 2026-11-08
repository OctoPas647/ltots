package com.ltots.app.identify

import com.ltots.app.ytm.LinkTarget
import com.ltots.app.ytm.Links

/** What the Get tab's search/paste box does with what you typed or pasted. */
sealed interface LinkRoute {
    /** A YouTube Music song, album, playlist or artist: open or download it as before. */
    data class Music(val target: LinkTarget) : LinkRoute
    /** A video on any platform (TikTok, Shorts, YouTube, Instagram…): find the song in it. */
    data class FindSong(val url: String) : LinkRoute
    /** Plain words: search YouTube Music. */
    data class Search(val text: String) : LinkRoute

    companion object {
        fun of(text: String): LinkRoute {
            val t = text.trim()
            val url = SongFinder.linkIn(t)
                ?: t.takeIf { it.startsWith("music.youtube.com") || it.startsWith("youtu") || it.startsWith("www.youtube") || it.startsWith("m.youtube") }?.let { "https://$it" }
                ?: return Search(t)
            val host = url.substringAfter("://").substringBefore('/').lowercase().removePrefix("www.").removePrefix("m.")
            val target = Links.parse(url)
            return when {
                // YouTube Music links keep working exactly as before.
                host == "music.youtube.com" && target != null -> Music(target)
                // On YouTube itself, a single video goes to the song finder; playlists, albums
                // and channels still open as music pages.
                target is LinkTarget.Song -> FindSong(url)
                target != null -> Music(target)
                else -> FindSong(url)
            }
        }
    }
}
