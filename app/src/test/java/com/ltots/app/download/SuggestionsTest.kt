package com.ltots.app.download

import com.ltots.app.ytm.Parser
import com.ltots.app.ytm.YtRef
import com.ltots.app.ytm.YtSong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionsTest {
    private fun song(id: String, title: String, artist: String, video: Boolean = false) =
        YtSong(id, title, listOf(YtRef(artist, null)), isVideo = video)

    @Test fun radiosTakeTurnsWithoutRepeatsOrSongsWeHave() {
        val a = listOf(song("a1", "One", "A"), song("a2", "Two", "A"), song("a3", "Three", "A"))
        val b = listOf(song("b1", "Uno", "B"), song("a2v", "A - Two (Official Video)", "A", video = true), song("b3", "Tres", "B"))
        val have = setOf("b1")
        val out = Suggestions.merge(listOf(a, b), { it.videoId in have }, limit = 4)
        // b1 is already here; "A - Two (Official Video)" is the same song as "Two" by A.
        assertEquals(listOf("a1", "a2", "a3", "b3"), out.map { it.videoId })
    }

    @Test fun sameSongWhateverItsSpelling() {
        assertEquals(Suggestions.key("Get Lucky", "Daft Punk"), Suggestions.key("Daft Punk - Get Lucky (Official Audio)", "Daft Punk, Pharrell Williams"))
    }

    @Test fun radioQueueKeepsThePlayedVersionsInOrder() {
        fun panel(id: String, title: String, type: String) = JSONObject()
            .put("videoId", id)
            .put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", title))))
            .put("longBylineText", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Artist"))))
            .put("navigationEndpoint", JSONObject().put("watchEndpointMusicSupportedConfigs",
                JSONObject().put("watchEndpointMusicConfig", JSONObject().put("musicVideoType", type))))
        val atv = "MUSIC_VIDEO_TYPE_ATV"; val omv = "MUSIC_VIDEO_TYPE_OMV"
        val contents = JSONArray()
            .put(JSONObject().put("playlistPanelVideoRenderer", panel("seed", "Seed", atv)))
            .put(JSONObject().put("playlistPanelVideoWrapperRenderer", JSONObject()
                .put("primaryRenderer", JSONObject().put("playlistPanelVideoRenderer", panel("v2", "Second", omv)))
                .put("counterpart", JSONArray().put(JSONObject().put("counterpartRenderer",
                    JSONObject().put("playlistPanelVideoRenderer", panel("a2", "Second", atv)))))))
            .put(JSONObject().put("playlistPanelVideoRenderer", panel("s3", "Third", atv)))
        val root = JSONObject().put("contents", JSONObject().put("playlistPanelRenderer", JSONObject().put("contents", contents)))
        val q = Parser.parseQueue(root)
        assertEquals(listOf("seed", "v2", "s3"), q.map { it.videoId })
        assertEquals(listOf(false, true, false), q.map { it.isVideo })
        assertEquals("Artist", q[2].artistText)
    }
}
