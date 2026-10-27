package com.ltots.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ShuffleKeeperTest {
    private val ids = List(12) { "s$it" }

    @Test fun songsAlreadyHeardNeverComeBeforeNewOnes() {
        // Heard the first five in album order, now on song 5, then turned shuffle on.
        repeat(50) { seed ->
            val heard = setOf("s0", "s1", "s2", "s3", "s4")
            val order = ShuffleKeeper.order(ids, 4, heard, Random(seed)).toList()
            val cur = order.indexOf(4)
            // Everything after the playing song is new; everything before it was heard.
            assertEquals((5 until 12).toSet(), order.drop(cur + 1).toSet())
            assertEquals(setOf(0, 1, 2, 3), order.take(cur).toSet())
        }
    }

    @Test fun aFreshQueueStartsWithThePlayingSong() {
        val order = ShuffleKeeper.order(ids, 7, setOf("s7"), Random(1))
        assertEquals(7, order.first())
        assertEquals(ids.indices.toSet(), order.toSet())
    }
}
