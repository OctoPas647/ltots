package com.ltots.app.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.Crossfade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sleep timer: stops the music after a while, optionally fading out and letting the song
 * that's playing finish (Profile > Sleep timer).
 *
 * One alarm, no ticking: nothing runs until the fade (or the stop) is due. The alarm is exact
 * when Android allows it (Profile > Sleep timer > Precise timing); while the audio chip plays
 * by itself the processor sleeps for long stretches, and only an alarm reliably wakes it on time.
 * A plain timer runs alongside as a backup for when the phone is awake anyway.
 */
@UnstableApi
object SleepTimer {
    data class State(
        /** When the music stops (elapsedRealtime), or, while waiting for the song to end, when that's expected. */
        val endsAt: Long,
        /** The time is up and the song that's playing is being allowed to finish. */
        val finishing: Boolean = false,
        /** Fading out now. */
        val fading: Boolean = false,
    )

    private const val ACTION = "com.ltots.app.SLEEP_TIMER"
    private const val STEP_MS = 100L

    private val _state = MutableStateFlow<State?>(null)
    /** The running timer, or null. */
    val state: StateFlow<State?> = _state

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var wake: PowerManager.WakeLock? = null
    private var fadeFrom = 1f
    private var fadeStart = 0L
    private var fadeLength = 0L

    /** Called whenever the timer starts, stops or changes (the notification's button follows it). */
    var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Starts (or restarts) the timer for [minutes]. */
    fun start(context: Context, minutes: Int) {
        init(context)
        stopFade(restore = true)
        _state.value = State(SystemClock.elapsedRealtime() + minutes * 60_000L)
        schedule()
        onChanged?.invoke()
    }

    /** Stops the timer; the music carries on as it is (at full volume). */
    fun cancel() {
        if (_state.value == null) return
        _state.value = null
        unschedule()
        stopFade(restore = true)
        AudioEngine.player.value?.pauseAtEndOfMediaItems = false
        onChanged?.invoke()
    }

    /** The notification's moon button: start the usual length, or stop a running timer. */
    fun toggle(context: Context) {
        if (_state.value != null) cancel() else start(context, AudioEngine.settings.value.sleepMinutes)
    }

    /** Whether Android lets LToTS wake the phone at the exact time (Android 12+ asks the user). */
    fun preciseAllowed(context: Context): Boolean =
        runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)

    private fun fadeMs(): Long = with(AudioEngine.settings.value) { if (sleepFade) sleepFadeSeconds * 1000L else 0L }

    /** The moment something has to happen next: the fade's start, or the end. */
    private fun nextDue(s: State): Long = if (s.fading) s.endsAt else s.endsAt - fadeMs()

    private fun schedule() {
        val s = _state.value ?: return
        unschedule()
        val due = nextDue(s)
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = pending()
        runCatching {
            if (preciseAllowed(app)) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pi)
        }.onFailure { android.util.Log.w("LToTS", "sleep timer alarm", it) }
        handler.postDelayed(tick, (due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun unschedule() {
        handler.removeCallbacks(tick)
        if (::app.isInitialized) runCatching { app.getSystemService(AlarmManager::class.java).cancel(pending()) }
    }

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        app, 7, Intent(app, Receiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The alarm (or the backup timer) went off. */
    private val tick = Runnable { onDue() }

    internal fun onDue() {
        val s = _state.value ?: return
        val now = SystemClock.elapsedRealtime()
        if (now < nextDue(s) - 500) { schedule(); return } // woke early: wait for the rest
        val p = AudioEngine.player.value
        if (p == null || !p.isPlaying) { finish(); return }

        // Time's up but the song should finish: wait for its end instead.
        if (!s.finishing && AudioEngine.settings.value.sleepFinishSong && now >= s.endsAt - fadeMs()) {
            val left = songLeftMs(p)
            if (left > 1500) {
                p.pauseAtEndOfMediaItems = true
                _state.value = State(now + left, finishing = true)
                schedule()
                onChanged?.invoke()
                return
            }
        }
        val fade = fadeMs()
        if (!s.fading && fade > 0) {
            _state.value = s.copy(fading = true)
            startFade(p, (s.endsAt - now).coerceIn(1000L, fade))
            schedule()
            return
        }
        if (now >= s.endsAt - 500 || fade == 0L) finish()
    }

    /** What's left of the playing song in real time (speed taken into account). */
    private fun songLeftMs(p: Player): Long {
        val d = p.duration.takeIf { it > 0 } ?: return 0
        val speed = p.playbackParameters.speed.coerceAtLeast(0.1f)
        return ((d - p.currentPosition).coerceAtLeast(0) / speed).toLong()
    }

    private fun startFade(p: Player, length: Long) {
        Crossfade.abort()
        fadeFrom = p.volume
        fadeStart = SystemClock.elapsedRealtime()
        fadeLength = length
        // The phone has to stay awake for the fade (it's short).
        if (wake == null) {
            wake = app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LToTS:sleep-fade").apply { setReferenceCounted(false) }
        }
        wake?.acquire(length + 10_000)
        handler.post(fadeStep)
    }

    private val fadeStep = object : Runnable {
        override fun run() {
            val p = AudioEngine.player.value ?: return
            val t = ((SystemClock.elapsedRealtime() - fadeStart).toFloat() / fadeLength).coerceIn(0f, 1f)
            // An even fade to the ear: volume falls along a curve, not a straight line.
            val v = fadeFrom * (1f - t) * (1f - t) * (1f - t)
            p.volume = v
            if (t >= 1f) finish() else handler.postDelayed(this, STEP_MS)
        }
    }

    private fun stopFade(restore: Boolean) {
        handler.removeCallbacks(fadeStep)
        if (restore) AudioEngine.player.value?.let { if (it.volume < 1f) it.volume = 1f }
        runCatching { if (wake?.isHeld == true) wake?.release() }
    }

    /** Stops the music and puts everything back for next time. */
    private fun finish() {
        val p = AudioEngine.player.value
        p?.pause()
        p?.pauseAtEndOfMediaItems = false
        stopFade(restore = true)
        unschedule()
        _state.value = null
        onChanged?.invoke()
    }

    /** The song ended and the player paused there (finish the song): the timer is done. */
    fun onPaused() {
        val s = _state.value ?: return
        if (s.finishing && SystemClock.elapsedRealtime() >= s.endsAt - 3000) finish()
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            init(context)
            onDue()
        }
    }
}
