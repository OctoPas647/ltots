package com.ltots.app.download

import com.ltots.app.data.Track
import com.ltots.app.ytm.Parser
import com.ltots.app.ytm.YtSong
import java.util.Locale

/**
 * Helpers for picking songs to suggest: one list from several YouTube Music radios, and a key
 * that's the same for a song whatever its spelling. The suggestions themselves are on the Home
 * tab ([com.ltots.app.recs.Recommender]).
 */
object Suggestions {
    /** The phone's songs, newest first. */
    fun newest(library: List<Track>): List<Track> = library.filter { it.addedAt != null }.sortedByDescending { it.addedAt }

    /**
     * One list from several radios: taking turns (the first of each, then the second of each…)
     * so every seed gets a say, with each song only once (a video and its audio count as one)
     * and [skip]ped songs left out.
     */
    fun merge(radios: List<List<YtSong>>, skip: (YtSong) -> Boolean, limit: Int): List<YtSong> {
        val out = mutableListOf<YtSong>()
        val seen = HashSet<String>()
        val longest = radios.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (r in radios) {
                val s = r.getOrNull(i) ?: continue
                if (skip(s) || !seen.add(key(s.title, s.artists.firstOrNull()?.name))) continue
                out += s
                if (out.size == limit) return out
            }
        }
        return out
    }

    /** Same song whatever its spelling: "Artist - Title (Official Video)" = "Title" by Artist. */
    fun key(title: String, artist: String?): String {
        val a = artist?.split(',', '&')?.firstOrNull()?.trim()?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")?.trim().orEmpty()
        return Parser.normalizeTitle(title, artist) + "|" + a
    }
}
