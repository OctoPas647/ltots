package com.ltots.app.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.ltots.app.CrashReport
import com.ltots.app.data.AppSettings
import com.ltots.app.playback.SleepTimer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fading from one song into the next (Profile > App settings), the two overlapping.
 *
 * The main player moves on to the next song when the fade begins, so the notification, lyrics,
 * history and per-song EQ all follow it as usual. The last seconds of the outgoing song are
 * played by a second, short-lived player with its own copy of the sound chain (EQ, pitch), which
 * is started a moment earlier from the same spot, so the song carries on without a gap.
 *
 * Only automatic changes at the end of a song fade: skipping or seeking cuts straight over.
 */
@UnstableApi
object Crossfade {
    /** How long before the fade the outgoing song's second player gets ready. */
    private const val PREPARE_LEAD_MS = 2_500L
    private const val STEP_MS = 40L
    /** The player's own events for the jump to the next song arrive within this time. */
    private const val OWN_SEEK_MS = 600L
    /** At most this long waiting for the second player to make sound. */
    private const val START_TIMEOUT_MS = 500L

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var app: Context? = null
    private var ghost: ExoPlayer? = null
    private var ghostFor: String? = null

    /** The second player has been started (the fade is under way, or about to be). */
    private var running = false
    private var handedOver = false
    private var fadeStart = 0L
    private var fadeLength = 0L
    private var pausedAt = 0L
    private var ownSeekAt = 0L

