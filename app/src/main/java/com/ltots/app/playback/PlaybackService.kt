package com.ltots.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ltots.app.MainActivity
import com.ltots.app.audio.AudioEngine

/** Keeps music playing in the background with a media notification and lock-screen controls. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Paused for a long time: let go of the decoder and the audio output (and with them any
     * reason for the phone to stay busy). The queue and position stay; pressing play picks up
     * exactly where it stopped.
     */
    private val releaseIdle = Runnable {
        val p = session?.player ?: return@Runnable
        if (!p.playWhenReady && p.playbackState != Player.STATE_IDLE) p.stop()
    }

    override fun onCreate() {
        super.onCreate()
        AudioEngine.init(this)
        val player = AudioEngine.buildPlayer(this)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // The home-screen icon follows each song's cover, and reads the next one ahead.
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) {
                    com.ltots.app.ui.IconColor.onSong(player.currentMediaItem?.mediaId, player.mediaMetadata.artworkData)
                    val next = player.nextMediaItemIndex
                    if (next != C.INDEX_UNSET) player.getMediaItemAt(next).let { item ->
                        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                        if (uri != null && item.mediaId.isNotBlank()) com.ltots.app.ui.IconColor.prefetch(item.mediaId, uri)
                    }
                }
                if (!events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) return
                handler.removeCallbacks(releaseIdle)
                if (!player.playWhenReady && player.playbackState != Player.STATE_IDLE) {
                    handler.postDelayed(releaseIdle, IDLE_RELEASE_MS)
                }
            }
        })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Controllers don't pass the playable URI across, so restore it from requestMetadata.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        if (uri != null) item.buildUpon().setUri(uri).build() else item
                    }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(releaseIdle)
        session?.run {
            release()
            session = null
        }
        AudioEngine.releasePlayer()
        super.onDestroy()
    }

    private companion object {
        const val IDLE_RELEASE_MS = 10 * 60 * 1000L
    }
}
