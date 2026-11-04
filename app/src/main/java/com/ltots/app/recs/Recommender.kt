package com.ltots.app.recs

import android.content.Context
import com.ltots.app.audio.AudioEngine
import com.ltots.app.data.Listen
import com.ltots.app.data.ListenHistory
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.PlayStats
import com.ltots.app.data.Store
import com.ltots.app.data.Track
import com.ltots.app.download.DownloadManager
import com.ltots.app.download.Suggestions
import com.ltots.app.download.TaskState
import com.ltots.app.lyrics.SongMatch
import com.ltots.app.moods.Clap
import com.ltots.app.moods.MoodIndex
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.YtAlbum
import com.ltots.app.ytm.YtItem
import com.ltots.app.ytm.YtPlaylist
import com.ltots.app.ytm.YtRef
import com.ltots.app.ytm.YtSong
import com.ltots.app.ytm.YtmAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.max

/** A song the Home tab recommends, and why. */
data class RecSong(val song: YtSong, val score: Double, val reason: String)

/** One row of the Home tab. */
data class Shelf(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val kind: Kind,
    val songs: List<RecSong> = emptyList(),
    /** Albums and playlists (YouTube Music's own shelves, when signed in). */
    val items: List<YtItem> = emptyList(),
) {
    enum class Kind { QUICK_PICKS, BECAUSE, AI_VIBE, YOUTUBE }
}

data class HomeFeed(
    val shelves: List<Shelf>,
    val builtAt: Long,
    /** The songs the picks are based on (for "Based on …"). */
    val basedOn: List<String>,
    /** How many plays the picks learnt from. */
    val historySize: Int,
    val signedIn: Boolean,
)

/** One of the user's songs the recommendations start from, and how much it counts. */
data class Seed(val key: String, val title: String, val artist: String, val weight: Double, val trackId: Long = -1L, val videoId: String? = null)

/**
 * The Home tab's recommendations, YouTube Music style: what the user listens to (the in-app
 * history — finished songs count more than skipped ones, recent plays more than old ones — plus
 * play counts and downloads, and their YouTube Music history when signed in) picks a few seed
 * songs; YouTube Music's own "radio" for each seed gives candidates; a candidate several seeds
 * point to, or by an artist the user loves, ranks higher; songs already on the phone, downloaded,
 * by hidden artists or marked "Not interested" are left out; and no artist gets to crowd the top.
 *
 * The **AI** part: the on-device music model (CLAP, the one behind moods and AI albums) has heard
 * the user's songs; their recent listening, weighted the same way, gives a "sound" — which of
 * the AI's descriptions ("Night drive", "Lo-fi corner", "Heartstrings"…) it's closest to, beyond
 * what's usual for the library — and that becomes shelves of new songs in that vibe.
 */
object Recommender {
    /** Rebuilt at most this often on its own (a refresh rebuilds at once). */
    private const val MAX_AGE_MS = 6L * 3600_000L
    private const val TAU_DAYS = 21.0
    private const val SEEDS = 6
    private const val QUICK_PICKS = 24
    private const val SHELF_SIZE = 14

    private val _feed = MutableStateFlow<HomeFeed?>(null)
    val feed: StateFlow<HomeFeed?> = _feed
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val lock = Mutex()
    @Volatile private var stale = false
    private var loadedFrom: String? = null

    private fun feedFile() = Store.userFile("recommendations.json")
    private fun feedbackFile() = Store.userFile("rec_feedback.json")
    private fun idsFile() = File(Store.rootDir, "yt_ids.json")

    /** Forget the current picks (signed in/out, history cleared): the next load builds new ones. */
    fun invalidate() { stale = true }

    // ---- feedback ----------------------------------------------------------------------------

    private var feedback: Feedback? = null
    private data class Feedback(val notInterested: MutableSet<String>, val lessArtists: MutableSet<String>)

