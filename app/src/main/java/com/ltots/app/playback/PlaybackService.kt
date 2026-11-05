package com.ltots.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
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
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) SleepTimer.onPaused()
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
            .setMediaButtonPreferences(listOf(sleepButton()))
            .setCallback(object : MediaSession.Callback {
                // The sleep timer's moon button in the notification and on the lock screen.
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SLEEP_COMMAND).build()
                        )
                        .build()

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == SLEEP_ACTION) {
                        SleepTimer.toggle(this@PlaybackService)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }

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
        SleepTimer.init(this)
        SleepTimer.onChanged = { session?.setMediaButtonPreferences(listOf(sleepButton())) }
    }

    /** The moon: filled while the timer runs. Tap starts the usual length, or stops it. */
    private fun sleepButton(): CommandButton {
        val on = SleepTimer.state.value != null
        return CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (on) com.ltots.app.R.drawable.ic_sleep_on else com.ltots.app.R.drawable.ic_sleep)
            .setDisplayName(if (on) "Stop sleep timer" else "Sleep timer (${AudioEngine.settings.value.sleepMinutes} min)")
            .setSessionCommand(SLEEP_COMMAND)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(releaseIdle)
        SleepTimer.onChanged = null
        SleepTimer.cancel()
        session?.run {
            release()
            session = null
        }
        AudioEngine.releasePlayer()
        super.onDestroy()
    }

    private companion object {
        const val IDLE_RELEASE_MS = 10 * 60 * 1000L
        const val SLEEP_ACTION = "com.ltots.app.SLEEP_TIMER_TOGGLE"
        val SLEEP_COMMAND = SessionCommand(SLEEP_ACTION, Bundle.EMPTY)
    }
}
