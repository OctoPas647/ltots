package com.ltots.app.data

import java.util.Locale

/** A playlist the app makes by itself from the songs on the phone. */
data class Mix(
    /** Stable key (for lists). */
    val id: String,
    val kind: Kind,
    val title: String,
    val tracks: List<Track>,
) {
    enum class Kind(val label: String) {
        SOUNDTRACK("Games & soundtracks"), DECADE("Through the years"), GENRE("Genres"),
    }

    val subtitle: String get() = "${tracks.size} songs"
}

/**
 * Builds playlists from the library, the way a streaming app's "made for you" does, but only
 * from what's on the phone:
 *  - **games & soundtracks** – every song from one game, film or series together, also when it's
 *    spread over several albums ("Halo 3 (Original Soundtrack) Vol. 1" and "Halo 3 OST, Disc 2"
 *    are one playlist) or comes as single songs ("Snake Eater (From \"Metal Gear Solid 3\")");
 *  - **decades** – the 80s, the 90s, the 2000s… from each song's release year;
 *  - **genres** – one per genre family (Rock, Hip-hop, R&B & soul…).
 * A playlist needs at least [MIN_SONGS] songs.
 */
object Mixes {
    const val MIN_SONGS = 3

    fun build(tracks: List<Track>): List<Mix> = soundtracks(tracks) + decades(tracks) + genres(tracks)

    // ---- games & soundtracks ---------------------------------------------------------------

    private val SOUNDTRACK_GENRE = Regex("soundtrack|video ?game|\\bgame\\b|\\bost\\b|\\bscore\\b|anime|film|movie", RegexOption.IGNORE_CASE)
    private val SOUNDTRACK_TEXT = Regex(
        "\\bo\\.?s\\.?t\\b|soundtrack|original score|video ?game|game music|music from|from the (game|film|movie|series)",
        RegexOption.IGNORE_CASE,
    )
    /** "(From "Metal Gear Solid 3")", "[from The Witcher 3]", "(Music from Halo)". */
    private val FROM_IN_TITLE = Regex(
        "[(\\[](?:music |theme |song )?from (?:the )?(?:video ?game |game |film |movie |series |anime )?[\"“'‘]?([^\"”'’)\\]]+?)[\"”'’]?\\s*[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    /** Words that say which part of a soundtrack an album is, not which game it's from. */
    private val ALBUM_EXTRAS = listOf(
        // A bracketed note: "(Original Game Soundtrack)", "[Deluxe Edition]", "(Music from the Motion Picture)"
        Regex("\\s*[(\\[][^)\\]]*(soundtrack|o\\.?s\\.?t|score|music from|edition|deluxe|remaster|version|vol)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE),
        // "… Original Game Soundtrack", "… OST", "… Soundtrack"
        Regex("[\\s:–-]+(the )?(official |original |complete )*(video ?game |game |motion picture |film |movie |series |tv )?(sound ?track|o\\.?s\\.?t\\.?|score)$", RegexOption.IGNORE_CASE),
        // "… - Volume Alpha", "… Vol. 2", "… Disc 1", "… Part II"
        Regex("[\\s:–-]+(vol(ume)?\\.?|disc|disk|cd|part)\\s*[\\w.]+$", RegexOption.IGNORE_CASE),
    )

    /** Whether a song is from a game, film or series soundtrack. */
    fun isSoundtrack(t: Track): Boolean =
        (t.genre != null && SOUNDTRACK_GENRE.containsMatchIn(SongSorter.genreName(t.genre) ?: "")) ||
            SOUNDTRACK_TEXT.containsMatchIn(t.album) || FROM_IN_TITLE.containsMatchIn(t.title)

    /** The game / film a soundtrack song is from, as a playlist name; null when unclear. */
    fun soundtrackName(t: Track): String? {
        FROM_IN_TITLE.find(t.title)?.let { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { return it } }
        var name = t.album.trim()
        var before: String
        do {
            before = name
            for (r in ALBUM_EXTRAS) name = name.replace(r, "").trim().trimEnd('-', '–', ':', ',', ' ')
        } while (name != before)
        return name.takeIf { it.isNotBlank() }
    }

    private fun key(name: String) = name.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun soundtracks(tracks: List<Track>): List<Mix> =
        tracks.filter { isSoundtrack(it) }
            .mapNotNull { t -> soundtrackName(t)?.let { it to t } }
            .groupBy { key(it.first) }
            .filterKeys { it.isNotEmpty() }
            .values
            .filter { it.size >= MIN_SONGS }
            .map { group ->
                // Named as most of its songs spell it.
                val title = group.groupingBy { it.first }.eachCount().maxBy { it.value }.key
                Mix("ost:${key(title)}", Mix.Kind.SOUNDTRACK, title, group.map { it.second }.sortedWith(ALBUM_ORDER))
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

    // ---- decades ---------------------------------------------------------------------------

    /** "The 90s" for 1990–1999, "The 2010s" for 2010–2019; null for a missing or odd year. */
    fun decadeName(year: Int?): String? {
        if (year == null || year !in 1900..2100) return null
        val d = year / 10 * 10
        return if (d < 2000) "The ${d % 100}s" else "The ${d}s"
    }

    private fun decades(tracks: List<Track>): List<Mix> =
        tracks.filter { decadeName(it.year) != null }
            .groupBy { it.year!! / 10 }
            .filterValues { it.size >= MIN_SONGS }
            .toSortedMap()
            .map { (d, g) ->
                Mix("decade:$d", Mix.Kind.DECADE, decadeName(d * 10)!!,
                    g.sortedWith(compareBy<Track> { it.year }.then(ALBUM_ORDER)))
            }

    // ---- genres ----------------------------------------------------------------------------

    private fun genres(tracks: List<Track>): List<Mix> =
        tracks.mapNotNull { t ->
            // A genre that fits no family gets a playlist of its own name.
            (SongSorter.familyName(t.genre) ?: SongSorter.genreName(t.genre))?.let { it to t }
        }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= MIN_SONGS }
            .map { (name, g) -> Mix("genre:${key(name)}", Mix.Kind.GENRE, name, SongSorter.sort(g, SongSort.GENRE)) }
            .sortedBy { SongSorter.familyIndex(it.tracks.first().genre) }

    /** Album by album, each in its own track order. */
    private val ALBUM_ORDER: Comparator<Track> = compareBy<Track> { it.album.lowercase(Locale.ROOT) }
        .thenBy { it.trackNo ?: Int.MAX_VALUE }
        .thenBy { it.title.lowercase(Locale.ROOT) }
}
