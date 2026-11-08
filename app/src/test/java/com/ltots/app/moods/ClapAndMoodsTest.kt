package com.ltots.app.moods

import android.net.Uri
import com.ltots.app.data.Mix
import com.ltots.app.data.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ClapAndMoodsTest {
    /** The log-mel input must match transformers' ClapFeatureExtractor, which the model was trained with. */
    @Test fun melFeaturesMatchTheReferenceExtractor() {
        val ref = JSONObject(javaClass.classLoader!!.getResource("clap_mel_reference.json").readText())
        val n = 300_000
        val x = FloatArray(n) { i ->
            (0.3 * sin(2 * PI * 440 * i / 48000.0) + 0.2 * sin(2 * PI * 3000 * i / 48000.0 * (1 + i / 480000.0))).toFloat()
        }
        val f = Clap.features(x)
        assertEquals(Clap.FRAMES * Clap.MELS, f.size)
        val frames = ref.getJSONObject("frames")
        var worst = 0.0
        for (k in frames.keys()) {
            val t = k.toInt(); val row = frames.getJSONArray(k)
            for (m in 0 until Clap.MELS) worst = maxOf(worst, abs(row.getDouble(m) - f[t * Clap.MELS + m]))
        }
        assertTrue("worst difference $worst dB", worst < 0.05)
        assertEquals(ref.getDouble("mean"), f.average(), 0.05)
    }

    private fun track(id: Long) = Track(id, Uri.EMPTY, "Song $id", "Artist $id", "Album", 1L, 200_000L, null, null, null)

    private fun unit(v: FloatArray) = Clap.normalize(v)

    @Test fun songsThatSoundAlikeBecomeOneNamedAlbumAndThemesFollowTheLibrary() {
        val rnd = Random(1)
        val dim = 16
        fun axis(i: Int) = FloatArray(dim).also { it[i] = 1f }
        fun near(i: Int) = unit(FloatArray(dim) { j -> (if (j == i) 1f else 0f) + (rnd.nextFloat() - 0.5f) * 0.3f })
        // 12 songs near direction 0 ("sad"), 12 near direction 1 ("party").
        val tracks = (1L..24L).map(::track)
        val emb = tracks.associate { it.profileKey to near(if (it.id <= 12) 0 else 1) }
        val text = ClapText(
            themes = listOf(Prompt("sad", "Sad", axis(0)), Prompt("party", "Party", axis(1)), Prompt("jazz", "Jazz", axis(5))),
            albums = listOf(Prompt("sad-hours", "Sad hours", axis(0)), Prompt("dance-floor", "Dance floor", axis(1)), Prompt("other", "Other", axis(7))),
        )
        val albums = MoodMixes.albums(tracks, emb, text)
        assertEquals(setOf("Sad hours", "Dance floor"), albums.map { it.title }.toSet())
        for (a in albums) {
            assertEquals(Mix.Kind.AI_ALBUM, a.kind)
            val sad = a.title == "Sad hours"
            assertTrue(a.tracks.all { (it.id <= 12) == sad })
        }
        val themes = MoodMixes.themes(tracks, emb, text).associateBy { it.title }
        assertTrue(themes.getValue("Sad").tracks.all { it.id <= 12 })
        assertTrue(themes.getValue("Party").tracks.all { it.id > 12 })
    }
}
