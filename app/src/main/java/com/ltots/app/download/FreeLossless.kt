package com.ltots.app.download

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free lossless downloads from the Internet Archive: a legal, key-less catalog of Creative
 * Commons, public-domain and independently uploaded music, much of it in FLAC.
 */
class FreeLossless(private val base: String = "https://archive.org") {

    class Hit(
        val fileUrl: String,
        val title: String,
        val creator: String,
        val durationSec: Double,
        val extension: String,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetch(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "LToTS/1.0 (https://github.com/OctoPas647/ltots)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Looks for a FLAC file that clearly matches [meta]: title and artist must be similar and
     * the length within a few seconds, so we never pass off a different recording as the song.
     */
    fun find(meta: TrackMeta): Hit? {
        val title = meta.title.trim()
        val artist = meta.artist.trim()
        if (title.isBlank()) return null
        val q = buildString {
            append("mediatype:audio AND format:Flac")
            append(" AND title:\"").append(title.replace("\"", "")).append("\"")
            if (artist.isNotBlank() && artist != "Unknown artist") {
                append(" AND creator:\"").append(artist.replace("\"", "")).append("\"")
            }
        }
        val searchUrl = "$base/advancedsearch.php?q=${enc(q)}&fl%5B%5D=identifier&rows=8&output=json"
        val ids = runCatching {
            JSONObject(fetch(searchUrl)).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        }.getOrNull() ?: return null

        for (i in 0 until ids.length()) {
            val id = ids.optJSONObject(i)?.optString("identifier") ?: continue
            if (id.isBlank()) continue
            val metaJson = runCatching { JSONObject(fetch("$base/metadata/$id")) }.getOrNull() ?: continue
            val md = metaJson.optJSONObject("metadata") ?: continue
            val itemTitle = md.optString("title")
            val creator = when (val c = md.opt("creator")) {
                is String -> c
                is JSONArray -> c.optString(0)
                else -> ""
            }
            val files = metaJson.optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                val name = f.optString("name")
                if (!name.endsWith(".flac", true)) continue
                val length = parseLength(f.optString("length")) ?: continue
                val want = meta.durationSec.takeIf { it > 0 } ?: return@find null
                if (kotlin.math.abs(length - want) > 4.0) continue
                val fileTitle = f.optString("title").ifBlank { itemTitle }
                val tScore = com.ltots.app.lyrics.SongMatch.similarity(title, fileTitle)
                val aScore = if (artist.isBlank()) 0.7 else com.ltots.app.lyrics.SongMatch.similarity(artist, creator)
                if (tScore < 0.6 || aScore < 0.5) continue
                return Hit(
                    fileUrl = "$base/download/$id/${enc(name)}",
                    title = fileTitle.ifBlank { title },
                    creator = creator.ifBlank { artist },
                    durationSec = length,
                    extension = "flac",
                )
            }
        }
        return null
    }

    /** Archive lengths are either seconds ("213.45") or "mm:ss". */
    private fun parseLength(s: String): Double? = when {
        s.isBlank() -> null
        ":" in s -> s.split(":").mapNotNull { it.toDoubleOrNull() }.reduce { a, b -> a * 60 + b }
        else -> s.toDoubleOrNull()
    }
}
