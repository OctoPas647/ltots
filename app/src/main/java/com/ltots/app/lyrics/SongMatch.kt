package com.ltots.app.lyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** What we know about a song on the phone, for finding its lyrics. */
data class SongQuery(
    val key: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

/** A candidate from the lyrics database. */
data class Candidate(
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Double,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean,
)

/**
 * Basic song recognition from the file's tags: cleans up titles the way they often arrive
 * ("Song (Official Video) [HD]", "Artist - Song.mp3", "feat." credits), and only accepts a
 * database entry whose length matches the file to within a few seconds and whose name matches.
 */
object SongMatch {
    private val noise = Regex(
        """\s*[(\[](?:official\s*(?:music\s*)?(?:video|audio|lyric(?:s)?\s*video|visuali[sz]er)|lyric(?:s)?(?:\s*video)?|audio|hd|hq|4k|explicit|clean|visuali[sz]er|remaster(?:ed)?(?:\s*\d{4})?|\d{4}\s*remaster(?:ed)?)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    // "(feat. X)", "[ft. X]" or a trailing "feat. X" — whole words only ("Daft Punk" stays intact).
    private val feat = Regex("""\s*(?:[(\[]\s*\b(?:feat|ft|featuring)\b\.?[^)\]]*[)\]]|\b(?:feat|ft|featuring)\b\.?\s.*$)""", RegexOption.IGNORE_CASE)
    private val ext = Regex("""\.(mp3|m4a|aac|flac|ogg|opus|wav|webm|alac|wma)$""", RegexOption.IGNORE_CASE)
    private val topic = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)

    /** Title without file extensions, "(Official Video)" and similar, and feature credits. */
    // Anything in [square brackets] is a tag like "[HD]" or "[4K UPGRADE]", never part of the name.
    private val square = Regex("""\s*\[[^\]]*]""")
    private val noiseWords = Regex("""\s*\((?=[^)]*\b(?:video|audio|lyrics?|visuali[sz]er|official|hd|hq|4k|mv|m/v|upgrade|explicit)\b)[^)]*\)""", RegexOption.IGNORE_CASE)

    fun cleanTitle(t: String): String =
        t.replace(ext, "").replace(noise, "").replace(square, "").replace(noiseWords, "").replace(feat, "").replace(Regex("\\s+"), " ").trim().trim('-', '–', '—', ' ')

    /** Main artist only: "A feat. B", "A, B", "A & B" → "A". "Artist - Topic" → "Artist". */
    fun mainArtist(a: String): String =
        a.replace(topic, "").split(Regex("""\s*(?:,|;|/|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\s&\s|\bx\b)\s*""", RegexOption.IGNORE_CASE))
            .first().trim()

    /**
     * Ways to look the song up, best first. Without a usable artist tag, a title like
     * "Artist - Song" is split into both halves.
     */
    fun interpretations(q: SongQuery): List<Pair<String?, String>> {
        val out = LinkedHashSet<Pair<String?, String>>()
        val artist = q.artist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) && !it.equals("unknown artist", true) }
        val title = cleanTitle(q.title)
        if (artist != null) {
            out += mainArtist(artist) to title
            out += artist.replace(topic, "").trim() to title
        }
        // "Artist - Title" (common in file names and YouTube titles).
        val dash = Regex("""\s+[-–—]\s+""").split(title, limit = 2)
        if (dash.size == 2) {
            out += mainArtist(dash[0]) to cleanTitle(dash[1])
            if (artist != null) out += mainArtist(artist) to cleanTitle(dash[1])
        }
        out += null to title
        return out.filter { it.second.isNotBlank() }
    }

    // Edit labels re-uploads add: "(Sped Up)", "[Slowed + Reverb]", "(But it hits different)",
    // "- Nightcore", "(8D Audio)", "(TikTok Version)"…
    private val editWords = Regex(
        """\b(?:sped|speed|slowed|slow|reverb|nightcore|daycore|8d|bass\s*boost(?:ed)?|tiktok|hits\s+different|but\s+it|lofi|lo-fi|phonk|edit|remix|version|extended|loop|perfect(?:ly)?|cover|instrumental|acoustic|live|mashup|vibe|chill|ver\.?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketed = Regex("""\s*[(\[{][^)\]}]*[)\]}]""")
    private val dashTail = Regex("""\s+[-–—|]\s+(.+)$""")
    // "'Song' but it fits perfectly", "Song but it hits different"
    private val butTail = Regex("""\s+but\s+(?:it|it's|its|this|now)\b.*$""", RegexOption.IGNORE_CASE)

    /**
     * The song's own name, without anything a re-upload adds: every bracketed part and a
     * dashed tail that names an edit. "Rather Be (But it hits different)" → "Rather Be".
     */
    fun baseTitle(t: String): String {
        var x = cleanTitle(t).replace(bracketed, "")
        dashTail.find(x)?.let { m -> if (editWords.containsMatchIn(m.groupValues[1])) x = x.substring(0, m.range.first) }
        x = x.replace(butTail, "")
        return x.replace(Regex("\\s+"), " ").trim().trim('-', '–', '—', '|', ' ', '\'', '"', '‘', '’', '“', '”').ifBlank { cleanTitle(t) }
    }

    /** Whether the title says it's an edit of a song (sped up, slowed, "hits different", …). */
    fun isEdit(t: String): Boolean = baseTitle(t) != cleanTitle(t) &&
        (bracketed.findAll(t).any { editWords.containsMatchIn(it.value) } || butTail.containsMatchIn(t) || dashTail.find(t)?.let { editWords.containsMatchIn(it.value) } == true)

    /**
     * A looser fit, for edits and re-uploads whose length and uploader differ from the record:
     * the song's name must match exactly, the length may be off by up to a quarter.
     */
    fun looseScore(q: SongQuery, artist: String?, title: String, c: Candidate): Double {
        if (c.synced.isNullOrBlank() && c.plain.isNullOrBlank()) return 0.0
        val ts = maxOf(similarity(title, baseTitle(c.title)), similarity(title, c.title).takeIf { it == 1.0 } ?: 0.0)
        if (ts < 0.99) return 0.0
        if (q.durationMs > 0 && c.durationSec > 0) {
            val diff = abs(c.durationSec - q.durationMs / 1000.0) / (q.durationMs / 1000.0)
            if (diff > 0.25) return 0.0
        }
        val asim = if (artist == null) 0.5 else max(similarity(artist, c.artist), similarity(artist, mainArtist(c.artist)))
        // Synced lyrics and a matching artist first; a re-uploader's name rarely matches.
        return 0.5 + 0.3 * asim + (if (!c.synced.isNullOrBlank()) 0.2 else 0.0)
    }

    /**
     * Picks the song a name-only search means. Several different songs share a name ("Rather
     * Be" by Clean Bandit, The Verve, Riley Green…), so: the file's own artist when it's among
     * them; otherwise the artist uploaded most often under that name (a hit gets uploaded again
     * and again). With no clear favourite, nothing — better no lyrics than another song's.
     */
    fun pickLoose(q: SongQuery, artist: String?, title: String, cs: List<Candidate>): Candidate? {
        val named = cs.filter { looseScore(q, null, title, it) > 0 }
        if (named.isEmpty()) return null
        if (artist != null) {
            named.filter { max(similarity(artist, it.artist), similarity(artist, mainArtist(it.artist))) >= 0.7 }
                .takeIf { it.isNotEmpty() }?.let { return closest(q, it) }
        }
        val groups = named.groupBy { norm(mainArtist(it.artist)) }.filterKeys { it.isNotBlank() }
            .values.sortedByDescending { it.size }
        val top = groups.firstOrNull() ?: return null
        val second = groups.getOrNull(1)?.size ?: 0
        if (top.size < 2 || top.size < 2 * second) return null
        return closest(q, top)
    }

    /** Synced first, then the length nearest the file's. */
    private fun closest(q: SongQuery, cs: List<Candidate>): Candidate =
        cs.sortedWith(compareByDescending<Candidate> { !it.synced.isNullOrBlank() }.thenBy { abs(it.durationSec - q.durationMs / 1000.0) }).first()

    /** Lower-case letters and digits only, accents removed, so "Beyoncé" == "beyonce". */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /** 0..1 similarity of two names (shared words, order-insensitive). */
    fun similarity(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85
        val wa = x.split(' ').toSet(); val wb = y.split(' ').toSet()
        return wa.intersect(wb).size.toDouble() / max(wa.size, wb.size)
    }

    /** Lengths must agree: lyrics for a different edit would drift out of sync. */
    const val MAX_LENGTH_DIFF_SEC = 3.0

    // "- 2011 Remaster", "(Album Version)", "- Radio Edit": the same recording under another label.
    private val sameRecording = Regex(
        """\s*[-–—(\[]\s*(?:(?:\d{4}\s*)?(?:digital(?:ly)?\s*)?remaster(?:ed)?(?:\s*\d{4})?(?:\s*version)?|(?:album|single|radio|original|stereo|mono)\s*(?:version|edit|mix))\s*[)\]]?""",
        RegexOption.IGNORE_CASE,
    )
    // Words that mean a different recording of the song: its lyrics or timing won't fit.
    private val otherVersion = Regex(
        """\b(?:remix|live|acoustic|instrumental|karaoke|cover|sped|slowed|nightcore|daycore|demo|unplugged|reverb|mashup|edit|mix)\b""",
        RegexOption.IGNORE_CASE,
    )
    private fun versionsIn(t: String): Set<String> =
        otherVersion.findAll(t.replace(sameRecording, "")).map { it.value.lowercase(Locale.ROOT) }.toSet()

    private val artistSplit = Regex("""\s*(?:,|;|/|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\s&\s|\band\b|\bwith\b|\bx\b)\s*""", RegexOption.IGNORE_CASE)
    private fun names(a: String): List<String> =
        (a.replace(topic, "").split(artistSplit) + a.replace(topic, "")).map { norm(it).removePrefix("the ") }.filter { it.isNotBlank() }.distinct()

    /** The artist tag, when it says anything. */
    fun knownArtist(q: SongQuery): String? =
        q.artist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) && !it.equals("unknown artist", true) }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * Whether two names are the same song title (1 = exactly). "Love" and "Love Story" are not,
     * and neither are a song and its remix or live version; a typo or "&" for "and" is forgiven.
     */
    fun titleMatch(wanted: String, found: String): Double {
        val a = norm(cleanTitle(wanted).replace(sameRecording, ""))
        val b = norm(cleanTitle(found).replace(sameRecording, ""))
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (versionsIn(wanted) != versionsIn(found)) return 0.0
        if (norm(baseTitle(wanted)) == norm(baseTitle(found))) return 0.95
        val ratio = 1.0 - editDistance(a, b).toDouble() / max(a.length, b.length)
        return if (ratio >= 0.9) ratio else 0.0
    }

    /** Whether [found] (possibly several credited artists) includes the artist [wanted]. */
    fun artistMatch(wanted: String, found: String): Double {
        val w = names(wanted); val f = names(found)
        if (w.isEmpty() || f.isEmpty()) return 0.0
        if (w.any { it in f }) return 1.0
        var best = 0.0
        for (x in w) for (y in f) {
            val short = minOf(x.length, y.length)
            val s = when {
                short >= 6 && (x.startsWith("$y ") || y.startsWith("$x ") || x.endsWith(" $y") || y.endsWith(" $x")) -> 0.85
                else -> 1.0 - editDistance(x, y).toDouble() / max(x.length, y.length)
            }
            best = max(best, s)
        }
        return best
    }

    /**
     * How well the name, artist and length of [c] fit [q] (0 = not this song), not looking at
     * the lyrics themselves. The artist has to match whenever the file says who it is, also for
     * a search by title alone; with no artist at all, only an exact title of the same length counts.
     */
    fun identity(q: SongQuery, artist: String?, title: String, c: Candidate): Double {
        val lenDiff = abs(c.durationSec - q.durationMs / 1000.0)
        if (q.durationMs > 0 && lenDiff > MAX_LENGTH_DIFF_SEC) return 0.0
        val ts = max(titleMatch(title, c.title), titleMatch(q.title, c.title))
        if (ts < 0.9) return 0.0
        val who = artist ?: knownArtist(q)
        if (who == null && (q.durationMs <= 0 || lenDiff > 2.0 || ts < 1.0)) return 0.0
        val asim = if (who == null) 0.7 else artistMatch(who, c.artist)
        if (who != null && asim < 0.8) return 0.0
        val lenScore = if (q.durationMs > 0) 1.0 - lenDiff / (MAX_LENGTH_DIFF_SEC * 2) else 0.6
        val album = if (!q.album.isNullOrBlank() && c.album.isNotBlank() && norm(q.album) == norm(c.album)) 0.03 else 0.0
        return ts * 0.3 + asim * 0.25 + lenScore * 0.3 + album
    }

    /** How well [c] fits [q] (0 = reject): [identity], then whether it has lyrics, synced best. */
    fun score(q: SongQuery, artist: String?, title: String, c: Candidate): Double {
        val id = identity(q, artist, title, c)
        if (id == 0.0) return 0.0
        val content = when {
            !c.synced.isNullOrBlank() -> 1.0
            c.instrumental -> 0.8
            !c.plain.isNullOrBlank() -> 0.7
            else -> 0.0
        }
        if (content == 0.0) return 0.0
        return id + content * 0.15
    }

    /** The best fit: synced lyrics first, then the closest match. */
    fun best(q: SongQuery, artist: String?, title: String, cs: List<Candidate>): Candidate? =
        cs.map { it to score(q, artist, title, it) }.filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<Candidate, Double>> { !it.first.synced.isNullOrBlank() }.thenBy { it.second })?.first
}

