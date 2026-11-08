package com.ltots.app.identify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads a post's web page directly — the fallback for when the download engine can't handle a
 * link, and a second source of song tags it doesn't read:
 *  - the post's video or audio file (Open Graph tags, and the JSON TikTok, Instagram, Facebook
 *    and Snapchat embed in their pages),
 *  - the song the platform says is playing (TikTok's sound, Instagram's music sticker),
 *  - "♬ Song - Artist" style credits in the caption.
 */
object PageProbe {
    data class Page(val html: String, val mediaUrls: List<String>, val tag: ClipTag?, val title: String?)

    suspend fun read(context: Context, url: String): Page? = withContext(Dispatchers.IO) {
        val html = runCatching { fetchText(url, Platforms.cookieHeader(context, url)) }.getOrNull() ?: return@withContext null
        Page(html, mediaUrls(html), tagFrom(html), meta(html, "og:title"))
    }

    /** Downloads one of [urls] (the first that works) into [dir]. */
    suspend fun download(urls: List<String>, referer: String, dir: File, cookie: String?): File? = withContext(Dispatchers.IO) {
        for ((i, u) in urls.withIndex()) {
            val out = File(dir, "page$i.media")
            val ok = runCatching {
                val c = open(u, cookie, referer)
                try {
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    val type = c.contentType.orEmpty()
                    if (type.startsWith("text/") || type.contains("json")) throw IOException("Not media: $type")
                    c.inputStream.use { inp -> out.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024); var total = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            total += n; if (total > MAX_BYTES) break
                            o.write(buf, 0, n)
                        }
                    } }
                } finally { c.disconnect() }
                out.length() > 10_000
            }.getOrDefault(false)
            if (ok) return@withContext out
            out.delete()
        }
        null
    }

    private const val MAX_BYTES = 150L * 1024 * 1024

    // ---- media -------------------------------------------------------------------------------

    private val JSON_MEDIA = listOf(
        "playAddr", "downloadAddr", "playUrl", // TikTok (playUrl = the sound itself)
        "video_url", "audio_url", // Instagram, Threads
        "browser_native_hd_url", "browser_native_sd_url", "playable_url_quality_hd", "playable_url", // Facebook
        "mediaUrl", "videoUrl", "contentUrl", "stream_url", "hlsUrl", // Snapchat, others, JSON-LD
    )

    internal fun mediaUrls(html: String): List<String> {
        val out = LinkedHashSet<String>()
        // TikTok's sound first: it's the song itself, with no talking over it.
        Regex(""""music"\s*:\s*\{[^{}]*?"playUrl"\s*:\s*"([^"]+)"""").find(html)?.let { out += unescape(it.groupValues[1]) }
        for (p in listOf("og:audio:secure_url", "og:audio", "og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream")) {
            meta(html, p)?.let { out += it }
        }
        for (k in JSON_MEDIA) {
            Regex(""""$k"\s*:\s*"(https?:[^"]+)"""").findAll(html).take(3).forEach { out += unescape(it.groupValues[1]) }
        }
        return out.filter { it.startsWith("http") && !it.contains(".jpg") && !it.contains(".webp") && !it.contains(".png") && !it.endsWith(".m3u8") }
    }

    // ---- song tags ---------------------------------------------------------------------------

    internal fun tagFrom(html: String): ClipTag? {
        // TikTok: "music":{"id":…,"title":"Song","playUrl":…,"authorName":"Artist",…,"original":false}
        Regex(""""music"\s*:\s*\{([^{}]{0,4000})\}""").find(html)?.groupValues?.get(1)?.let { m ->
            val title = field(m, "title"); val artist = field(m, "authorName")
            val original = Regex(""""original"\s*:\s*true""").containsMatchIn(m)
            if (title != null && artist != null && !original && !ClipTag.isOriginalSound(title)) return ClipTag(title, artist, field(m, "album"))
        }
        // Instagram: clips_music_attribution_info / music_asset_info
        Regex(""""(?:clips_music_attribution_info|music_asset_info|music_info)"\s*:\s*\{([^{}]{0,4000})""").findAll(html).forEach { mm ->
            val m = mm.groupValues[1]
            val title = field(m, "song_name") ?: field(m, "title")
            val artist = field(m, "artist_name") ?: field(m, "display_artist")
            val original = Regex(""""uses_original_audio"\s*:\s*true""").containsMatchIn(m)
            if (title != null && artist != null && !original && !ClipTag.isOriginalSound(title)) return ClipTag(title, artist)
        }
        // Facebook reels: "music_title" / "music_artist" style fields.
        val fbTitle = Regex(""""(?:music_title|track_title)"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val fbArtist = Regex(""""(?:music_artist|artist_name)"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (fbTitle != null && fbArtist != null) return ClipTag(unescape(fbTitle), unescape(fbArtist))
        // A credit in the caption.
        return creditIn(meta(html, "og:description").orEmpty() + "\n" + meta(html, "description").orEmpty())
    }

    private val CREDIT = listOf(
        // ♬ Song - Artist   (TikTok/Instagram style; the sound's name comes first)
        Regex("""[♬♪♫🎵🎶]\s*([^\n·|#@]{2,80}?)\s+[-–—]\s+([^\n·|#@]{2,60})"""),
        // Song: X ... Artist: Y
        Regex("""(?i)\b(?:song|track|music)\s*[:：]\s*([^\n|#@]{2,80}?)\s*(?:[\n|·,]|\bby\b)\s*(?:artist\s*[:：]\s*)?([^\n|#@]{2,60})"""),
    )

    /** "♬ Blinding Lights - The Weeknd", "Song: X · Artist: Y" — a hint, not proof. */
    internal fun creditIn(text: String): ClipTag? {
        if (text.isBlank()) return null
        for (re in CREDIT) {
            val m = re.find(text) ?: continue
            val a = m.groupValues[1].trim(); val b = m.groupValues[2].trim().trimEnd('.', ',')
            if (ClipTag.isOriginalSound(a) || ClipTag.isOriginalSound(b)) continue
            if (a.equals("original audio", true) || b.equals("original audio", true)) continue
            return ClipTag(a, b, weak = true)
        }
        return null
    }

    /** "Artist - Song (Official Video)" in a YouTube-style title — the weakest hint. */
    internal fun titleHint(title: String?): ClipTag? {
        val t = title?.trim() ?: return null
        val m = Regex("""^(.{1,60}?)\s+[-–—]\s+(.{1,100})$""").find(t) ?: return null
        val artist = m.groupValues[1].trim()
        val song = com.ltots.app.lyrics.SongMatch.cleanTitle(m.groupValues[2])
        if (song.isBlank() || artist.isBlank()) return null
        return ClipTag(song, artist, weak = true)
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun field(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifBlank { null }

    private fun meta(html: String, name: String): String? {
        val n = Regex.escape(name)
        return (Regex("""<meta[^>]+(?:property|name)=["']$n["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']$n["']""", RegexOption.IGNORE_CASE).find(html))
            ?.groupValues?.get(1)?.let(::htmlUnescape)?.ifBlank { null }
    }

    internal fun unescape(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> if (i + 5 < s.length) { sb.append(s.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?'); i += 6; continue }
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> Unit
                    else -> sb.append(n)
                }
                i += 2; continue
            }
            sb.append(c); i++
        }
        return htmlUnescape(sb.toString())
    }

    private fun htmlUnescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun open(url: String, cookie: String?, referer: String? = null) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("User-Agent", Platforms.BROWSER_UA)
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        cookie?.let { setRequestProperty("Cookie", it) }
        referer?.let { setRequestProperty("Referer", it) }
    }

    private fun fetchText(url: String, cookie: String?): String {
        val c = open(url, cookie)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { inp ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                while (out.size() < 4 * 1024 * 1024) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally { c.disconnect() }
    }
}