    private fun feedback(): Feedback = feedback ?: run {
        val o = runCatching { JSONObject(feedbackFile().readText()) }.getOrNull()
        fun set(k: String) = o?.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toMutableSet()
        Feedback(set("not"), set("less")).also { feedback = it }
    }

    private fun saveFeedback() {
        val f = feedback()
        Store.writeLater(feedbackFile()) { JSONObject().put("not", JSONArray(f.notInterested.toList())).put("less", JSONArray(f.lessArtists.toList())).toString() }
    }

    /** "Not interested": never suggested again, and gone from the screen at once. */
    fun notInterested(song: YtSong) {
        feedback().notInterested += song.videoId
        feedback().notInterested += Suggestions.key(song.title, song.artists.firstOrNull()?.name)
        saveFeedback()
        _feed.value = _feed.value?.let { f -> f.copy(shelves = f.shelves.map { s -> s.copy(songs = s.songs.filterNot { it.song.videoId == song.videoId }) }) }
    }

    /** "Fewer songs by this artist". */
    fun lessOfArtist(artist: String) {
        feedback().lessArtists += norm(artist)
        saveFeedback()
        _feed.value = _feed.value?.let { f ->
            f.copy(shelves = f.shelves.map { s -> s.copy(songs = s.songs.filterNot { norm(it.song.artists.firstOrNull()?.name ?: "") == norm(artist) }) })
        }
    }

    // ---- loading -----------------------------------------------------------------------------

