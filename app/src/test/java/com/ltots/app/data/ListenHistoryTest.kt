package com.ltots.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ListenHistoryTest {
    private fun at(daysAgo: Int, hour: Int = 12): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
    }.timeInMillis

    private fun listen(key: String, daysAgo: Int, listened: Long = 200_000L) =
        Listen(key, 1L, "Title $key", "Artist", "Album", 200_000L, at(daysAgo), listened)

    @Test fun groupsByDayLikeYouTubeMusic() {
        val list = listOf(listen("a", 0), listen("b", 0), listen("a", 0), listen("c", 1), listen("d", 3), listen("e", 9))
        val groups = ListenHistory.groups(list, now = at(0, 18))
        assertEquals(listOf("Today", "Yesterday", "This week", "Last week"), groups.map { it.first })
        // A song shows once per day, at its latest play.
        assertEquals(listOf("a", "b"), groups[0].second.map { it.key })
    }

    @Test fun finishedCountsMoreThanSkipped() {
        val done = listen("a", 0, listened = 200_000L)
        val skip = listen("b", 0, listened = 4_000L)
        assertEquals(1f, done.completion, 1e-6f)
        assertFalse(done.skipped)
        assertTrue(skip.skipped)
    }

    @Test fun savesAndReadsBack() {
        val list = listOf(listen("song:x|y", 0), listen("local:5", 2, 12_345L))
        assertEquals(list, ListenHistory.parse(ListenHistory.serialize(list)))
    }
}
