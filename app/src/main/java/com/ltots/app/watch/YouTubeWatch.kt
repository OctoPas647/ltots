package com.ltots.app.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.ltots.app.R
import com.ltots.app.data.Track
import com.ltots.app.download.DownloadManager
import com.ltots.app.download.TrackMeta
import com.ltots.app.playback.PlayerConnection
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.YtSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Notices a song playing in the YouTube app and offers to bring it into LToTS.
 *
 * Music playing in YouTube can't follow you between phones (Rooms, the earbuds' handoff): only
 * LToTS's own player can. So when YouTube has played something for a few seconds, a
 * notification asks: *Download & switch*. LToTS finds the same song on YouTube Music
 * ([YouTubeMatch]), downloads it (or finds it already in your library), pauses YouTube and
 * carries on in LToTS at the very same moment of the song — in your room, if one is on.
 *
 * Seeing what other apps play needs notification access (Android's only way to read other apps'
 * media sessions); [YouTubeListener] is that listener. Nothing leaves the phone except the
 * YouTube Music search for the song.
 */
object YouTubeWatch {
    private val PACKAGES = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
        "com.vanced.android.youtube",
    )
    private const val CHANNEL = "youtube_switch"
    private const val NOTE_ID = 7301
    private const val ACTION_SWITCH = "com.ltots.app.YT_SWITCH"
    private const val ACTION_NOT_NOW = "com.ltots.app.YT_NOT_NOW"
    /** YouTube has to play this long before LToTS asks (not for every video you skim). */
    private const val ASK_AFTER_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.ltots.app.CrashReport.guard("YouTube switch"))

    /** What YouTube is playing now. */
    private data class Seen(val pkg: String, val title: String, val channel: String, val durationMs: Long, val mediaId: String?)

    private val controllers = HashMap<MediaController, MediaController.Callback>()
    private var asked = HashSet<String>()
    private var pending: Runnable? = null
    @Volatile private var current: Pair<MediaController, Seen>? = null
    @Volatile private var lastOffer: Seen? = null

    private fun prefs(c: Context) = c.getSharedPreferences("youtube_watch", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("on", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("on", on).apply()
        if (!on) cancelNote(context)
    }

    /** LToTS may read other apps' media sessions (notification access). */
    fun hasAccess(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, YouTubeListener::class.java))
    }.getOrDefault(false)

    /** Opens Android's page for giving LToTS notification access. */
    fun openAccessSettings(context: Context) {
        val cn = ComponentName(context, YouTubeListener::class.java).flattenToString()
        val detail = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(detail) }.recoverCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- watching (main thread) -------------------------------------------------------------

    /** The media sessions playing on the phone changed (from [YouTubeListener]). */
    internal fun onSessions(context: Context, sessions: List<MediaController>) = main.post {
        val app = context.applicationContext
        val yt = sessions.filter { it.packageName in PACKAGES }
        // Forget sessions that went away.
        for ((c, cb) in controllers.toList()) if (yt.none { it.sessionToken == c.sessionToken }) {
            runCatching { c.unregisterCallback(cb) }
            controllers.remove(c)
        }
        for (c in yt) if (controllers.keys.none { it.sessionToken == c.sessionToken }) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate(app)
                override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate(app)
                override fun onSessionDestroyed() = evaluate(app)
            }
            runCatching { c.registerCallback(cb, main) }
            controllers[c] = cb
        }
        evaluate(app)
    }

    private fun seen(c: MediaController): Seen? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val channel = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
        return Seen(c.packageName, title, channel, dur, id)
    }

    private fun evaluate(context: Context) {
        val playing = controllers.keys.firstNotNullOfOrNull { c ->
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) seen(c)?.let { c to it } else null
        }
        current = playing ?: controllers.keys.firstNotNullOfOrNull { c -> seen(c)?.let { c to it } }
        pending?.let { main.removeCallbacks(it) }
        pending = null
        val s = playing?.second ?: return
        // Not for Shorts and clips, not twice for the same video, only when wanted.
        if (!enabled(context) || (s.durationMs in 1..59_999) || "${s.pkg}|${s.title}" in asked) return
        val r = Runnable {
            val still = current?.second
            if (still == s && current?.first?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                asked += "${s.pkg}|${s.title}"
                offer(context, s)
            }
        }
        pending = r
        main.postDelayed(r, ASK_AFTER_MS)
    }

    // ---- the offer ---------------------------------------------------------------------------

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Songs playing on YouTube", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Offers to continue a song from YouTube in LToTS, so it can follow you between phones."
            }
        )
    }

    private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, YouTubeActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun note(context: Context, title: String, text: String, ask: Boolean, progress: Boolean = false) {
        channel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, com.ltots.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(!ask)
            .setAutoCancel(!progress)
            .setOngoing(progress)
        if (progress) b.setProgress(0, 0, true)
        if (ask) {
            b.addAction(Notification.Action.Builder(null, "Download & switch", action(context, ACTION_SWITCH, 1)).build())
            b.addAction(Notification.Action.Builder(null, "Not now", action(context, ACTION_NOT_NOW, 2)).build())
        }
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTE_ID, b.build()) }
    }

    private fun cancelNote(context: Context) = runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTE_ID) }

    private fun offer(context: Context, s: Seen) {
        lastOffer = s
        note(
            context, "Playing on YouTube: ${s.title}",
            "Download it and continue in LToTS from the same moment, so it can follow you between phones and play in your room.",
            ask = true,
        )
    }

    internal fun onAction(context: Context, action: String?) {
        val app = context.applicationContext
        when (action) {
            ACTION_NOT_NOW -> cancelNote(app)
            ACTION_SWITCH -> lastOffer?.let { s -> scope.launch { switchOver(app, s) } }
        }
    }

    // ---- download & switch ---------------------------------------------------------------------

    private suspend fun switchOver(context: Context, s: Seen) {
        note(context, s.title, "Finding the song…", ask = false, progress = true)
        // Already in the library (same title and length)?
        val have = findInLibrary(s)
        val uri: Uri
        val title: String
        val artist: String
        if (have != null) {
            uri = have.uri; title = have.title; artist = have.artist
        } else {
            val song = find(s) ?: run {
                note(context, s.title, "Couldn't find this one on YouTube Music.", ask = false)
                return
            }
            // Exactly this recording (no swap for the "official audio"): its timing is what YouTube plays.
            val meta = TrackMeta.from(song).copy(isVideo = false)
            note(context, song.title, "Downloading…", ask = false, progress = true)
            if (!DownloadManager.isDownloaded(song.videoId)) DownloadManager.enqueue(listOf(meta), anyway = true)
            val done = withTimeoutOrNull(6 * 60_000L) { DownloadManager.downloaded.first { it.containsKey(song.videoId) } }
                ?.get(song.videoId)
            if (done == null) {
                note(context, song.title, "The download didn't finish. It's in Get › Downloads.", ask = false)
                return
            }
            uri = Uri.parse(done); title = meta.title; artist = meta.artist
        }
        // Still playing on YouTube? Then pause it there and carry on here from the same moment.
        val cur = current
        val posMs = withContext(Dispatchers.Main) {
            val (c, now) = cur ?: return@withContext null
            if (now.title != s.title || now.pkg != s.pkg) return@withContext null
            val st = c.playbackState ?: return@withContext null
            val pos = if (st.state == PlaybackState.STATE_PLAYING)
                st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            else st.position
            runCatching { c.transportControls.pause() }
            pos.coerceAtLeast(0L)
        }
        val ok = play(context, uri, title, artist, (s.durationMs.takeIf { it > 0 } ?: 0L), posMs ?: 0L)
        if (ok) cancelNote(context)
        else note(context, title, "Downloaded. Open LToTS to play it.", ask = false)
    }

    private fun findInLibrary(s: Seen): Track? {
        val lib = com.ltots.app.data.LocalLibrary.cached() ?: return null
        val want = YouTubeMatch.clean(s.title)
        return lib.firstOrNull { t ->
            com.ltots.app.data.Duplicates.sameLength(t.durationMs, s.durationMs) && t.durationMs > 0 && s.durationMs > 0 &&
                YouTubeMatch.clean("${t.artist} ${t.title}").let { it == want || YouTubeMatch.clean(t.title) == want }
        }
    }

    private suspend fun find(s: Seen): YtSong? {
        // YouTube sometimes says which video it is.
        s.mediaId?.let { id -> runCatching { InnerTube.song(id) }.getOrNull()?.let { return it.copy(isVideo = true) } }
        val result = runCatching { InnerTube.search(YouTubeMatch.query(s.title, s.channel)) }.getOrNull() ?: return null
        val candidates = result.sections.flatMap { it.items }.filterIsInstance<YtSong>()
        return YouTubeMatch.pick(s.title, s.channel, s.durationMs, candidates)
    }

    /** Plays [uri] in LToTS from [posMs]. */
    private suspend fun play(context: Context, uri: Uri, title: String, artist: String, durationMs: Long, posMs: Long): Boolean {
        withContext(Dispatchers.Main) { PlayerConnection.connect(context) }
        val c = withTimeoutOrNull(5_000) { PlayerConnection.controller.first { it != null } } ?: return false
        val id = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
        val track = Track(id, uri, title, artist, "", -1L, durationMs, null, null, null)
        withContext(Dispatchers.Main) {
            c.setMediaItem(track.toMediaItem(), posMs)
            c.prepare()
            c.play()
        }
        // Give the player a moment; YouTube stays paused.
        delay(300)
        return true
    }
}

/** Lets LToTS see what other apps play (YouTube): Android's notification access. */
class YouTubeListener : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { list -> YouTubeWatch.onSessions(this, list.orEmpty()) }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        val cn = ComponentName(this, YouTubeListener::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(changed, cn, Handler(Looper.getMainLooper()))
            YouTubeWatch.onSessions(this, m.getActiveSessions(cn))
        }.onFailure { com.ltots.app.CrashReport.handled("YouTubeListener", it) }
    }

    override fun onListenerDisconnected() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(changed) }
        YouTubeWatch.onSessions(this, emptyList())
    }
}

/** The notification's buttons. */
class YouTubeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = YouTubeWatch.onAction(context, intent.action)
}
