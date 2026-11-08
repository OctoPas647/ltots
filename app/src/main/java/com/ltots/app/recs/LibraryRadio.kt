package com.ltots.app.recs

import com.ltots.app.data.SongSorter
import com.ltots.app.data.Track
import com.ltots.app.lyrics.SongMatch
import com.ltots.app.moods.Clap

/**
 * What plays after a song started from Home: the songs on the phone most like it, most similar
 * first. How alike two songs sound comes from the on-device music model when it has heard them;
 * their genre and artist count too, and decide alone for songs it hasn't heard yet.
 */
object LibraryRadio {
    const val MAX_SONGS = 300

    private fun artistOf(t: Track) = SongMatch.norm(SongMatch.mainArtist(t.artist))

    /** How alike [seed] and [t] are, about 0..1. */
    internal fun likeness(seed: Track, t: Track, seedSound: FloatArray?, sound: FloatArray?): Double {
        val genre = when {
            seed.genre.isNullOrBlank() || t.genre.isNullOrBlank() -> 0.0
            SongSorter.genreName(seed.genre) == SongSorter.genreName(t.genre) -> 1.0
            SongSorter.familyName(seed.genre) != null && SongSorter.familyName(seed.genre) == SongSorter.familyName(t.genre) -> 0.7
            else -> 0.0
        }
        val artist = if (artistOf(seed).isNotBlank() && artistOf(seed) == artistOf(t) && t.artist != Track.UNKNOWN) 1.0 else 0.0
        return if (seedSound != null && sound != null && seedSound.size == sound.size && seedSound.isNotEmpty()) {
            0.6 * Clap.dot(seedSound, sound) + 0.25 * genre + 0.15 * artist
        } else 0.65 * genre + 0.35 * artist
    }

    /** The other songs of [library], most like [seed] first. [sounds]: the music model's, by profile key. */
    fun similarTo(seed: Track, library: List<Track>, sounds: Map<String, FloatArray>): List<Track> {
        val own = sounds[seed.profileKey]
        // Shuffled first, so songs equally alike don't always come in the same order.
        return library.filter { it.id != seed.id }.shuffled()
            .map { it to likeness(seed, it, own, sounds[it.profileKey]) }
            .sortedByDescending { it.second }
            .take(MAX_SONGS).map { it.first }
    }
}
