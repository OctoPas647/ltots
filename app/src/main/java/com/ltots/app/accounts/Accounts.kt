package com.ltots.app.accounts

import android.content.Context
import android.webkit.CookieManager
import com.ltots.app.data.ImportedPlay
import com.ltots.app.data.ListenHistory
import com.ltots.app.data.ListenStats
import com.ltots.app.data.Store
import com.ltots.app.elsewhere.OtherApps
import com.ltots.app.elsewhere.Service
import com.ltots.app.identify.Platforms
import com.ltots.app.recs.TasteSong
import com.ltots.app.ytm.YtmAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.exp

/**
 * The music accounts LToTS can learn from, besides YouTube Music ([YtmAccount]):
 *
 *  - **Spotify**, **Apple Music**, **SoundCloud** — signed in on their own website inside
 *    LToTS (the password only goes to them); their web player's session is then used to read
 *    your top songs and artists, liked songs and what you played recently.
 *  - **Deezer** — signed in the same way; your favourites and your own charts.
 *  - **Last.fm** — just your username: your scrobbles are public.
 *
 * What's read feeds Home's picks ([com.ltots.app.recs.AccountTaste]), and songs played there
 * recently (with the time they were played) count in your listening numbers and Wrapped —
 * except plays LToTS already counted itself, or saw playing in that app ([OtherApps]).
 *
 * These use the services' web players the way a browser does, not an official API, so a service
 * changing its site can stop one from working; then it's simply left out, nothing breaks.
 */
object Accounts {
    data class Connection(val service: Service, val name: String, val since: Long, val lastSync: Long = 0L, val error: String? = null)

    private val _connections = MutableStateFlow<Map<Service, Connection>>(emptyMap())
    val connections: StateFlow<Map<Service, Connection>> = _connections

    private var loadedFor: String? = null

    private fun file() = Store.userFile("accounts.json")

    @Synchronized
    fun load() {
        val path = file().path
        if (loadedFor == path) return
        loadedFor = path
        val o = runCatching { JSONObject(file().readText()) }.getOrNull()
        val map = HashMap<Service, Connection>()
        o?.keys()?.forEach { k ->
            val s = runCatching { Service.valueOf(k) }.getOrNull() ?: return@forEach
            val c = o.optJSONObject(k) ?: return@forEach
            map[s] = Connection(s, c.optString("name"), c.optLong("since"), c.optLong("sync"), c.optString("error").takeIf { it.isNotBlank() })
        }
        _connections.value = map
    }

    @Synchronized
    private fun save(map: Map<Service, Connection>) {
        _connections.value = map
        val o = JSONObject()
        map.forEach { (s, c) -> o.put(s.name, JSONObject().put("name", c.name).put("since", c.since).put("sync", c.lastSync).put("error", c.error ?: "")) }
        Store.writeLater(file()) { o.toString() }
    }

    fun isConnected(context: Context, s: Service): Boolean {
        if (s == Service.YOUTUBE_MUSIC) return runCatching { YtmAccount.signedIn }.getOrDefault(false)
        load()
        return _connections.value.containsKey(s)
    }

    // ---- signing in ----------------------------------------------------------------------------

    fun loginUrl(s: Service): String = when (s) {
        Service.SPOTIFY -> "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        Service.DEEZER -> "https://www.deezer.com/login"
        Service.SOUNDCLOUD -> "https://soundcloud.com/signin"
        Service.APPLE_MUSIC -> "https://music.apple.com/login"
        Service.LASTFM -> "https://www.last.fm/login"
        Service.YOUTUBE_MUSIC -> YtmAccount.LOGIN_URL
    }

    /** The cookie a service sets once you're signed in to it. */
    private fun sessionCookie(s: Service): Pair<String, String>? = when (s) {
        Service.SPOTIFY -> "https://open.spotify.com" to "sp_dc"
        Service.DEEZER -> "https://www.deezer.com" to "arl"
        Service.SOUNDCLOUD -> "https://soundcloud.com" to "oauth_token"
        Service.APPLE_MUSIC -> "https://music.apple.com" to "media-user-token"
        else -> null
    }

    /** Signed in on the sign-in page (its session cookie is there). */
    fun browserSignedIn(s: Service): Boolean {
        val (url, name) = sessionCookie(s) ?: return false
        return TokenGrabber.cookie(url, name) != null ||
            // Spotify sets its cookie on the whole domain.
            (s == Service.SPOTIFY && TokenGrabber.cookie("https://accounts.spotify.com", name) != null)
    }

