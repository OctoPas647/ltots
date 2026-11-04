package com.ltots.app.recs

import android.content.Context
import com.ltots.app.data.Store
import com.ltots.app.identify.Platform
import com.ltots.app.identify.Platforms
import com.ltots.app.identify.Suggestion
import com.ltots.app.lyrics.SongMatch
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.YtSong
import com.ltots.app.ytm.YtmAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A song the user showed they like somewhere else, and how strongly. */
data class TasteSong(val title: String, val artist: String, val videoId: String? = null, val weight: Double, val source: String)

/**
 * Taste from the accounts the user signed in to, for the Home tab's picks:
 *
 *  - **YouTube Music**: liked songs (newest count most), songs in their own playlists, the
 *    artists they subscribe to and the artists of the albums they saved.
 *  - **Instagram**: the songs on the reels they saved or liked.
 *  - **The song finder**: songs they looked up from a clip or by listening.
 *
 * Read at most every [MAX_AGE_MS] (kept per user), so the Home tab doesn't ask every time.
 * TikTok isn't read: its site only answers requests signed by its own app code.
 */
object AccountTaste {
    private const val MAX_AGE_MS = 6L * 3600_000L

    data class Signals(
        val songs: List<TasteSong> = emptyList(),
        /** Main artist (normalised) → how much it counts, 0..1. */
        val artists: Map<String, Double> = emptyMap(),
        /** Liked songs: already known, so not recommended again. */
        val likedIds: Set<String> = emptySet(),
        val builtAt: Long = 0L,
        /** Whether YouTube Music was signed in when these were read. */
        val ytSigned: Boolean = false,
    ) {
        val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty()
    }

    private fun file() = Store.userFile("account_taste.json")
    @Volatile private var cached: Signals? = null

    fun invalidate() { cached = null; runCatching { file().delete() } }

