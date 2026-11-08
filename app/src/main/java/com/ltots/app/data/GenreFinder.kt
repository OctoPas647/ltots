package com.ltots.app.data

import com.ltots.app.identify.Ranking
import com.ltots.app.lyrics.SongMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The real genre of each song, looked up by artist + title in music databases (Deezer, then
 * Apple's iTunes catalogue), instead of trusting the file's genre tag — which is often missing
 * (songs downloaded from YouTube have none), a whole album's worth of one guess, or nonsense
 * like "Other" or "Music".
 *
 * For each song, in order:
 *  1. the database genre of that exact song (its album's genre on Deezer),
 *  2. when the databases don't know the song: what most of the same artist's songs are,
 *  3. the file's own genre tag.
 *
 * Answers are kept in a file, so each song is looked up once, ever.
 */
object GenreFinder {
    private val cache = HashMap<String, String>() // song key → genre ("" = the databases don't know it)
    private val ranks = HashMap<String, Int>() // song key → Deezer popularity (0..1 000 000)
    private var loaded = false

    private val _genres = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** The genre picked for each track id (only tracks that have one). */
    val genres: StateFlow<Map<Long, String>> = _genres

    private val _popularity = MutableStateFlow<Map<Long, Int>>(emptyMap())
    /** How popular each song is worldwide (Deezer's rank, 0..1 000 000), by track id, when known. */
    val popularity: StateFlow<Map<Long, Int>> = _popularity

    private val _busy = MutableStateFlow(false)
    /** True while songs are being looked up. */
    val busy: StateFlow<Boolean> = _busy

    private fun file() = File(Store.rootDir, "genres.json")

    /** Same song, same key — "(Sped Up)", "feat. X" and "(Official Video)" don't matter. */
    fun key(t: Track): String? {
        if (t.artist.isBlank() || t.artist == Track.UNKNOWN) return null
        val a = SongMatch.norm(SongMatch.mainArtist(t.artist))
        val s = SongMatch.norm(Ranking.baseTitle(t.title))
        return if (a.isEmpty() || s.isEmpty()) null else "$a|$s"
    }

    /** Applies the found genres to [tracks] (for sorting and mixes). */
    fun apply(tracks: List<Track>, genres: Map<Long, String>): List<Track> =
        if (genres.isEmpty()) tracks else tracks.map { t -> genres[t.id]?.takeIf { it != t.genre }?.let { t.copy(genre = it) } ?: t }

    /**
     * Looks up every song in [tracks] the databases haven't been asked about yet, publishing
     * the genres as they come in so the list sorts itself while it works.
     */
    suspend fun find(tracks: List<Track>) = withContext(Dispatchers.IO) {
        synchronized(cache) {
            if (!loaded) {
                loaded = true
                runCatching {
                    val o = JSONObject(file().readText())
                    val g = o.optJSONObject("genres") ?: JSONObject()
                    for (k in g.keys()) cache[k] = g.getString(k)
                    val r = o.optJSONObject("ranks") ?: JSONObject()
                    for (k in r.keys()) ranks[k] = r.getInt(k)
                }
            }
        }
        publish(tracks)
        val missing = tracks.mapNotNull { t -> key(t)?.let { it to t } }
            .distinctBy { it.first }
            .filter { (k, _) -> synchronized(cache) { k !in cache } }
        if (missing.isEmpty()) return@withContext
        _busy.value = true
        try {
            for (chunk in missing.chunked(24)) {
                coroutineScope {
                    val gate = Semaphore(3)
                    chunk.map { (k, t) ->
                        async {
                            gate.withPermit {
                                // A network failure isn't remembered, so the song is tried again next time.
                                runCatching { lookup(SongMatch.mainArtist(t.artist), Ranking.baseTitle(t.title)) }
                                    .onSuccess { f ->
                                        synchronized(cache) {
                                            cache[k] = f.genre ?: ""
                                            f.rank?.let { ranks[k] = it }
                                        }
                                    }
                            }
                        }
                    }.awaitAll()
                }
                publish(tracks)
                save()
            }
        } finally {
            _busy.value = false
        }
    }

    private fun save() {
        val json = synchronized(cache) {
            JSONObject()
                .put("genres", JSONObject().apply { cache.forEach { (k, v) -> put(k, v) } })
                .put("ranks", JSONObject().apply { ranks.forEach { (k, v) -> put(k, v) } })
                .toString()
        }
        runCatching { Store.writeFileNow(file(), json) }
    }

    private fun publish(tracks: List<Track>) {
        val known = synchronized(cache) { HashMap(cache) }
        val r = synchronized(cache) { HashMap(ranks) }
        _genres.value = pick(tracks, known)
        _popularity.value = tracks.mapNotNull { t -> key(t)?.let { r[it] }?.let { t.id to it } }.toMap()
    }

    /**
     * The genre for each of [tracks]: the database's ([known], by [key]), else what most of
     * the artist's other songs are, else the file's tag.
     */
    internal fun pick(tracks: List<Track>, known: Map<String, String>): Map<Long, String> {
        val fromDb = HashMap<Long, String>()
        for (t in tracks) key(t)?.let { known[it] }?.takeIf { it.isNotBlank() }?.let { fromDb[t.id] = it }
        // Each artist's most common family, and its most common genre name within that family.
        val byArtist = HashMap<String, String>()
        tracks.filter { it.id in fromDb }
            .groupBy { SongMatch.norm(SongMatch.mainArtist(it.artist)) }
            .forEach { (artist, songs) ->
                val names = songs.mapNotNull { fromDb[it.id] }
                val family = names.groupingBy { SongSorter.familyIndex(it) }.eachCount().maxByOrNull { it.value }?.key ?: return@forEach
                byArtist[artist] = names.filter { SongSorter.familyIndex(it) == family }
                    .groupingBy { it }.eachCount().maxBy { it.value }.key
            }
        val out = HashMap<Long, String>()
        for (t in tracks) {
            val g = fromDb[t.id]
                ?: byArtist[SongMatch.norm(SongMatch.mainArtist(t.artist))].takeIf { t.artist != Track.UNKNOWN }
                ?: SongSorter.genreName(t.genre)?.takeIf { !it.equals("other", true) && !it.equals("music", true) && !it.equals("unknown", true) }
            if (g != null) out[t.id] = g
        }
        return out
    }

    // ---- databases -------------------------------------------------------------------------

    /** What the databases say about a song. */
    internal data class Facts(val genre: String?, val rank: Int?)

    /** The genre (and popularity) of [title] by [artist]; nulls when neither database has the song. */
    internal suspend fun lookup(artist: String, title: String): Facts {
        val d = deezer(artist, title)
        if (d.genre != null) return d
        return Facts(itunes(artist, title), d.rank)
    }

    private val albumGenres = HashMap<Long, String>()
    private val deezerGate = Mutex()
    private var deezerLast = 0L
    private val itunesGate = Mutex()
    private var itunesLast = 0L

    /** Deezer allows 50 requests per 5 s: one every 125 ms keeps well inside that. */
    private suspend fun deezerGet(url: String): JSONObject {
        repeat(2) { attempt ->
            deezerGate.withLock {
                val wait = deezerLast + 125 - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                deezerLast = System.currentTimeMillis()
            }
            val o = JSONObject(get(url))
            val err = o.optJSONObject("error")
            if (err == null) return o
            // Quota exceeded: wait it out once.
            if (err.optInt("code") == 4 && attempt == 0) delay(5_000) else throw IOException(err.optString("message"))
        }
        throw IOException("Deezer is busy")
    }

    private suspend fun deezer(artist: String, title: String): Facts {
        val q = "artist:\"${artist.replace("\"", "")}\" track:\"${title.replace("\"", "")}\""
        val data = deezerGet("https://api.deezer.com/search?limit=8&q=" + enc(q)).optJSONArray("data")
            ?: return Facts(null, null)
        val best = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .map { it to SongMatch.similarity(title, it.optString("title_short").ifBlank { it.optString("title") }) + SongMatch.similarity(artist, it.optJSONObject("artist")?.optString("name").orEmpty()) }
            .filter { it.second >= 1.3 }
            .maxByOrNull { it.second }?.first ?: return Facts(null, null)
        val rank = best.optInt("rank", -1).takeIf { it >= 0 }
        val albumId = best.optJSONObject("album")?.optLong("id") ?: return Facts(null, rank)
        synchronized(albumGenres) { albumGenres[albumId] }?.let { return Facts(it.ifBlank { null }, rank) }
        val album = deezerGet("https://api.deezer.com/album/$albumId")
        val names = album.optJSONObject("genres")?.optJSONArray("data")
        // An album lists its main genre first, sometimes a broader one after ("Rap/Hip Hop", "Pop").
        val g = if (names == null || names.length() == 0) "" else names.getJSONObject(0).optString("name")
        synchronized(albumGenres) { albumGenres[albumId] = g }
        return Facts(g.ifBlank { null }, rank)
    }

    /** iTunes asks for ~20 searches a minute at most, so it's only the fallback. */
    private suspend fun itunes(artist: String, title: String): String? {
        itunesGate.withLock {
            val wait = itunesLast + 3_000 - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            itunesLast = System.currentTimeMillis()
        }
        val r = JSONObject(get("https://itunes.apple.com/search?entity=song&limit=8&term=" + enc("$artist $title"))).optJSONArray("results")
            ?: return null
        return (0 until r.length()).mapNotNull { r.optJSONObject(it) }
            .map { it to SongMatch.similarity(title, it.optString("trackName")) + SongMatch.similarity(artist, it.optString("artistName")) }
            .filter { it.second >= 1.3 }
            .maxByOrNull { it.second }?.first?.optString("primaryGenreName")?.ifBlank { null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "LToTS/1.0 (Android music player)")
        }
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}
