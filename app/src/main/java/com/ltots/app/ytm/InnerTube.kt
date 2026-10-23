package com.ltots.app.ytm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Minimal YouTube Music client (the same internal API the music.youtube.com website uses).
 * No account: everything here is what a logged-out visitor sees.
 */
object InnerTube {
    private const val BASE = "https://music.youtube.com/youtubei/v1/"
    private const val FALLBACK_VERSION = "1.20261013.15.00"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    @Volatile private var clientVersion = FALLBACK_VERSION
    @Volatile private var versionCheckedAt = 0L
    @Volatile private var visitorData: String? = null

    private val country: String get() = Locale.getDefault().country.ifBlank { "US" }

    class YtmException(message: String) : IOException(message)

    /** Keeps the web client version current, as YouTube rejects very old ones. Once a day. */
    private fun refreshVersion() {
        if (System.currentTimeMillis() - versionCheckedAt < 24 * 3600_000L) return
        versionCheckedAt = System.currentTimeMillis()
        runCatching {
            val c = (URL("https://music.youtube.com/").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "en")
                connectTimeout = 10_000; readTimeout = 15_000
            }
            val html = c.inputStream.bufferedReader().use { it.readText() }
            Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { clientVersion = it }
            Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { visitorData = it }
        }
    }

    private fun post(endpoint: String, body: JSONObject, query: String = ""): JSONObject {
        refreshVersion()
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", clientVersion)
            .put("hl", "en")
            .put("gl", country)
        visitorData?.let { client.put("visitorData", it) }
        body.put("context", JSONObject().put("client", client))
        val c = (URL("$BASE$endpoint?prettyPrint=false$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            visitorData?.let { setRequestProperty("X-Goog-Visitor-Id", it) }
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw YtmException("YouTube Music returned $code")
        val json = JSONObject(text)
        json.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotBlank() }?.let {
            if (visitorData == null) visitorData = it
        }
        return json
    }

    suspend fun search(query: String, params: String? = null): SearchResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", query)
        params?.let { body.put("params", it) }
        Parser.parseSearch(post("search", body))
    }

    suspend fun searchMore(continuation: String): SearchResult = withContext(Dispatchers.IO) {
        Parser.parseSearch(post("search", JSONObject(), "&ctoken=$continuation&continuation=$continuation&type=next"))
    }

    suspend fun album(browseId: String): Collection = withContext(Dispatchers.IO) {
        val c = Parser.parseCollection(browseId, post("browse", JSONObject().put("browseId", browseId)))
        if (c.tracks.isEmpty()) throw YtmException("This album isn't available")
        c
    }

    /** Full playlist, following "load more" pages up to [maxTracks]. */
    suspend fun playlist(playlistId: String, maxTracks: Int = 5000): Collection = withContext(Dispatchers.IO) {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        var page = Parser.parseCollection(browseId, post("browse", JSONObject().put("browseId", browseId)))
        val tracks = page.tracks.toMutableList()
        var cont = page.continuation
        var guard = 0
        while (cont != null && tracks.size < maxTracks && guard++ < 100) {
            val (more, next) = Parser.parseContinuationTracks(post("browse", JSONObject().put("continuation", cont)))
            if (more.isEmpty()) break
            tracks += more
            cont = next
        }
        if (tracks.isEmpty()) throw YtmException("This playlist is empty or private")
        page.copy(tracks = tracks.distinctBy { it.videoId }, continuation = null)
    }

    suspend fun artist(channelId: String): ArtistPage = withContext(Dispatchers.IO) {
        Parser.parseArtist(channelId, post("browse", JSONObject().put("browseId", channelId)))
    }

    /** Title, artists, album, year and square cover for one song. */
    suspend fun song(videoId: String): YtSong? = withContext(Dispatchers.IO) {
        Parser.parseNext(videoId, post("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true)))
    }

    /**
     * Songs like [videoId]: its "radio", the endless queue YouTube Music plays after a song,
     * without the song itself.
     */
    suspend fun radio(videoId: String): List<YtSong> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("videoId", videoId).put("playlistId", "RDAMVM$videoId").put("isAudioOnly", true)
        Parser.parseQueue(post("next", body)).filter { it.videoId != videoId }
    }

    /** The song YouTube Music finds first for [query] (official audio over videos), or null. */
    suspend fun findSong(query: String): YtSong? {
        val songs = search(query).sections.flatMap { it.items }.filterIsInstance<YtSong>()
        return songs.firstOrNull { !it.isVideo } ?: songs.firstOrNull()
    }

    /**
     * For a music video, finds the official audio release of the same song: YouTube Music's
     * own pairing first, then a "Songs" search matched on title, artist and length.
     * Returns null if there is no confident match (the video is then used as is).
     */
    suspend fun officialAudio(video: YtSong): YtSong? = withContext(Dispatchers.IO) {
        runCatching {
            Parser.parseCounterpart(video.videoId, post("next", JSONObject().put("videoId", video.videoId).put("isAudioOnly", true)))
        }.getOrNull()?.let { return@withContext it }

        val artist = video.artists.firstOrNull()?.name
        val wanted = Parser.normalizeTitle(video.title, artist)
        if (wanted.isBlank()) return@withContext null
        val query = listOfNotNull(wanted, artist).joinToString(" ")
        val first = runCatching { search(query) }.getOrNull() ?: return@withContext null
        val songsChip = first.chips.firstOrNull { it.text.equals("Songs", true) }?.params
        val pool = buildList {
            addAll(first.sections.flatMap { it.items }.filterIsInstance<YtSong>())
            if (songsChip != null) runCatching { search(query, songsChip) }.getOrNull()
                ?.let { r -> addAll(r.sections.flatMap { it.items }.filterIsInstance<YtSong>()) }
        }
        pool.filter { !it.isVideo && it.videoId != video.videoId }.firstOrNull { cand ->
            val sameTitle = Parser.normalizeTitle(cand.title, cand.artists.firstOrNull()?.name) == wanted
            val sameArtist = artist == null || cand.artists.any { it.name.equals(artist, true) } ||
                cand.artistText.contains(artist, ignoreCase = true)
            val closeLength = video.durationSec == null || cand.durationSec == null ||
                kotlin.math.abs(video.durationSec - cand.durationSec) <= 12
            sameTitle && sameArtist && closeLength
        }
    }
}
