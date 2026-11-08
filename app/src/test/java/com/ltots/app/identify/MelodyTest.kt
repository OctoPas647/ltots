package com.ltots.app.identify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class MelodyTest {
    private val rate = Melody.RATE

    /** An instrument playing [notes] (MIDI), each [noteSec] long, with a few harmonics. */
    private fun play(notes: List<Int>, noteSec: Double, transpose: Int = 0): FloatArray {
        val per = (noteSec * rate).toInt()
        val out = FloatArray(notes.size * per)
        var phase = 0.0
        notes.forEachIndexed { k, n ->
            val f = 440.0 * 2.0.pow((n + transpose - 69) / 12.0)
            for (i in 0 until per) {
                phase += 2 * PI * f / rate
                val env = minOf(1.0, i / (0.01 * rate)) * (1 - 0.4 * i / per)
                out[k * per + i] = (env * (0.6 * sin(phase) + 0.25 * sin(2 * phase) + 0.1 * sin(3 * phase))).toFloat()
            }
        }
        return out
    }

    private fun tune(seed: Int, n: Int = 40): List<Int> {
        val r = java.util.Random(seed.toLong())
        var p = 64
        return List(n) { p = (p + r.nextInt(7) - 3).coerceIn(57, 76); p }
    }

    @Test fun hearsTheNotesPlayed() {
        val notes = listOf(60, 62, 64, 65, 67, 69, 71, 72)
        val c = Melody.contour(play(notes, 0.5))
        // Ten values a second: the middle of each note.
        notes.forEachIndexed { k, n ->
            val v = c[k * 5 + 2]
            assertTrue("note $k: heard $v, played $n", abs(v - n) < 0.5)
        }
    }

    @Test fun findsTheSongInAnotherKeyAndTempo() {
        // Twelve "songs"; the clip plays song 7's tune 3 semitones higher and 10 % slower.
        val songs = (0 until 12).map { s -> s to Melody.contour(play(tune(s), 0.35)) }
        val clip = Melody.contour(play(tune(7).take(30), 0.385, transpose = 3))
        val ranked = Melody.rank(clip, songs)
        assertEquals(7, ranked.first().key)
        assertTrue("stands out: z=${ranked.first().z}", ranked.first().z >= Melody.CONFIDENT)
    }

    @Test fun packsAndUnpacks() {
        val c = floatArrayOf(60.0f, Float.NaN, 61.5f)
        val back = Melody.unpack(Melody.pack(c))
        assertEquals(60.0f, back[0], 0.05f); assertTrue(back[1].isNaN()); assertEquals(61.5f, back[2], 0.05f)
    }
}
