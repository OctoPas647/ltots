package com.ltots.app.data

import com.ltots.app.lyrics.SongMatch

/**
 * What's left of the library after the user's filters: hidden artists, and "popular songs
 * only" — hits the world knows, the songs the user plays most, or either.
 */
object LibraryFilter {
    /** Rank on Deezer (0..1 000 000) from which a song counts as a hit by itself. */
    const val HIT_RANK = 500_000

    /** The main artist of a track, as shown in the artist list. */
    fun artistOf(t: Track): String = SongMatch.mainArtist(t.artist).ifBlank { t.artist }

    fun isHidden(t: Track, hidden: Set<String>): Boolean =
        hidden.isNotEmpty() && SongMatch.norm(artistOf(t)) in hidden

    /**
     * The hits among [tracks]: songs ranked [HIT_RANK]+ worldwide, plus each artist's best-known
     * songs in the library (their top quarter by rank, at least one), so an artist with smaller
     * numbers still keeps their best. Songs not looked up yet stay while [stillLooking].
     */
    fun hits(tracks: List<Track>, popularity: Map<Long, Int>, stillLooking: Boolean): Set<Long> {
        val out = HashSet<Long>()
        tracks.filter { it.id in popularity }.groupBy { SongMatch.norm(artistOf(it)) }.forEach { (_, songs) ->
            val ranked = songs.sortedByDescending { popularity[it.id] ?: 0 }
            val keep = maxOf(1, (ranked.size + 3) / 4)
            ranked.forEachIndexed { i, t ->
                val r = popularity[t.id] ?: 0
                if (r >= HIT_RANK || (i < keep && r > 0)) out += t.id
            }
        }
        if (stillLooking) tracks.filter { it.id !in popularity }.forEach { out += it.id }
        return out
    }

    fun apply(
        tracks: List<Track>,
        settings: AppSettings,
        popularity: Map<Long, Int>,
        plays: Map<String, Int>,
        stillLooking: Boolean,
    ): List<Track> {
        val hidden = settings.hiddenArtists.map { SongMatch.norm(it) }.toSet()
        var list = if (hidden.isEmpty()) tracks else tracks.filter { !isHidden(it, hidden) }
        if (settings.onlyHits || settings.onlyMostPlayed) {
            val keep = HashSet<Long>()
            if (settings.onlyHits) keep += hits(list, popularity, stillLooking)
            if (settings.onlyMostPlayed) keep += PlayStats.mostPlayed(list, plays)
            list = list.filter { it.id in keep }
        }
        return list
    }

    /** Every artist in the library with how many songs, most songs first. */
    fun artists(tracks: List<Track>): List<Pair<String, Int>> =
        tracks.filter { it.artist != Track.UNKNOWN && it.artist.isNotBlank() }
            .groupBy { SongMatch.norm(artistOf(it)) }
            .map { (_, songs) -> artistOf(songs.first()) to songs.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
}
