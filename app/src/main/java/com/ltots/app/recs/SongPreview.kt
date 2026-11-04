package com.ltots.app.recs

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ltots.app.download.Engine
import com.ltots.app.lyrics.SongMatch
import com.ltots.app.ytm.YtSong
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Listen to part of a recommended song before downloading it: 30 seconds, from the part most
 * likely to be the hook. Separate from the main player (which is paused meanwhile).
 *
 * The quickest source is the free iTunes preview (a 30-second clip of the chorus, there in well
 * under a second, no account); when iTunes doesn't have the song, it's streamed from YouTube
 * Music and played from about a third in.
 *
 * Played with the app's own player engine (Media3), which handles YouTube's audio format and the
 * request headers its streams want. A YouTube stream address stops working after a few hours, so
 * it's only reused while it's good; and when a preview fails, the next source is tried (iTunes
 * → YouTube, or YouTube looked up again) before it gives up.
 */
object SongPreview {
    const val LENGTH_MS = 30_000L
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    data class State(
        val videoId: String? = null,
        val title: String = "",
        val artist: String = "",
        val thumbUrl: String? = null,
        val loading: Boolean = false,
        /** 0..1 through the 30 seconds. */
        val progress: Float = 0f,
        val error: String? = null,
        /** The song itself, to download it from the preview bar. */
        val song: YtSong? = null,
    ) {
        val active: Boolean get() = videoId != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var ticker: Job? = null
    private var app: Context? = null

    /** Where a preview plays from, and until when that address works. */
    private class Src(val url: String, val startMs: Long, val headers: Map<String, String>, val youTube: Boolean, val goodUntil: Long) {
        val fresh: Boolean get() = System.currentTimeMillis() < goodUntil
    }
    private val sources = java.util.concurrent.ConcurrentHashMap<String, Src>()

    fun init(context: Context) { app = context.applicationContext }

    /** Starts [song]'s preview, or stops it if it's the one playing. [onStart] pauses the main player. */
    fun toggle(song: YtSong, onStart: () -> Unit = {}) {
        if (_state.value.videoId == song.videoId) { stop(); return }
        stop()
        _state.value = State(song.videoId, song.title, song.artistText, song.thumbUrl, loading = true, song = song)
        job = scope.launch { play(song, onStart, attempt = 0, skipItunes = false) }
    }

    private suspend fun play(song: YtSong, onStart: () -> Unit, attempt: Int, skipItunes: Boolean) {
        val ctx = app ?: return fail(song)
        val cached = sources[song.videoId]?.takeIf { it.fresh && !(skipItunes && !it.youTube) }
        val src = cached ?: withContext(Dispatchers.IO) { source(song, skipItunes) }?.also { sources[song.videoId] = it }
        if (_state.value.videoId != song.videoId) return
        if (src == null) return fail(song)
        val exo = newPlayer(ctx, src.headers)
        player = exo
        var started = false
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== exo) return
                if (state == Player.STATE_READY && !started) {
                    started = true
                    onStart()
                    _state.value = _state.value.copy(loading = false)
                    tick(exo)
                }
                if (state == Player.STATE_ENDED) stop()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exo) return
                release()
                sources.remove(song.videoId)
                when {
                    // The iTunes clip didn't play: the song itself, from YouTube.
                    !src.youTube -> job = scope.launch { play(song, onStart, attempt, skipItunes = true) }
                    // An expired or refused stream address: look it up again, once.
                    attempt == 0 -> job = scope.launch { play(song, onStart, attempt = 1, skipItunes = true) }
                    else -> fail(song)
                }
            }
        })
        val item = MediaItem.Builder().setUri(src.url)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(src.startMs).setEndPositionMs(src.startMs + LENGTH_MS).build(),
            ).build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun newPlayer(ctx: Context, headers: Map<String, String>): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(10_000)
        return ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .build()
    }

    private fun fail(song: YtSong) {
        if (_state.value.videoId != song.videoId) return
        release()
        _state.value = State(error = "Couldn't play a preview of “${song.title}”. Check your connection and try again.")
    }

    private fun tick(exo: ExoPlayer) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && player === exo) {
                _state.value = _state.value.copy(progress = (exo.currentPosition / LENGTH_MS.toFloat()).coerceIn(0f, 1f))
                delay(200)
            }
        }
    }

    private fun release() {
        ticker?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun stop() {
        job?.cancel()
        release()
        _state.value = State()
    }

    fun clearError() { if (_state.value.error != null) _state.value = State() }

    /** Where to play [song] from: the iTunes clip (quickest), else the song on YouTube Music. */
    private suspend fun source(song: YtSong, skipItunes: Boolean): Src? {
        if (!skipItunes) runCatching { itunes(song) }.getOrNull()?.let { return itunesSrc(it) }
        return stream(song.videoId, previewStart(song.durationSec) * 1000L)
    }

    private fun itunesSrc(url: String) = Src(url, 0L, emptyMap(), youTube = false, goodUntil = System.currentTimeMillis() + 24 * 3600_000L)

    /** The iTunes Search API's 30-second preview of the same song (title and artist must match). */
    internal fun itunes(song: YtSong): String? {
        val artist = song.artists.firstOrNull()?.name.orEmpty()
        val title = SongMatch.cleanTitle(song.title)
        val term = URLEncoder.encode("$title $artist".trim(), "UTF-8")
        val c = (URL("https://itunes.apple.com/search?term=$term&entity=song&limit=8").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 6000
        }
        val json = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val results = JSONObject(json).optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val url = r.optString("previewUrl").ifBlank { continue }
            val ts = SongMatch.similarity(title, SongMatch.cleanTitle(r.optString("trackName")))
            val asim = if (artist.isBlank()) 0.7 else maxOf(SongMatch.similarity(artist, r.optString("artistName")), SongMatch.similarity(SongMatch.mainArtist(artist), SongMatch.mainArtist(r.optString("artistName"))))
            if (ts >= 0.85 && asim >= 0.6) return url
        }
        return null
    }

    /**
     * The song's audio on YouTube Music (via the download engine), with the request headers the
     * stream wants and how long its address is good for.
     */
    private suspend fun stream(videoId: String, startMs: Long): Src? = withContext(Dispatchers.IO) {
        if (!Engine.ensureReady()) return@withContext null
        runCatching {
            val req = YoutubeDLRequest("https://music.youtube.com/watch?v=$videoId").apply {
                addOption("-j"); addOption("-f", "ba[ext=m4a]/ba"); addOption("--no-playlist"); addOption("--cache-dir", Engine.cacheDir())
            }
            val out = YoutubeDL.execute(req, "rec-preview-$videoId").out
            streamFromJson(out, startMs)
        }.getOrNull()
    }

    /** yt-dlp's -j output for one format → where to stream it from. */
    private fun streamFromJson(out: String, startMs: Long): Src? {
        val o = JSONObject(out.lines().first { it.trimStart().startsWith("{") })
        val url = o.optString("url").ifBlank { return null }
        val headers = HashMap<String, String>()
        o.optJSONObject("http_headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        return Src(url, startMs, headers, youTube = true, goodUntil = expiresAt(url))
    }

    /** When a stream address stops working ("expire=" seconds in it), with a few minutes' margin; else in an hour. */
    internal fun expiresAt(url: String, now: Long = System.currentTimeMillis()): Long =
        Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000 - 5 * 60_000 }
            ?: (now + 3600_000L)

    /** Looks up the first few songs' previews ahead of time, so tapping them plays at once. */
    fun warmUp(songs: List<YtSong>) {
        scope.launch(Dispatchers.IO) {
            for (s in songs.take(6)) {
                if (sources[s.videoId]?.fresh == true) continue
                runCatching { itunes(s) }.getOrNull()?.let { sources[s.videoId] = itunesSrc(it) }
                delay(400) // gentle on the free service
            }
        }
    }
}
