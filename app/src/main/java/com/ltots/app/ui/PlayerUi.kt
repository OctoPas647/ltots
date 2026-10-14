package com.ltots.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Compose-friendly snapshot of the player, kept up to date from the media controller. */
@Stable
class PlayerUi {
    var controller: MediaController? by mutableStateOf(null)
    var isPlaying by mutableStateOf(false)
    var hasMedia by mutableStateOf(false)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var album by mutableStateOf("")
    var mediaId by mutableStateOf<String?>(null)
    var trackId by mutableLongStateOf(-1L)
    var artworkData by mutableStateOf<ByteArray?>(null)
    var durationMs by mutableLongStateOf(0L)
    var positionMs by mutableLongStateOf(0L)
    var bufferedFraction by mutableFloatStateOf(0f)
    var shuffle by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)

    fun refresh() {
        val c = controller ?: return
        isPlaying = c.isPlaying
        hasMedia = c.mediaItemCount > 0
        val md: MediaMetadata = c.mediaMetadata
        title = md.title?.toString() ?: c.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
        artist = (md.artist ?: c.currentMediaItem?.mediaMetadata?.artist)?.toString()?.takeIf { it != Track.UNKNOWN } ?: ""
        album = md.albumTitle?.toString() ?: ""
        mediaId = c.currentMediaItem?.mediaId
        trackId = c.currentMediaItem?.mediaMetadata?.extras?.getLong("trackId", -1L) ?: -1L
        artworkData = md.artworkData
        durationMs = c.duration.takeIf { it > 0 } ?: (c.currentMediaItem?.mediaMetadata?.durationMs ?: 0L)
        positionMs = c.currentPosition
        bufferedFraction = c.bufferedPercentage / 100f
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        c.shuffleModeEnabled = shuffle
        c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(0, tracks.size - 1), 0L)
        c.prepare()
        c.play()
    }
}

@Composable
fun rememberPlayerUi(controller: MediaController?): PlayerUi {
    val ui = remember { PlayerUi() }
    ui.controller = controller
    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = ui.refresh()
        }
        controller.addListener(listener)
        ui.refresh()
        onDispose { controller.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            ui.controller?.let { ui.positionMs = it.currentPosition; ui.bufferedFraction = it.bufferedPercentage / 100f }
            delay(if (ui.isPlaying) 200 else 600)
        }
    }
    return ui
}

/** Artwork of the current song: the cached album thumbnail, else art embedded in the file. */
@Composable
fun rememberArtwork(trackId: Long, embedded: ByteArray?, px: Int = 768): Bitmap? {
    val context = LocalContext.current
    val state = produceState<Bitmap?>(null, trackId, embedded) {
        value = loadArtwork(context, trackId, embedded, px)
    }
    return state.value
}

suspend fun loadArtwork(context: Context, trackId: Long, embedded: ByteArray?, px: Int): Bitmap? {
    if (trackId >= 0) LocalLibrary.artwork(context, trackId, px)?.let { return it }
    if (embedded != null) return withContext(Dispatchers.Default) {
        runCatching { BitmapFactory.decodeByteArray(embedded, 0, embedded.size) }.getOrNull()
    }
    return null
}
