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
    /** A remark shown under the lyrics, e.g. how an AI match lined them up. */
    val note: String = "",
) {
    val isEmpty: Boolean get() = synced.isNullOrEmpty() && plain.isNullOrBlank() && !instrumental

    /** Index of the line playing at [positionMs] (-1 before the first line). */
    fun lineAt(positionMs: Long): Int = synced?.let { Lrc.lineAt(it, positionMs) } ?: -1

    fun toJson(): JSONObject = JSONObject().apply {
        synced?.let { put("lrc", Lrc.format(it)) }
        plain?.let { put("plain", it) }
        put("instrumental", instrumental)
        put("source", source)
        put("as", matchedAs)
        if (note.isNotBlank()) put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject) = LyricsDoc(
            synced = o.optString("lrc").takeIf { it.isNotBlank() }?.let { Lrc.parse(it) }?.takeIf { it.isNotEmpty() },
            plain = o.optString("plain").takeIf { it.isNotBlank() },
            instrumental = o.optBoolean("instrumental"),
            source = o.optString("source"),
            matchedAs = o.optString("as"),
            note = o.optString("note"),
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

    /** Index of the line playing at [positionMs] in [lines] (-1 before the first line). */
    fun lineAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /**
     * Guessed times for lyrics that only exist as plain text, so they can still follow the song
     * and be tapped: the lines are spread over the song (after a short intro, ending a little
     * before the end), each taking time by its length, with a pause at every verse break.
     * Null for an empty text or an unknown length.
     */
    fun estimate(plain: String?, durationMs: Long): List<LyricLine>? {
        if (plain.isNullOrBlank() || durationMs <= 0) return null
        val lines = ArrayList<String>()
        val pauseBefore = ArrayList<Boolean>()
        var gap = false
        for (raw in plain.lineSequence()) {
            val t = raw.trim()
            if (t.isEmpty()) { gap = lines.isNotEmpty(); continue }
            lines += t; pauseBefore += gap; gap = false
        }
        if (lines.isEmpty()) return null
        val edge = (durationMs * 0.06).toLong().coerceIn(3_000L, 15_000L).coerceAtMost(durationMs / 4)
        val start = edge; val end = durationMs - edge
        // A line takes a moment plus time for its words; a verse break is a short pause.
        fun weight(t: String) = 1.5 + t.length / 12.0
        val pause = 2.0
        val total = lines.indices.sumOf { i -> weight(lines[i]) + if (pauseBefore[i]) pause else 0.0 }
        val msPer = (end - start) / total
        var at = start.toDouble()
        return lines.mapIndexed { i, t ->
            if (pauseBefore[i]) at += pause * msPer
            LyricLine(at.toLong(), t).also { at += weight(t) * msPer }
        }
    }

    fun format(lines: List<LyricLine>): String = lines.joinToString("\n") { l ->
        val m = l.timeMs / 60_000
        val s = (l.timeMs / 1000) % 60
        val cs = (l.timeMs % 1000) / 10
        "[%02d:%02d.%02d] %s".format(java.util.Locale.ROOT, m, s, cs, l.text)
    }
}
