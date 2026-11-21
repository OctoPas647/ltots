package com.ltots.app.watch

import com.ltots.app.ytm.YtSong
import java.util.Locale
import kotlin.math.abs

/**
 * Finds the song YouTube is playing on YouTube Music. The YouTube app tells other apps only the
 * video's title, its channel and its length, so: the same title and length (the very video,
 * best: switching over lands on exactly the same moment), else the same length and a title that
 * clearly is the same song (its official audio: the same recording, usually the same timing).
 *
 * Pure logic, tested.
 */
object YouTubeMatch {
    /** Lengths this close (s) count as the same recording. */
    private const val SAME_SEC = 2

    private val noise = Regex(
        "[(\\[][^)\\]]*(official|video|audio|lyric|lyrics|visualizer|hd|4k|remaster|live|mv|clip)[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )

    /** "Artist - Song (Official Video) [4K]" → "artist song". */
    fun clean(title: String): String =
        title.replace(noise, " ").lowercase(Locale.ROOT)
            .replace(Regex("\\b(ft|feat|featuring)\\b\\.?"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** "Artist - Topic" / "ArtistVEVO" → "artist". */
    fun cleanChannel(channel: String): String =
        channel.replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "").trim()

    /** What to search YouTube Music for. */
    fun query(title: String, channel: String): String {
        val t = title.replace(noise, " ").replace(Regex("\\s+"), " ").trim()
        val ch = cleanChannel(channel)
        // A title that already names the artist ("Artist - Song") needs no channel.
        return if (ch.isBlank() || t.contains(ch, ignoreCase = true)) t else "$ch $t"
    }

    private fun words(s: String) = clean(s).split(' ').filter { it.isNotBlank() }.toSet()

    /** Share of the found song's title (and artist) words that appear in the video's title. */
    private fun overlap(videoTitle: String, channel: String, s: YtSong): Double {
        val have = words(videoTitle) + words(cleanChannel(channel))
        val want = words(s.title)
        if (want.isEmpty()) return 0.0
        return want.count { it in have }.toDouble() / want.size
    }

    /** The best candidate for the video, or null when none is clearly the same song. */
    fun pick(videoTitle: String, channel: String, durationMs: Long, candidates: List<YtSong>): YtSong? {
        val sec = (durationMs / 1000).toInt()
        fun sameLength(s: YtSong) = durationMs <= 0 || s.durationSec == null || abs(s.durationSec - sec) <= SAME_SEC
        // The very video: same title, same length.
        candidates.firstOrNull { it.isVideo && clean(it.title) == clean(videoTitle) && sameLength(it) && it.durationSec != null }?.let { return it }
        return candidates
            .filter { sameLength(it) && it.durationSec != null && overlap(videoTitle, channel, it) >= 0.75 }
            .maxByOrNull { overlap(videoTitle, channel, it) * 10 - abs((it.durationSec ?: sec) - sec) - (if (it.isVideo) 0.5 else 0.0) }
    }
}
