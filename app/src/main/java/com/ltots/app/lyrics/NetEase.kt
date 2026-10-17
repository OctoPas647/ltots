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
            for (i in 0 until songs.length()) {
                val o = songs.optJSONObject(i) ?: continue
                val id = o.optLong("id"); if (id <= 0L) continue
                val candTitle = o.optString("name")
                val candArtist = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                val durationSec = o.optLong("duration") / 1000.0
                val lyricBody = runCatching {
                    fetch("$base/api/song/lyric?id=$id&lv=1&kv=1&tv=-1")
                }.getOrNull() ?: continue
                val lrc = runCatching {
                    JSONObject(lyricBody).optJSONObject("lrc")?.optString("lyric").orEmpty()
                }.getOrDefault("")
                if (lrc.isBlank()) continue
                val c = Candidate(
                    title = candTitle, artist = candArtist, album = "",
                    durationSec = durationSec,
                    synced = lrc, plain = null, instrumental = false,
                )
                val score = SongMatch.score(q, artist, title, c)
                if (score > bestScore) { best = c to "$artist — $title"; bestScore = score }
            }
            if (best?.first?.synced != null) return best
        }
        return best
    }
}
