package com.ltots.app.identify

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.UUID
import java.util.zip.GZIPInputStream

/** What Shazam said one fingerprint is. */
data class ShazamHit(
    val key: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val year: String? = null,
    val coverUrl: String? = null,
    val isrc: String? = null,
    /** Short preview of the original song (Apple Music), when Shazam offers one. */
    val previewUrl: String? = null,
    /** Shazam's own estimate of how much faster/slower the sample is (0 = same speed). */
    val frequencySkew: Double = 0.0,
    val timeSkew: Double = 0.0,
    /** Where in the song the sample starts, in seconds. */
    val offsetSec: Double? = null,
)

/**
 * Talks to Shazam's recognition service the way its apps do (unofficial; may change).
 * Sends only the fingerprint from [ShazamSignature], never the audio itself.
 */
object Shazam {
    class ShazamException(message: String) : IOException(message)

    private val agents = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 14; 2410DPN6CC Build/UKQ1.240624.001)",
        "Dalvik/2.1.0 (Linux; U; Android 13; SM-S918B Build/TP1A.220624.014)",
        "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 6 Build/SD1A.210817.036)",
    )

    /** Asks Shazam about one fingerprint. Null when Shazam doesn't know it. */
    fun recognize(sig: ShazamSignature, language: String = "en", country: String = "US"): ShazamHit? {
        val body = JSONObject()
            .put("timezone", TimeZone.getDefault().id)
            .put("signature", JSONObject().put("uri", sig.toUri()).put("samplems", sig.durationMs))
            .put("timestamp", System.currentTimeMillis())
            .put("context", JSONObject())
            .put("geolocation", JSONObject())
        val u1 = UUID.randomUUID().toString().uppercase()
        val u2 = UUID.randomUUID().toString().uppercase()
        val url = "https://amp.shazam.com/discovery/v5/$language/$country/android/-/tag/$u1/$u2" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true" +
            "&hubv5minorversion=v5.1&hidelb=true&video=v3"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Accept-Language", language)
        c.setRequestProperty("Accept-Encoding", "gzip")
        c.setRequestProperty("X-Shazam-Platform", "IPHONE")
        c.setRequestProperty("X-Shazam-AppVersion", "14.1.0")
        c.setRequestProperty("User-Agent", agents.random())
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code == 429) throw ShazamException("Shazam is busy (too many requests). Try again in a minute.")
            if (code !in 200..299) throw ShazamException("Shazam returned $code")
            val raw = c.inputStream
            val stream = if (c.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
            return parse(JSONObject(stream.bufferedReader().use { it.readText() }))
        } finally {
            c.disconnect()
        }
    }

    internal fun parse(json: JSONObject): ShazamHit? {
        val track = json.optJSONObject("track") ?: return null
        val match = json.optJSONArray("matches")?.optJSONObject(0)
        val title = track.optString("title").ifBlank { return null }
        var album: String? = null
        var year: String? = null
        track.optJSONArray("sections")?.let { sections ->
            for (i in 0 until sections.length()) {
                val meta = sections.optJSONObject(i)?.optJSONArray("metadata") ?: continue
                for (j in 0 until meta.length()) {
                    val m = meta.optJSONObject(j) ?: continue
                    when (m.optString("title").lowercase()) {
                        "album" -> album = m.optString("text").ifBlank { null }
                        "released" -> year = m.optString("text").ifBlank { null }
                    }
                }
            }
        }
        var preview: String? = null
        track.optJSONObject("hub")?.optJSONArray("actions")?.let { a ->
            for (i in 0 until a.length()) {
                val uri = a.optJSONObject(i)?.optString("uri").orEmpty()
                if (uri.startsWith("http") && (uri.contains(".m4a") || uri.contains("audio"))) { preview = uri; break }
            }
        }
        val images = track.optJSONObject("images")
        return ShazamHit(
            key = track.optString("key"),
            title = title,
            artist = track.optString("subtitle"),
            album = album,
            year = year,
            coverUrl = images?.optString("coverarthq")?.ifBlank { null } ?: images?.optString("coverart")?.ifBlank { null },
            isrc = track.optString("isrc").ifBlank { null },
            previewUrl = preview,
            frequencySkew = match?.optDouble("frequencyskew", 0.0) ?: 0.0,
            timeSkew = match?.optDouble("timeskew", 0.0) ?: 0.0,
            offsetSec = match?.optDouble("offset")?.takeIf { !it.isNaN() },
        )
    }
}
