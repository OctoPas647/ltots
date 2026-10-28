package com.ltots.app.identify

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PlatformsTest {
    @Test fun recognisesThePlatforms() {
        assertEquals(Platform.INSTAGRAM, Platform.of("https://www.instagram.com/reel/abc/"))
        assertEquals(Platform.TIKTOK, Platform.of("https://vm.tiktok.com/ZMabc/"))
        assertEquals(Platform.FACEBOOK, Platform.of("https://fb.watch/xyz/"))
        assertEquals(Platform.SNAPCHAT, Platform.of("https://www.snapchat.com/spotlight/W7_abc"))
        assertEquals(Platform.X, Platform.of("https://twitter.com/a/status/1"))
        assertEquals(Platform.YOUTUBE_MUSIC, Platform.of("https://music.youtube.com/watch?v=x"))
        assertEquals(Platform.YOUTUBE, Platform.of("https://youtu.be/x"))
        assertEquals(Platform.OTHER, Platform.of("https://example.org/v"))
    }

    @Test fun shareLinksAreTidied() {
        assertEquals("https://www.instagram.com/reel/C1abc/", Platforms.tidy("https://www.instagram.com/reels/C1abc/?igsh=MWQ1ZGUxMzBkMA=="))
        assertEquals("https://www.instagram.com/reel/C1abc/", Platforms.tidy("https://www.instagram.com/someone/reel/C1abc/?utm_source=ig_web_copy_link"))
        assertEquals("https://www.tiktok.com/@a/video/123", Platforms.tidy("https://www.tiktok.com/@a/video/123?is_from_webapp=1&sender_device=pc&_r=1"))
        assertEquals("https://www.youtube.com/watch?v=abc&t=42", Platforms.tidy("https://www.youtube.com/watch?v=abc&si=xyz&t=42"))
        assertEquals("https://www.facebook.com/reel/123", Platforms.tidy("https://m.facebook.com/reel/123?mibextid=abc"))
    }

    @Test fun songTagsAreReadFromPages() {
        val tiktok = """{"music":{"id":"1","title":"Blinding Lights","playUrl":"https:\/\/x.mp3","authorName":"The Weeknd","original":false}}"""
        assertEquals(ClipTag("Blinding Lights", "The Weeknd"), PageProbe.tagFrom(tiktok))
        val original = """{"music":{"title":"original sound - someone","authorName":"someone","original":true}}"""
        assertNull(PageProbe.tagFrom(original))
        val ig = """{"clips_music_attribution_info":{"artist_name":"Dua Lipa","song_name":"Houdini","uses_original_audio":false}}"""
        assertEquals(ClipTag("Houdini", "Dua Lipa"), PageProbe.tagFrom(ig))
        assertEquals("https://x.mp3", PageProbe.mediaUrls(tiktok).first())
    }

    @Test fun captionsAndTitlesGiveWeakHints() {
        val c = PageProbe.creditIn("best summer ever ☀️ ♬ Espresso - Sabrina Carpenter")!!
        assertEquals("Espresso", c.title); assertEquals("Sabrina Carpenter", c.artist); assertTrue(c.weak)
        assertNull(PageProbe.creditIn("♬ original sound - dancer123"))
        val t = PageProbe.titleHint("Daft Punk - Get Lucky (Official Video)")!!
        assertEquals("Daft Punk", t.artist); assertEquals("Get Lucky", t.title)
    }
}
