package com.ltots.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LyricAlignTest {
    private val plain = """
        So the last time that you guys saw
        An AK-50 video was about
        A month ago when
        We showed off the AK-50

        Big russian gun
        Part of my personality
        AR guys are always like
        Big russian gun
    """.trimIndent()
    /** When each line is really sung (ms). */
    private val truth = listOf(9_000L, 12_500L, 16_000L, 18_000L, 40_000L, 43_000L, 46_500L, 50_000L)

    /** What a recognizer might hear: every word at 350 ms, with some missed, misheard or extra. */
    private fun heard(skip: Set<String> = emptySet(), mishear: Map<String, String> = emptyMap(), noise: Boolean = true): List<HeardWord> {
        val out = ArrayList<HeardWord>()
        val lines = plain.lines().filter { it.isNotBlank() }
        lines.forEachIndexed { li, line ->
            LyricAlign.words(line).forEachIndexed { k, w ->
                val t = truth[li] + k * 350L
                if (w !in skip) out += HeardWord(mishear[w] ?: w, t, t + 300)
            }
            if (noise) out += HeardWord("yeah", truth[li] + 3_000, truth[li] + 3_200)
        }
        return out.sortedBy { it.startMs }
    }

    private fun close(expected: List<Long>, got: List<LyricLine>, within: Long) =
        expected.zip(got).all { (e, g) -> abs(e - g.timeMs) <= within }

    @Test fun cleanRecognitionGivesTheRealTimes() {
        val r = LyricAlign.align(plain, heard(noise = false), 130_000)!!
        assertEquals(1.0, r.coverage, 0.0)
        assertTrue(r.lines.map { it.timeMs }.toString(), close(truth, r.lines, 1))
    }

    @Test fun missedMisheardAndExtraWordsStillLandEachLine() {
        // The first words of two lines missed, "personality" misheard, "yeah" heard all over.
        val r = LyricAlign.align(plain, heard(skip = setOf("so", "big"), mishear = mapOf("personality" to "personalty", "russian" to "rushing")), 130_000)!!
        assertTrue(r.lines.map { it.timeMs }.toString(), close(truth, r.lines, 500))
        // The repeated chorus line goes to its own place, not the first time it was sung.
        assertTrue(abs(r.lines[7].timeMs - 50_000) <= 500)
    }

    @Test fun anUnheardVerseSitsBetweenItsNeighbours() {
        val gone = LyricAlign.words("A month ago when We showed off the").toSet() + "ak" + "50"
        val r = LyricAlign.align(plain, heard(skip = gone, noise = false), 130_000)!!
        val t = r.lines.map { it.timeMs }
        assertTrue(t.toString(), t[1] < t[2] && t[2] < t[3] && t[3] < t[4])
        assertTrue(t.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun hearingNothingUsefulIsNotTrusted() {
        val junk = (0 until 40).map { HeardWord("la", it * 1000L, it * 1000L + 300) }
        assertNull(LyricAlign.align(plain, junk, 130_000))
        assertNull(LyricAlign.align(plain, emptyList(), 130_000))
    }

    @Test fun vocabularyIsTheLyricsWords() {
        assertEquals(listOf("dont", "stop", "ak", "50"), LyricAlign.vocabulary("Don't stop\nAK-50 don't"))
    }
}
