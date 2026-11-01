package com.ltots.app.data

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SongKindsTest {
    private fun file(
        name: String, folder: String, artist: String = Track.UNKNOWN, album: String = "", durationMs: Long = 200_000L,
        flags: Int = Track.KIND_MUSIC, mime: String = "audio/mpeg",
    ) = Track(1L, Uri.EMPTY, name.substringBeforeLast('.'), artist, album, 1L, durationMs, null, null, mime,
        folder = folder, fileName = name, mediaFlags = flags)

    private fun kind(t: Track) = SongKinds.byRules(t).first

    @Test fun recordingsVoiceNotesAndSoundsAreNotSongs() {
        assertEquals(SongKinds.Kind.NOT_SONG, kind(file("Recording_012.m4a", "Recordings/")))
        assertEquals(SongKinds.Kind.NOT_SONG, kind(file("Call@+359888123456.m4a", "MIUI/sound_recorder/call_rec/")))
        assertEquals(SongKinds.Kind.NOT_SONG, kind(file("PTT-20260101-WA0003.opus", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202601/")))
        assertEquals(SongKinds.Kind.NOT_SONG, kind(file("ding.ogg", "Notifications/", durationMs = 2_000L)))
        assertEquals(SongKinds.Kind.NOT_SONG, kind(file("anything.mp3", "Download/", flags = Track.KIND_RECORDING)))
        assertEquals(SongKinds.Kind.NOT_SONG, kind(file("voice.amr", "Download/", mime = "audio/amr")))
        assertEquals(SongKinds.Kind.NOT_SONG, kind(file("clip.mp3", "Download/", durationMs = 12_000L)))
    }

    @Test fun downloadsAndTaggedMusicAreSongs() {
        assertEquals(SongKinds.Kind.SONG, kind(file("Song.opus", "Music/LToTS/Coldplay/Viva La Vida/")))
        assertEquals(SongKinds.Kind.SONG, kind(file("01 Yellow.mp3", "Music/", artist = "Coldplay", album = "Parachutes")))
    }

    @Test fun strayFilesAreLeftToTheAi() {
        val t = file("audio_2026.mp3", "Download/")
        assertEquals(SongKinds.Kind.UNSURE, kind(t))
        assertFalse(SongKinds.isSong(t, mapOf(t.fileKey to SongKinds.Verdict(false, true, "Speech (AI)"))))
        assertTrue(SongKinds.isSong(t, mapOf(t.fileKey to SongKinds.Verdict(true, true, "Music (AI)"))))
    }

    /** CLAP scores measured on real audio (see SongKinds.decideAi). */
    @Test fun aiDecisionFollowsTheMeasuredRanges() {
        assertTrue(SongKinds.decideAi(mapOf("song" to 0.226f, "speech" to -0.087f, "noise" to -0.149f)).song) // solo piano
        assertTrue(SongKinds.decideAi(mapOf("song" to 0.323f, "speech" to -0.009f, "noise" to -0.129f)).song) // rap
        assertFalse(SongKinds.decideAi(mapOf("song" to 0.099f, "speech" to 0.142f, "noise" to -0.025f)).song) // talking
        assertFalse(SongKinds.decideAi(mapOf("song" to 0.051f, "speech" to 0.049f, "noise" to -0.115f)).song) // phone call
        assertFalse(SongKinds.decideAi(mapOf("song" to 0.060f, "speech" to 0.021f, "noise" to 0.166f)).song) // beep
    }

    @Test fun simpleCheckTellsHeldNotesFromSyllables() {
        val rate = 16_000
        // Music: a held chord.
        val music = FloatArray(rate * 8) { i -> (0.3 * (sin(2 * PI * 220 * i / rate) + sin(2 * PI * 277 * i / rate) + sin(2 * PI * 330 * i / rate))).toFloat() }
        // Speech-like: 200 ms bursts of changing pitch with gaps between them.
        val rnd = java.util.Random(1)
        val speech = FloatArray(rate * 8) { i ->
            val syl = i / (rate / 4)
            val on = (i % (rate / 4)) < rate / 6 && rnd.nextInt(5) != 0
            if (!on) (rnd.nextGaussian() * 0.002).toFloat()
            else (0.5 * sin(2 * PI * (120 + 40 * (syl % 5)) * i / rate) * sin(PI * (i % (rate / 6)) / (rate / 6))).toFloat()
        }
        assertTrue(SongKinds.musicLike(SongKinds.soundFeatures(music, rate)))
        assertFalse(SongKinds.musicLike(SongKinds.soundFeatures(speech, rate)))
    }
}