    /** The sign-in page saw the session: remember the account (its name is read on the first sync). */
    fun saveFromBrowser(s: Service): Boolean {
        if (!browserSignedIn(s)) return false
        runCatching { CookieManager.getInstance().flush() }
        load()
        save(_connections.value + (s to Connection(s, s.label, System.currentTimeMillis())))
        TokenGrabber.forget(s)
        com.ltots.app.recs.AccountTaste.invalidate()
        return true
    }

    /** Last.fm: by username (checked against the profile page). */
    suspend fun connectLastFm(user: String): Boolean = withContext(Dispatchers.IO) {
        val u = user.trim().removePrefix("@")
        if (u.isBlank()) return@withContext false
        val ok = get("https://www.last.fm/user/${URLEncoder.encode(u, "UTF-8")}") != null
        if (ok) {
            load()
            save(_connections.value + (Service.LASTFM to Connection(Service.LASTFM, u, System.currentTimeMillis())))
            com.ltots.app.recs.AccountTaste.invalidate()
        }
        ok
    }

    fun disconnect(s: Service) {
        if (s == Service.YOUTUBE_MUSIC) { YtmAccount.signOut(); return }
        load()
        save(_connections.value - s)
        TokenGrabber.forget(s)
        sessionCookie(s)?.let { (url, name) ->
            runCatching { CookieManager.getInstance().setCookie(url, "$name=; Max-Age=0") }
        }
        com.ltots.app.recs.AccountTaste.invalidate()
    }

    // ---- reading taste ---------------------------------------------------------------------------

    /** What one account says about your taste. */
    data class Taste(
        val songs: List<TasteSong> = emptyList(),
        /** Main artist → 0..1. */
        val artists: Map<String, Double> = emptyMap(),
        /** Plays with the time they happened (for the listening numbers). */
        val plays: List<ImportedPlay> = emptyList(),
        val name: String? = null,
    )

    /**
     * Reads every connected account (in parallel); updates each one's name and last sync, and
     * counts their recent plays that LToTS didn't count already.
     */
    suspend fun readAll(context: Context): Taste = coroutineScope {
        load()
        val jobs = _connections.value.keys.map { s ->
            s to async(Dispatchers.IO) { runCatching { read(context, s) } }
        }
        val got = jobs.map { (s, j) -> s to j.await() }
        val map = _connections.value.toMutableMap()
        for ((s, r) in got) {
            val c = map[s] ?: continue
            map[s] = r.fold(
                { t -> c.copy(name = t.name ?: c.name, lastSync = System.currentTimeMillis(), error = null) },
                { e -> c.copy(error = e.message?.take(120) ?: "Couldn't read") },
            )
        }
        save(map)
        val all = got.mapNotNull { it.second.getOrNull() }
        countPlays(all.flatMap { it.plays })
        val artists = HashMap<String, Double>()
        all.forEach { t -> t.artists.forEach { (a, w) -> artists[a] = maxOf(artists[a] ?: 0.0, w) } }
        Taste(songs = all.flatMap { it.songs }, artists = artists)
    }

    private suspend fun read(context: Context, s: Service): Taste = when (s) {
        Service.SPOTIFY -> spotify(context)
        Service.APPLE_MUSIC -> appleMusic(context)
        Service.SOUNDCLOUD -> soundCloud(context)
        Service.DEEZER -> deezer()
        Service.LASTFM -> lastFm()
        Service.YOUTUBE_MUSIC -> Taste()
    }

    /** Plays LToTS didn't already count (in its own player, or seeing that app play). */
    private fun countPlays(plays: List<ImportedPlay>) {
        if (plays.isEmpty()) return
        val own = ListenHistory.items.value
        val fresh = plays.filter { p ->
            !OtherApps.heardAround(p.title, p.artist, p.at) &&
                own.none { l -> kotlin.math.abs(l.at - p.at) < 15 * 60_000L && ListenStats.key(l.title, l.artist) == ListenStats.key(p.title, p.artist) }
        }
        if (fresh.isNotEmpty()) com.ltots.app.data.StatsThread.post { ListenStats.import(fresh) }
    }

    private fun decay(i: Int, n: Double = 40.0) = exp(-i / n)

    private fun artistWeights(names: List<String>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        names.forEachIndexed { i, a -> if (a.isNotBlank() && a !in out) out[com.ltots.app.lyrics.SongMatch.mainArtist(a)] = 0.4 + 0.6 * decay(i, 15.0) }
        return out
    }

    private val ISO = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private fun isoTime(s: String): Long? = runCatching { ISO.get()!!.parse(s.take(19))?.time }.getOrNull()

