package com.ltots.app.moods

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.data.Mixes
import com.ltots.app.data.Track
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Songs the mood AI couldn't read are stored with an empty entry. They must never reach the AI
 * albums and themes (that crashed the app on start-up, in Mixes, with "length=0; index=0").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UnreadableSongsTest {
    private fun track(id: Long) = Track(id, Uri.parse("content://media/$id"), "Song $id", "Artist $id", "Album", 1L, 200_000L, null, null, null)

    private fun unit(r: Random, dim: Int): FloatArray {
        val v = FloatArray(dim) { r.nextFloat() - 0.5f }
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dim) { v[it] / n }
    }

    @Test fun emptyEntriesDontCrashTheMixes() {
        val r = Random(3)
        val tracks = (1L..80L).map(::track)
        // Every fourth song couldn't be read: an empty entry.
        val moods = tracks.associate { t -> t.profileKey to (if (t.id % 4 == 0L) FloatArray(0) else unit(r, 512)) }
        val text = ClapText(
            themes = (1..6).map { Prompt("t$it", "Theme $it", unit(r, 512)) },
            albums = (1..10).map { Prompt("a$it", "Album $it", unit(r, 512)) },
        )
        val mixes = Mixes.build(tracks, moods, text)
        assertTrue("AI mixes still made from the readable songs: ${mixes.map { it.title }}", mixes.any { it.kind == com.ltots.app.data.Mix.Kind.AI_ALBUM })
        assertTrue(mixes.flatMap { it.tracks }.none { it.id % 4 == 0L && it in tracks && mixes.any { m -> m.kind == com.ltots.app.data.Mix.Kind.AI_ALBUM && it in m.tracks } })
    }

    @Test fun dotIsSafeWithEmptyVectors() {
        assertTrue(Clap.dot(floatArrayOf(1f, 2f), FloatArray(0)) == 0f)
        assertTrue(Clap.dot(FloatArray(0), floatArrayOf(1f)) == 0f)
    }
}
