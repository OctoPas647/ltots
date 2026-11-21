package com.ltots.app.watch

import com.ltots.app.ytm.YtRef
import com.ltots.app.ytm.YtSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMatchTest {
    private fun song(id: String, title: String, artist: String, sec: Int?, video: Boolean = false) =
        YtSong(id, title, listOf(YtRef(artist, null)), durationSec = sec, isVideo = video)

    @Test fun cleansVideoTitles() {
        assertEquals("dua lipa houdini", YouTubeMatch.clean("Dua Lipa - Houdini (Official Music Video)"))
        assertEquals("song", YouTubeMatch.clean("Song [Lyrics] (4K Remaster)"))
        assertEquals("Dua Lipa", YouTubeMatch.cleanChannel("Dua Lipa - Topic"))
        assertEquals("DuaLipa", YouTubeMatch.cleanChannel("DuaLipaVEVO"))
    }

    @Test fun queryAddsTheChannelOnlyWhenMissing() {
        assertEquals("Dua Lipa - Houdini", YouTubeMatch.query("Dua Lipa - Houdini (Official Music Video)", "Dua Lipa"))
        assertEquals("Dua Lipa Houdini", YouTubeMatch.query("Houdini", "Dua Lipa - Topic"))
    }

    @Test fun prefersTheVeryVideo() {
        val title = "Dua Lipa - Houdini (Official Music Video)"
        val c = listOf(
            song("audio", "Houdini", "Dua Lipa", 185),
            song("video", title, "Dua Lipa", 196, video = true),
        )
        assertEquals("video", YouTubeMatch.pick(title, "Dua Lipa", 196_000, c)?.videoId)
    }

    @Test fun takesTheSongWhenItIsTheSameLength() {
        val c = listOf(
            song("other", "Houdini (Remix)", "Someone", 240),
            song("audio", "Houdini", "Dua Lipa", 186),
        )
        assertEquals("audio", YouTubeMatch.pick("Dua Lipa - Houdini (Official Audio)", "Dua Lipa", 185_000, c)?.videoId)
    }

    @Test fun nothingWhenNoneIsTheSameSong() {
        val c = listOf(song("x", "Something Else", "Other", 185), song("y", "Houdini", "Dua Lipa", 260))
        assertNull(YouTubeMatch.pick("Dua Lipa - Houdini", "Dua Lipa", 185_000, c))
    }
}
