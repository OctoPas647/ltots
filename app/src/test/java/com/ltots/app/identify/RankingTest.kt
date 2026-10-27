package com.ltots.app.identify

import com.ltots.app.ytm.YtRef
import com.ltots.app.ytm.YtSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {
    private fun song(id: String, title: String, artist: String, video: Boolean = false) =
        YtSong(id, title, listOf(YtRef(artist, "UC$id")), isVideo = video)

    private fun sound(title: String, artist: String, speed: Double? = null) = Identity(
        title, artist, speed = speed,
        clues = listOf(Clue(if (speed == null) Clue.Kind.SOUND else Clue.Kind.SOUND_SPEED, if (speed == null) 2.0 else 1.7, "Sound match")),
    )

    @Test fun cluesForTheSameSongAddUp() {
        val ids = Ranking.merge(listOf(
            sound("Blinding Lights", "The Weeknd"),
            Identity("Blinding Lights (Sped Up)", "The Weeknd", clues = listOf(Clue(Clue.Kind.TAG, 3.0, "Tag"))),
            sound("Other Song", "Someone"),
            sound("Blinding Lights", "The Weeknd"),
        ))
        assertEquals(2, ids.size)
        assertEquals("Blinding Lights", ids[0].title)
        assertEquals(3, ids[0].clues.size)
        assertTrue(ids[0].score > ids[1].score)
    }

    @Test fun mostLikelyFirstAndVersionsFollowTheirSong() {
        val a = sound("Song A", "Artist A").let { it.copy(clues = it.clues + Clue(Clue.Kind.TAG, 3.0, "Tag")) }
        val b = sound("Song B", "Artist B")
        val found = mapOf(
            a.key to listOf(song("a1", "Song A", "Artist A"), song("a2", "Song A (Sped Up)", "Artist A"), song("a3", "Song A", "Artist A", video = true), song("x", "Unrelated", "Nobody")),
            b.key to listOf(song("b1", "Song B", "Artist B")),
        )
        val rows = Ranking.suggestions(listOf(a, b), found)
        assertEquals("a1", rows[0].song?.videoId)
        assertTrue(rows.none { it.song?.videoId == "x" })
        // Sorted by confidence throughout.
        assertEquals(rows.map { it.confidence }.sortedDescending(), rows.map { it.confidence })
        assertTrue(rows.any { it.song?.videoId == "b1" })
    }

    @Test fun spedUpClipPutsTheSpedUpReleaseFirst() {
        val id = sound("Song A", "Artist A", speed = 1.26)
        val found = mapOf(id.key to listOf(song("orig", "Song A", "Artist A"), song("fast", "Song A (Sped Up)", "Artist A")))
        val rows = Ranking.suggestions(listOf(id), found)
        assertEquals("fast", rows[0].song?.videoId)
        assertEquals("orig", rows[1].song?.videoId)
        assertEquals("Original", rows[1].version)
    }

    @Test fun shazamAndYoutubeArtistSpellingsMatch() {
        val id = sound("Calm Down", "Rema & Selena Gomez")
        assertTrue(Ranking.fit(id, song("c", "Calm Down", "Rema, Selena Gomez")) > 0)
        assertEquals(0.0, Ranking.fit(id, song("d", "Calm Down", "Taylor Swift")), 0.0)
    }

    @Test fun uploadsThatNameTheArtistInTheTitleCount() {
        val id = sound("Sneaky Snitch", "Kevin MacLeod")
        val up = YtSong("u", "Sneaky Snitch (Kevin MacLeod) - Background Music (HD)", listOf(YtRef("Gaming Sound FX", "UCx")), isVideo = true)
        assertTrue(Ranking.fit(id, up) > 0)
        assertEquals(0.0, Ranking.fit(id, YtSong("v", "Sneaky Snitch", listOf(YtRef("Someone Else", "UCy")), isVideo = true)), 0.0)
    }

    @Test fun versionNames() {
        assertEquals("Sped up", Ranking.versionOf("Song (sped up)"))
        assertEquals("Slowed", Ranking.versionOf("Song - Slowed + Reverb"))
        assertEquals(null, Ranking.versionOf("Song"))
        assertEquals("Song", Ranking.baseTitle("Song (Sped Up Version)"))
    }

    @Test fun windowsAvoidTalkingAndSpreadOut() {
        val rate = ClipAudio.RATE
        val n = 40 * rate
        val music = FloatArray(n) { (Math.sin(it * 0.05) * 0.5).toFloat() }
        // Talking over the first 20 s only.
        val voice = FloatArray(n) { if (it < 20 * rate) (Math.sin(it * 0.3) * 0.8).toFloat() else 0f }
        val mix = FloatArray(n) { music[it] + voice[it] }
        val w = Identifier.pickWindows(mix, music, voice, forStem = false)
        assertTrue(w.first().start >= 18 * rate)
        for (i in w.indices) for (j in i + 1 until w.size) assertTrue(kotlin.math.abs(w[i].start - w[j].start) >= 6 * rate)
        assertEquals(1, Identifier.pickWindows(FloatArray(8 * rate), null, null, false).size)
    }

    @Test fun instrumentPlayingTheTuneIsNotTalking() {
        val r = ClipAudio.RATE; val n = 30 * r; val rnd = java.util.Random(1)
        fun pulse(t: Double) = 0.6 + 0.4 * kotlin.math.abs(Math.sin(Math.PI * t * 2.0))
        val music = FloatArray(n) { val t = it / r.toDouble(); (pulse(t) * 0.4 * Math.sin(2 * Math.PI * 110 * t) + 0.05 * rnd.nextGaussian()).toFloat() }
        // Talking: syllables, gaps between words and sentences.
        val talk = FloatArray(n) { val t = it / r.toDouble()
            val syl = maxOf(0.0, Math.sin(Math.PI * t * 4.3)); val word = if ((t * 1.3) % 1.0 < 0.7) 1.0 else 0.0; val sent = if (t % 4.0 < 3.0) 1.0 else 0.0
            (0.5 * syl * word * sent * Math.sin(2 * Math.PI * 180 * t)).toFloat() }
        // Singing: held notes in phrases with breaths.
        val sing = FloatArray(n) { val t = it / r.toDouble()
            val phrase = if (t % 5.0 < 3.6) 1.0 else 0.0; val gap = if ((t * 1.5) % 1.0 < 0.85) 1.0 else 0.0
            (0.4 * phrase * gap * Math.sin(2 * Math.PI * 260 * t)).toFloat() }
        // An instrument melody: notes one after another, swelling with the song.
        val inst = FloatArray(n) { val t = it / r.toDouble()
            val note = 220.0 * listOf(1.0, 1.25, 1.5, 1.33)[((t * 2).toInt()) % 4]; val attack = 0.75 + 0.25 * Math.exp(-((t * 2) % 1.0) * 3)
            (0.4 * pulse(t) * attack * Math.sin(2 * Math.PI * note * t)).toFloat() }
        assertTrue(Identifier.instrumentLike(inst, music))
        assertTrue(!Identifier.instrumentLike(talk, music))
        assertTrue(!Identifier.instrumentLike(sing, music))
    }

    @Test fun lyricsCleanup() {
        val t = Lyricist.cleanup(listOf("♪ I got a feeling ♪", "Thanks for watching!", "I got a feeling", "that tonight's gonna be a good night"))
        assertEquals("I got a feeling / that tonight's gonna be a good night", t)
        assertEquals("that tonight's gonna be a good night", Lyricist.searchPhrase(t))
    }

    @Test fun clipLinks() {
        assertTrue(SongFinder.isClipLink("Check this https://vm.tiktok.com/ZMabc123/"))
        assertTrue(SongFinder.isClipLink("https://www.tiktok.com/@user/video/7300000000000000000"))
        assertTrue(SongFinder.isClipLink("https://youtube.com/shorts/abcdefghijk?si=x"))
        assertTrue(!SongFinder.isClipLink("https://music.youtube.com/watch?v=abcdefghijk"))
        assertTrue(!SongFinder.isClipLink("https://www.youtube.com/watch?v=abcdefghijk"))
    }

    @Test fun tagFromYtDlp() {
        val tag = ClipLoader.tagFrom(org.json.JSONObject("""{"track":"Espresso","artist":"Sabrina Carpenter","title":"dance"}"""))
        assertEquals(ClipTag("Espresso", "Sabrina Carpenter"), tag)
        assertEquals(null, ClipLoader.tagFrom(org.json.JSONObject("""{"track":"original sound - someone","artist":"someone"}""")))
    }
}
