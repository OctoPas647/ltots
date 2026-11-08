package com.ltots.app.recs

import android.content.Context
import com.ltots.app.audio.AudioEngine
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.YtAlbum
import com.ltots.app.ytm.YtArtist
import com.ltots.app.ytm.YtSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** A radio: the endless queue YouTube Music plays after [seed]. */
data class Radio(val title: String, val subtitle: String, val thumbUrl: String?, val seed: YtSong)

/** The Home tab's "what's popular" part, for one country (or worldwide). */
data class DiscoverFeed(
    val region: String,
    val trending: List<YtSong>,
    val topSongs: List<YtSong>,
    val artists: List<YtArtist>,
    val albums: List<YtAlbum>,
    val radios: List<Radio>,
    val builtAt: Long,
)

/**
 * Popular music for the Home tab, from YouTube Music's charts and new releases: trending songs,
 * the top songs and artists of the chosen country (Home > region, worldwide by default), new
 * albums, and radios started from popular songs and from the user's favourites.
 */
object Discover {
    private const val MAX_AGE_MS = 3L * 3600_000L

    private val _feed = MutableStateFlow<DiscoverFeed?>(null)
    val feed: StateFlow<DiscoverFeed?> = _feed
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val lock = Mutex()

    /** Countries to pick from, by name; "ZZ" is worldwide. */
    fun regions(): List<Pair<String, String>> =
        listOf("ZZ" to "Worldwide") + Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(Locale.ENGLISH) }.sortedBy { it.second }

    fun regionName(code: String): String = if (code == "ZZ") "Worldwide" else Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }

    suspend fun load(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val region = AudioEngine.settings.value.chartsRegion
        val f = _feed.value
        if (!force && f != null && f.region == region && System.currentTimeMillis() - f.builtAt < MAX_AGE_MS) return@withContext
        if (lock.isLocked) return@withContext
        lock.withLock {
            _loading.value = true
            try {
                runCatching { build(context, region) }.getOrNull()?.let { _feed.value = it }
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun build(context: Context, region: String): DiscoverFeed = coroutineScope {
        val charts = async { runCatching { InnerTube.charts(region) }.getOrDefault(emptyList()) }
        val releases = async { runCatching { InnerTube.newReleases() }.getOrDefault(emptyList()) }
        val taste = async { runCatching { AccountTaste.load(context) }.getOrNull() }
        val sections = charts.await()
        val artists = sections.flatMap { it.items.filterIsInstance<YtArtist>() }.distinctBy { it.browseId }.take(20)
        val songSections = sections.filter { s -> s.items.any { it is YtSong } }
        val trending = songSections.filter { it.title?.contains("trend", true) == true }.flatMap { it.items.filterIsInstance<YtSong>() }
            .distinctBy { it.videoId }.take(20)
        val top = songSections.filter { it.title?.contains("trend", true) != true }.flatMap { it.items.filterIsInstance<YtSong>() }
            .distinctBy { it.videoId }.filter { s -> trending.none { it.videoId == s.videoId } }.take(20)
        val albums = releases.await().filterIsInstance<YtAlbum>().distinctBy { it.browseId }.take(20)

        // Radios: from the user's own favourites first (liked, found with the song finder), then
        // from what's popular.
        val mine = taste.await()?.songs.orEmpty().filter { it.videoId != null }.sortedByDescending { it.weight }
            .map { YtSong(it.videoId!!, it.title, listOf(com.ltots.app.ytm.YtRef(it.artist, null))) }
        val seeds = (mine.take(6) + top.take(4) + trending.take(4)).distinctBy { com.ltots.app.download.Suggestions.key(it.title, it.artists.firstOrNull()?.name) }
            .distinctBy { it.artists.firstOrNull()?.name?.lowercase() ?: it.videoId }.take(10)
        val radios = seeds.map { s -> Radio("${s.title} Radio", s.artistText.ifBlank { "Songs like this" }, s.thumbUrl ?: "https://i.ytimg.com/vi/${s.videoId}/hqdefault.jpg", s) }
        DiscoverFeed(region, trending, top, artists, albums, radios, System.currentTimeMillis())
    }

    /** A radio's songs (YouTube Music's queue after its song). */
    suspend fun radioSongs(r: Radio): List<RecSong> =
        InnerTube.radio(r.seed.videoId).distinctBy { it.videoId }.take(40).map { RecSong(it, 0.0, "Radio") }
}
