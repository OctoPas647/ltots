package com.ltots.app.recs

import android.media.AudioAttributes
import android.media.MediaPlayer
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
 */
object SongPreview {
    const val LENGTH_MS = 30_000L

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
    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val urls = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>() // videoId → (url, start ms)

    /** Starts [song]'s preview, or stops it if it's the one playing. [onStart] pauses the main player. */
    fun toggle(song: YtSong, onStart: () -> Unit = {}) {
        if (_state.value.videoId == song.videoId) { stop(); return }
        stop()
        _state.value = State(song.videoId, song.title, song.artistText, song.thumbUrl, loading = true, song = song)
        job = scope.launch {
            val src = withContext(Dispatchers.IO) { urls[song.videoId] ?: source(song)?.also { urls[song.videoId] = it } }
            if (_state.value.videoId != song.videoId) return@launch
            if (src == null) { _state.value = State(error = "No preview for “${song.title}”"); return@launch }
            val (url, startMs) = src
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                if (startMs > 0) runCatching { it.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST_SYNC) }
                onStart()
                it.start()
                _state.value = _state.value.copy(loading = false)
                tick(it, startMs)
            }
            mp.setOnCompletionListener { if (player === it) stop() }
            mp.setOnErrorListener { p, _, _ -> if (player === p) { stop(); _state.value = State(error = "Couldn't play the preview") }; true }
            runCatching { mp.setDataSource(url); mp.prepareAsync() }.onFailure { stop(); _state.value = State(error = "Couldn't play the preview") }
        }
    }

    private fun tick(mp: MediaPlayer, startMs: Int) {
        scope.launch {
            while (isActive && player === mp) {
                val pos = runCatching { mp.currentPosition }.getOrDefault(startMs) - startMs
                _state.value = _state.value.copy(progress = (pos / LENGTH_MS.toFloat()).coerceIn(0f, 1f))
                if (pos >= LENGTH_MS) { stop(); break }
                delay(200)
            }
        }
    }

    fun stop() {
        job?.cancel()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _state.value = State()
    }

    fun clearError() { if (_state.value.error != null) _state.value = State() }

    /** Where to play [song] from, and from which millisecond. */
    private suspend fun source(song: YtSong): Pair<String, Int>? =
        runCatching { itunes(song) }.getOrNull()?.let { it to 0 }
            ?: stream(song.videoId)?.let { it to previewStart(song.durationSec) * 1000 }

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

    /** The song's audio stream on YouTube Music (via the download engine). */
    private suspend fun stream(videoId: String): String? = withContext(Dispatchers.IO) {
        if (!Engine.ensureReady()) return@withContext null
        runCatching {
            val req = YoutubeDLRequest("https://music.youtube.com/watch?v=$videoId").apply {
                addOption("-g"); addOption("-f", "ba[ext=m4a]/ba"); addOption("--no-playlist"); addOption("--cache-dir", Engine.cacheDir())
            }
            YoutubeDL.execute(req, "rec-preview-$videoId").out.lines().firstOrNull { it.startsWith("http") }
        }.getOrNull()
    }

    /** Looks up the first few songs' previews ahead of time, so tapping them plays at once. */
    fun warmUp(songs: List<YtSong>) {
        scope.launch(Dispatchers.IO) {
            for (s in songs.take(6)) {
                if (urls.containsKey(s.videoId)) continue
                runCatching { itunes(s) }.getOrNull()?.let { urls[s.videoId] = it to 0 }
                delay(400) // gentle on the free service
            }
        }
    }
}
