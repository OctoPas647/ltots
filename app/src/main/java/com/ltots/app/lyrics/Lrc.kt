package com.ltots.app.lyrics

import org.json.JSONObject

/** One line of synced lyrics: shown from [timeMs] until the next line starts. */
data class LyricLine(val timeMs: Long, val text: String)

/** Lyrics saved for a song. */
data class LyricsDoc(
    /** Timed lines (karaoke-style), or null if only plain text exists. */
    val synced: List<LyricLine>?,
    val plain: String?,
    val instrumental: Boolean = false,
    /** Where they came from, e.g. "LRCLIB". */
    val source: String = "",
    /** What the match was found as (artist — title), for display. */
    val matchedAs: String = "",
) {
    val isEmpty: Boolean get() = synced.isNullOrEmpty() && plain.isNullOrBlank() && !instrumental

    /** Index of the line playing at [positionMs] (-1 before the first line). */
    fun lineAt(positionMs: Long): Int {
        val l = synced ?: return -1
        var lo = 0; var hi = l.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (l[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun toJson(): JSONObject = JSONObject().apply {
        synced?.let { put("lrc", Lrc.format(it)) }
        plain?.let { put("plain", it) }
        put("instrumental", instrumental)
        put("source", source)
        put("as", matchedAs)
    }

    companion object {
        fun fromJson(o: JSONObject) = LyricsDoc(
            synced = o.optString("lrc").takeIf { it.isNotBlank() }?.let { Lrc.parse(it) }?.takeIf { it.isNotEmpty() },
            plain = o.optString("plain").takeIf { it.isNotBlank() },
            instrumental = o.optBoolean("instrumental"),
            source = o.optString("source"),
            matchedAs = o.optString("as"),
        )
    }
}

/** Reads and writes the standard .lrc format: "[mm:ss.xx] words". */
object Lrc {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LyricLine> {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            var pos = 0
            val times = ArrayList<Long>()
            // A line can carry several time stamps: "[00:12.00][01:30.00] chorus".
            while (true) {
                val m = stamp.matchAt(line, pos) ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                times += min * 60_000 + sec * 1000 + ms
                pos = m.range.last + 1
            }
            if (times.isEmpty()) continue
            val words = line.substring(pos).trim()
            // LRC's offset tag: positive means lyrics come earlier.
            for (t in times) out += LyricLine((t - offset).coerceAtLeast(0), words)
        }
        out.sortBy { it.timeMs }
        return out
    }

    fun format(lines: List<LyricLine>): String = lines.joinToString("\n") { l ->
        val m = l.timeMs / 60_000
        val s = (l.timeMs / 1000) % 60
        val cs = (l.timeMs % 1000) / 10
        "[%02d:%02d.%02d] %s".format(java.util.Locale.ROOT, m, s, cs, l.text)
    }
}
