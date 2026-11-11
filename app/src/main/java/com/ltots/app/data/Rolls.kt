package com.ltots.app.data

import com.ltots.app.lyrics.SongMatch
import java.util.Locale
import kotlin.random.Random

/** One mix rolled from the library: a name, a line about how it was made, and its songs in order. */
data class Roll(val id: String, val title: String, val sub: String, val tracks: List<Track>)

/**
 * Home's rolls: mixes dealt fresh from the songs on the phone. The same [seed] always deals the
 * same rolls (they stay put until the re-roll button is tapped); a new seed deals new ones.
 *
 * Every roll is spread over genres: songs are dealt one genre at a time, round and round, so a
 * roll never sits in one style for long, no artist appears more than twice, and across a set of
 * rolls every genre in the library gets its turn (the two-genre "clash" rolls take the genres in
 * a shuffled order, each used once before any is used again).
 */
object Rolls {
    const val SIZE = 25
    private const val MIN_LIBRARY = 12
    private const val NO_GENRE = "Unsorted"

    private fun family(t: Track): String = SongSorter.familyName(t.genre) ?: SongSorter.genreName(t.genre) ?: NO_GENRE
    private fun artist(t: Track): String = SongMatch.mainArtist(t.artist).lowercase(Locale.ROOT)

    /**
     * Deals [pool] one genre at a time: the genres in a random order, each genre's songs shuffled,
     * then one from each in turn until [n] songs are out. At most [perArtist] songs by one artist.
     */
    internal fun spread(pool: List<Track>, n: Int, rnd: Random, perArtist: Int = 2): List<Track> {
        val piles = pool.groupBy { family(it) }.mapValues { ArrayDeque(it.value.shuffled(rnd)) }
        val order = piles.keys.shuffled(rnd)
        val out = ArrayList<Track>(n)
        val byArtist = HashMap<String, Int>()
        var dealt = true
        while (out.size < n && dealt) {
            dealt = false
            for (g in order) {
                val pile = piles.getValue(g)
                while (pile.isNotEmpty()) {
                    val t = pile.removeFirst()
                    val a = artist(t)
                    if (a.isNotBlank() && (byArtist[a] ?: 0) >= perArtist) continue
                    byArtist[a] = (byArtist[a] ?: 0) + 1
                    out += t; dealt = true
                    break
                }
                if (out.size >= n) break
            }
        }
        return out
    }

    /** How many different genres are in [tracks]. */
    fun genreCount(tracks: List<Track>): Int = tracks.mapTo(HashSet()) { family(it) }.size

    private fun sub(tracks: List<Track>, lead: String): String {
        val g = genreCount(tracks)
        return if (g > 1) "$lead · $g genres" else lead
    }

    /**
     * The rolls for [seed]. [plays] is how often each song was played ([Track.profileKey] → count),
     * for the rolls about what's played a lot or never.
     */
    fun deal(tracks: List<Track>, seed: Long, plays: Map<String, Int> = emptyMap(), size: Int = SIZE): List<Roll> {
        if (tracks.size < MIN_LIBRARY) return emptyList()
        val rnd = Random(seed)
        val out = ArrayList<Roll>()
        fun add(id: String, title: String, lead: String, songs: List<Track>) {
            if (songs.size >= 8) out += Roll("$id:$seed", title, sub(songs, lead), songs)
        }

        // 1. A bit of everything: the whole library, one genre at a time.
        add("all", "A bit of everything", "One genre after another", spread(tracks, size, rnd))

        // 2-3. Genre clashes: two genres that don't usually meet, song for song.
        val genres = tracks.groupBy { family(it) }.filter { it.key != NO_GENRE && it.value.size >= 4 }
        val names = genres.keys.shuffled(rnd)
        names.chunked(2).filter { it.size == 2 }.take(2).forEach { (a, b) ->
            val mixed = spread(genres.getValue(a) + genres.getValue(b), size, rnd)
            add("clash-$a-$b", "$a × $b", "Song for song", mixed)
        }

        // 4. Never played: what's on the phone and hasn't had its turn.
        val unplayed = tracks.filter { (plays[it.profileKey] ?: 0) == 0 }
        if (plays.isNotEmpty() && unplayed.size >= 8) add("fresh", "Never played", "On your phone, still waiting", spread(unplayed, size, rnd))

        // 5. Favourites, cut with strangers: every other song is one you play a lot, the rest from
        // genres your favourites don't cover.
        val loved = tracks.filter { (plays[it.profileKey] ?: 0) >= 3 }.sortedByDescending { plays[it.profileKey] ?: 0 }.take(60)
        if (loved.size >= 6) {
            val lovedGenres = loved.mapTo(HashSet()) { family(it) }
            val lovedIds = loved.mapTo(HashSet()) { it.id }
            val others = tracks.filter { it.id !in lovedIds }
            val far = others.filter { family(it) !in lovedGenres }.ifEmpty { others }
            val a = spread(loved, size / 2 + 1, rnd)
            val b = spread(far, size / 2, rnd)
            val mixed = ArrayList<Track>()
            for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let { mixed += it }; b.getOrNull(i)?.let { mixed += it } }
            add("remix", "Favourites remixed", "Yours, cut with something else", mixed)
        }

        // 6. One decade, every genre in it.
        val decades = tracks.filter { it.year != null && it.year in 1950..2100 }.groupBy { it.year!! / 10 * 10 }.filter { it.value.size >= 10 }
        decades.keys.shuffled(rnd).firstOrNull()?.let { d ->
            add("decade-$d", (Mixes.decadeName(d) ?: "${d}s") + " roll", "One decade, shuffled", spread(decades.getValue(d), size, rnd))
        }

        // 7. One song each: as many different artists as there are.
        add("artists", "One song each", "A different artist every time", spread(tracks, size, rnd, perArtist = 1))

        return out
    }
}
