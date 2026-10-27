package com.ltots.app.identify

import com.ltots.app.lyrics.SongMatch
import com.ltots.app.ytm.YtSong
import kotlin.math.abs
import kotlin.math.exp

/** One piece of evidence that the clip is a certain song. */
data class Clue(val kind: Kind, val weight: Double, val note: String) {
    enum class Kind { TAG, SOUND, SOUND_AI, SOUND_SPEED, LYRICS }
}

/** A song the clip might be, with everything that points to it. */
data class Identity(
    val title: String,
    val artist: String,
    val album: String? = null,
    val year: String? = null,
    val coverUrl: String? = null,
    val previewUrl: String? = null,
    /** How fast the video plays the song, when it isn't 1× (e.g. 1.26 for a sped-up edit). */
    val speed: Double? = null,
    val clues: List<Clue> = emptyList(),
    /** Already-known YouTube Music song (lyrics search finds songs directly). */
    val ytSong: YtSong? = null,
    /** Where in the clip its sound was recognised (seconds), for clips with several songs. */
    val heardAt: List<Double> = emptyList(),
) {
    val key: String get() = Ranking.key(title, artist)
    val score: Double get() = Ranking.score(clues)
}

/** A row on the results page. */
data class Suggestion(
    val title: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    /** The song on YouTube Music, ready to download. Null when it couldn't be found there. */
    val song: YtSong?,
    /** 0..1 */
    val confidence: Double,
    val reasons: List<String>,
    /** "Original", "Sped up", "Remix"… when it differs from the main match. */
    val version: String? = null,
    val previewUrl: String? = null,
    val identityKey: String,
    /** Which song of the clip this is (1, 2, …) when the clip plays more than one; else null. */
    val part: Int? = null,
    /** Where in the clip that song plays (seconds), for the "at 0:45" label. */
    val heardAt: List<Double> = emptyList(),
)

object Ranking {
    fun key(title: String, artist: String): String =
        SongMatch.norm(baseTitle(title)) + "|" + SongMatch.norm(SongMatch.mainArtist(artist))

    private val versionWords = Regex(
        """\s*[(\[-]\s*[^)\]]*\b(sped\s*up|speed\s*up|slowed|reverb|nightcore|remix|edit|version|live|acoustic|instrumental|cover|tiktok|extended|radio|remaster(ed)?|mix|8d|bass\s*boosted)\b[^)\]]*[)\]]?""",
        RegexOption.IGNORE_CASE,
    )

    /** Song name without version notes: "Song (Sped Up)" → "Song". */
    fun baseTitle(t: String): String = SongMatch.cleanTitle(t).replace(versionWords, "").trim().ifBlank { SongMatch.cleanTitle(t) }

    /** What kind of version a title names, if any. */
    fun versionOf(title: String): String? {
        val t = title.lowercase()
        return when {
            Regex("""sped\s*up|speed\s*up|nightcore""").containsMatchIn(t) -> "Sped up"
            Regex("""slowed""").containsMatchIn(t) -> "Slowed"
            "remix" in t || Regex("""\bmix\b""").containsMatchIn(t) -> "Remix"
            Regex("""\blive\b""").containsMatchIn(t) -> "Live"
            "acoustic" in t -> "Acoustic"
            "instrumental" in t -> "Instrumental"
            Regex("""\bcover\b""").containsMatchIn(t) -> "Cover"
            Regex("""\bedit\b""").containsMatchIn(t) -> "Edit"
            else -> null
        }
    }

    /** Strongest clue counts fully, each further one a little less (they aren't independent). */
    fun score(clues: List<Clue>): Double {
        var s = 0.0
        var f = 1.0
        for (c in clues.sortedByDescending { it.weight }) { s += c.weight * f; f *= 0.6 }
        return s
    }

    /** Score → 0..1. One sound match ≈ 63 %, a music tag ≈ 78 %, both ≈ 93 %. */
    fun confidence(score: Double): Double = 1 - exp(-score / 2.0)

    /** Puts clues about the same song together. */
    fun merge(identities: List<Identity>): List<Identity> {
        val out = LinkedHashMap<String, Identity>()
        for (i in identities) {
            val k = i.key
            val prev = out[k]
            out[k] = if (prev == null) i else prev.copy(
                album = prev.album ?: i.album,
                year = prev.year ?: i.year,
                coverUrl = prev.coverUrl ?: i.coverUrl,
                previewUrl = prev.previewUrl ?: i.previewUrl,
                speed = prev.speed ?: i.speed,
                ytSong = prev.ytSong ?: i.ytSong,
                clues = prev.clues + i.clues,
                heardAt = (prev.heardAt + i.heardAt).distinct().sorted(),
            )
        }
        return out.values.sortedByDescending { it.score }
    }