    suspend fun load(context: Context, force: Boolean = false): Signals = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val have = cached ?: read()?.also { cached = it }
        if (!force && have != null && now - have.builtAt < MAX_AGE_MS && have.ytSigned == YtmAccount.signedIn) return@withContext have
        val fresh = runCatching { build(context) }.getOrNull()
        // Nothing could be read (offline): keep the last good one.
        val use = if (fresh == null || (fresh.isEmpty && have != null)) have ?: Signals(builtAt = now) else fresh
        cached = use
        if (fresh != null && !fresh.isEmpty) runCatching { file().writeText(write(fresh).toString()) }
        use
    }

    private suspend fun build(context: Context): Signals = coroutineScope {
        val yt = if (YtmAccount.signedIn) async { runCatching { youTubeMusic() }.getOrNull() } else null
        val ig = if (Platforms.isSignedIn(context, Platform.INSTAGRAM)) async { runCatching { instagram(context) }.getOrDefault(emptyList()) } else null
        val finds = FinderFinds.all().mapIndexed { i, f -> TasteSong(f.title, f.artist, f.videoId, 0.7 * kotlin.math.exp(-i / 30.0), "finder") }
        val y = yt?.await()
        Signals(
            songs = (y?.songs.orEmpty()) + (ig?.await().orEmpty()) + finds,
            artists = y?.artists.orEmpty(),
            likedIds = y?.likedIds.orEmpty(),
            builtAt = System.currentTimeMillis(),
            ytSigned = YtmAccount.signedIn,
        )
    }

    private suspend fun youTubeMusic(): Signals = coroutineScope {
        val liked = async { runCatching { InnerTube.likedSongs(150) }.getOrDefault(emptyList()) }
        val subs = async { runCatching { InnerTube.subscribedArtists() }.getOrDefault(emptyList()) }
        val albums = async { runCatching { InnerTube.savedAlbums() }.getOrDefault(emptyList()) }
        val lists = async { runCatching { InnerTube.libraryPlaylists() }.getOrDefault(emptyList()) }
        val likedSongs = liked.await()
        // Their own playlists (not "Liked music" or podcasts): a few songs from the first three.
        val own = lists.await().filter { it.playlistId != "LM" && it.playlistId != "SE" && !it.title.contains("episodes", true) }.take(3)
        val inLists = own.map { p -> async { runCatching { InnerTube.playlist(p.playlistId, 40, auth = true).tracks }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }
        val songs = likedSongs.mapIndexed { i, s -> taste(s, 0.85 * kotlin.math.exp(-i / 40.0), "liked") } +
            inLists.map { taste(it, 0.35, "playlist") }
        val artists = HashMap<String, Double>()
        fun add(name: String, w: Double) {
            val k = SongMatch.norm(SongMatch.mainArtist(name)).ifBlank { return }
            artists[k] = maxOf(artists[k] ?: 0.0, w)
        }
        subs.await().forEach { add(it.title, 0.7) }
        albums.await().forEach { add(it.artist, 0.5) }
        Signals(songs, artists, likedSongs.map { it.videoId }.toSet(), System.currentTimeMillis())
    }

    private fun taste(s: YtSong, w: Double, source: String) = TasteSong(s.title, s.artists.firstOrNull()?.name ?: "", s.videoId, w, source)

    // ---- Instagram -------------------------------------------------------------------------

    /**
     * The songs on reels the user saved or liked, read the way instagram.com itself reads them
     * (with the sign-in from the song finder's accounts). Reels with their own "original audio"
     * aren't songs and are left out.
     */
    private fun instagram(context: Context): List<TasteSong> {
        val out = ArrayList<TasteSong>()
        for ((path, w) in listOf("feed/saved/posts/" to 0.75, "feed/liked/" to 0.5)) {
            val json = runCatching { igGet(context, "https://www.instagram.com/api/v1/$path") }.getOrNull() ?: continue
            igSongs(json).forEachIndexed { i, (title, artist) ->
                out += TasteSong(title, artist, null, w * kotlin.math.exp(-i / 30.0), "instagram")
            }
        }
        return out.distinctBy { SongMatch.norm(it.title) + "|" + SongMatch.norm(it.artist) }
    }

    /** Title and artist of every licensed song in an Instagram feed page, in order. */
    internal fun igSongs(root: JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    val info = n.optJSONObject("music_asset_info")
                    if (info != null) {
                        val t = info.optString("title").trim(); val a = info.optString("display_artist").trim()
                        if (t.isNotEmpty() && a.isNotEmpty()) out += t to a
                    }
                    n.keys().forEach { k -> if (k != "music_asset_info") walk(n.opt(k)) }
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(root)
        return out.distinct()
    }

    private fun igGet(context: Context, url: String): JSONObject? {
        val cookie = Platforms.cookieHeader(context, url) ?: return null
        val csrf = Regex("""csrftoken=([^;]+)""").find(cookie)?.groupValues?.get(1)
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Cookie", cookie)
            setRequestProperty("X-IG-App-ID", "936619743392459")
            csrf?.let { setRequestProperty("X-CSRFToken", it) }
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.instagram.com/")
            setRequestProperty("Accept", "application/json")
        }
        if (c.responseCode !in 200..299) return null
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    // ---- saved copy --------------------------------------------------------------------------

    private fun write(s: Signals) = JSONObject()
        .put("at", s.builtAt).put("yt", s.ytSigned)
        .put("songs", JSONArray().apply {
            s.songs.forEach { put(JSONObject().put("t", it.title).put("a", it.artist).put("v", it.videoId ?: "").put("w", it.weight).put("s", it.source)) }
        })
        .put("artists", JSONObject().apply { s.artists.forEach { (k, v) -> put(k, v) } })
        .put("liked", JSONArray(s.likedIds.toList()))

    private fun read(): Signals? = runCatching {
        val o = JSONObject(file().readText())
        val songs = o.optJSONArray("songs") ?: JSONArray()
        val artists = o.optJSONObject("artists") ?: JSONObject()
        val liked = o.optJSONArray("liked") ?: JSONArray()
        Signals(
            (0 until songs.length()).map { i ->
                val s = songs.getJSONObject(i)
                TasteSong(s.optString("t"), s.optString("a"), s.optString("v").ifBlank { null }, s.optDouble("w"), s.optString("s"))
            },
            artists.keys().asSequence().associateWith { artists.optDouble(it) },
            (0 until liked.length()).map { liked.getString(it) }.toSet(),
            o.optLong("at"),
            o.optBoolean("yt"),
        )
    }.getOrNull()
}

/**
 * Songs the user looked up with the song finder (Get tab): each clip's best match, newest first.
 * Kept per user, the last [MAX].
 */
object FinderFinds {
    private const val MAX = 200
    data class Find(val title: String, val artist: String, val videoId: String?, val at: Long)

    private fun file() = Store.userFile("finder_finds.json")

    /** Remembers the clip's best match (each song's, for clips with several songs). */
    @Synchronized
    fun record(rows: List<Suggestion>) {
        val best = rows.groupBy { it.part }.values.mapNotNull { g -> g.maxByOrNull { it.confidence }?.takeIf { it.confidence >= 0.5 } }
        if (best.isEmpty()) return
        val now = System.currentTimeMillis()
        val added = best.map { Find(it.title, it.artist, it.song?.videoId, now) }
        val keys = added.map { key(it) }.toSet()
        val list = (added + all().filter { key(it) !in keys }).take(MAX)
        runCatching {
            file().writeText(JSONArray().apply {
                list.forEach { put(JSONObject().put("t", it.title).put("a", it.artist).put("v", it.videoId ?: "").put("at", it.at)) }
            }.toString())
        }
    }

    private fun key(f: Find) = SongMatch.norm(f.title) + "|" + SongMatch.norm(SongMatch.mainArtist(f.artist))

    @Synchronized
    fun all(): List<Find> = runCatching {
        val a = JSONArray(file().readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Find(o.optString("t"), o.optString("a"), o.optString("v").ifBlank { null }, o.optLong("at"))
        }
    }.getOrDefault(emptyList())
}