/** Catches lyrics that are garbled, or plainly belong to a different recording. */
object LyricsQuality {
    private val credit = Regex("""^\s*(?:作词|作曲|编曲|制作人|混音|母带|监制|吉他|贝斯|鼓|和声|录音|出品|人声|弦乐|Lyrics by|Composed by|Written by|Producer)\s*[:：].*""", RegexOption.IGNORE_CASE)
    private val stamp = Regex("""\[[^\]]*]""")
    private val mojibake = Regex("""[ÃÂÐÑØÙ][\u0080-\u00BF\u0152-\u0178\u20AC\u201A-\u2122]|â€""")

    /** Without the credit lines NetEase puts in front of the words. */
    fun withoutCredits(lines: List<LyricLine>): List<LyricLine> = lines.filterNot { credit.matches(it.text) }

    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
    private fun isLatinName(s: String) = s.any { it.isLetter() } && s.filter { it.isLetter() }.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }

    /** Whether the words of [c] can be real lyrics of [q]. */
    fun ok(q: SongQuery, c: Candidate, fromNetEase: Boolean = false): Boolean {
        val synced = c.synced?.let { Lrc.parse(it) }?.let(::withoutCredits)
        val text = synced?.joinToString("\n") { it.text } ?: c.plain?.replace(stamp, "").orEmpty()
        if (text.isBlank()) return c.instrumental
        if ('\uFFFD' in text) return false
        val ink = text.count { !it.isWhitespace() }
        if (ink == 0 || mojibake.findAll(text).count() * 2.0 / ink > 0.02) return false
        if (text.count { it.isLetter() }.toDouble() / ink < 0.6) return false
        if (q.durationMs > 60_000 && text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().size < 3) return false
        if (synced != null && synced.isNotEmpty() && q.durationMs > 0) {
            val last = synced.last { it.text.isNotBlank() || it === synced.last() }.timeMs
            if (last > q.durationMs + 5_000 || last < q.durationMs * 0.4) return false
        }
        if (fromNetEase) {
            if ("纯音乐" in text) return false
            // Chinese words for a song whose title and artist aren't: a wrong entry.
            val han = text.count(::isHan).toDouble() / text.count { it.isLetter() }.coerceAtLeast(1)
            val kanaOrHangul = text.any { Character.UnicodeScript.of(it.code).let { s -> s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL } }
            if (han > 0.5 && !kanaOrHangul && isLatinName(q.title + (q.artist ?: ""))) return false
        }
        return true
    }
}
