package com.ltots.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ltots.app.MainActivity
import com.ltots.app.audio.AudioEngine

/**
 * Keeps music playing in the background with a media notification and lock-screen controls.
 *
 * Power: the service does no scheduling of its own. Media3's [MediaSessionService] keeps it in
 * the foreground only while something plays (paused, the notification becomes dismissible and
 * the system may stop the service), and the session's player decides when the processor runs:
 *  - **No wake lock of LToTS's own.** The only one is ExoPlayer's managed lock
 *    ([C.WAKE_MODE_LOCAL], set in AudioEngine), which Media3 holds only while the app processor
 *    has to produce sound. It's released when paused, stopped or ended, and while the audio chip
 *    plays by itself and ExoPlayer is "sleeping for offload" — then the phone sleeps between
 *    the chip's requests for more audio. During normal (processed) playback the phone's audio
 *    server holds its own wake lock anyway, so ExoPlayer's costs nothing extra; it's kept
 *    because without it a wake-up from deep sleep (the chip asking for audio, a skip from the
 *    headphones) could doze off again before the next block is written, and the music would
 *    stop by itself.
 *  - **Dynamic scheduling** (AudioEngine): ExoPlayer sleeps until the audio output needs more,
 *    about half of what's buffered — ≈4 s with the screen off (see ScreenOffBuffer).
 *  - **Stall guard**: the one case where the wake lock does burn battery for nothing is a
 *    stream stuck buffering with the screen off (a tunnel, a dead network) — the phone is held
 *    awake while nothing plays. After [STALL_MS] of that the wake lock is let go: the phone
 *    can sleep, loading carries on whenever it's awake anyway, and the lock comes straight back
 *    once audio flows again or the screen turns on.
 *  - **Long pause**: after [IDLE_RELEASE_MS] paused the decoder and the audio output are let go.
 *    The delay counts only while the phone is awake, so it never wakes the phone by itself.
 * The sleep timer's short fade and pre-rendering hold their own small, time-limited locks.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var exo: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    /** True while the stall guard has let the wake lock go. */
    private var wakeReleasedForStall = false

    /**
     * Paused for a long time: let go of the decoder and the audio output (and with them any
     * reason for the phone to stay busy). The queue and position stay; pressing play picks up
     * exactly where it stopped.
     */
    private val releaseIdle = Runnable {
        val p = session?.player ?: return@Runnable
        if (!p.playWhenReady && p.playbackState != Player.STATE_IDLE) p.stop()
    }

    /** Still stuck buffering with the screen off: stop holding the phone awake for it. */
    private val stallGuard = Runnable {
        val p = exo ?: return@Runnable
        if (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING && !ScreenState.screenOn.value) {
            p.setWakeMode(C.WAKE_MODE_NONE)
            wakeReleasedForStall = true
        }
    }

    /** The screen came on: a stalled stream gets its wake lock back while you're looking. */
    private val screenListener = ScreenState.Listener { screenOn, _ ->
        if (screenOn) restoreWake() else exo?.let { updateStallGuard(it) }
    }

    override fun onCreate() {
        super.onCreate()
        AudioEngine.init(this)
        val player = AudioEngine.buildPlayer(this)
        exo = player
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying) SleepTimer.onPaused()
                if (!events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) return
                handler.removeCallbacks(releaseIdle)
                if (!player.playWhenReady && player.playbackState != Player.STATE_IDLE) {
                    handler.postDelayed(releaseIdle, IDLE_RELEASE_MS)
                }
                exo?.let { updateStallGuard(it) }
            }
        })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // In shuffle with repeat on, Back at the song the shuffle started on restarts it, rather
        // than wrapping round to the end of the order (a song you never played).
        val sessionPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
            private fun atShuffleStart() = player.shuffleModeEnabled && player.repeatMode == Player.REPEAT_MODE_ALL &&
                !player.currentTimeline.isEmpty && player.currentMediaItemIndex == player.currentTimeline.getFirstWindowIndex(true)
            override fun seekToPrevious() { if (atShuffleStart()) player.seekTo(0L) else super.seekToPrevious() }
            override fun seekToPreviousMediaItem() { if (atShuffleStart()) player.seekTo(0L) else super.seekToPreviousMediaItem() }
        }
        session = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(sleepButtons())
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
        SleepTimer.onChanged = { runCatching { session?.setMediaButtonPreferences(sleepButtons()) } }
        ScreenState.addListener(screenListener)
    }

    /**
     * Buffering with play pressed: start the stall clock (if the lock isn't already let go).
     * Anything else (playing, paused, ended): no clock, and the lock goes back to normal.
     */
    private fun updateStallGuard(p: ExoPlayer) {
        handler.removeCallbacks(stallGuard)
        val stuck = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING
        if (!stuck) {
            restoreWake()
            return
        }
        if (!wakeReleasedForStall && !ScreenState.screenOn.value) handler.postDelayed(stallGuard, STALL_MS)
    }

    private fun restoreWake() {
        handler.removeCallbacks(stallGuard)
        if (!wakeReleasedForStall) return
        wakeReleasedForStall = false
        exo?.setWakeMode(C.WAKE_MODE_LOCAL)
    }

    /** The notification's extra buttons (none if the moon can't be made on this phone). */
    private fun sleepButtons(): List<CommandButton> = runCatching { listOf(sleepButton()) }.getOrDefault(emptyList())

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
        ScreenState.removeListener(screenListener)
        handler.removeCallbacks(releaseIdle)
        handler.removeCallbacks(stallGuard)
        SleepTimer.onChanged = null
        SleepTimer.cancel()
        session?.run {
            release()
            session = null
        }
        exo = null
        com.ltots.app.audio.Crossfade.detach()
        AudioEngine.releasePlayer()
        super.onDestroy()
    }

    internal companion object {
        const val IDLE_RELEASE_MS = 10 * 60 * 1000L
        /** How long a stream may sit buffering with the screen off before the wake lock is let go. */
        const val STALL_MS = 60 * 1000L
        const val SLEEP_ACTION = "com.ltots.app.SLEEP_TIMER_TOGGLE"
        val SLEEP_COMMAND = SessionCommand(SLEEP_ACTION, Bundle.EMPTY)
    }
}
