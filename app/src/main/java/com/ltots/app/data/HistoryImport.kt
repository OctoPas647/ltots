package com.ltots.app.data

import android.content.Context
import android.net.Uri
import com.ltots.app.ytm.InnerTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Listening history from other apps, added to the all-time numbers ([ListenStats]) so Wrapped and
 * the stats include them:
 *
 *  - **YouTube Music**: the signed-in account's history (YouTube keeps a few months; it only
 *    says roughly when — "Yesterday", "This week", "October" — so plays are put in that period).
 *  - **Spotify**: the files from Spotify's "Download your data" (Account privacy page): the
 *    zip as it comes, or the StreamingHistory / Streaming_History_Audio JSON files in it. Exact
 *    times and how long each play lasted.
 *  - **Last.fm**: everything scrobbled, by username (read from the user's public library pages).
 *  - **Any other app**: a CSV or JSON listing plays (Apple Music's Play Activity, Deezer,
 *    SoundCloud, export tools…), recognised by its column names.
 *
 * The same play imported twice counts once.
 */
object HistoryImport {
    /** Plays without a length (Last.fm, YouTube Music): about a typical song. */
    private const val TYPICAL_MS = 200_000L

    class Result(val found: Int, val added: Int)

    // ---- YouTube Music ---------------------------------------------------------------------

    suspend fun youTubeMusic(): Result = withContext(Dispatchers.IO) {
        val groups = InnerTube.historyGroups()
        val now = System.currentTimeMillis()
        val plays = ArrayList<ImportedPlay>()
        for ((title, songs) in groups) {
            val (from, to) = periodOf(title, now) ?: continue
            songs.forEachIndexed { i, s ->
                // Newest first: spread the group's songs over its period, newest at its end.
                val at = to - ((to - from) * (i + 0.5) / songs.size).toLong()
                plays += ImportedPlay(s.title, s.artists.firstOrNull()?.name ?: "", s.album?.name ?: "", at,
                    s.durationSec?.let { it * 1000L } ?: TYPICAL_MS, "ytm")
            }
        }
        Result(plays.size, ListenStats.import(plays))
    }

    /** Start and end (ms) of a YouTube Music history group's title. */
    internal fun periodOf(title: String, now: Long): Pair<Long, Long>? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        fun dayStart(daysAgo: Int) = (cal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val t = title.trim().lowercase(Locale.ROOT)
        return when (t) {
            "today" -> dayStart(0) to now
            "yesterday" -> dayStart(1) to dayStart(0)
            "this week" -> dayStart(6) to dayStart(1)
            "last week" -> dayStart(13) to dayStart(6)
            "this month" -> dayStart(29) to dayStart(13)
            "last month" -> dayStart(59) to dayStart(29)
            else -> {
                // "October" or "October 2025".
                val parts = t.split(' ')
                val month = runCatching { SimpleDateFormat("MMMM", Locale.ENGLISH).parse(parts[0]) }.getOrNull() ?: return null
                val m = Calendar.getInstance().apply { time = month }.get(Calendar.MONTH)
                var year = parts.getOrNull(1)?.toIntOrNull() ?: cal.get(Calendar.YEAR)
                if (parts.size == 1 && m > cal.get(Calendar.MONTH)) year--
                val start = Calendar.getInstance().apply { clear(); set(year, m, 1) }
                val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
                start.timeInMillis to minOf(end.timeInMillis, now)
            }
        }
    }

    // ---- files (Spotify, CSV, JSON) -------------------------------------------------------

    /** Reads every file picked (Spotify's zip, JSON or CSV files) and imports the plays in them. */
    suspend fun files(context: Context, uris: List<Uri>): Result = withContext(Dispatchers.IO) {
        val plays = ArrayList<ImportedPlay>()
        for (u in uris) {
            coroutineContext.ensureActive()
            val name = runCatching {
                context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: u.lastPathSegment ?: ""
            context.contentResolver.openInputStream(u)?.use { input ->
                if (name.endsWith(".zip", true)) {
                    ZipInputStream(input.buffered()).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            if (e.isDirectory) continue
                            val n = e.name.substringAfterLast('/')
                            // Spotify's zip also holds the user's data, playlists, searches…: only plays.
                            if (!(n.endsWith(".json", true) || n.endsWith(".csv", true))) continue
                            if (!Regex("(?i)history|stream|play|listen|scrobble").containsMatchIn(n)) continue
                            plays += parse(n, z.readBytes().toString(Charsets.UTF_8))
                        }
                    }
                } else plays += parse(name, input.readBytes().toString(Charsets.UTF_8))
            }
        }
        Result(plays.size, ListenStats.import(plays))
    }

    /** The plays in one file, by its contents (JSON list of plays, or CSV with a header row). */
    internal fun parse(name: String, text: String): List<ImportedPlay> {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        val source = when {
            name.contains("spotify", true) || name.startsWith("Streaming", true) || name.startsWith("StreamingHistory", true) -> "spotify"
            else -> "file:" + name.substringBeforeLast('.').lowercase(Locale.ROOT)
        }
        return when {
            t.startsWith("[") -> fromJson(JSONArray(t), source)
            t.startsWith("{") -> JSONObject(t).let { o ->
                // Some tools wrap the list: {"plays": [...]}, {"tracks": [...]}…
                o.keys().asSequence().mapNotNull { o.optJSONArray(it) }.firstOrNull()?.let { fromJson(it, source) }.orEmpty()
            }
            else -> fromCsv(t, source)
        }
    }

    private val titleKeys = listOf("master_metadata_track_name", "trackname", "track_name", "song name", "song_name", "track name", "title", "track", "song", "name")
    private val artistKeys = listOf("master_metadata_album_artist_name", "artistname", "artist_name", "artist name", "artist", "artists", "artist(s)", "album artist")
    private val albumKeys = listOf("master_metadata_album_album_name", "album_name", "album name", "albumname", "album", "container description")
    private val timeKeys = listOf("ts", "endtime", "end_time", "event start timestamp", "event end timestamp", "played_at", "played at", "timestamp", "utc_time", "date", "time", "datetime")
    private val msKeys = listOf("ms_played", "msplayed", "play duration milliseconds", "media duration in milliseconds", "duration_ms", "duration ms", "listening time", "ms")
    private val secKeys = listOf("seconds", "duration_s", "listened_seconds", "seconds played")

    private fun pick(cols: List<String>, keys: List<String>): Int {
        val lower = cols.map { it.trim().trim('"').lowercase(Locale.ROOT) }
        for (k in keys) lower.indexOf(k).takeIf { it >= 0 }?.let { return it }
        return -1
    }

    private fun fromJson(a: JSONArray, source: String): List<ImportedPlay> {
        val out = ArrayList<ImportedPlay>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val keys = o.keys().asSequence().toList()
            fun str(list: List<String>): String = pick(keys, list).takeIf { it >= 0 }?.let { o.optString(keys[it]).takeIf { v -> v != "null" } }.orEmpty()
            val title = str(titleKeys).trim()
            if (title.isEmpty()) continue // podcasts, audiobooks, or not a play
            val at = parseTime(str(timeKeys)) ?: continue
            val ms = str(msKeys).toDoubleOrNull()?.toLong() ?: str(secKeys).toDoubleOrNull()?.let { (it * 1000).toLong() } ?: TYPICAL_MS
            if (ms in 0 until 5_000) continue // skipped straight away
            out += ImportedPlay(title, str(artistKeys).trim(), str(albumKeys).trim(), at, ms, source)
        }
        return out
    }

    private fun fromCsv(text: String, source: String): List<ImportedPlay> {
        val rows = csvRows(text)
        if (rows.size < 2) return emptyList()
        val head = rows.first()
        val ti = pick(head, titleKeys); val ai = pick(head, artistKeys); val al = pick(head, albumKeys)
        val tm = pick(head, timeKeys); val ms = pick(head, msKeys); val sec = pick(head, secKeys)
        if (ti < 0 || tm < 0) return emptyList()
        return rows.drop(1).mapNotNull { r ->
            val title = r.getOrNull(ti)?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            val at = parseTime(r.getOrNull(tm).orEmpty()) ?: return@mapNotNull null
            val heard = r.getOrNull(ms)?.toDoubleOrNull()?.toLong() ?: r.getOrNull(sec)?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: TYPICAL_MS
            if (heard in 0 until 5_000) return@mapNotNull null
            ImportedPlay(title, r.getOrNull(ai)?.trim().orEmpty(), r.getOrNull(al)?.trim().orEmpty(), at, heard, source)
        }
    }

    /** CSV with quotes ("a, b" and "" inside), commas or semicolons. */
    internal fun csvRows(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else if (firstLine.contains('\t')) '\t' else ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>(); val cell = StringBuilder(); var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    private val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "dd MMM yyyy, HH:mm", "dd MMM yyyy HH:mm", "yyyy-MM-dd",
        "MM/dd/yyyy HH:mm", "dd.MM.yyyy HH:mm", "dd/MM/yyyy HH:mm",
    )

    /** Times as the exports write them (UTC unless they say), or seconds / ms since 1970. */
    internal fun parseTime(s: String): Long? {
        val v = s.trim().trim('"')
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { n -> return if (n > 100_000_000_000L) n else n * 1000 }
        for (f in formats) {
            val p = SimpleDateFormat(f, Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
            runCatching { p.parse(v) }.getOrNull()?.let { return it.time }
        }
        return null
    }

    // ---- Last.fm ---------------------------------------------------------------------------

    /**
     * Every scrobble of [user], newest first, from their public Last.fm library pages (50 a
     * page). [onProgress]: pages read and how many there are.
     */
    suspend fun lastFm(user: String, maxPages: Int = 400, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result = withContext(Dispatchers.IO) {
        val name = user.trim().removePrefix("@")
        require(name.isNotEmpty()) { "Type your Last.fm username" }
        val plays = ArrayList<ImportedPlay>()
        var pages = 1
        var page = 1
        while (page <= pages && page <= maxPages) {
            coroutineContext.ensureActive()
            val html = get("https://www.last.fm/user/${URLEncoder.encode(name, "UTF-8")}/library?page=$page")
                ?: if (page == 1) throw IllegalStateException("Couldn't open $name's Last.fm library. Check the username and that the profile is public.") else break
            if (page == 1) pages = lastFmPages(html)
            val got = lastFmScrobbles(html)
            if (got.isEmpty()) break
            plays += got
            onProgress(page, minOf(pages, maxPages))
            page++
        }
        Result(plays.size, ListenStats.import(plays))
    }

    internal fun lastFmPages(html: String): Int =
        Regex("""class="pagination-page"[^>]*>\s*<a[^>]*>\s*(\d+)\s*</a>""").findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1

    /** The scrobbles on one Last.fm library page: title, artist, when. */
    internal fun lastFmScrobbles(html: String): List<ImportedPlay> {
        val out = ArrayList<ImportedPlay>()
        val rows = html.split(Regex("""<tr\s[^>]*class="[^"]*chartlist-row""")).drop(1)
        for (r in rows) {
            val title = Regex("""class="chartlist-name"[^>]*>\s*<a[^>]*title="([^"]+)"""").find(r)?.groupValues?.get(1)
                ?: Regex("""class="chartlist-name"[^>]*>\s*<a[^>]*>([^<]+)</a>""").find(r)?.groupValues?.get(1) ?: continue
            val artist = Regex("""class="chartlist-artist"[^>]*>\s*<a[^>]*>([^<]+)</a>""").find(r)?.groupValues?.get(1) ?: ""
            val time = Regex("""class="chartlist-timestamp[^"]*"[^>]*>\s*<span[^>]*title="([^"]+)"""").find(r)?.groupValues?.get(1) ?: continue
            val at = lastFmTime(time) ?: continue
            out += ImportedPlay(unescape(title.trim()), unescape(artist.trim()), "", at, TYPICAL_MS, "lastfm")
        }
        return out
    }

    /** "Tuesday 3 Nov 2026, 10:41pm" (Last.fm writes it in UTC for visitors without a time zone). */
    internal fun lastFmTime(s: String): Long? {
        val v = s.substringAfter(' ').trim() // drop the weekday
        for (f in listOf("d MMM yyyy, h:mma", "d MMM yyyy, H:mm")) {
            val p = SimpleDateFormat(f, Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }
            runCatching { p.parse(v.replace("am", "AM").replace("pm", "PM")) }.getOrNull()?.let { return it.time }
        }
        return null
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    private fun get(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "en")
        }
        if (c.responseCode !in 200..299) return null
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
