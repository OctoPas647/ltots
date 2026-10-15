package com.ltots.app.ytm

import java.net.URLDecoder

/** What a pasted or shared YouTube / YouTube Music link points to. */
sealed interface LinkTarget {
    data class Song(val videoId: String) : LinkTarget
    data class Album(val browseId: String) : LinkTarget
    /** Playlist id without the "VL" prefix (PL…, OLAK5uy_… album playlists, RD… mixes). */
    data class Playlist(val playlistId: String) : LinkTarget
    data class Artist(val channelId: String) : LinkTarget
}

object Links {
    private val urlRe = Regex("""https?://[^\s"'<>]+""")
    private val idRe = Regex("""^[A-Za-z0-9_-]{11}$""")

    /** Finds the first YouTube link in [text] (shared text often has a title before the URL). */
    fun parse(text: String): LinkTarget? {
        val url = urlRe.find(text)?.value ?: text.trim().takeIf { it.startsWith("music.youtube.com") || it.startsWith("youtu") || it.startsWith("www.youtube") }?.let { "https://$it" }
            ?: return null
        val noScheme = url.substringAfter("://")
        val host = noScheme.substringBefore('/').lowercase().removePrefix("www.").removePrefix("m.")
        if (host != "youtube.com" && host != "music.youtube.com" && host != "youtu.be" && host != "youtube-nocookie.com") return null
        val pathAndQuery = noScheme.substringAfter('/', "")
        val path = pathAndQuery.substringBefore('?').substringBefore('#').trimEnd('/')
        val query = pathAndQuery.substringAfter('?', "").substringBefore('#')
            .split('&').mapNotNull { kv ->
                val k = kv.substringBefore('=', "").ifEmpty { return@mapNotNull null }
                k to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
            }.toMap()

        if (host == "youtu.be") return path.takeIf { idRe.matches(it) }?.let { LinkTarget.Song(it) }

        val segments = path.split('/').filter { it.isNotEmpty() }
        when (segments.firstOrNull()) {
            "watch" -> {
                query["v"]?.takeIf { idRe.matches(it) }?.let { return LinkTarget.Song(it) }
                query["list"]?.let { return LinkTarget.Playlist(it) }
            }
            "playlist" -> query["list"]?.let { return playlistOrAlbum(it) }
            "browse" -> segments.getOrNull(1)?.let { id ->
                return when {
                    id.startsWith("MPREb") -> LinkTarget.Album(id)
                    id.startsWith("VL") -> playlistOrAlbum(id.removePrefix("VL"))
                    id.startsWith("UC") -> LinkTarget.Artist(id)
                    else -> null
                }
            }
            "channel" -> segments.getOrNull(1)?.takeIf { it.startsWith("UC") }?.let { return LinkTarget.Artist(it) }
            "shorts", "live", "embed", "v" -> segments.getOrNull(1)?.takeIf { idRe.matches(it) }?.let { return LinkTarget.Song(it) }
        }
        return null
    }

    private fun playlistOrAlbum(id: String): LinkTarget = LinkTarget.Playlist(id)
}

object Covers {
    /**
     * Highest-resolution version of a YouTube Music cover. Google image URLs carry their size
     * after "="; asking for 2400 px returns the original (usually 1200–3000 px, square).
     */
    fun highRes(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = url.trim()
        if (u.contains("googleusercontent.com") || u.contains("ggpht.com")) {
            val base = u.substringBefore('=')
            return "$base=w2400-h2400-l100-rj"
        }
        if (u.contains("i.ytimg.com/vi")) {
            val id = u.substringAfter("/vi/").substringBefore('/')
            return "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
        }
        return u
    }

    /** Video thumbnails are 16:9 and must be cropped to a square; music covers already are. */
    fun needsSquareCrop(url: String?): Boolean = url != null && url.contains("i.ytimg.com")

    fun videoThumb(videoId: String) = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
}
