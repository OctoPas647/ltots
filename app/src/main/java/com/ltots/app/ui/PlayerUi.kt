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
import androidx.media3.common.C
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
    /** Where the song sits in the queue, in the order it plays (shuffle included): 1-based. */
    var queuePos by mutableIntStateOf(0)
    var queueSize by mutableIntStateOf(0)
    /** Length of the songs that play before this one, and of the whole queue (ms). */
    var queueBeforeMs by mutableLongStateOf(0L)
    var queueTotalMs by mutableLongStateOf(0L)

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
        readQueue(c)
    }

    private fun readQueue(c: Player) {
        val tl = c.currentTimeline
        val cur = c.currentMediaItemIndex
        if (tl.isEmpty || cur == C.INDEX_UNSET) { queueSize = 0; return }
        fun len(i: Int) = c.getMediaItemAt(i).mediaMetadata.durationMs?.takeIf { it > 0 } ?: 0L
        var pos = 0; var before = 0L; var total = 0L; var passed = false
        var i = tl.getFirstWindowIndex(c.shuffleModeEnabled)
        var guard = 0
        while (i != C.INDEX_UNSET && guard++ < tl.windowCount) {
            val l = if (i == cur) durationMs.takeIf { it > 0 } ?: len(i) else len(i)
            if (i == cur) { passed = true; pos = guard }
            else if (!passed) before += l
            total += l
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        queueSize = tl.windowCount; queuePos = pos; queueBeforeMs = before; queueTotalMs = total
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
    // Seek bar position, refreshed only while the app is visible (awaitOnScreen): with the
    // screen off nothing here wakes the phone.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller, lifecycle) {
        while (true) {
            awaitOnScreen(lifecycle)
            ui.controller?.let { ui.positionMs = it.currentPosition; ui.bufferedFraction = it.bufferedPercentage / 100f }
            delay(if (ui.isPlaying) 250 else 700)
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
