package com.ltots.app.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * NetEase Cloud Music as a second lyrics source. Its public web API can return time-synced
 * LRC lyrics for songs LRCLIB doesn't have, especially remixes and regional releases.
 */
class NetEase(private val base: String = "https://music.163.com") {
    class NetworkError(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetch(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) LToTS/1.0")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw NetworkError("NetEase answered $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: NetworkError) {
            throw e
        } catch (e: Exception) {
            throw NetworkError(e.message ?: "network error", e)
        } finally {
            c.disconnect()
        }
    }

    /** Best matching song with synced lyrics for [q], or null. */
    fun find(q: SongQuery): Pair<Candidate, String>? {
        val tries = SongMatch.interpretations(q)
        var best: Pair<Candidate, String>? = null
        var bestScore = 0.0
        for ((artist, title) in tries.take(3)) {
            val query = if (artist != null) "$artist $title" else title
            val body = runCatching {
                fetch("$base/api/search/get/web?s=${enc(query)}&type=1&offset=0&limit=10")
            }.getOrNull() ?: continue
            val songs = runCatching {
                JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
            }.getOrNull() ?: continue
            // By name, artist and length first; only the likeliest few have their lyrics read.
            val likely = (0 until songs.length()).mapNotNull { i ->
                val o = songs.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id"); if (id <= 0L) return@mapNotNull null
                val who = o.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty().joinToString(", ")
                val c = Candidate(o.optString("name"), who, o.optJSONObject("album")?.optString("name").orEmpty(), o.optLong("duration") / 1000.0, null, null, false)
                val fit = SongMatch.identity(q, artist, title, c)
                if (fit > 0) Triple(id, c, fit) else null
            }.sortedByDescending { it.third }.take(3)
            for ((id, c0, fit) in likely) {
                val lyricBody = runCatching {
                    fetch("$base/api/song/lyric?id=$id&lv=1&kv=1&tv=-1")
                }.getOrNull() ?: continue
                val lrc = runCatching {
                    JSONObject(lyricBody).optJSONObject("lrc")?.optString("lyric").orEmpty()
                }.getOrDefault("")
                if (lrc.isBlank()) continue
                val c = c0.copy(synced = lrc)
                if (!LyricsQuality.ok(q, c, fromNetEase = true)) continue
                if (fit > bestScore) { best = c to "$artist — $title"; bestScore = fit }
            }
            if (best?.first?.synced != null) return best
        }
        return best
    }
}
