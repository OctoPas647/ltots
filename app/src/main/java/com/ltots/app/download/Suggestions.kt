package com.ltots.app.download

import android.content.Context
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.Track
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.Parser
import com.ltots.app.ytm.YtSong
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Songs to download next, found with YouTube Music's own "radio" (what it would play after a
 * song): on the Get tab before anything is searched, songs like the last few downloads (or,
 * without downloads yet, the newest songs on the phone); after a search, songs like the one
 * found. Songs already on the phone, downloaded or in the queue are left out.
 */
object Suggestions {
    /** How many songs the Get tab's start page suggests. */
    const val HOME_COUNT = 15
    /** How many songs a search suggests under its results. */
    const val SEARCH_COUNT = 10
    /** How many recent songs the start page's suggestions are based on. */
    private const val SEEDS = 3

    /** Suggestions for the start page, and the songs they're based on. */
    data class Home(val basedOn: List<String>, val songs: List<YtSong>, val seedKey: String)

    private val _home = MutableStateFlow<Home?>(null)
    val home: StateFlow<Home?> = _home

    private val related = HashMap<String, List<YtSong>>()

    /** A song from the library or a download to base suggestions on. */
    private data class Seed(val label: String, val key: String, val videoId: String?, val query: String)

    /**
     * Fills [home] for the newest downloads / songs. Kept while they stay the same, so opening
     * the tab again costs nothing; [force] asks YouTube Music again (new picks).
     */
    suspend fun loadHome(context: Context, force: Boolean = false) {
        val seeds = homeSeeds(context)
        if (seeds.isEmpty()) { _home.value = Home(emptyList(), emptyList(), ""); return }
        val seedKey = seeds.joinToString("·") { it.key }
        if (!force && _home.value?.seedKey == seedKey && _home.value?.songs?.isNotEmpty() == true) return
        val radios = coroutineScope {
            seeds.map { s ->
                async {
                    runCatching {
                        val id = s.videoId ?: InnerTube.findSong(s.query)?.videoId ?: return@runCatching emptyList()
                        InnerTube.radio(id).let { if (force) it.shuffled() else it }
                    }.getOrDefault(emptyList())
                }
            }.map { it.await() }
        }
        _home.value = Home(seeds.map { it.label }, merge(radios, skipper(context, seeds.mapNotNull { it.videoId }), HOME_COUNT), seedKey)
    }

    /** Songs like [song], for under its search results (remembered while the app runs). */
    suspend fun forSong(context: Context, song: YtSong): List<YtSong> {
        val radio = synchronized(related) { related[song.videoId] } ?: InnerTube.radio(song.videoId).also {
            synchronized(related) { related[song.videoId] = it }
        }
        return merge(listOf(radio), skipper(context, listOf(song.videoId)), SEARCH_COUNT)
    }

    /** The last few downloads, then the songs that arrived on the phone most recently. */
    private suspend fun homeSeeds(context: Context): List<Seed> {
        val seeds = DownloadManager.tasks.value
            .filter { it.state == TaskState.DONE }
            .sortedByDescending { it.finishedAt }
            .distinctBy { it.meta.videoId }
            .take(SEEDS)
            .map { Seed(it.meta.title, key(it.meta.title, it.meta.artist), it.meta.videoId, "${it.meta.title} ${it.meta.artist}") }
            .toMutableList()
        if (seeds.size < SEEDS) {
            val library = LocalLibrary.cached() ?: runCatching { LocalLibrary.load(context) }.getOrDefault(emptyList())
            newest(library).map { t ->
                val artist = t.artist.takeIf { it != Track.UNKNOWN }
                Seed(t.title, key(t.title, artist), null, listOfNotNull(t.title, artist).joinToString(" "))
            }.filter { s -> seeds.none { it.key == s.key } }.take(SEEDS - seeds.size).forEach { seeds += it }
        }
        return seeds
    }

    /** The phone's songs, newest first. */
    fun newest(library: List<Track>): List<Track> = library.filter { it.addedAt != null }.sortedByDescending { it.addedAt }

    /** Leaves out what's already here: downloaded, queued, on the phone, or a seed itself. */
    private fun skipper(context: Context, seedIds: List<String>): (YtSong) -> Boolean {
        val have = DownloadManager.downloaded.value.keys + DownloadManager.tasks.value.filter { !it.state.finished }.map { it.meta.videoId } + seedIds
        val onPhone = (LocalLibrary.cached() ?: emptyList()).mapTo(HashSet()) { key(it.title, it.artist.takeIf { a -> a != Track.UNKNOWN }) }
        return { s -> s.videoId in have || key(s.title, s.artists.firstOrNull()?.name) in onPhone }
    }

    /**
     * One list from several radios: taking turns (the first of each, then the second of each…)
     * so every seed gets a say, with each song only once (a video and its audio count as one)
     * and [skip]ped songs left out.
     */
    fun merge(radios: List<List<YtSong>>, skip: (YtSong) -> Boolean, limit: Int): List<YtSong> {
        val out = mutableListOf<YtSong>()
        val seen = HashSet<String>()
        val longest = radios.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (r in radios) {
                val s = r.getOrNull(i) ?: continue
                if (skip(s) || !seen.add(key(s.title, s.artists.firstOrNull()?.name))) continue
                out += s
                if (out.size == limit) return out
            }
        }
        return out
    }

    /** Same song whatever its spelling: "Artist - Title (Official Video)" = "Title" by Artist. */
    fun key(title: String, artist: String?): String {
        val a = artist?.split(',', '&')?.firstOrNull()?.trim()?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")?.trim().orEmpty()
        return Parser.normalizeTitle(title, artist) + "|" + a
    }
}
