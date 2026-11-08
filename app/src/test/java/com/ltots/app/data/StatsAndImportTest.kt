package com.ltots.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.recs.AccountTaste
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Calendar

/** All-time listening numbers, and importing history from Spotify, Last.fm and other apps. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StatsAndImportTest {
    @Before fun setUp() {
        Store.init(ApplicationProvider.getApplicationContext())
        Store.userFile("stats").deleteRecursively()
        ListenStats.reload()
    }

    private fun at(y: Int, m: Int, d: Int, h: Int) = Calendar.getInstance().apply { clear(); set(y, m, d, h, 0) }.timeInMillis

    @Test fun playsAndTimeAddUpPerSongAndArtist() {
        ListenStats.addPlay("Blinding Lights", "The Weeknd", "After Hours", 3_000, at(2026, 2, 1, 21))
        ListenStats.addTime("Blinding Lights", "The Weeknd", 197_000, at(2026, 2, 1, 21))
        ListenStats.addPlay("Blinding Lights", "The Weeknd", "", 200_000, at(2026, 5, 9, 8))
        ListenStats.addPlay("Save Your Tears", "The Weeknd", "", 215_000, at(2026, 5, 9, 9))
        ListenStats.addPlay("Levitating", "Dua Lipa", "", 60_000, at(2026, 6, 2, 9))
        val s = ListenStats.summary(2026)
        assertEquals(4, s.plays)
        assertEquals("Blinding Lights", s.songs.first().title)
        assertEquals(2, s.songs.first().plays)
        assertEquals(400_000L, s.songs.first().ms)
        assertEquals("The Weeknd", s.artists.first().name)
        assertEquals(2, s.artists.first().songs)
        assertEquals(3, s.activeDays)
        assertEquals(415_000L, s.hours[8] + s.hours[9] - 60_000L)
        // A play in another year is that year's.
        ListenStats.addPlay("Levitating", "Dua Lipa", "", 60_000, at(2025, 11, 31, 23))
        assertEquals(4, ListenStats.summary(2026).plays)
        assertEquals(5, ListenStats.summary(null).plays)
    }

    @Test fun spotifyExportsBothKinds() {
        val basic = """[{"endTime":"2025-03-01 21:14","artistName":"Arctic Monkeys","trackName":"505","msPlayed":253000},
            {"endTime":"2025-03-01 21:15","artistName":"Arctic Monkeys","trackName":"505","msPlayed":1200}]"""
        val extended = """[{"ts":"2024-07-02T18:01:55Z","ms_played":180000,"master_metadata_track_name":"Do I Wanna Know?",
            "master_metadata_album_artist_name":"Arctic Monkeys","master_metadata_album_album_name":"AM"},
            {"ts":"2024-07-02T18:05:00Z","ms_played":600000,"master_metadata_track_name":null,"episode_name":"A podcast"}]"""
        val a = HistoryImport.parse("StreamingHistory_music_0.json", basic)
        assertEquals(1, a.size) // the 1 s skip doesn't count
        assertEquals("505", a[0].title); assertEquals(253_000L, a[0].ms)
        val b = HistoryImport.parse("Streaming_History_Audio_2024.json", extended)
        assertEquals(1, b.size) // the podcast isn't a song
        assertEquals("AM", b[0].album)
        // Importing the same file twice counts its plays once.
        assertEquals(1, ListenStats.import(b))
        assertEquals(0, ListenStats.import(HistoryImport.parse("Streaming_History_Audio_2024.json", extended)))
        assertEquals(1, ListenStats.summary(2024).plays)
    }

    @Test fun csvFromOtherApps() {
        val csv = "Song Name,Artist Name,Event Start Timestamp,Play Duration Milliseconds\n" +
            "\"Hello, World\",Someone,2026-01-05T10:00:00Z,190000\n" +
            "Short,Someone,2026-01-05T10:04:00Z,900\n"
        val p = HistoryImport.parse("Apple Music Play Activity.csv", csv)
        assertEquals(1, p.size)
        assertEquals("Hello, World", p[0].title)
        assertEquals("Someone", p[0].artist)
    }

    @Test fun lastFmLibraryPage() {
        val html = """
            <ul><li class="pagination-page"><a href="?page=1">1</a></li><li class="pagination-page"><a href="?page=37">37</a></li></ul>
            <tr class="chartlist-row chartlist-row--with-artist">
              <td class="chartlist-name"><a href="/music/Daft+Punk/_/One+More+Time" title="One More Time">One More Time</a></td>
              <td class="chartlist-artist"><a href="/music/Daft+Punk">Daft Punk</a></td>
              <td class="chartlist-timestamp chartlist-timestamp--lang-en"><span title="Tuesday 3 Nov 2026, 10:41pm">2 hours ago</span></td>
            </tr>
            <tr class="chartlist-row chartlist-row--with-artist">
              <td class="chartlist-name"><a href="#" title="Rock &amp; Roll">Rock &amp; Roll</a></td>
              <td class="chartlist-artist"><a href="#">Led Zeppelin</a></td>
              <td class="chartlist-timestamp"><span title="Monday 2 Nov 2026, 9:05am">yesterday</span></td>
            </tr>"""
        assertEquals(37, HistoryImport.lastFmPages(html))
        val s = HistoryImport.lastFmScrobbles(html)
        assertEquals(2, s.size)
        assertEquals("Daft Punk", s[0].artist)
        assertEquals("Rock & Roll", s[1].title)
        val c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = s[0].at }
        assertEquals(22, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun youTubeMusicHistoryGroups() {
        val now = at(2026, 10, 4, 12)
        val (from, to) = HistoryImport.periodOf("Yesterday", now)!!
        assertEquals(at(2026, 10, 3, 0), from); assertEquals(at(2026, 10, 4, 0), to)
        val (sep, _) = HistoryImport.periodOf("September", now)!!
        assertEquals(at(2026, 8, 1, 0), sep)
        // A month later in the year than now is last year's.
        val (dec, _) = HistoryImport.periodOf("December", now)!!
        assertEquals(at(2025, 11, 1, 0), dec)
        assertNotNull(HistoryImport.periodOf("March 2025", now))
    }

    @Test fun instagramReelSongs() {
        val page = JSONObject("""{"items":[{"media":{"clips_metadata":{"music_info":{"music_asset_info":{"title":"Espresso","display_artist":"Sabrina Carpenter"}}}}},
            {"media":{"clips_metadata":{"original_sound_info":{"original_audio_title":"Original audio"}}}},
            {"media":{"clips_metadata":{"music_info":{"music_asset_info":{"title":"Espresso","display_artist":"Sabrina Carpenter"}}}}}]}""")
        val songs = AccountTaste.igSongs(page)
        assertEquals(listOf("Espresso" to "Sabrina Carpenter"), songs)
        assertTrue(AccountTaste.igSongs(JSONObject("{}")).isEmpty())
    }
}
