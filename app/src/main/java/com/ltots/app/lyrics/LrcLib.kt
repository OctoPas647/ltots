package com.ltots.app.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * LRCLIB (lrclib.net): a free, open lyrics database with time-synced lyrics. No account or key.
 * Only the song's name, artist, album and length are sent.
 */
class LrcLib(private val base: String = "https://lrclib.net/api") {
    class NetworkError(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** One retry when the service says it's busy. */
    private fun fetch(url: String): String? = try {
        fetchOnce(url)
    } catch (e: Busy) {
        Thread.sleep(1200)
        try { fetchOnce(url) } catch (e2: Busy) { throw NetworkError(e2.message ?: "busy") }
    }

    private class Busy(msg: String) : Exception(msg)

    private fun fetchOnce(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "LToTS (https://github.com/OctoPas647/ltots)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = c.responseCode
            if (code == 404) return null
            if (code == 503 || code == 429) throw Busy("LRCLIB is busy ($code)")
            if (code !in 200..299) throw NetworkError("LRCLIB answered $code (${url.substringAfter("/api/").substringBefore('?')})")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            return null
        } catch (e: NetworkError) {
            throw e
        } catch (e: Busy) {
            throw e
        } catch (e: Exception) {
            throw NetworkError(e.message ?: "network error", e)
        } finally {
            c.disconnect()
        }
    }

    private fun candidate(o: JSONObject) = Candidate(
        title = o.optString("trackName"),
        artist = o.optString("artistName"),
        album = o.optString("albumName"),
        durationSec = o.optDouble("duration", 0.0),
        synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" },
        plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" },
        instrumental = o.optBoolean("instrumental"),
    )

    /** Exact lookup by name, artist and length. */
    fun get(artist: String, title: String, album: String?, durationSec: Long?): Candidate? {
        val url = buildString {
            append(base).append("/get?artist_name=").append(enc(artist)).append("&track_name=").append(enc(title))
            if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
            if (durationSec != null && durationSec > 0) append("&duration=").append(durationSec)
        }
        return fetch(url)?.let { candidate(JSONObject(it)) }
    }

    /** Free search; results are checked by [SongMatch] before use. */
    fun search(artist: String?, title: String): List<Candidate> {
        val url = if (artist != null) "$base/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        else "$base/search?q=${enc(title)}"
        val body = fetch(url) ?: return emptyList()
        val arr = JSONArray(body)
        return List(arr.length()) { candidate(arr.getJSONObject(it)) }
    }

    /**
     * Finds lyrics for [q]: exact lookups first (fast, one request), then searches, trying each
     * reading of the tags. Returns null when nothing trustworthy turns up.
     */
    fun find(q: SongQuery): Pair<Candidate, String>? {
        val secs = (q.durationMs / 1000).takeIf { it > 0 }
        val tries = SongMatch.interpretations(q)
        var reached = false
        var lastError: NetworkError? = null
        fun <T> attempt(block: () -> T): T? = try {
            block().also { reached = true }
        } catch (e: NetworkError) {
            lastError = e; null
        }
        for ((artist, title) in tries) {
            if (artist == null) continue
            val c = attempt { get(artist, title, q.album, secs) } ?: attempt { get(artist, title, null, secs) }
            if (c != null && SongMatch.score(q, artist, title, c) > 0) return c to "$artist — $title"
        }
        for ((artist, title) in tries.take(3)) {
            // The service sometimes refuses a search under load; once it has answered us at all,
            // that only means "not found this time".
            val results = attempt { search(artist, title) } ?: emptyList()
            val best = SongMatch.best(q, artist, title, results)
            if (best != null) return best to "${best.artist} — ${best.title}"
        }
        // Never got through at all: report it, so the song is tried again later.
        if (!reached) throw lastError ?: NetworkError("LRCLIB unreachable")
        return null
    }
}
