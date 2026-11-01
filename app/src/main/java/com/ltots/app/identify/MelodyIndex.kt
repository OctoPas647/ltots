package com.ltots.app.identify

import android.content.Context
import android.net.Uri
import com.ltots.app.data.Store
import com.ltots.app.data.Track
import com.ltots.app.lyrics.SongMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * The tunes the song finder compares an instrument cover or a hummed melody with: today's most
 * played songs (Apple's free charts — the songs people play covers of — worldwide and in the
 * phone's country, heard from their free 30-second previews) and the songs on the phone. Made in
 * the background (charts once a week on Wi-Fi, the phone's songs while charging); songs named in
 * a video's caption are heard on the spot.
 */
object MelodyIndex {
    data class Entry(val title: String, val artist: String, val tune: FloatArray, val fromPhone: Boolean = false)

    private const val CHART_SIZE = 50
    private const val WEEK = 7L * 24 * 3600_000L
    private val lock = Mutex()
    @Volatile private var cache: Pair<Long, List<Entry>>? = null

    private fun file() = File(Store.rootDir, "melody_index.json")

    fun entries(): List<Entry> = synchronized(this) {
        cache?.second ?: read().also { cache = System.currentTimeMillis() to it }
    }

    private fun read(): List<Entry> = runCatching {
        val o = JSONObject(file().readText())
        val a = o.getJSONArray("songs")
        (0 until a.length()).map { i ->
            val e = a.getJSONObject(i)
            Entry(e.getString("t"), e.getString("a"), Melody.unpack(e.getString("m")), e.optBoolean("p"))
        }
    }.getOrDefault(emptyList())

    private fun lastChartUpdate(): Long = runCatching { JSONObject(file().readText()).optLong("charts") }.getOrDefault(0L)

    private fun write(list: List<Entry>, chartsAt: Long) {
        val a = JSONArray()
        list.forEach { e -> a.put(JSONObject().put("t", e.title).put("a", e.artist).put("m", Melody.pack(e.tune)).put("p", e.fromPhone)) }
        Store.writeFileNow(file(), JSONObject().put("charts", chartsAt).put("songs", a).toString())
        synchronized(this) { cache = System.currentTimeMillis() to list }
    }

    /** Fetches this week's charts and hears their previews (once a week). */
    suspend fun updateCharts(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!force && System.currentTimeMillis() - lastChartUpdate() < WEEK) return@withLock
            val country = Locale.getDefault().country.lowercase(Locale.ROOT).ifBlank { "us" }
            val ids = (listOf(country, "us").distinct().flatMap { cc -> runCatching { chart(cc) }.getOrDefault(emptyList()) }).distinct()
            if (ids.isEmpty()) return@withLock
            val songs = ids.chunked(50).flatMap { runCatching { lookup(it) }.getOrDefault(emptyList()) }
            val old = entries()
            val fresh = ArrayList<Entry>()
            val dir = File(context.cacheDir, "melody").apply { mkdirs() }
            for ((title, artist, url) in songs) {
                currentCoroutineContext().ensureActive()
                old.firstOrNull { !it.fromPhone && it.title == title && it.artist == artist }?.let { fresh += it; continue }
                runCatching { hear(context, url, dir) }.getOrNull()?.let { fresh += Entry(title, artist, it) }
            }
            dir.deleteRecursively()
            write(fresh + old.filter { it.fromPhone }, System.currentTimeMillis())
        }
    }

    /** Hears the songs on the phone not heard yet (30 s from a third in, where choruses tend to be). */
    suspend fun addPhoneSongs(context: Context, tracks: List<Track>, keepGoing: () -> Boolean = { true }) = withContext(Dispatchers.Default) {
        lock.withLock {
            val list = entries().toMutableList()
            val have = list.filter { it.fromPhone }.map { key(it.title, it.artist) }.toHashSet()
            var added = 0
            for (t in tracks) {
                if (!keepGoing()) break
                currentCoroutineContext().ensureActive()
                val artist = t.artist.takeIf { it != Track.UNKNOWN } ?: ""
                if (key(t.title, artist) in have) continue
                val pcm = runCatching { ClipLoader.decode(context, t.uri, maxSeconds = 30, startSec = t.durationMs / 3000.0) }.getOrNull() ?: continue
                val tune = Melody.contour(pcm)
                if (Melody.voiced(tune) >= Melody.MIN_VOICED) { list += Entry(t.title, artist, tune, fromPhone = true); have += key(t.title, artist) }
                if (++added % 25 == 0) write(list, lastChartUpdate())
            }
            if (added > 0) write(list, lastChartUpdate())
        }
    }

    /** Songs named in a video's caption or title: their previews, heard right now (at most [max]). */
    suspend fun hearNamed(context: Context, text: String, max: Int = 4): List<Entry> = withContext(Dispatchers.IO) {
        val q = text.replace(Regex("#\\w+"), " ").replace(Regex("(?i)\\b(piano|guitar|violin|cello|sax|saxophone|flute|cover|tutorial|instrumental|version|easy|how to play|fingerstyle|ukulele|acoustic)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().take(80)
        if (q.length < 3) return@withContext emptyList()
        val found = runCatching { search(q) }.getOrDefault(emptyList()).take(max)
        val dir = File(context.cacheDir, "melody-now").apply { mkdirs() }
        try {
            found.mapNotNull { (t, a, url) -> runCatching { hear(context, url, dir) }.getOrNull()?.let { Entry(t, a, it) } }
        } finally { dir.deleteRecursively() }
    }

    private suspend fun hear(context: Context, url: String, dir: File): FloatArray? {
        val f = File(dir, "p${url.hashCode()}.m4a")
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }
        try { c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { c.disconnect() }
        val pcm = ClipLoader.decode(context, Uri.fromFile(f), maxSeconds = 30)
        f.delete()
        return Melody.contour(pcm).takeIf { Melody.voiced(it) >= Melody.MIN_VOICED }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }
        try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    /** Apple Music's most played songs in country [cc]: their ids. */
    private fun chart(cc: String): List<String> {
        val text = listOf("https://rss.marketingtools.apple.com", "https://rss.applemarketingtools.com").firstNotNullOfOrNull { host ->
            runCatching { get("$host/api/v2/$cc/music/most-played/$CHART_SIZE/songs.json") }.getOrNull()
        } ?: return emptyList()
        val r = JSONObject(text).getJSONObject("feed").getJSONArray("results")
        return (0 until r.length()).map { r.getJSONObject(it).getString("id") }
    }

    /** Title, artist and preview of each id (iTunes lookup). */
    private fun lookup(ids: List<String>): List<Triple<String, String, String>> {
        val r = JSONObject(get("https://itunes.apple.com/lookup?id=${ids.joinToString(",")}&entity=song")).getJSONArray("results")
        return (0 until r.length()).mapNotNull { i ->
            val o = r.getJSONObject(i)
            val url = o.optString("previewUrl").ifBlank { return@mapNotNull null }
            Triple(o.optString("trackName"), o.optString("artistName"), url)
        }
    }

    private fun search(q: String): List<Triple<String, String, String>> {
        val r = JSONObject(get("https://itunes.apple.com/search?term=${URLEncoder.encode(q, "UTF-8")}&entity=song&limit=6")).getJSONArray("results")
        return (0 until r.length()).mapNotNull { i ->
            val o = r.getJSONObject(i)
            val url = o.optString("previewUrl").ifBlank { return@mapNotNull null }
            Triple(o.optString("trackName"), o.optString("artistName"), url)
        }
    }

    private fun key(t: String, a: String) = SongMatch.norm(SongMatch.cleanTitle(t)) + "|" + SongMatch.norm(SongMatch.mainArtist(a))
}