    // Spotify: the Web API with the web player's token.
    private suspend fun spotify(context: Context): Taste {
        val tok = TokenGrabber.token(context, Service.SPOTIFY)?.bearer ?: error("Spotify's session ran out — sign in again")
        val h = mapOf("Authorization" to "Bearer $tok")
        fun api(path: String) = get("https://api.spotify.com/v1/$path", h)?.let { JSONObject(it) }
        val me = api("me") ?: run { TokenGrabber.forget(Service.SPOTIFY); error("Spotify didn't answer") }
        val songs = ArrayList<TasteSong>()
        fun tracks(a: JSONArray?, w: Double, src: String, get: (JSONObject) -> JSONObject?) {
            a ?: return
            for (i in 0 until a.length()) {
                val t = get(a.getJSONObject(i)) ?: continue
                val artist = t.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
                songs += TasteSong(t.optString("name"), artist, null, w * decay(i), src)
            }
        }
        tracks(api("me/top/tracks?limit=50&time_range=short_term")?.optJSONArray("items"), 1.0, "spotify") { it }
        tracks(api("me/top/tracks?limit=50&time_range=medium_term")?.optJSONArray("items"), 0.8, "spotify") { it }
        tracks(api("me/tracks?limit=50")?.optJSONArray("items"), 0.7, "spotify") { it.optJSONObject("track") }
        val topArtists = api("me/top/artists?limit=40&time_range=medium_term")?.optJSONArray("items")
        val artistNames = (0 until (topArtists?.length() ?: 0)).map { topArtists!!.getJSONObject(it).optString("name") }
        val recent = api("me/player/recently-played?limit=50")?.optJSONArray("items")
        val plays = ArrayList<ImportedPlay>()
        if (recent != null) for (i in 0 until recent.length()) {
            val o = recent.getJSONObject(i)
            val t = o.optJSONObject("track") ?: continue
            val end = isoTime(o.optString("played_at")) ?: continue
            val ms = t.optLong("duration_ms", 200_000L)
            val artist = t.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            plays += ImportedPlay(t.optString("name"), artist, t.optJSONObject("album")?.optString("name").orEmpty(), end - ms, ms, "spotify")
            songs += TasteSong(t.optString("name"), artist, null, 0.6 * decay(i), "spotify")
        }
        return Taste(songs, artistWeights(artistNames), plays, me.optString("display_name").takeIf { it.isNotBlank() })
    }

    // Apple Music: the web player's API (developer token + your media-user-token).
    private suspend fun appleMusic(context: Context): Taste {
        val c = TokenGrabber.token(context, Service.APPLE_MUSIC) ?: error("Apple Music's session ran out — sign in again")
        val h = mapOf(
            "Authorization" to "Bearer ${c.bearer}",
            "Media-User-Token" to (c.extra["media-user-token"] ?: ""),
            "Origin" to "https://music.apple.com",
        )
        fun api(path: String) = get("https://amp-api.music.apple.com/v1/$path", h)?.let { JSONObject(it) }
        val songs = ArrayList<TasteSong>()
        val artists = ArrayList<String>()
        fun songsFrom(a: JSONArray?, w: Double) {
            a ?: return
            for (i in 0 until a.length()) {
                val at = a.getJSONObject(i).optJSONObject("attributes") ?: continue
                val artist = at.optString("artistName")
                if (at.has("durationInMillis") || a.getJSONObject(i).optString("type").contains("song")) {
                    songs += TasteSong(at.optString("name"), artist, null, w * decay(i), "apple")
                }
                artists += artist
            }
        }
        val recent = api("me/recent/played/tracks?limit=30") ?: run { TokenGrabber.forget(Service.APPLE_MUSIC); error("Apple Music didn't answer") }
        songsFrom(recent.optJSONArray("data"), 0.9)
        songsFrom(api("me/history/heavy-rotation?limit=20")?.optJSONArray("data"), 1.0)
        songsFrom(api("me/library/songs?limit=100")?.optJSONArray("data"), 0.6)
        // Apple doesn't say when a song was played: taste only, no plays to count.
        return Taste(songs, artistWeights(artists))
    }

