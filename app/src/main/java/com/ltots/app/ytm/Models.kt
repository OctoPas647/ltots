package com.ltots.app.ytm

/** Something on YouTube Music you can open or download. */
sealed interface YtItem {
    val title: String
    val thumbUrl: String?
}

data class YtRef(val name: String, val id: String?)

data class YtSong(
    val videoId: String,
    override val title: String,
    val artists: List<YtRef>,
    val album: YtRef? = null,
    val durationSec: Int? = null,
    override val thumbUrl: String? = null,
    /** True for music videos/user uploads; false for official audio ("songs"). */
    val isVideo: Boolean = false,
    val year: String? = null,
    /** Position on its album, when known. */
    val trackNumber: Int? = null,
) : YtItem {
    val artistText: String get() = artists.joinToString(", ") { it.name }
}

data class YtAlbum(
    val browseId: String,
    override val title: String,
    val artist: String,
    val year: String? = null,
    override val thumbUrl: String? = null,
    val kind: String = "Album",
) : YtItem

data class YtPlaylist(
    /** Browse id ("VL…"). */
    val browseId: String,
    override val title: String,
    val author: String,
    override val thumbUrl: String? = null,
    val countText: String? = null,
) : YtItem {
    val playlistId: String get() = browseId.removePrefix("VL")
}

data class YtArtist(
    val browseId: String,
    override val title: String,
    override val thumbUrl: String? = null,
    val subtitle: String? = null,
) : YtItem

data class SearchChip(val text: String, val params: String?, val selected: Boolean)

data class SearchSection(val title: String?, val items: List<YtItem>)

data class SearchResult(
    val chips: List<SearchChip>,
    val sections: List<SearchSection>,
    val continuation: String? = null,
)

/** An album or playlist page: header plus its tracks. */
data class Collection(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbUrl: String?,
    val isAlbum: Boolean,
    val artist: String?,
    val year: String?,
    val tracks: List<YtSong>,
    val continuation: String? = null,
)

data class ArtistPage(
    val id: String,
    val name: String,
    val thumbUrl: String?,
    val sections: List<SearchSection>,
)

/** Rich details for one song (artist ids, album, year, square cover). */
data class SongDetails(val song: YtSong)
