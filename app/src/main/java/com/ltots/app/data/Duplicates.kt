package com.ltots.app.data

import com.ltots.app.lyrics.SongMatch
import java.util.Locale
import kotlin.math.abs

/**
 * Finds the same recording saved more than once.
 *
 * Two files are the same recording when they have the same song title and main artist, are the
 * same version, and run for (nearly) the same time. A remix, a live take, a sped-up or slowed
 * edit, an acoustic or instrumental version, a cover, a remaster… is a different recording and
 * is never counted as a duplicate of the original, whatever its length.
 */
object Duplicates {
    /** Lengths this close together (ms) are the same recording (different encoders pad differently). */
    const val SAME_LENGTH_MS = 3_000L

    /** Words that make a bracket or a " - …" ending a *version* rather than decoration. */
    private val VERSION_WORDS = listOf(
        "remix", "rmx", "mix", "live", "sped up", "speed up", "spedup", "slowed", "reverb", "nightcore", "daycore",
        "acoustic", "unplugged", "instrumental", "karaoke", "cover", "demo", "extended", "edit", "version", "remaster",
        "remastered", "orchestral", "a cappella", "acapella", "mashup", "bootleg", "flip", "rework", "re-recorded",
        "rerecorded", "taylor's version", "8d", "bass boosted", "piano", "session", "stripped", "vip", "dub", "club",
        "mono", "stereo", "concert", "tour", "festival", "unreleased", "tiktok",
    )
    private val bracket = Regex("""[(\[{]([^)\]}]*)[)\]}]""")
    private val dashTail = Regex("""\s[-–—|]\s([^-–—|]+)$""")

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun isVersion(text: String): Boolean {
        val n = " " + norm(text) + " "
        return VERSION_WORDS.any { n.contains(" " + norm(it) + " ") }
    }

    /** The song's plain name and what version it is ("" = the original). */
    internal fun split(title: String): Pair<String, String> {
        val versions = ArrayList<String>()
        var t = bracket.replace(title) { m ->
            if (isVersion(m.groupValues[1])) versions += norm(m.groupValues[1])
            " " // "(Official Video)", "(feat. X)", "[Lyrics]"… say nothing about the recording
        }
        // "Song - Live at Wembley", "Song - Remastered 2011"
        while (true) {
            val m = dashTail.find(t) ?: break
            if (!isVersion(m.groupValues[1])) break
            versions += norm(m.groupValues[1])
            t = t.removeRange(m.range)
        }
        // A bare version word at the end of the title: "Song sped up", "Song Remix".
        val words = norm(t)
        val tail = listOf("sped up", "speed up", "slowed", "nightcore", "remix", "instrumental", "acoustic", "karaoke")
            .firstOrNull { words.endsWith(" $it") }
        if (tail != null) return words.removeSuffix(" $tail").trim() to (versions + tail).sorted().joinToString("+")
        return words to versions.sorted().joinToString("+")
    }

    /** Same key = same song and version (the length still has to agree, see [sameLength]). */
    fun key(title: String, artist: String?): String {
        var a = artist?.takeIf { it.isNotBlank() && it != Track.UNKNOWN && !it.equals("unknown artist", true) }
        var name = title
        // No artist tag but an "Artist - Song" title (a file saved by another downloader).
        if (a == null) {
            val i = title.indexOf(" - ")
            if (i > 0) { a = title.substring(0, i); name = title.substring(i + 3) }
        }
        val (base, version) = split(name)
        return norm(a?.let { SongMatch.mainArtist(it) } ?: "") + "|" + base + "|" + version
    }

    fun sameLength(aMs: Long, bMs: Long): Boolean = aMs <= 0 || bMs <= 0 || abs(aMs - bMs) <= SAME_LENGTH_MS

    /** Higher = the copy worth keeping: lossless first, then finer sound, then the bigger file. */
    fun quality(t: Track): Long {
        val mime = t.mime.orEmpty().lowercase(Locale.ROOT)
        val lossless = listOf("flac", "wav", "alac", "aiff", "ape").any { mime.contains(it) }
        val codec = when {
            lossless -> 3L
            mime.contains("opus") -> 2L
            else -> 1L
        }
        val bits = (t.bitsPerSample ?: 16).coerceIn(0, 64).toLong()
        val rate = ((t.sampleRate ?: 44_100) / 1000).coerceIn(0, 999).toLong()
        // Digits: codec | bits | kHz | size in 100 kB (capped).
        return codec * 1_000_000_000_000L + bits * 10_000_000_000L + rate * 10_000_000L + (t.sizeBytes / 100_000L).coerceIn(0L, 9_999_999L)
    }

    /**
     * Every recording that's on the phone more than once: each group best copy first, the groups
     * in title order. Songs without a title to go by are left alone.
     */
    fun groups(tracks: List<Track>): List<List<Track>> {
        val out = ArrayList<List<Track>>()
        for ((k, same) in tracks.groupBy { key(it.title, it.artist) }) {
            if (same.size < 2 || k.split('|')[1].isBlank()) continue
            // Same name and version: split by length, so a 3:10 and a 4:25 "Song" stay apart.
            val byLength = same.sortedBy { it.durationMs }
            var cluster = ArrayList<Track>()
            fun close() { if (cluster.size > 1) out += cluster.sortedByDescending { quality(it) }; cluster = ArrayList() }
            for (t in byLength) {
                if (cluster.isNotEmpty() && !sameLength(cluster.last().durationMs, t.durationMs)) close()
                cluster += t
            }
            close()
        }
        return out.sortedBy { it.first().title.lowercase(Locale.ROOT) }
    }

    /** The copy of this recording already on the phone, if there is one. */
    fun find(tracks: List<Track>, title: String, artist: String?, durationMs: Long): Track? {
        val k = key(title, artist)
        if (k.split('|')[1].isBlank()) return null
        return tracks.firstOrNull { key(it.title, it.artist) == k && sameLength(it.durationMs, durationMs) }
    }
}
