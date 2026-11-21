package com.ltots.app.elsewhere

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ltots.app.data.ListenStats
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.StatsThread
import com.ltots.app.data.Store
import com.ltots.app.data.Track
import com.ltots.app.lyrics.SongMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Music playing in other apps — Spotify, YouTube Music, Deezer, SoundCloud, Apple Music and any
 * music player — seen through Android's media sessions (the same thing the lock screen's player
 * reads). Needs notification access, which only the person can turn on (Profile > Other music
 * apps); nothing is read before that.
 *
 *  - **Counted**: a song heard for 30 s (or half of it, if shorter) is a play in the listening
 *    numbers, Home's stats and Wrapped, with the time heard added as it plays — the same rules
 *    as songs played in LToTS. Which app it was is kept, for Wrapped's "Where you listened".
 *  - **Continue in LToTS**: when the song is on the phone, LToTS can take over at the same
 *    second (its own player starts, which makes the other app pause; it's told to pause too).
 *    When it isn't, LToTS can download it and take over once it's there.
 *  - **Sign in**: playing in an app whose account LToTS doesn't know yet brings a one-time
 *    suggestion to connect it, for better picks on Home.
 *
 * All media-session callbacks run on this module's own thread, never the main thread.
 */
object OtherApps {
    private const val PREFS = "elsewhere"
    /** A song counts as a play after this much (or half the song, if shorter). */
    private const val PLAY_MS = 30_000L
    private const val TICK_MS = 30_000L
    private const val LOG_MAX = 1500
    private const val CHANNEL = "elsewhere"
    private const val NOTIFICATION_ID = 4711
    const val EXTRA_CONTINUE = "ltots.continue_elsewhere"

    /** What another app is playing (or just paused). */
    data class NowPlaying(
        val pkg: String,
        val app: MusicApp,
        val appLabel: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val playing: Boolean,
        val positionMs: Long,
        /** [SystemClock.elapsedRealtime] of [positionMs]. */
        val positionAt: Long,
        val speed: Float,
        /** When it last changed, elapsedRealtime. */
        val changedAt: Long,
    ) {
        /** Where the song is right now. */
        fun positionNow(): Long =
            if (!playing || positionMs < 0) positionMs.coerceAtLeast(0L)
            else positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed.coerceIn(0.25f, 4f)).toLong()
    }

    /** One play heard in another app. */
    data class Play(val title: String, val artist: String, val album: String, val source: String, val at: Long, val ms: Long)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    /** The song another app is playing, or paused less than a few minutes ago. */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private val _connected = MutableStateFlow(false)
    /** Notification access is on and the listener is running. */
    val connected: StateFlow<Boolean> = _connected

    private val _promptFor = MutableStateFlow<MusicApp?>(null)
    /** An app is playing whose account LToTS could use: suggest connecting it (once, then snoozed). */
    val promptFor: StateFlow<MusicApp?> = _promptFor

    private val _plays = MutableStateFlow<List<Play>>(emptyList())
    /** Plays heard in other apps, newest first (the last [LOG_MAX]). */
    val plays: StateFlow<List<Play>> = _plays

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var sessions: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var appContext: Context? = null
    private val trackers = HashMap<android.media.session.MediaSession.Token, Tracker>()
    private var loaded = false

    // ---- settings ------------------------------------------------------------------------------

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun countPlays(c: Context) = prefs(c).getBoolean("count", true)
    fun setCountPlays(c: Context, on: Boolean) = prefs(c).edit().putBoolean("count", on).apply()
    fun offerContinue(c: Context) = prefs(c).getBoolean("continue", true)
    fun setOfferContinue(c: Context, on: Boolean) = prefs(c).edit().putBoolean("continue", on).apply()
    fun notifyContinue(c: Context) = prefs(c).getBoolean("notify", true)
    fun setNotifyContinue(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("notify", on).apply()
        if (!on) cancelNotification(c)
    }

    /** Whether the person has given LToTS notification access (needed to see other apps' players). */
    fun hasAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, NowPlayingListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Opens Android's page to give LToTS notification access. */
    fun openAccessSettings(context: Context) {
        val me = ComponentName(context, NowPlayingListener::class.java)
        val tries = listOf(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me.flattenToString()),
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        )
        for (i in tries) if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }

    /** "Connect your Spotify" was dismissed: not again for two weeks. */
    fun snoozePrompt(context: Context, app: MusicApp) {
        val s = app.service ?: return
        prefs(context).edit().putLong("snooze_${s.name}", System.currentTimeMillis() + 14L * 24 * 3600_000L).apply()
        _promptFor.value = null
    }

    private fun shouldPrompt(context: Context, app: MusicApp): Boolean {
        val s = app.service ?: return false
        if (com.ltots.app.accounts.Accounts.isConnected(context, s)) return false
        return System.currentTimeMillis() > prefs(context).getLong("snooze_${s.name}", 0L)
    }

    // ---- listener lifecycle (NowPlayingListener) ----------------------------------------------

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list.orEmpty()) }

    internal fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        ensureLoaded()
        val t = thread ?: HandlerThread("LToTS-elsewhere").also { it.start(); thread = it }
        val h = handler ?: Handler(t.looper).also { handler = it }
        h.post {
            runCatching {
                val msm = app.getSystemService(MediaSessionManager::class.java) ?: return@post
                val me = ComponentName(app, NowPlayingListener::class.java)
                sessions = msm
                component = me
                msm.addOnActiveSessionsChangedListener(sessionsChanged, me, h)
                onSessions(msm.getActiveSessions(me))
                _connected.value = true
            }.onFailure { com.ltots.app.CrashReport.handled("elsewhere attach", it) }
        }
    }

    internal fun detach() {
        val h = handler ?: return
        h.post {
            runCatching { sessions?.removeOnActiveSessionsChangedListener(sessionsChanged) }
            trackers.values.forEach { it.close() }
            trackers.clear()
            sessions = null
            _connected.value = false
            _nowPlaying.value = null
        }
    }

    private fun onSessions(list: List<MediaController>) {
        val ctx = appContext ?: return
        val h = handler ?: return
        val live = list.mapTo(HashSet()) { it.sessionToken }
        trackers.keys.filter { it !in live }.forEach { trackers.remove(it)?.close() }
        for (c in list) {
            if (trackers.containsKey(c.sessionToken)) continue
            val app = MusicApp.of(ctx, c.packageName) ?: continue
            val t = Tracker(c, app, MusicApp.labelOf(ctx, c.packageName).takeIf { app == MusicApp.OTHER } ?: app.label)
            trackers[c.sessionToken] = t
            c.registerCallback(t, h)
            t.onMetadataChanged(c.metadata)
            t.onPlaybackStateChanged(c.playbackState)
        }
        publish()
    }

    // ---- one app's player ----------------------------------------------------------------------

    private class Tracker(val controller: MediaController, val app: MusicApp, val label: String) : MediaController.Callback() {
        var title = ""; var artist = ""; var album = ""; var duration = 0L
        var playing = false
        var since = 0L
        var heard = 0L
        var logged = false
        var startedAt = 0L
        var changedAt = SystemClock.elapsedRealtime()
        var state: PlaybackState? = null

        override fun onMetadataChanged(md: MediaMetadata?) {
            val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            val a = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
            if (t == title && a == artist) return
            finish()
            title = t; artist = a
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
            duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0L
            heard = 0L; logged = false
            startedAt = System.currentTimeMillis()
            if (playing) since = SystemClock.elapsedRealtime()
            changedAt = SystemClock.elapsedRealtime()
            publish()
        }

        override fun onPlaybackStateChanged(s: PlaybackState?) {
            settle()
            state = s
            val now = s?.state == PlaybackState.STATE_PLAYING
            if (now != playing) changedAt = SystemClock.elapsedRealtime()
            playing = now
            since = if (now) SystemClock.elapsedRealtime() else 0L
            if (now) scheduleTick()
            publish()
        }

        override fun onSessionDestroyed() {
            close()
            handler?.post { trackers.entries.removeAll { it.value === this }; publish() }
        }

        /** Adds what was heard since the last look; counts the play once it's long enough. */
        fun settle() {
            if (title.isBlank()) return
            val add = if (playing && since > 0) (SystemClock.elapsedRealtime() - since).coerceIn(0L, 3 * 3600_000L) else 0L
            if (playing) since = SystemClock.elapsedRealtime()
            if (add <= 0) return
            heard += add
            val ctx = appContext ?: return
            if (!countPlays(ctx)) return
            val need = if (duration in 1 until 2 * PLAY_MS) duration / 2 else PLAY_MS
            val t = title; val a = artist; val al = album; val src = label
            if (!logged && heard >= need) {
                logged = true
                val ms = heard
                val at = startedAt
                StatsThread.post {
                    ListenStats.addPlay(t, a, al, ms, imported = true)
                    addToLog(Play(t, a, al, src, at, ms))
                    addToTotals(src, ms)
                }
            } else if (logged) {
                val at = startedAt
                StatsThread.post {
                    ListenStats.addTime(t, a, add)
                    updateLog(t, a, at, add)
                    addToTotals(src, add)
                }
            }
        }

        fun finish() { settle() }

        fun close() {
            settle()
            runCatching { controller.unregisterCallback(this) }
        }

        fun snapshot(): NowPlaying? {
            if (title.isBlank()) return null
            val s = state
            return NowPlaying(
                controller.packageName, app, label, title, artist, album, duration, playing,
                s?.position ?: -1L, s?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
                s?.playbackSpeed?.takeIf { it > 0f } ?: 1f, changedAt,
            )
        }
    }

    private val tick = Runnable {
        trackers.values.forEach { it.settle() }
        if (trackers.values.any { it.playing }) scheduleTick()
        publish()
    }

    private fun scheduleTick() {
        val h = handler ?: return
        h.removeCallbacks(tick)
        h.postDelayed(tick, TICK_MS)
    }

    /** The app playing now (else the one paused most recently, for a few minutes). */
    private fun publish() {
        val ctx = appContext
        val now = SystemClock.elapsedRealtime()
        val best = trackers.values.filter { it.title.isNotBlank() }
            .sortedWith(compareByDescending<Tracker> { it.playing }.thenByDescending { it.changedAt })
            .firstOrNull()
            ?.takeIf { it.playing || now - it.changedAt < 5 * 60_000L }
        val np = best?.snapshot()
        val before = _nowPlaying.value
        _nowPlaying.value = np
        if (ctx != null) {
            _promptFor.value = np?.takeIf { it.playing && shouldPrompt(ctx, it.app) }?.app
            if (np?.playing == true && (before?.title != np.title || before.pkg != np.pkg || !before.playing)) maybeNotify(ctx, np)
            if (np?.playing != true) cancelNotification(ctx)
        }
    }

    // ---- continuing here -----------------------------------------------------------------------

    enum class Outcome { PLAYING, NOT_ON_PHONE, NOTHING }

    /** The song on the phone that [np] is, if there is one. */
    suspend fun findLocal(context: Context, np: NowPlaying): Track? = withContext(Dispatchers.IO) {
        val songs = runCatching { LocalLibrary.loadSongs(context) }.getOrDefault(emptyList())
        match(np.title, np.artist, np.durationMs, songs)
    }

    /**
     * The best match for a song by title and artist: the same title once "(Official Video)",
     * "- Remastered" and featured artists are set aside, an artist in common, and — when both
     * lengths are known — within 10 s. A remix or live take on the phone isn't the same song.
     */
    internal fun match(title: String, artist: String, durationMs: Long, songs: List<Track>): Track? {
        val t = baseTitle(title)
        if (t.isBlank()) return null
        val artists = artistsOf(artist)
        return songs.asSequence()
            .filter { baseTitle(it.title) == t }
            .filter { s -> artists.isEmpty() || artistsOf(s.artist).any { it in artists } || s.artist == Track.UNKNOWN }
            .filter { s -> durationMs <= 0 || s.durationMs <= 0 || kotlin.math.abs(s.durationMs - durationMs) < 10_000L }
            .sortedBy { s -> if (durationMs > 0 && s.durationMs > 0) kotlin.math.abs(s.durationMs - durationMs) else 5_000L }
            .firstOrNull()
    }

    private val CLUTTER = Regex("""\s*[(\[](official|lyrics?|audio|video|visuali[sz]er|explicit|clean|hd|hq|4k|music video|mv)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT = Regex("""\s*[(\[]?\s*(feat\.?|ft\.?|featuring|with)\s+[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE)
    private val REMASTER = Regex("""\s*-\s*(\d{4}\s*)?remaster(ed)?(\s*\d{4})?.*$""", RegexOption.IGNORE_CASE)

    internal fun baseTitle(t: String): String = SongMatch.norm(t.replace(CLUTTER, "").replace(REMASTER, "").replace(FEAT, ""))

    internal fun artistsOf(a: String): Set<String> =
        a.split(Regex("""\s*(,|&|\bx\b|\band\b|\bfeat\.?|\bft\.?|\bwith\b|\bvs\.?|/|;)\s*""", RegexOption.IGNORE_CASE))
            .map { SongMatch.norm(it) }.filter { it.isNotBlank() }.toSet()

    /**
     * Takes over the other app's song at the same second: LToTS starts it (and the songs on the
     * phone most like it after), the other app is told to pause.
     */
    suspend fun continueHere(context: Context): Outcome {
        val np = _nowPlaying.value ?: return Outcome.NOTHING
        val t = findLocal(context, np) ?: return Outcome.NOT_ON_PHONE
        // A moment passes before LToTS' sound starts: start that much further on.
        val pos = (np.positionNow() + 250L).coerceIn(0L, (t.durationMs - 3_000L).coerceAtLeast(0L))
        val lib = withContext(Dispatchers.IO) { runCatching { LocalLibrary.loadSongs(context) }.getOrDefault(emptyList()) }
        val next = runCatching {
            com.ltots.app.recs.LibraryRadio.similarTo(t, lib, com.ltots.app.moods.MoodIndex.embeddings.value).take(40)
        }.getOrDefault(emptyList())
        val ok = withContext(Dispatchers.Main) {
            com.ltots.app.playback.PlayerConnection.connect(context)
            val c = withTimeoutOrNull(5_000L) {
                var c = com.ltots.app.playback.PlayerConnection.controller.value
                while (c == null) { delay(50); c = com.ltots.app.playback.PlayerConnection.controller.value }
                c
            } ?: return@withContext false
            com.ltots.app.recs.SongPreview.stop()
            c.setMediaItems((listOf(t) + next).map { it.toMediaItem() }, 0, pos)
            c.prepare()
            c.play()
            true
        }
        if (!ok) return Outcome.NOTHING
        pauseOther(np.pkg)
        com.ltots.app.CrashReport.step("continued ${np.appLabel}'s song here at ${pos / 1000} s")
        cancelNotification(context)
        return Outcome.PLAYING
    }

    /** Asks the other app to pause (LToTS taking the sound over usually does that already). */
    fun pauseOther(pkg: String) {
        handler?.post {
            trackers.values.firstOrNull { it.controller.packageName == pkg }?.let {
                runCatching { it.controller.transportControls.pause() }
            }
        }
    }

    /**
     * The song isn't on the phone: download it from YouTube Music, then continue here once it's
     * there (if the other app is still on that song). Returns false when it couldn't be found.
     */
    suspend fun downloadAndContinue(context: Context): Boolean {
        val np = _nowPlaying.value ?: return false
        val song = withContext(Dispatchers.IO) {
            runCatching {
                val r = com.ltots.app.ytm.InnerTube.search("${np.title} ${SongMatchArtist(np.artist)}")
                val songs = r.sections.flatMap { it.items }.filterIsInstance<com.ltots.app.ytm.YtSong>()
                songs.firstOrNull { !it.isVideo && baseTitle(it.title) == baseTitle(np.title) }
                    ?: songs.firstOrNull { baseTitle(it.title) == baseTitle(np.title) }
                    ?: songs.firstOrNull()
            }.getOrNull()
        } ?: return false
        com.ltots.app.download.DownloadManager.enqueueSong(song)
        scope.launch {
            // Wait for the file (up to 5 minutes), then take over if that song is still playing there.
            repeat(150) {
                delay(2_000L)
                if (com.ltots.app.download.DownloadManager.isDownloaded(song.videoId)) {
                    delay(1_500L) // the phone's media database lists the new file
                    val still = _nowPlaying.value
                    if (still?.playing == true && baseTitle(still.title) == baseTitle(np.title)) {
                        withContext(Dispatchers.Main) { continueHere(context) }
                    }
                    return@launch
                }
            }
        }
        return true
    }

    private fun SongMatchArtist(a: String) = SongMatch.mainArtist(a)

    // ---- the "continue in LToTS" notification -------------------------------------------------

    private fun maybeNotify(context: Context, np: NowPlaying) {
        if (!notifyContinue(context) || !offerContinue(context)) return
        if (com.ltots.app.CrashReport.inFront) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        scope.launch {
            // Only for songs that are on the phone: then one tap really takes it over.
            findLocal(context, np) ?: return@launch
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Continue in LToTS", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "When another app plays a song that's on your phone"
                        setShowBadge(false)
                    },
                )
                val open = Intent(context, com.ltots.app.MainActivity::class.java)
                    .putExtra(EXTRA_CONTINUE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pi = PendingIntent.getActivity(context, 47, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(com.ltots.app.R.drawable.ic_stat_handoff)
                    .setContentTitle("Continue in LToTS")
                    .setContentText("${np.title} — playing on ${np.appLabel}. It's on your phone: tap to carry on with your EQ.")
                    .setContentIntent(pi)
                    .addAction(0, "Continue here", pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setTimeoutAfter(10 * 60_000L)
                    .build()
                nm.notify(NOTIFICATION_ID, n)
            }
        }
    }

    private fun cancelNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    // ---- the log of plays elsewhere -------------------------------------------------------------

    private fun logFile() = Store.userFile("elsewhere.json")
    private fun totalsFile() = Store.userFile("elsewhere_totals.json")

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _plays.value = runCatching {
            val a = JSONArray(logFile().readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Play(o.optString("t"), o.optString("a"), o.optString("al"), o.optString("s"), o.optLong("at"), o.optLong("ms"))
            }
        }.getOrDefault(emptyList())
    }

    /** After switching user. */
    @Synchronized
    fun reload() { loaded = false; ensureLoaded() }

    @Synchronized
    private fun addToLog(p: Play) {
        ensureLoaded()
        saveLog((listOf(p) + _plays.value).take(LOG_MAX))
    }

    @Synchronized
    private fun updateLog(title: String, artist: String, at: Long, add: Long) {
        val list = _plays.value
        val i = list.indexOfFirst { it.title == title && it.artist == artist && it.at == at }
        if (i < 0) return
        saveLog(list.toMutableList().also { it[i] = it[i].copy(ms = it[i].ms + add) })
    }

    private fun saveLog(list: List<Play>) {
        _plays.value = list
        Store.writeLater(logFile()) {
            JSONArray().apply {
                list.forEach { put(JSONObject().put("t", it.title).put("a", it.artist).put("al", it.album).put("s", it.source).put("at", it.at).put("ms", it.ms)) }
            }.toString()
        }
    }

    /** Was this song heard in another app around [at] (so an account's history doesn't count it twice)? */
    fun heardAround(title: String, artist: String, at: Long, windowMs: Long = 15 * 60_000L): Boolean {
        ensureLoaded()
        val k = ListenStats.key(title, artist)
        return _plays.value.any { kotlin.math.abs(it.at - at) < windowMs && ListenStats.key(it.title, it.artist) == k }
    }

    @Synchronized
    private fun addToTotals(source: String, ms: Long) {
        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        val o = runCatching { JSONObject(totalsFile().readText()) }.getOrDefault(JSONObject())
        val y = o.optJSONObject(year) ?: JSONObject().also { o.put(year, it) }
        y.put(source, y.optLong(source) + ms)
        runCatching { Store.writeFileNow(totalsFile(), o.toString()) }
    }

    /** Listening time per app (other apps only) in [year] (null: all years), biggest first. */
    fun totals(year: Int?): List<Pair<String, Long>> {
        val o = runCatching { JSONObject(totalsFile().readText()) }.getOrNull() ?: return emptyList()
        val sum = HashMap<String, Long>()
        for (y in o.keys()) {
            if (year != null && y != year.toString()) continue
            val yo = o.optJSONObject(y) ?: continue
            for (k in yo.keys()) sum[k] = (sum[k] ?: 0L) + yo.optLong(k)
        }
        return sum.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