    /**
     * How well a YouTube Music song fits the identity: name and main artist must agree.
     * 0 = not this song.
     */
    fun fit(id: Identity, s: YtSong): Double {
        val ts = maxOf(SongMatch.similarity(baseTitle(id.title), baseTitle(s.title)), SongMatch.similarity(id.title, s.title))
        if (ts < 0.6) return 0.0
        val artists = s.artists.map { it.name } + s.artistText
        val asim = artists.maxOfOrNull { maxOf(SongMatch.similarity(SongMatch.mainArtist(id.artist), it), SongMatch.similarity(id.artist, it)) } ?: 0.0
        // Shazam often lists "A & B" where YouTube has "A, B"; the main artist is enough.
        // Uploads by other channels name the artist in the title instead: "Song (Artist) - …".
        val inTitle = SongMatch.norm(s.title).contains(SongMatch.norm(SongMatch.mainArtist(id.artist)).ifBlank { "\u0000" })
        val asimEff = if (asim < 0.5 && inTitle) 0.75 else asim
        if (asimEff < 0.5) return 0.0
        val official = if (s.isVideo) 0.0 else 0.1
        return ts * 0.55 + asimEff * 0.35 + official
    }

    /**
     * Turns identities plus what YouTube Music found for each into the ranked list.
     * [found] maps an identity key to YouTube Music search results for it.
     */
    fun suggestions(identities: List<Identity>, found: Map<String, List<YtSong>>, maxRows: Int = 12): List<Suggestion> {
        val parts = parts(identities)
        val rows = ArrayList<Suggestion>()
        val seen = HashSet<String>()
        for (id in identities) {
            val conf = confidence(id.score)
            val reasons = id.clues.sortedByDescending { it.weight }.map { it.note }.distinct()
            val results = found[id.key].orEmpty()
            val ranked = results.map { it to fit(id, it) }.filter { it.second > 0 }.sortedByDescending { it.second }
            val wantVersion = speedVersion(id.speed) ?: versionOf(id.title)
            // The main row: the version the clip actually plays, when YouTube Music has it; else the original.
            val main = id.ytSong
                ?: ranked.firstOrNull { versionOf(it.first.title) == wantVersion && !it.first.isVideo }?.first
                ?: ranked.firstOrNull { versionOf(it.first.title) == wantVersion }?.first
                ?: ranked.firstOrNull { versionOf(it.first.title) == null && !it.first.isVideo }?.first
                ?: ranked.firstOrNull()?.first
            if (main == null || seen.add(main.videoId)) {
                rows += Suggestion(
                    title = main?.title ?: id.title,
                    artist = main?.artistText?.ifBlank { null } ?: id.artist,
                    album = main?.album?.name ?: id.album,
                    coverUrl = main?.thumbUrl ?: id.coverUrl,
                    song = main,
                    confidence = conf,
                    reasons = reasons,
                    version = main?.let { versionOf(it.title) },
                    previewUrl = id.previewUrl,
                    identityKey = id.key,
                    part = parts[id.key],
                    heardAt = id.heardAt,
                )
            }
            // Other versions of the same song: original vs. sped up, remixes, the music video.
            var alts = 0
            for ((s, _) in ranked) {
                if (alts >= 3) break
                if (!seen.add(s.videoId)) continue
                val v = versionOf(s.title)
                val factor = when {
                    v == wantVersion -> 0.85
                    v == null && !s.isVideo -> 0.75 // the original of a sped-up edit
                    v == null -> 0.55 // music video
                    else -> 0.45
                }
                rows += Suggestion(
                    title = s.title, artist = s.artistText.ifBlank { id.artist }, album = s.album?.name,
                    coverUrl = s.thumbUrl ?: id.coverUrl, song = s, confidence = conf * factor,
                    reasons = listOf(if (s.isVideo) "Music video of this song" else "Another version of this song"),
                    version = v ?: if (s.isVideo) "Video" else "Original",
                    identityKey = id.key,
                )
                alts++
            }
        }
        if (parts.size < 2) return rows.sortedByDescending { it.confidence }.take(maxRows)
        // Several songs in the clip: each one's best row first, in the order they play, then
        // their other versions, then everything else.
        val mains = rows.filter { it.part != null }.sortedBy { it.part }
        val rest = rows.filter { it.part == null }.sortedWith(compareBy<Suggestion> { parts[it.identityKey] ?: Int.MAX_VALUE }.thenByDescending { it.confidence })
        return (mains + rest).take(maxRows.coerceAtLeast(mains.size))
    }

    /**
     * Songs heard at different moments of the clip — a video that plays one song and then
     * another. Key → 1, 2, … in the order they play. Empty unless there are at least two that
     * were each recognised at moments where the other wasn't.
     */
    fun parts(identities: List<Identity>): Map<String, Int> {
        val heard = identities.filter { it.heardAt.isNotEmpty() && confidence(it.score) >= 0.45 }
        if (heard.size < 2) return emptyMap()
        // Keep songs whose moments don't overlap one already kept (a mix-up Shazam made on the same
        // moment is not a second song).
        val kept = ArrayList<Identity>()
        for (id in heard.sortedByDescending { it.score }) {
            if (kept.none { k -> k.heardAt.any { a -> id.heardAt.any { b -> abs(a - b) < 6 } } }) kept += id
            if (kept.size == 4) break
        }
        if (kept.size < 2) return emptyMap()
        return kept.sortedBy { it.heardAt.first() }.mapIndexed { i, id -> id.key to i + 1 }.toMap()
    }

    /** A clip played this fast is a sped-up / slowed edit. */
    fun speedVersion(speed: Double?): String? = when {
        speed == null || abs(speed - 1.0) < 0.06 -> null
        speed > 1 -> "Sped up"
        else -> "Slowed"
    }
}
