package com.ltots.app.cast

import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.ltots.app.playback.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/** Cast settings: Google's default media receiver (plays audio files from a web address). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Chromecast and Google TV (Sound > Cast to a speaker or TV): the queue moves to the Cast
 * device at the same second, and when casting stops it comes back to the phone where the
 * speaker had got to. The device fetches the songs from the phone ([LocalStream]); a song with
 * a pre-rendered copy (Profile > Screen off > Pre-render) goes with its EQ and pitch baked in,
 * the others as they are (a Cast device has no EQ of its own).
 *
 * Needs Google Play services; without them the Cast option says so and nothing else changes.
 */
object Cast {
    data class State(val device: String, val title: String = "", val playing: Boolean = false)

    private val _state = MutableStateFlow<State?>(null)
    /** Casting now (to which device), or null. */
    val state: StateFlow<State?> = _state

    private val _available = MutableStateFlow<Boolean?>(null)
    /** Google Play services' Cast is there (null: not checked yet). */
    val available: StateFlow<Boolean?> = _available

    @Volatile private var ctx: CastContext? = null
    private var app: Context? = null
    /** Local queue index of the first song sent to the device. */
    private var sentFrom = 0
    private var sentIds: List<Int> = emptyList()
    private var lastRemoteIndex = 0
    private var lastRemotePos = 0L

    /** Gets Cast ready (call on the main thread, once the app is open). */
    fun init(context: Context) {
        if (ctx != null || _available.value == false) return
        // The desktop UI tests have no Google Play services.
        if (android.os.Build.FINGERPRINT == "robolectric") { _available.value = false; return }
        app = context.applicationContext
        runCatching {
            CastContext.getSharedInstance(context.applicationContext, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { c ->
                    ctx = c
                    _available.value = true
                    c.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
                    c.sessionManager.currentCastSession?.let { s -> if (s.isConnected) onConnected(s, resumed = true) }
                }
                .addOnFailureListener { _available.value = false }
        }.onFailure { _available.value = false }
    }

    /** Opens the list of Cast devices on the network (from an activity). */
    fun choose(activity: Context): Boolean {
        val c = ctx ?: return false
        return runCatching {
            val themed = ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat_DayNight_Dialog)
            val d = androidx.mediarouter.app.MediaRouteChooserDialog(themed)
            d.routeSelector = c.mergedSelector ?: return false
            d.show()
            true
        }.getOrDefault(false)
    }

    /** Stops casting; the music comes back to the phone. */
    fun stop() {
        runCatching { ctx?.sessionManager?.endCurrentSession(true) }
    }

    fun togglePlay() {
        val rmc = ctx?.sessionManager?.currentCastSession?.remoteMediaClient ?: return
        runCatching { if (rmc.isPlaying) rmc.pause() else rmc.play() }
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session, resumed = false)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session, resumed = true)
        override fun onSessionEnding(session: CastSession) = remember(session)
        override fun onSessionEnded(session: CastSession, error: Int) = comeBack()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) { _state.value = null }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) { _state.value = null }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private val remoteCallback = object : com.google.android.gms.cast.framework.media.RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { update() }
        override fun onMetadataUpdated() { update() }
    }

    private fun update() {
        val s = ctx?.sessionManager?.currentCastSession ?: return
        val rmc = s.remoteMediaClient ?: return
        val md = rmc.mediaInfo?.metadata
        _state.value = State(
            s.castDevice?.friendlyName ?: "Cast device",
            md?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
            rmc.playerState == MediaStatus.PLAYER_STATE_PLAYING || rmc.playerState == MediaStatus.PLAYER_STATE_BUFFERING,
        )
        remember(s)
    }

    /** Where the device is, so the phone can pick up from there. */
    private fun remember(s: CastSession) {
        val rmc = s.remoteMediaClient ?: return
        val id = rmc.currentItem?.itemId
        val k = sentIds.indexOf(id)
        if (k >= 0) lastRemoteIndex = k
        lastRemotePos = runCatching { rmc.approximateStreamPosition }.getOrDefault(lastRemotePos)
    }

    private fun onConnected(s: CastSession, resumed: Boolean) {
        val context = app ?: return
        val rmc = s.remoteMediaClient ?: return
        rmc.registerCallback(remoteCallback)
        _state.value = State(s.castDevice?.friendlyName ?: "Cast device")
        if (resumed && rmc.mediaQueue.itemCount > 0) { update(); return }
        val c = PlayerConnection.controller.value ?: return
        if (c.mediaItemCount == 0) return
        val from = c.currentMediaItemIndex.coerceAtLeast(0)
        val until = minOf(c.mediaItemCount, from + 100)
        val items = (from until until).mapNotNull { i -> queueItem(context, c.getMediaItemAt(i)) }
        if (items.isEmpty()) return
        val pos = c.currentPosition
        sentFrom = from
        lastRemoteIndex = 0
        lastRemotePos = pos
        val request = MediaLoadRequestData.Builder()
            .setQueueData(MediaQueueData.Builder().setItems(items).setStartIndex(0).build())
            .setCurrentTime(pos)
            .setAutoplay(c.isPlaying || c.playWhenReady)
            .build()
        runCatching {
            rmc.load(request).setResultCallback {
                sentIds = (0 until rmc.mediaQueue.itemCount).map { i -> rmc.mediaQueue.itemIdAtIndex(i) }
            }
        }
        // The phone goes quiet while the speaker plays.
        c.pause()
        com.ltots.app.CrashReport.step("cast to ${s.castDevice?.friendlyName}: ${items.size} songs from ${pos / 1000} s")
    }

    private fun queueItem(context: Context, item: MediaItem): MediaQueueItem? {
        val extras = item.mediaMetadata.extras
        // A pre-rendered copy has the EQ and pitch baked in; it's the item's own file then.
        val uri: Uri = item.localConfiguration?.uri ?: return null
        val url = LocalStream.publish(context, uri, item.localConfiguration?.mimeType) ?: return null
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, item.mediaMetadata.title?.toString().orEmpty())
            putString(MediaMetadata.KEY_ARTIST, item.mediaMetadata.artist?.toString().orEmpty())
            putString(MediaMetadata.KEY_ALBUM_TITLE, item.mediaMetadata.albumTitle?.toString().orEmpty())
        }
        val mime = item.localConfiguration?.mimeType ?: runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "audio/mpeg"
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(md)
            .apply { extras?.getLong("trackId", -1L)?.takeIf { it >= 0 }?.let { setCustomData(org.json.JSONObject().put("trackId", it)) } }
            .build()
        return MediaQueueItem.Builder(info).setAutoplay(true).setPreloadTime(10.0).build()
    }

    /** Casting stopped: carry on on the phone from where the speaker was. */
    private fun comeBack() {
        _state.value = null
        LocalStream.stop()
        val c = PlayerConnection.controller.value ?: return
        val index = (sentFrom + lastRemoteIndex).coerceIn(0, (c.mediaItemCount - 1).coerceAtLeast(0))
        runCatching {
            if (c.mediaItemCount > 0) c.seekTo(index, lastRemotePos.coerceAtLeast(0L))
        }
        com.ltots.app.CrashReport.step("cast ended: back on the phone at song ${index + 1}, ${lastRemotePos / 1000} s")
    }
}