    private val settings: AppSettings get() = AudioEngine.settings.value
    private fun lengthMs(): Long = if (settings.crossfade && !settings.bitPerfectUsb) settings.crossfadeSeconds * 1000L else 0L
    private fun ownSeek() = SystemClock.uptimeMillis() - ownSeekAt < OWN_SEEK_MS

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!running) reschedule() }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!running) return
            val g = ghost ?: return
            g.playWhenReady = playWhenReady
            if (!handedOver) return
            if (playWhenReady) {
                if (pausedAt != 0L) fadeStart += SystemClock.uptimeMillis() - pausedAt
                pausedAt = 0L
                handler.removeCallbacks(step)
                handler.post(step)
            } else {
                pausedAt = SystemClock.uptimeMillis()
                handler.removeCallbacks(step)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (running) { if (!ownSeek()) abort() } else reschedule()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val seek = reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            if (running) { if (seek && !ownSeek()) abort() } else { if (seek) dropGhost(); reschedule() }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { if (!running) reschedule() }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (running && timeline.isEmpty) abort() else if (!running) reschedule()
        }
    }

    fun attach(context: Context, p: ExoPlayer) {
        detach()
        app = context.applicationContext
        player = p
        p.addListener(listener)
        reschedule()
    }

    fun detach() {
        abort()
        handler.removeCallbacksAndMessages(null)
        player?.removeListener(listener)
        player = null
    }

    fun settingsChanged() {
        if (lengthMs() == 0L) abort()
        reschedule()
    }

    /** What's left of the song in real time (speed taken into account), or null if unknown. */
    private fun leftMs(p: Player): Long? {
        val d = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return null
        return ((d - p.currentPosition).coerceAtLeast(0) / p.playbackParameters.speed.coerceAtLeast(0.1f)).toLong()
    }

    /** The sleep timer's fade and "finish the song" take over from a fade between songs. */
    private fun sleepTimerBusy(): Boolean = SleepTimer.state.value?.let { it.fading || it.finishing } == true

    private fun allowed(p: ExoPlayer, length: Long): Boolean =
        length > 0 && p.isPlaying && p.repeatMode != Player.REPEAT_MODE_ONE && p.hasNextMediaItem() &&
            !p.pauseAtEndOfMediaItems && !sleepTimerBusy() && p.currentMediaItem != null && !AudioEngine.direct.value &&
            p.duration > 2 * length * p.playbackParameters.speed

    private val check = Runnable { tick() }

    private fun reschedule() {
        handler.removeCallbacks(check)
        val p = player ?: return
        if (running) return
        val length = lengthMs()
        if (length == 0L || !p.isPlaying) { if (!p.isPlaying) dropGhost(); return }
        val left = leftMs(p) ?: return
        handler.postDelayed(check, (left - length - PREPARE_LEAD_MS).coerceAtLeast(0L))
    }

    private fun tick() {
        val p = player ?: return
        if (running) return
        val length = lengthMs()
        val left = leftMs(p)
        if (left == null || !allowed(p, length)) { dropGhost(); return }
        val untilFade = left - length
        when {
            // Seeked into the last seconds: too late to fade, the song just ends.
            untilFade < -300 -> dropGhost()
            untilFade > PREPARE_LEAD_MS + 300 -> reschedule()
            ghost == null || ghostFor != p.currentMediaItem?.mediaId -> {
                prepareGhost(p, length)
                handler.postDelayed(check, 100)
            }
            untilFade > GHOST_START_LATENCY_MS -> handler.postDelayed(check, (untilFade - GHOST_START_LATENCY_MS).coerceIn(5L, 100L))
            ghost?.playbackState == Player.STATE_READY -> begin(p, length)
            else -> dropGhost() // not ready in time: no fade this once
        }
    }

    /** About how long a stopped player takes to make sound after being started. */
    private const val GHOST_START_LATENCY_MS = 120L

    private fun buildGhost(context: Context): ExoPlayer {
        // Its own copy of the chain: the sound stays the same, and the main chain isn't touched.
        val chain = LToTSProcessorChain(EqProcessor(AnalyzerTap(), AudioEngine), TimePitchProcessor(AudioEngine), OutputStage())
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessorChain(chain).build()
        }
        return ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(AudioEngine.attributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    private fun prepareGhost(p: ExoPlayer, length: Long) {
        dropGhost()
        val context = app ?: return
        val item = p.currentMediaItem ?: return
        val speed = p.playbackParameters
        val startAt = (p.duration - length * speed.speed).toLong().coerceAtLeast(0L)
        runCatching {
            val g = buildGhost(context)
            ghost = g
            ghostFor = item.mediaId
            g.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { if (ghost === g) abort() }
                override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying && ghost === g && running) handOver() }
                override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED && ghost === g && running && handedOver) finishFade() }
            })
            g.playbackParameters = speed
            g.setMediaItem(item, startAt)
            g.volume = 1f
            g.playWhenReady = false
            g.prepare()
        }.onFailure { android.util.Log.w("LToTS", "crossfade", it); dropGhost() }
    }

    private val startTimeout = Runnable { if (running && !handedOver) handOver() }

    private fun begin(p: ExoPlayer, length: Long) {
        val g = ghost ?: return
        running = true
        handedOver = false
        pausedAt = 0L
        fadeLength = length
        CrashReport.step("crossfade to the next song")
        g.playWhenReady = true
        handler.postDelayed(startTimeout, START_TIMEOUT_MS)
    }

    /** The outgoing song is sounding from the second player: the main one moves on, silently. */
    private fun handOver() {
        val p = player ?: return abort()
        if (handedOver) return
        handler.removeCallbacks(startTimeout)
        if (!p.hasNextMediaItem()) return abort()
        handedOver = true
        ownSeekAt = SystemClock.uptimeMillis()
        p.volume = 0f
        p.seekToNextMediaItem()
        fadeStart = SystemClock.uptimeMillis()
        handler.post(step)
    }

    private val step = object : Runnable {
        override fun run() {
            val p = player ?: return abort()
            val g = ghost ?: return abort()
            val t = ((SystemClock.uptimeMillis() - fadeStart).toFloat() / fadeLength).coerceIn(0f, 1f)
            // Equal power: the sum stays as loud as either song all the way through.
            p.volume = sin(t * PI.toFloat() / 2f)
            g.volume = cos(t * PI.toFloat() / 2f)
            if (t >= 1f) finishFade() else handler.postDelayed(this, STEP_MS)
        }
    }

    private fun finishFade() {
        handler.removeCallbacks(step)
        player?.volume = 1f
        running = false
        handedOver = false
        dropGhost()
        reschedule()
    }

    /** Skipped, seeked, an error: back to normal playback at once. */
    fun abort() {
        handler.removeCallbacks(step)
        handler.removeCallbacks(startTimeout)
        val wasRunning = running
        running = false
        handedOver = false
        dropGhost()
        if (wasRunning) player?.volume = 1f
    }

    private fun dropGhost() {
        val g = ghost ?: return
        ghost = null
        ghostFor = null
        runCatching { g.release() }
    }
}
