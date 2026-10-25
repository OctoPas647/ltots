package com.ltots.app.identify

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.ltots.app.download.Engine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Plays a suggestion before you download it: Shazam's 30-second preview when there is one,
 * otherwise the song streamed from YouTube Music. Separate from the main player.
 */
object Preview {
    data class State(val id: String? = null, val loading: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var player: MediaPlayer? = null

    suspend fun toggle(id: String, previewUrl: String?, videoId: String?, onStart: () -> Unit = {}) {
        if (_state.value.id == id) { stop(); return }
        stop()
        _state.value = State(id, loading = true)
        val url = previewUrl ?: videoId?.let { streamUrl(it) }
        if (url == null) { _state.value = State(error = "No preview for this song"); return }
        if (_state.value.id != id) return
        withContext(Dispatchers.Main) {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setOnPreparedListener { if (player === it) { onStart(); it.start(); _state.value = State(id) } }
            mp.setOnCompletionListener { if (player === it) stop() }
            mp.setOnErrorListener { p, _, _ -> if (player === p) { stop(); _state.value = State(error = "Couldn't play the preview") }; true }
            runCatching { mp.setDataSource(url); mp.prepareAsync() }.onFailure { stop(); _state.value = State(error = "Couldn't play the preview") }
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _state.value = State()
    }

    private suspend fun streamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (!Engine.ensureReady()) return@withContext null
        runCatching {
            val req = YoutubeDLRequest("https://music.youtube.com/watch?v=$videoId").apply {
                addOption("-g"); addOption("-f", "ba[ext=m4a]/ba"); addOption("--no-playlist")
            }
            YoutubeDL.execute(req, "preview-$videoId").out.lines().firstOrNull { it.startsWith("http") }
        }.getOrNull()
    }
}
