package com.ltots.app.identify

import com.ltots.app.ytm.LinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRouteTest {
    private fun find(t: String) = assertTrue(t, LinkRoute.of(t) is LinkRoute.FindSong)

    @Test fun videoLinksFromAnyPlatformStartTheSongFinder() {
        find("https://vm.tiktok.com/ZMabc123/")
        find("Look at this! https://www.tiktok.com/@user/video/7300000000000000000?is_from_webapp=1")
        find("https://youtube.com/shorts/abcdefghijk?si=xyz")
        find("https://www.youtube.com/watch?v=abcdefghijk")
        find("https://youtu.be/abcdefghijk")
        find("youtu.be/abcdefghijk")
        find("https://www.instagram.com/reel/C1abc/")
        find("https://www.facebook.com/watch/?v=123")
        find("https://vimeo.com/123456")
        find("https://www.twitch.tv/videos/123")
        assertEquals("https://vm.tiktok.com/ZMabc123/", (LinkRoute.of("Check https://vm.tiktok.com/ZMabc123/ lol") as LinkRoute.FindSong).url)
    }

    @Test fun youtubeMusicLinksWorkAsBefore() {
        assertEquals(LinkRoute.Music(LinkTarget.Song("abcdefghijk")), LinkRoute.of("https://music.youtube.com/watch?v=abcdefghijk&si=1"))
        assertTrue(LinkRoute.of("https://music.youtube.com/browse/MPREb_abc") is LinkRoute.Music)
        assertTrue(LinkRoute.of("https://music.youtube.com/playlist?list=PLabc") is LinkRoute.Music)
        // A YouTube playlist or channel is still a music page, not one video.
        assertTrue(LinkRoute.of("https://www.youtube.com/playlist?list=PLabc") is LinkRoute.Music)
        assertTrue(LinkRoute.of("https://www.youtube.com/channel/UCabc") is LinkRoute.Music)
    }

    @Test fun wordsSearch() {
        assertEquals(LinkRoute.Search("daft punk one more time"), LinkRoute.of("  daft punk one more time "))
    }
}