    /** Shows the saved picks at once, then builds new ones when they're old (or [force]). */
    suspend fun load(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (loadedFrom != feedFile().path) {
            loadedFrom = feedFile().path
            _feed.value = runCatching { readFeed(JSONObject(feedFile().readText())) }.getOrNull()
        }
        val f = _feed.value
        val newPlays = f?.let { feed -> ListenHistory.items.value.count { it.at > feed.builtAt } } ?: 0
        if (!force && !stale && f != null && System.currentTimeMillis() - f.builtAt < MAX_AGE_MS && newPlays < 8 &&
            f.signedIn == YtmAccount.signedIn) return@withContext
        if (lock.isLocked) return@withContext
        lock.withLock {
            _loading.value = true
            _error.value = null
            try {
                val built = build(context, force)
                stale = false
                _feed.value = built
                Store.writeLater(feedFile()) { writeFeed(built).toString() }
            } catch (e: NothingYet) {
                _error.value = if (_feed.value == null) "Play or download a few songs and recommendations show up here." else null
            } catch (e: Exception) {
                _error.value = if (_feed.value == null) "Couldn't load recommendations. Check your connection and tap ↻." else null
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun build(context: Context, force: Boolean = false): HomeFeed = coroutineScope {
        ListenHistory.ensureLoaded()
        val history = ListenHistory.items.value
        val library = runCatching { LocalLibrary.loadSongs(context) }.getOrDefault(emptyList())
        val plays = PlayStats.plays.value
        val fb = feedback()
        val settings = AudioEngine.settings.value
        val hidden = settings.hiddenArtists.map { norm(it) }.toSet()

        // YouTube Music, when signed in: its history adds seeds, its home adds shelves.
        val signed = YtmAccount.signedIn
        val ytHome = if (signed) async { runCatching { InnerTube.home() }.getOrDefault(emptyList()) } else null
        val ytHistory = if (signed) runCatching { InnerTube.history() }.getOrDefault(emptyList()) else emptyList()
        // The accounts the user signed in to: YouTube Music likes, playlists, subscriptions and
        // saved albums; Instagram's saved and liked reels; songs found with the song finder.
        val account = runCatching { AccountTaste.load(context, force) }.getOrDefault(AccountTaste.Signals())

        // 1. Taste: seed songs and how much the user likes each artist.
        val now = System.currentTimeMillis()
        val downloads = DownloadManager.tasks.value.filter { it.state == TaskState.DONE }.sortedByDescending { it.finishedAt }.take(20)
            .map { Seed(Suggestions.key(it.meta.title, it.meta.artist), it.meta.title, it.meta.artist, 0.6 * decay(now - it.finishedAt), videoId = it.meta.videoId) }
        val local = Taste.seeds(history, plays, library, now)
        val fromYt = ytHistory.take(30).mapIndexed { i, s -> Seed("yt:" + s.videoId, s.title, s.artistText, 0.9 * exp(-i / 10.0), videoId = s.videoId) }
        val fromAccounts = account.songs.filter { it.title.isNotBlank() }.map {
            Seed(it.videoId?.let { v -> "yt:$v" } ?: Suggestions.key(it.title, it.artist), it.title, it.artist, it.weight, videoId = it.videoId)
        }
        // What they've played most of all time, imported plays (Spotify, Last.fm…) included.
        val allTime = runCatching { com.ltots.app.data.ListenStats.summary(null).songs.take(40) }.getOrDefault(emptyList())
        val topMs = allTime.firstOrNull()?.ms?.takeIf { it > 0 }?.toDouble() ?: 1.0
        val fromStats = allTime.map { Seed(it.key, it.title, it.artist, 0.3 * (it.ms / topMs)) }
        val all = Taste.combine(local + downloads + fromYt + fromAccounts + fromStats)
        // Artists they follow or saved albums of count as loved, even before they're played here.
        val artistLove = Taste.artistAffinity(all).toMutableMap().also { m ->
            account.artists.forEach { (a, w) -> m[a] = maxOf(m[a] ?: 0.0, w) }
        }
        val seeds = Taste.pickSeeds(all, SEEDS)
        if (seeds.isEmpty() && ytHome == null) throw NothingYet()

        // 2. Candidates from each seed's radio.
        val ids = loadIds()
        val gate = Semaphore(3)
        val radios = seeds.map { s ->
            async {
                gate.withPermit {
                    val vid = s.videoId ?: ids[s.key] ?: runCatching { resolve(s) }.getOrNull()?.also { ids[s.key] = it }
                    s to (vid?.let { v -> runCatching { InnerTube.radio(v) }.getOrDefault(emptyList()) } ?: emptyList())
                }
            }
        }.awaitAll()
        saveIds(ids)

        // What not to suggest: already here, hidden, or turned down.
        val onPhone = library.mapTo(HashSet()) { Suggestions.key(it.title, it.artist.takeIf { a -> a != Track.UNKNOWN }) }
        val have = DownloadManager.downloaded.value.keys + DownloadManager.tasks.value.filter { !it.state.finished }.map { it.meta.videoId } +
            radios.mapNotNull { it.first.videoId }
        val seedKeys = seeds.map { Suggestions.key(it.title, it.artist) }.toSet()
        fun skip(s: YtSong): Boolean {
            val k = Suggestions.key(s.title, s.artists.firstOrNull()?.name)
            val a = norm(s.artists.firstOrNull()?.name ?: "")
            return s.videoId in have || s.videoId in account.likedIds || k in onPhone || k in seedKeys || s.videoId in fb.notInterested || k in fb.notInterested ||
                a in hidden || hidden.any { h -> s.artists.any { norm(it.name) == h } }
        }

        // 3. Score: seeds that point to a song add up; loved artists rank higher; turned-down
        // artists lower.
        val totalW = seeds.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0
        val cands = LinkedHashMap<String, Cand>()
        for ((seed, radio) in radios) {
            radio.filterNot(::skip).forEachIndexed { rank, s ->
                val k = Suggestions.key(s.title, s.artists.firstOrNull()?.name)
                val c = cands.getOrPut(k) { Cand(s) }
                c.score += (seed.weight / totalW) * (1.0 / (1.0 + rank / 6.0))
                c.seeds += seed.title
                if (c.song.isVideo && !s.isVideo) c.song = s
            }
        }
        // AI: the sound of what the user's been playing.
        val vibes = runCatching { AiVibes.vibes(context, history, library, now) }.getOrDefault(emptyList())
        val vibeSongs = vibes.map { v ->
            async {
                gate.withPermit { v to runCatching { searchSongs("${v.label} songs") }.getOrDefault(emptyList()).filterNot(::skip) }
            }
        }.awaitAll()
        for ((v, songs) in vibeSongs) songs.forEachIndexed { rank, s ->
            val k = Suggestions.key(s.title, s.artists.firstOrNull()?.name)
            val c = cands.getOrPut(k) { Cand(s) }
            c.score += 0.35 * v.strength / (1.0 + rank / 8.0)
            c.vibe = c.vibe ?: v.label
        }
        val home = ytHome?.await().orEmpty()
        // Songs YouTube Music itself picks for the user count too.
        home.firstOrNull { it.title?.contains("quick picks", true) == true }?.items?.filterIsInstance<YtSong>()?.filterNot(::skip)?.forEachIndexed { rank, s ->
            val k = Suggestions.key(s.title, s.artists.firstOrNull()?.name)
            val c = cands.getOrPut(k) { Cand(s) }
            c.score += 0.5 / (1.0 + rank / 10.0)
            c.fromYt = true
        }
        for (c in cands.values) {
            val artist = norm(c.song.artists.firstOrNull()?.name ?: "")
            c.score += 0.45 * (artistLove[artist] ?: 0.0)
            c.score += 0.12 * (c.seeds.size - 1).coerceAtLeast(0)
            if (artist in fb.lessArtists) c.score -= 0.6
        }
        val ranked = Taste.diversify(cands.values.sortedByDescending { it.score }.map { RecSong(it.song, it.score, it.reason()) }, QUICK_PICKS * 2)

        // 4. Shelves.
        val shelves = ArrayList<Shelf>()
        val quick = ranked.take(QUICK_PICKS)
        if (quick.isNotEmpty()) shelves += Shelf("quick", "Quick picks", "Songs you'll probably like, ready to preview", Shelf.Kind.QUICK_PICKS, quick)
        val shownTop = quick.take(8).map { it.song.videoId }.toSet()
        for ((seed, radio) in radios.filter { it.second.isNotEmpty() }.take(3)) {
            val songs = radio.filterNot(::skip).filter { it.videoId !in shownTop }.distinctBy { Suggestions.key(it.title, it.artists.firstOrNull()?.name) }
                .take(SHELF_SIZE).map { RecSong(it, 0.0, "Like “${seed.title}”") }
            if (songs.size >= 4) shelves += Shelf("because:${seed.key}", "Because you listened to", "${seed.title} · ${seed.artist}".trim(' ', '·'), Shelf.Kind.BECAUSE, songs)
        }
        for ((v, songs) in vibeSongs) {
            val list = songs.distinctBy { Suggestions.key(it.title, it.artists.firstOrNull()?.name) }.take(SHELF_SIZE).map { RecSong(it, 0.0, "AI: ${v.label}") }
            if (list.size >= 4) shelves += Shelf("vibe:${v.label}", "AI picks · ${v.label}", "Your listening lately sounds like this", Shelf.Kind.AI_VIBE, list)
        }
        for (h in home.filter { it.title?.contains("quick picks", true) != true }.take(4)) {
            val songs = h.items.filterIsInstance<YtSong>().filterNot(::skip).take(SHELF_SIZE).map { RecSong(it, 0.0, "From your YouTube Music") }
            val others = h.items.filter { it is YtAlbum || it is YtPlaylist }.take(SHELF_SIZE)
            if (songs.size + others.size >= 3) shelves += Shelf("yt:${h.title}", h.title ?: "From YouTube Music", "From your YouTube Music", Shelf.Kind.YOUTUBE, songs, others)
        }
        HomeFeed(shelves, System.currentTimeMillis(), seeds.map { it.title }, history.size, signed)
    }

    private class NothingYet : Exception("Nothing to base recommendations on yet")

    private class Cand(var song: YtSong) {
        var score = 0.0
        val seeds = LinkedHashSet<String>()
        var vibe: String? = null
        var fromYt = false
        fun reason(): String = when {
            seeds.size >= 2 -> "Like “${seeds.first()}” and “${seeds.elementAt(1)}”"
            seeds.size == 1 -> "Like “${seeds.first()}”"
            vibe != null -> "AI: $vibe"
            fromYt -> "From your YouTube Music"
            else -> "For you"
        }
    }

    private fun decay(ageMs: Long): Double = exp(-(ageMs / 86_400_000.0) / TAU_DAYS)

    /** A local song's YouTube Music id: the first song result whose name matches. */
    private suspend fun resolve(s: Seed): String? {
        val q = listOf(s.title, s.artist).filter { it.isNotBlank() }.joinToString(" ")
        val songs = searchSongs(q)
        return songs.firstOrNull { SongMatch.similarity(SongMatch.cleanTitle(s.title), SongMatch.cleanTitle(it.title)) >= 0.6 }?.videoId
    }

    private suspend fun searchSongs(q: String): List<YtSong> {
        val r = InnerTube.search(q)
        val songsChip = r.chips.firstOrNull { it.text.equals("Songs", true) }?.params
        val songs = songsChip?.let { p -> runCatching { InnerTube.search(q, p).sections.flatMap { it.items }.filterIsInstance<YtSong>() }.getOrNull() }.orEmpty()
        return (songs + r.sections.flatMap { it.items }.filterIsInstance<YtSong>()).filter { !it.isVideo }.distinctBy { it.videoId }
    }

    /** Songs for a mood chip ("Energize", "Relax"…), leaning towards the artists the user plays. */
    suspend fun mood(context: Context, mood: String): List<RecSong> = withContext(Dispatchers.IO) {
        ListenHistory.ensureLoaded()
        val library = runCatching { LocalLibrary.loadSongs(context) }.getOrDefault(emptyList())
        val love = Taste.artistAffinity(Taste.seeds(ListenHistory.items.value, PlayStats.plays.value, library, System.currentTimeMillis()))
        val onPhone = library.mapTo(HashSet()) { Suggestions.key(it.title, it.artist.takeIf { a -> a != Track.UNKNOWN }) }
        val fb = feedback()
        val songs = searchSongs("$mood songs").filterNot { s ->
            s.videoId in fb.notInterested || Suggestions.key(s.title, s.artists.firstOrNull()?.name) in onPhone
        }
        Taste.diversify(
            songs.mapIndexed { i, s -> RecSong(s, 1.0 / (1 + i / 8.0) + 0.5 * (love[norm(s.artists.firstOrNull()?.name ?: "")] ?: 0.0), mood) }
                .sortedByDescending { it.score },
            30,
        )
    }

    private fun norm(s: String) = SongMatch.norm(SongMatch.mainArtist(s))

    // ---- id cache and saved feed -------------------------------------------------------------

    private fun loadIds(): MutableMap<String, String> = runCatching {
        val o = JSONObject(idsFile().readText())
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun saveIds(ids: Map<String, String>) {
        val o = JSONObject(); ids.entries.toList().takeLast(2000).forEach { (k, v) -> o.put(k, v) }
        Store.writeLater(idsFile()) { o.toString() }
    }

    private fun songJson(s: YtSong) = JSONObject().put("v", s.videoId).put("t", s.title)
        .put("a", JSONArray(s.artists.map { JSONObject().put("n", it.name).put("i", it.id ?: "") }))
        .put("al", s.album?.name ?: "").put("ali", s.album?.id ?: "").put("d", s.durationSec ?: 0).put("th", s.thumbUrl ?: "").put("vid", s.isVideo)

    private fun songOf(o: JSONObject): YtSong {
        val a = o.optJSONArray("a") ?: JSONArray()
        return YtSong(
            o.getString("v"), o.optString("t"),
            (0 until a.length()).map { a.getJSONObject(it).let { r -> YtRef(r.optString("n"), r.optString("i").ifBlank { null }) } },
            o.optString("al").ifBlank { null }?.let { YtRef(it, o.optString("ali").ifBlank { null }) },
            durationSec = o.optInt("d").takeIf { it > 0 }, thumbUrl = o.optString("th").ifBlank { null }, isVideo = o.optBoolean("vid"),
        )
    }

    private fun writeFeed(f: HomeFeed): JSONObject = JSONObject()
        .put("at", f.builtAt).put("n", f.historySize).put("signed", f.signedIn).put("based", JSONArray(f.basedOn))
        .put("shelves", JSONArray(f.shelves.map { s ->
            JSONObject().put("id", s.id).put("title", s.title).put("sub", s.subtitle ?: "").put("kind", s.kind.name)
                .put("songs", JSONArray(s.songs.map { r -> songJson(r.song).put("score", r.score).put("why", r.reason) }))
        }))

    private fun readFeed(o: JSONObject): HomeFeed {
        val sh = o.getJSONArray("shelves")
        val shelves = (0 until sh.length()).map { i ->
            val s = sh.getJSONObject(i)
            val songs = s.getJSONArray("songs")
            Shelf(
                s.getString("id"), s.getString("title"), s.optString("sub").ifBlank { null }, Shelf.Kind.valueOf(s.getString("kind")),
                (0 until songs.length()).map { j -> songs.getJSONObject(j).let { r -> RecSong(songOf(r), r.optDouble("score"), r.optString("why")) } },
            )
        }.filter { it.songs.isNotEmpty() }
        val based = o.optJSONArray("based")
        return HomeFeed(shelves, o.getLong("at"), (0 until (based?.length() ?: 0)).map { based!!.getString(it) }, o.optInt("n"), o.optBoolean("signed"))
    }
}

/** How the user's listening turns into taste: pure, so it can be tested. */
object Taste {
    private const val TAU_DAYS = 21.0

    /**
     * One weight per song from the history: a finished song counts fully, half a song about
     * two thirds, a skip counts against it; recent plays count more (three weeks to fall to a third).
     * All-time play counts and, with no history at all, the newest songs on the phone fill in.
     */
    fun seeds(history: List<Listen>, plays: Map<String, Int>, library: List<Track>, now: Long): List<Seed> {
        val byKey = LinkedHashMap<String, Seed>()
        for (l in history) {
            val age = (now - l.at).coerceAtLeast(0) / 86_400_000.0
            val w = (if (l.skipped) -0.4 else 0.35 + 0.65 * l.completion) * exp(-age / TAU_DAYS)
            val prev = byKey[l.key]
            byKey[l.key] = prev?.copy(weight = prev.weight + w) ?: Seed(l.key, l.title, l.artist, w, l.trackId)
        }
        val tracks = library.associateBy { it.profileKey }
        for ((k, n) in plays) {
            val t = tracks[k] ?: continue
            val w = 0.12 * n
            val prev = byKey[k]
            byKey[k] = prev?.copy(weight = prev.weight + w) ?: Seed(k, t.title, t.artist.takeIf { it != Track.UNKNOWN } ?: "", w, t.id)
        }
        if (byKey.isEmpty()) {
            library.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(5).forEachIndexed { i, t ->
                byKey[t.profileKey] = Seed(t.profileKey, t.title, t.artist.takeIf { it != Track.UNKNOWN } ?: "", 0.5 / (1 + i), t.id)
            }
        }
        return byKey.values.toList()
    }

    /** The same song from several sources (history, downloads, YouTube Music) counts once, added up. */
    fun combine(seeds: List<Seed>): List<Seed> =
        seeds.groupBy { Suggestions.key(it.title, it.artist) }.values.map { g ->
            g.reduce { a, b -> a.copy(weight = a.weight + b.weight, videoId = a.videoId ?: b.videoId, trackId = if (a.trackId >= 0) a.trackId else b.trackId) }
        }

    /** How much each artist (main artist, normalised) is loved, 0..1. */
    fun artistAffinity(seeds: List<Seed>): Map<String, Double> {
        val sums = HashMap<String, Double>()
        for (s in seeds) if (s.artist.isNotBlank() && s.weight > 0) {
            val a = SongMatch.norm(SongMatch.mainArtist(s.artist))
            sums[a] = (sums[a] ?: 0.0) + s.weight
        }
        val top = sums.values.maxOrNull()?.takeIf { it > 0 } ?: return emptyMap()
        return sums.mapValues { it.value / top }
    }

    /** The best-loved songs, at most two per artist, liked ones only. */
    fun pickSeeds(seeds: List<Seed>, n: Int): List<Seed> {
        val out = ArrayList<Seed>()
        val perArtist = HashMap<String, Int>()
        for (s in seeds.filter { it.weight > 0.05 }.sortedByDescending { it.weight }) {
            val a = SongMatch.norm(SongMatch.mainArtist(s.artist))
            if ((perArtist[a] ?: 0) >= 2 && a.isNotBlank()) continue
            perArtist[a] = (perArtist[a] ?: 0) + 1
            out += s
            if (out.size == n) break
        }
        return out
    }

    /**
     * Keeps the best first but stops one artist from taking over: each further song by an
     * artist already picked counts a bit less (maximal marginal relevance).
     */
    fun diversify(ranked: List<RecSong>, n: Int): List<RecSong> {
        val pool = ranked.toMutableList()
        val out = ArrayList<RecSong>()
        val count = HashMap<String, Int>()
        val top = ranked.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
        while (pool.isNotEmpty() && out.size < n) {
            val best = pool.maxBy { r ->
                val a = SongMatch.norm(SongMatch.mainArtist(r.song.artists.firstOrNull()?.name ?: ""))
                r.score - 0.35 * top * (count[a] ?: 0)
            }
            pool.remove(best)
            val a = SongMatch.norm(SongMatch.mainArtist(best.song.artists.firstOrNull()?.name ?: ""))
            count[a] = (count[a] ?: 0) + 1
            out += best
        }
        return out
    }
}

/** The AI side: what the user's recent listening sounds like, in the music model's words. */
object AiVibes {
    data class Vibe(val label: String, val strength: Double)

    /**
     * The history's songs that the on-device model has heard, weighted like [Taste.seeds],
     * make one "sound"; the AI album descriptions it's closest to — compared with the library as
     * a whole, so "happy" doesn't win for everything — are the vibes (at most two).
     */
    fun vibes(context: Context, history: List<Listen>, library: List<Track>, now: Long): List<Vibe> {
        MoodIndex.load()
        val emb = MoodIndex.embeddings.value
        if (emb.isEmpty()) return emptyList()
        val text = MoodIndex.text(context).albums
        if (text.isEmpty()) return emptyList()
        val weights = Taste.seeds(history, emptyMap(), library, now).filter { it.weight > 0 && it.key in emb }
        if (weights.size < 3) return emptyList()
        val taste = FloatArray(512)
        for (s in weights) { val e = emb.getValue(s.key); for (i in taste.indices) taste[i] += (s.weight * e[i]).toFloat() }
        val t = Clap.normalize(taste)
        val libVecs = library.mapNotNull { emb[it.profileKey]?.takeIf { e -> e.isNotEmpty() } }
        val scored = text.map { p ->
            val base = if (libVecs.isEmpty()) 0f else libVecs.sumOf { Clap.dot(it, p.vec).toDouble() }.toFloat() / libVecs.size
            p.label to (Clap.dot(t, p.vec) - base).toDouble()
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.second?.takeIf { it > 0 } ?: return emptyList()
        return scored.take(2).filter { it.second > 0.4 * best }.map { Vibe(it.first, max(0.2, it.second / best)) }
    }
}

/** Seconds of the song worth previewing: its chorus is usually about a third in. */
internal fun previewStart(durationSec: Int?): Int = durationSec?.let { (it * 0.33).toInt().coerceIn(0, max(0, it - 30)) } ?: 45
