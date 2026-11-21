package com.ltots.app.elsewhere

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.data.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Finding the song another app plays among the songs on the phone, and reading service accounts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class OtherAppsTest {
    private fun track(id: Long, title: String, artist: String, ms: Long) =
        Track(id, Uri.parse("content://x/$id"), title, artist, "", 0, ms, null, null, null)

    private val phone = listOf(
        track(1, "Blinding Lights", "The Weeknd", 200_000),
        track(2, "Blinding Lights (Remix)", "The Weeknd", 230_000),
        track(3, "Heading Home (feat. Josh Golden)", "Alok x Ella Eyre x Kenny Dope feat. Neve", 180_000),
        track(4, "Bohemian Rhapsody - Remastered 2011", "Queen", 355_000),
    )

    @Test fun sameSongDespiteDecorations() {
        assertEquals(1L, OtherApps.match("Blinding Lights", "The Weeknd", 201_000, phone)?.id)
        // Spotify lists the artists one way, the file another; "feat." in the title is set aside.
        assertEquals(3L, OtherApps.match("Heading Home", "Alok, Ella Eyre", 181_000, phone)?.id)
        assertEquals(4L, OtherApps.match("Bohemian Rhapsody (Official Video)", "Queen", 0, phone)?.id)
    }

    @Test fun aRemixOrAnotherLengthIsNotTheSameSong() {
        assertEquals(1L, OtherApps.match("Blinding Lights", "The Weeknd", 0, phone)?.id)
        assertNull(OtherApps.match("Blinding Lights", "The Weeknd", 260_000, phone))
        assertNull(OtherApps.match("Blinding Lights", "Someone Else", 200_000, phone))
    }

    @Test fun artistLists() {
        assertTrue("ella eyre" in OtherApps.artistsOf("Alok x Ella Eyre & Kenny Dope feat. Neve"))
        assertTrue("kenny dope" in OtherApps.artistsOf("Alok x Ella Eyre & Kenny Dope feat. Neve"))
    }

    @Test fun soundCloudTitles() {
        val t = JSONObject("""{"title":"Fred again.. - Delilah (pull me out of this)","user":{"username":"uploader"}}""")
        assertEquals("Delilah (pull me out of this)" to "Fred again..", com.ltots.app.accounts.Accounts.scTitle(t))
        val u = JSONObject("""{"title":"Song","publisher_metadata":{"artist":"Band"},"user":{"username":"x"}}""")
        assertEquals("Song" to "Band", com.ltots.app.accounts.Accounts.scTitle(u))
    }

    @Test fun tokenFromTheWebPlayersRequests() {
        val h = JSONObject().put("authorization", "Bearer abc123")
        assertEquals("abc123", com.ltots.app.accounts.TokenGrabber.pick(Service.SPOTIFY, "https://api-partner.spotify.com/pathfinder/v1/query", h)?.bearer)
        assertNull(com.ltots.app.accounts.TokenGrabber.pick(Service.SPOTIFY, "https://www.google-analytics.com/x", h))
        val a = JSONObject().put("authorization", "Bearer dev").put("media-user-token", "user")
        val got = com.ltots.app.accounts.TokenGrabber.pick(Service.APPLE_MUSIC, "https://amp-api.music.apple.com/v1/me/library", a)
        assertEquals("dev", got?.bearer); assertEquals("user", got?.extra?.get("media-user-token"))
        val sc = com.ltots.app.accounts.TokenGrabber.pick(Service.SOUNDCLOUD, "https://api-v2.soundcloud.com/me?client_id=AbC9", JSONObject().put("authorization", "OAuth tok"))
        assertEquals("tok", sc?.bearer); assertEquals("AbC9", sc?.extra?.get("client_id"))
    }
}