    // SoundCloud: api-v2 with your OAuth session and the site's client id.
    private suspend fun soundCloud(context: Context): Taste {
        val c = TokenGrabber.token(context, Service.SOUNDCLOUD) ?: error("SoundCloud's session ran out — sign in again")
        val id = c.extra["client_id"] ?: error("SoundCloud changed its site")
        val h = c.bearer?.let { mapOf("Authorization" to "OAuth $it") } ?: emptyMap()
        fun api(path: String) = get("https://api-v2.soundcloud.com/$path${if ('?' in path) '&' else '?'}client_id=$id", h)?.let { JSONObject(it) }
        val me = api("me") ?: run { TokenGrabber.forget(Service.SOUNDCLOUD); error("SoundCloud didn't answer") }
        val songs = ArrayList<TasteSong>()
        val plays = ArrayList<ImportedPlay>()
        val artists = ArrayList<String>()
        val history = api("me/play-history/tracks?limit=50")?.optJSONArray("collection")
        if (history != null) for (i in 0 until history.length()) {
            val o = history.getJSONObject(i)
            val t = o.optJSONObject("track") ?: continue
            val (title, artist) = scTitle(t)
            val at = o.optLong("played_at").takeIf { it > 0 }
            val ms = t.optLong("full_duration", t.optLong("duration", 200_000L))
            if (at != null) plays += ImportedPlay(title, artist, "", at, ms, "soundcloud")
            songs += TasteSong(title, artist, null, 0.8 * decay(i), "soundcloud")
            artists += artist
        }
        val likes = api("users/${me.optLong("id")}/track_likes?limit=50")?.optJSONArray("collection")
        if (likes != null) for (i in 0 until likes.length()) {
            val t = likes.getJSONObject(i).optJSONObject("track") ?: continue
            val (title, artist) = scTitle(t)
            songs += TasteSong(title, artist, null, 1.0 * decay(i), "soundcloud")
            artists += artist
        }
        return Taste(songs, artistWeights(artists), plays, me.optString("username").takeIf { it.isNotBlank() })
    }

    /** SoundCloud uploads often put "Artist - Title" in the title; the uploader is the fallback. */
    internal fun scTitle(t: JSONObject): Pair<String, String> {
        val pub = t.optJSONObject("publisher_metadata")
        val raw = t.optString("title")
        val artist = pub?.optString("artist")?.takeIf { it.isNotBlank() }
            ?: raw.substringBefore(" - ", "").takeIf { it.isNotBlank() }
            ?: t.optJSONObject("user")?.optString("username").orEmpty()
        val title = if (raw.contains(" - ") && pub?.optString("artist").isNullOrBlank()) raw.substringAfter(" - ") else raw
        return title.trim() to artist.trim()
    }

    // Deezer: who you are from the site (with your cookie), then your public favourites and charts.
    private fun deezer(): Taste {
        val cookie = runCatching { CookieManager.getInstance().getCookie("https://www.deezer.com") }.getOrNull()
            ?: error("Deezer's session ran out — sign in again")
        val user = get(
            "https://www.deezer.com/ajax/gw-light.php?method=deezer.getUserData&input=3&api_version=1.0&api_token=",
            mapOf("Cookie" to cookie),
        )?.let { JSONObject(it).optJSONObject("results")?.optJSONObject("USER") } ?: error("Deezer didn't answer")
        val uid = user.optLong("USER_ID").takeIf { it > 0 } ?: error("Not signed in to Deezer")
        fun api(path: String) = get("https://api.deezer.com/user/$uid/$path")?.let { JSONObject(it).optJSONArray("data") }
        val songs = ArrayList<TasteSong>()
        val artists = ArrayList<String>()
        fun add(a: JSONArray?, w: Double) {
            a ?: return
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val artist = o.optJSONObject("artist")?.optString("name").orEmpty()
                songs += TasteSong(o.optString("title"), artist, null, w * decay(i), "deezer")
                artists += artist
            }
        }
        add(api("charts/tracks?limit=50"), 1.0)
        add(api("tracks?limit=100"), 0.7)
        api("artists?limit=50")?.let { a -> for (i in 0 until a.length()) artists += a.getJSONObject(i).optString("name") }
        return Taste(songs, artistWeights(artists), name = user.optString("BLOG_NAME").takeIf { it.isNotBlank() })
    }

    // Last.fm: the latest scrobbles from the public profile.
    private suspend fun lastFm(): Taste {
        val u = _connections.value[Service.LASTFM]?.name ?: return Taste()
        val html = get("https://www.last.fm/user/${URLEncoder.encode(u, "UTF-8")}/library") ?: error("Last.fm didn't answer")
        val plays = com.ltots.app.data.HistoryImport.lastFmScrobbles(html)
        val songs = plays.mapIndexed { i, p -> TasteSong(p.title, p.artist, null, 0.8 * decay(i), "lastfm") }
        return Taste(songs, artistWeights(plays.map { it.artist }), plays, u)
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", Platforms.BROWSER_UA)
        c.setRequestProperty("Accept", "application/json, text/html;q=0.9")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }.getOrNull()
}
