package com.ltots.app.recs

import com.ltots.app.data.Listen
import com.ltots.app.ytm.YtRef
import com.ltots.app.ytm.YtSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private fun l(key: String, artist: String, daysAgo: Int, listened: Long) = Listen(key, -1, key, artist, "", 200_000L, now - daysAgo * day, listened)

    @Test fun finishedRecentSongsOutweighSkipsAndOldPlays() {
        val history = listOf(
            l("a", "A", 1, 200_000), l("a", "A", 2, 190_000),       // loved lately
            l("b", "B", 60, 200_000), l("b", "B", 61, 200_000),     // loved long ago
            l("c", "C", 1, 3_000), l("c", "C", 2, 4_000),           // skipped
        )
        val w = Taste.seeds(history, emptyMap(), emptyList(), now).associate { it.key to it.weight }
        assertTrue(w.getValue("a") > w.getValue("b"))
        assertTrue(w.getValue("c") < 0)
        assertEquals(listOf("a", "b"), Taste.pickSeeds(Taste.seeds(history, emptyMap(), emptyList(), now), 5).map { it.key })
    }

    @Test fun atMostTwoSeedsPerArtist() {
        val history = (1..5).map { l("s$it", "Same", 1, 200_000) } + l("x", "Other", 1, 150_000)
        val seeds = Taste.pickSeeds(Taste.seeds(history, emptyMap(), emptyList(), now), 4)
        assertEquals(2, seeds.count { it.artist == "Same" })
        assertTrue(seeds.any { it.artist == "Other" })
    }

    @Test fun oneArtistDoesntTakeOverThePicks() {
        fun r(id: String, artist: String, score: Double) = RecSong(YtSong(id, id, listOf(YtRef(artist, null))), score, "")
        val ranked = listOf(r("1", "A", 1.0), r("2", "A", 0.99), r("3", "A", 0.98), r("4", "B", 0.8), r("5", "C", 0.7))
        val top3 = Taste.diversify(ranked, 3).map { it.song.videoId }
        assertEquals("1", top3[0])
        assertTrue("4" in top3 && "5" in top3)
    }

    @Test fun previewStartsNearTheHook() {
        assertEquals(66, previewStart(200))
        assertEquals(0, previewStart(20))
    }
}
