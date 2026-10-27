package com.ltots.app.lyrics

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** A word the speech recognizer heard in the song, and when. */
data class HeardWord(val word: String, val startMs: Long, val endMs: Long)

/**
 * Times plain lyrics by what was actually heard in the song: the recognizer's words are lined up
 * with the lyrics' words in order (a misheard, missed or extra word costs a little, a match
 * scores), so each line is anchored where its words were sung. Lines nobody could hear (a
 * mumbled verse, a long instrumental) are placed between their neighbours.
 */
object LyricAlign {
    /** Under this share of lines heard, the result isn't trusted (wrong language, no vocals…). */
    const val MIN_COVERAGE = 0.3

    data class Result(val lines: List<LyricLine>, val coverage: Double)

    /** Lower case letters and digits only: "Don't" → "dont", "AK-50" → "ak", "50". */
    fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).replace("'", "").replace("’", "")
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** The words to tell the recognizer to listen for (it then hears only these, or "unknown"). */
    fun vocabulary(plain: String): List<String> = words(plain).distinct()

    /** How alike two words are: 2 the same, 1 nearly (one letter off in a longer word), 0 not. */
    private fun similarity(a: String, b: String): Int {
        if (a == b) return 2
        if (min(a.length, b.length) < 4) return 0
        return if (editDistance(a, b) <= max(1, max(a.length, b.length) / 5)) 1 else 0
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Line times for the non-blank lines of [plain], from [heard] (in time order). Null when too
     * few lines could be matched to trust it.
     */
    fun align(plain: String, heard: List<HeardWord>, durationMs: Long): Result? {
        val lineTexts = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lineTexts.isEmpty() || durationMs <= 0) return null
        // Every lyric word, with the line it belongs to and its place in that line.
        val lw = ArrayList<String>(); val lineOf = ArrayList<Int>(); val posInLine = ArrayList<Int>()
        lineTexts.forEachIndexed { li, t -> words(t).forEachIndexed { k, w -> lw += w; lineOf += li; posInLine += k } }
        val hw = heard.filter { it.word.isNotBlank() && it.word != "[unk]" }.map { it.copy(word = words(it.word).joinToString("")) }
        if (lw.isEmpty() || hw.isEmpty()) return null

        // Best way through both word lists in order (Needleman–Wunsch): +3 / +1 for a match,
        // -1 for a lyric word nobody heard or a heard word not in the lyrics here.
        val n = lw.size; val m = hw.size
        val score = Array(n + 1) { IntArray(m + 1) }
        val from = Array(n + 1) { ByteArray(m + 1) } // 0 diagonal, 1 skip lyric word, 2 skip heard word
        for (i in 1..n) { score[i][0] = -i; from[i][0] = 1 }
        for (j in 1..m) { score[0][j] = -j; from[0][j] = 2 }
        for (i in 1..n) for (j in 1..m) {
            val sim = similarity(lw[i - 1], hw[j - 1].word)
            val diag = score[i - 1][j - 1] + when (sim) { 2 -> 3; 1 -> 1; else -> -2 }
            val up = score[i - 1][j] - 1
            val left = score[i][j - 1] - 1
            if (diag >= up && diag >= left) { score[i][j] = diag; from[i][j] = 0 }
            else if (up >= left) { score[i][j] = up; from[i][j] = 1 }
            else { score[i][j] = left; from[i][j] = 2 }
        }
        // Walk back: the heard time of every lyric word that matched.
        val wordTime = LongArray(n) { -1L }
        var i = n; var j = m
        while (i > 0 && j > 0) {
            when (from[i][j].toInt()) {
                0 -> { if (similarity(lw[i - 1], hw[j - 1].word) > 0) wordTime[i - 1] = hw[j - 1].startMs; i--; j-- }
                1 -> i--
                else -> j--
            }
        }

        // A line starts where its first matched word was heard, moved back for the words before it.
        val wordMs = hw.map { it.endMs - it.startMs }.sorted().let { it[it.size / 2] }.coerceIn(150L, 700L)
        val lineTime = LongArray(lineTexts.size) { -1L }
        for (w in 0 until n) {
            val li = lineOf[w]
            if (wordTime[w] >= 0 && lineTime[li] < 0) lineTime[li] = max(0L, wordTime[w] - posInLine[w] * wordMs)
        }
        // Keep the anchors in order: one that jumps back behind an earlier line is dropped.
        var last = -1L
        for (li in lineTime.indices) {
            if (lineTime[li] < 0) continue
            if (lineTime[li] <= last) lineTime[li] = -1L else last = lineTime[li]
        }
        val anchored = lineTime.count { it >= 0 }
        val coverage = anchored.toDouble() / lineTexts.size
        if (anchored == 0 || coverage < MIN_COVERAGE) return null

        // Lines without an anchor share the time between their anchored neighbours by length.
        fun weight(li: Int) = 1.5 + lineTexts[li].length / 12.0
        val out = lineTime.copyOf()
        var li = 0
        while (li < out.size) {
            if (out[li] >= 0) { li++; continue }
            val startGap = li
            while (li < out.size && out[li] < 0) li++
            val before = if (startGap > 0) out[startGap - 1] else -1L
            val after = if (li < out.size) out[li] else -1L
            val gapLines = (startGap until li).toList()
            val lo: Double; val hi: Double
            when {
                before >= 0 && after >= 0 -> { lo = before.toDouble(); hi = after.toDouble() }
                // Before the first heard line: back from it, a line's length each.
                after >= 0 -> { hi = after.toDouble(); lo = max(0.0, hi - gapLines.sumOf { weight(it) } * 700.0) }
                // After the last heard line: on from it, until a little before the end.
                else -> { lo = before.toDouble(); hi = min(durationMs * 0.97, lo + (gapLines.sumOf { weight(it) } + weight(startGap - 1)) * 700.0) }
            }
            // Spread over the gap; when there's a line before, it keeps its share first.
            val weights = (if (before >= 0) listOf(startGap - 1) else emptyList()) + gapLines
            val total = weights.sumOf { weight(it) }
            var at = lo
            weights.forEach { w ->
                if (w >= startGap) out[w] = at.toLong()
                at += (hi - lo) * weight(w) / total
            }
        }
        // Strictly in order, at least a tenth of a second apart.
        for (k in 1 until out.size) if (out[k] <= out[k - 1]) out[k] = out[k - 1] + 100
        return Result(lineTexts.mapIndexed { k, t -> LyricLine(out[k], t) }, coverage)
    }
}
