package com.ltots.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.ltots.app.ytm.Collection
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.YtSong
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

const val MAX_DOWNLOADS_AT_ONCE = 10

data class DownloadSettings(
    val concurrency: Int = MAX_DOWNLOADS_AT_ONCE,
    val preferOfficialAudio: Boolean = true,
)

/**
 * The download queue: runs up to N downloads at once, survives the app being closed (the
 * queue and history are saved), remembers what's already downloaded, and keeps synced
 * playlists up to date.
 */
object DownloadManager {
    /** How long the free-lossless check may hold a download up. */
    private const val LOSSLESS_BUDGET_MS = 3_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.ltots.app.CrashReport.guard("downloads"))
    private lateinit var app: Context
    private lateinit var downloader: Downloader
    private val root get() = File(app.filesDir, "ltots").apply { mkdirs() }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    /** Active and recent tasks, newest first. */
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    private val _downloaded = MutableStateFlow<Map<String, String>>(emptyMap())
    /** videoId → content URI of everything downloaded so far. */
    val downloaded: StateFlow<Map<String, String>> = _downloaded

    /** videoId → what was downloaded (title, artist, cover…), so a deleted song can be offered again. */
    @Volatile private var metaIndex: Map<String, TrackMeta> = emptyMap()

    private val _deleted = MutableStateFlow<List<DeletedSong>>(emptyList())
    /** Songs LToTS downloaded that were deleted from the phone since, newest first. */
    val deleted: StateFlow<List<DeletedSong>> = _deleted
    /** False until this version has looked through the old download history for deleted songs (once). */
    private var deletedSeeded = false

    private val _synced = MutableStateFlow<List<SyncedPlaylist>>(emptyList())
    val synced: StateFlow<List<SyncedPlaylist>> = _synced

    private val _settings = MutableStateFlow(DownloadSettings())
    val settings: StateFlow<DownloadSettings> = _settings

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    /** Bumped whenever a file lands in Music, so the library reloads. */
    private val _libraryVersion = MutableStateFlow(0)
    val libraryVersion: StateFlow<Int> = _libraryVersion

    private val running = HashMap<String, Job>()
    /** yt-dlp process name currently used by each task (a new one per attempt). */
    private val processIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val attempts = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Stream information read ahead (videoId → it), for the songs about to download: while one
     * downloads, the next ones' slowest step is already done. Stream addresses expire after a
     * few hours, so a read-ahead is only used while fresh.
     */
    private val readAhead = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Downloader.Prefetched?>>()
    private val readAheadGate = kotlinx.coroutines.sync.Semaphore(4)
    private const val READ_AHEAD = 4
    private const val READ_AHEAD_FRESH_MS = 2 * 3600_000L

    private fun readAheadFor(videoId: String): kotlinx.coroutines.Deferred<Downloader.Prefetched?> =
        readAhead.getOrPut(videoId) {
            scope.async {
                readAheadGate.withPermit {
                    if (!Engine.ensureReady()) return@withPermit null
                    runCatching { downloader.readInfo(videoId, "ra-$videoId-${attempts.incrementAndGet()}") }.getOrNull()
                }
            }
        }

    private fun killProcesses(taskId: String) {
        val pid = processIds[taskId] ?: return
        for (i in 0..1) {
            runCatching { YoutubeDL.destroyProcessById("$pid-$i") }
            runCatching { YoutubeDL.destroyProcessById("$pid-$i-info") }
        }
    }
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        downloader = Downloader(app)
        Engine.init(app)
        load()
        // Downloads whose files were deleted from the phone move to the "Deleted" list, so they
        // can be downloaded again (and are one tap away there).
        scope.launch { runCatching { pruneMissing() } }
        // Anything that was mid-download when the app closed starts again.
        update { list -> list.map { if (it.state.active) it.copy(state = TaskState.QUEUED, progress = 0f) else it } }
        if (_tasks.value.any { it.state == TaskState.QUEUED }) pump()
    }

    // ---- persistence -----------------------------------------------------------------------

    private fun load() {
        runCatching {
            val f = File(root, "downloads.json")
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val arr = o.optJSONArray("tasks") ?: JSONArray()
                _tasks.value = List(arr.length()) { DownloadTask.fromJson(arr.getJSONObject(it)) }
                val idx = o.optJSONObject("index") ?: JSONObject()
                _downloaded.value = idx.keys().asSequence().associateWith { idx.getString(it) }
                o.optJSONObject("meta")?.let { m ->
                    metaIndex = m.keys().asSequence().mapNotNull { k -> runCatching { k to TrackMeta.fromJson(m.getJSONObject(k)) }.getOrNull() }.toMap()
                }
                o.optJSONArray("deleted")?.let { d ->
                    deletedSeeded = true
                    _deleted.value = (0 until d.length()).mapNotNull { i -> runCatching { DeletedSong.fromJson(d.getJSONObject(i)) }.getOrNull() }
                }
                val sp = o.optJSONArray("synced") ?: JSONArray()
                _synced.value = List(sp.length()) { SyncedPlaylist.fromJson(sp.getJSONObject(it)) }
                o.optJSONObject("settings")?.let {
                    // Set once to the new limit; "ten" marks a choice made since it went up to 10.
                    val conc = if (it.optBoolean("ten")) it.optInt("conc", MAX_DOWNLOADS_AT_ONCE) else MAX_DOWNLOADS_AT_ONCE
                    _settings.value = DownloadSettings(conc.coerceIn(1, MAX_DOWNLOADS_AT_ONCE), it.optBoolean("official", true))
                }
            }
        }
    }

    private var saveJob: Job? = null
    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val o = JSONObject()
            // Keep the queue plus the last 300 finished downloads as history.
            val keep = _tasks.value.let { l -> l.filter { !it.state.finished } + l.filter { it.state.finished }.take(300) }
            o.put("tasks", JSONArray().apply { keep.forEach { put(it.toJson()) } })
            o.put("index", JSONObject(_downloaded.value as Map<*, *>))
            o.put("meta", JSONObject().apply { metaIndex.forEach { (k, v) -> put(k, v.toJson()) } })
            if (deletedSeeded) o.put("deleted", JSONArray().apply { _deleted.value.take(MAX_DELETED).forEach { put(it.toJson()) } })
            o.put("synced", JSONArray().apply { _synced.value.forEach { put(it.toJson()) } })
            o.put("settings", JSONObject().put("conc", _settings.value.concurrency).put("ten", true).put("official", _settings.value.preferOfficialAudio))
            val f = File(root, "downloads.json")
            val tmp = File(root, "downloads.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    private val lock = Any()

    /** All task-list changes go through here, so progress and state updates never overwrite each other. */
    private fun update(save: Boolean = true, transform: (List<DownloadTask>) -> List<DownloadTask>) {
        synchronized(lock) { _tasks.value = transform(_tasks.value) }
        if (save) save()
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) =
        update { list -> list.map { if (it.id == id) transform(it) else it } }

    // ---- public API ------------------------------------------------------------------------

    fun isDownloaded(videoId: String) = _downloaded.value.containsKey(videoId)

    private fun fileExists(uri: String): Boolean = runCatching {
        app.contentResolver.query(Uri.parse(uri), arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: true // no answer from the provider: assume it's still there
    }.getOrDefault(true)

    /** At most this many deleted songs are remembered (the oldest drop off). */
    private const val MAX_DELETED = 500

    /** The phone's audio, to tell whether a recording is (still) there; null when it can't be read. */
    private suspend fun librarySnapshot(): List<com.ltots.app.data.Track>? =
        runCatching { com.ltots.app.data.LocalLibrary.load(app) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun inLibrary(lib: List<com.ltots.app.data.Track>?, m: TrackMeta): com.ltots.app.data.Track? =
        lib?.let { com.ltots.app.data.Duplicates.find(it, m.title, m.artist, (m.durationSec ?: 0) * 1000L) }

    /**
     * Downloads whose files are gone from the phone: forgotten as "downloaded" (so they can be
     * downloaded again) and remembered as deleted, unless another copy of the same recording is
     * still on the phone (a duplicate was cleaned up: nothing is missing).
     */
    private suspend fun pruneMissing() {
        val lib = librarySnapshot()
        // What each download was, for the ones saved before LToTS kept that: from the history.
        val known = HashMap(metaIndex)
        for (t in _tasks.value) if (t.state == TaskState.DONE) known.putIfAbsent(t.meta.videoId, t.meta)
        val gone = _downloaded.value.filterValues { !fileExists(it) }
        val lost = ArrayList<TrackMeta>()
        // Several ids can point at one file (a music video and its official audio): one entry.
        gone.entries.groupBy { it.value }.values.forEach { ids -> ids.firstNotNullOfOrNull { known[it.key] }?.let { lost += it } }
        if (!deletedSeeded) {
            // First run with the Deleted list: songs in the download history that aren't on the
            // phone any more were deleted before LToTS remembered such things.
            for (t in _tasks.value) {
                if (t.state == TaskState.DONE && !_downloaded.value.containsKey(t.meta.videoId) &&
                    t.savedUri != null && !fileExists(t.savedUri)) lost += t.meta
            }
        }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (gone.isNotEmpty()) _downloaded.value = _downloaded.value - gone.keys
            metaIndex = (known - gone.keys).filterKeys { _downloaded.value.containsKey(it) }
            val have = _deleted.value.mapTo(HashSet()) { it.meta.videoId }
            val fresh = lost.distinctBy { it.videoId }
                .filter { it.videoId !in have && !_downloaded.value.containsKey(it.videoId) && it.title != Downloader.PLACEHOLDER_TITLE && inLibrary(lib, it) == null }
                .map { DeletedSong(it, now) }
            if (fresh.isNotEmpty()) _deleted.value = (fresh + _deleted.value).take(MAX_DELETED)
            deletedSeeded = true
        }
        save()
    }

    /** Files were deleted from the phone inside LToTS (the Duplicates view): catch up at once. */
    fun onLibraryFilesDeleted() {
        _libraryVersion.value++
        scope.launch { runCatching { pruneMissing() } }
    }

    /** Downloads a deleted song again; it leaves the Deleted list. */
    fun redownload(videoId: String): Boolean {
        val d = _deleted.value.firstOrNull { it.meta.videoId == videoId } ?: return false
        return enqueue(listOf(d.meta), anyway = true) > 0
    }

    /** Downloads every deleted song again. Returns how many were queued. */
    fun redownloadAll(): Int = enqueue(_deleted.value.map { it.meta }, anyway = true)

    /** Drops one song (or, with null, all of them) from the Deleted list for good. */
    fun forgetDeleted(videoId: String? = null) {
        synchronized(lock) { _deleted.value = if (videoId == null) emptyList() else _deleted.value.filterNot { it.meta.videoId == videoId } }
        save()
    }

    fun isQueued(videoId: String) = _tasks.value.any { it.meta.videoId == videoId && !it.state.finished }

    /**
     * Adds songs to the queue. Returns how many were actually added (the rest are already here).
     *
     * Besides what LToTS downloaded itself, the whole library is checked: a song whose recording
     * is already on the phone (same title, artist, version and length, wherever the file came
     * from) isn't downloaded a second time. A single song skipped that way shows in Recents as
     * "Already in your library" with a button to download it anyway.
     *
     * @param anyway skip the library check (the user asked for this exact song again).
     * @param bringBack false for a playlist sync: songs the user deleted stay deleted.
     */
    fun enqueue(songs: List<TrackMeta>, sourcePlaylist: String? = null, anyway: Boolean = false, bringBack: Boolean = true): Int {
        // Re-check files the index thinks we have (the user may have deleted them).
        val claimed = songs.mapNotNull { s -> _downloaded.value[s.videoId]?.let { s.videoId to it } }
        val missing = claimed.filter { !fileExists(it.second) }.map { it.first }
        if (missing.isNotEmpty()) synchronized(lock) { _downloaded.value = _downloaded.value - missing.toSet() }
        val deletedIds = if (bringBack) emptySet() else _deleted.value.mapTo(HashSet()) { it.meta.videoId }
        var fresh = songs.distinctBy { it.videoId }.filter { !isDownloaded(it.videoId) && !isQueued(it.videoId) && it.videoId !in deletedIds }
        if (!anyway && fresh.isNotEmpty()) {
            val lib = com.ltots.app.data.LocalLibrary.cached()
            if (!lib.isNullOrEmpty()) {
                val byKey = lib.groupBy { com.ltots.app.data.Duplicates.key(it.title, it.artist) }
                val (have, need) = fresh.partition { m ->
                    m.title != Downloader.PLACEHOLDER_TITLE &&
                        byKey[com.ltots.app.data.Duplicates.key(m.title, m.artist)].orEmpty()
                            .any { com.ltots.app.data.Duplicates.sameLength(it.durationMs, (m.durationSec ?: 0) * 1000L) }
                }
                fresh = need
                // One song asked for by itself: say why nothing happened, and offer it anyway.
                if (have.size == 1 && songs.size == 1) {
                    val now = System.currentTimeMillis()
                    update { l ->
                        listOf(DownloadTask(UUID.randomUUID().toString(), have[0], state = TaskState.SKIPPED, error = IN_LIBRARY, sourcePlaylist = sourcePlaylist, finishedAt = now)) +
                            l.filterNot { it.state == TaskState.SKIPPED && it.meta.videoId == have[0].videoId }
                    }
                }
            }
        }
        if (fresh.isEmpty()) return 0
        // Asked for again: no longer "deleted".
        val back = fresh.mapTo(HashSet()) { it.videoId }
        if (_deleted.value.any { it.meta.videoId in back }) synchronized(lock) { _deleted.value = _deleted.value.filterNot { it.meta.videoId in back } }
        val newTasks = fresh.map { DownloadTask(UUID.randomUUID().toString(), it, sourcePlaylist = sourcePlaylist) }
        update { newTasks.reversed() + it }
        pump()
        return fresh.size
    }

    /** Marks a download skipped because the recording is already in the library (see [enqueue]). */
    const val IN_LIBRARY = "Already in your library"

    /** A song skipped as already in the library is downloaded after all. */
    fun downloadAnyway(taskId: String) {
        val t = _tasks.value.firstOrNull { it.id == taskId } ?: return
        update { l -> l.filterNot { it.id == taskId } }
        enqueue(listOf(t.meta), t.sourcePlaylist, anyway = true)
    }

    fun enqueueSong(s: YtSong) = enqueue(listOf(TrackMeta.from(s)))

    private val linkLookups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Queues a pasted song link without making the caller wait for its details, so the next link
     * can be pasted at once. False when the song is already here or on its way.
     */
    fun enqueueLink(videoId: String): Boolean {
        if (isDownloaded(videoId) || isQueued(videoId) || !linkLookups.add(videoId)) return false
        scope.launch {
            try {
                val s = runCatching { InnerTube.song(videoId) }.getOrNull()
                    ?: YtSong(videoId, Downloader.PLACEHOLDER_TITLE, emptyList(), isVideo = true)
                enqueueSong(s)
            } finally {
                linkLookups.remove(videoId)
            }
        }
        return true
    }

    /** Downloads a whole album, keeping its track order and album metadata. */
    fun enqueueAlbum(album: Collection, only: Set<String>? = null): Int = enqueue(
        album.tracks.filter { only == null || it.videoId in only }.map {
            TrackMeta.from(it, albumArtist = album.artist, cover = album.thumbUrl).copy(
                album = album.title, albumId = album.id.takeIf { id -> id.startsWith("MPREb") },
                year = it.year ?: album.year,
            )
        }
    )

    /** Downloads a playlist and remembers it so "Sync" can fetch new songs later. */
    fun enqueuePlaylist(p: Collection, only: Set<String>? = null, remember: Boolean = true): Int {
        val id = p.id.removePrefix("VL")
        if (remember) {
            _synced.value = _synced.value.filterNot { it.playlistId == id } +
                SyncedPlaylist(id, p.title, p.thumbUrl, System.currentTimeMillis(), p.tracks.size)
        }
        return enqueue(p.tracks.filter { only == null || it.videoId in only }.map { TrackMeta.from(it) }, id)
    }

    /** Re-reads a synced playlist and downloads only what's new. Returns the number of new songs. */
    suspend fun sync(playlistId: String): Int {
        val p = InnerTube.playlist(playlistId)
        _synced.value = _synced.value.map {
            if (it.playlistId == playlistId) it.copy(lastSync = System.currentTimeMillis(), trackCount = p.tracks.size, title = p.title) else it
        }
        save()
        // Songs deleted since the last sync aren't fetched again (Get > Deleted brings them back).
        return enqueue(p.tracks.map { TrackMeta.from(it) }, playlistId, bringBack = false)
    }

    suspend fun syncAll(): Int = _synced.value.sumOf { runCatching { sync(it.playlistId) }.getOrDefault(0) }

    fun forgetPlaylist(playlistId: String) {
        _synced.value = _synced.value.filterNot { it.playlistId == playlistId }
        save()
    }

    fun cancel(id: String) {
        killProcesses(id)
        synchronized(running) { running.remove(id)?.cancel() }
        updateTask(id) { if (it.state.finished) it else it.copy(state = TaskState.CANCELED, finishedAt = System.currentTimeMillis()) }
        pump()
    }

    fun retry(id: String) {
        updateTask(id) { it.copy(state = TaskState.QUEUED, progress = 0f, error = null, log = null) }
        pump()
    }

    fun retryFailed() {
        update { l -> l.map { if (it.state == TaskState.FAILED) it.copy(state = TaskState.QUEUED, progress = 0f, error = null, log = null) else it } }
        pump()
    }

    fun clearFinished() = update { l -> l.filter { !it.state.finished } }

    fun cancelAll() = _tasks.value.filter { !it.state.finished }.forEach { cancel(it.id) }

    fun setPaused(p: Boolean) {
        _paused.value = p
        if (p) {
            // Stop what's running and put it back in the queue.
            synchronized(running) {
                running.keys.toList().forEach { id ->
                    killProcesses(id)
                    running.remove(id)?.cancel()
                }
            }
            update { l -> l.map { if (it.state.active) it.copy(state = TaskState.QUEUED, progress = 0f) else it } }
        } else pump()
    }

    fun updateSettings(transform: (DownloadSettings) -> DownloadSettings) {
        _settings.value = transform(_settings.value).let { it.copy(concurrency = it.concurrency.coerceIn(1, MAX_DOWNLOADS_AT_ONCE)) }
        save()
        pump()
    }

    // ---- worker ----------------------------------------------------------------------------

    private fun pump() {
        if (_paused.value) return
        synchronized(running) {
            running.entries.removeAll { !it.value.isActive }
            val limit = _settings.value.concurrency
            val next = _tasks.value.filter { it.state == TaskState.QUEUED && it.id !in running }.reversed()
            for (t in next) {
                if (running.size >= limit) break
                running[t.id] = scope.launch { runTask(t) }
            }
            // The next songs waiting get their stream information read already.
            next.filter { it.id !in running && !it.meta.isVideo }.take(READ_AHEAD).forEach { readAheadFor(it.meta.videoId) }
            if (running.isNotEmpty()) startService()
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        try {
            if (!Engine.ensureReady()) throw IllegalStateException(Engine.state.value.error ?: "Download engine unavailable")
            if (isDownloaded(task.meta.videoId)) {
                updateTask(task.id) { it.copy(state = TaskState.SKIPPED, finishedAt = System.currentTimeMillis()) }
                return
            }
            updateTask(task.id) { it.copy(state = TaskState.PREPARING) }
            // Which video to fetch is settled first (a music video → its official audio); then
            // three things run at once instead of one after another: yt-dlp reading the stream
            // information (the slow part), the album / year / cover lookup, and the quick check
            // for a free lossless copy — which gets a few seconds at most.
            val chosen = downloader.officialAudio(task.meta, _settings.value.preferOfficialAudio)
            if (chosen.videoId != task.meta.videoId && isDownloaded(chosen.videoId)) {
                updateTask(task.id) { it.copy(meta = chosen, state = TaskState.SKIPPED, finishedAt = System.currentTimeMillis()) }
                return
            }
            val (meta, lossless, prefetched) = kotlinx.coroutines.coroutineScope {
                val info = readAheadFor(chosen.videoId)
                val started = System.currentTimeMillis()
                // Detached: a slow archive.org answer is simply not waited for.
                val flac = scope.async { runCatching { FreeLossless().find(chosen) }.getOrNull() }
                val m = downloader.details(chosen)
                val left = (LOSSLESS_BUDGET_MS - (System.currentTimeMillis() - started)).coerceAtLeast(1L)
                val hit = kotlinx.coroutines.withTimeoutOrNull(left) { flac.await() }
                if (hit == null) flac.cancel()
                // With a lossless copy, the stream information isn't needed.
                val pre = if (hit != null) null else info.await()?.takeIf { System.currentTimeMillis() - it.at < READ_AHEAD_FRESH_MS }
                Triple(m, hit, pre)
            }
            readAhead.remove(chosen.videoId)
            updateTask(task.id) { it.copy(meta = meta, state = TaskState.DOWNLOADING, progress = 0f) }
            var lastUi = 0L
            val pid = "${task.id}-${attempts.incrementAndGet()}"
            processIds[task.id] = pid
            val result = downloader.download(pid, meta, lossless = lossless, prefetched = prefetched, onProgress = { p, line ->
                val now = System.currentTimeMillis()
                if (line == "tagging") updateTask(task.id) { it.copy(state = TaskState.TAGGING, progress = 1f) }
                else if (now - lastUi > 500) {
                    lastUi = now
                    update(save = false) { l -> l.map { if (it.id == task.id && it.state == TaskState.DOWNLOADING) it.copy(progress = p) else it } }
                }
            })
            synchronized(lock) {
                _downloaded.value = _downloaded.value + (meta.videoId to result.uri.toString()) + (task.meta.videoId to result.uri.toString())
                metaIndex = metaIndex + (meta.videoId to result.meta.copy(videoId = meta.videoId)) + (task.meta.videoId to result.meta.copy(videoId = task.meta.videoId))
                val ids = setOf(meta.videoId, task.meta.videoId)
                if (_deleted.value.any { it.meta.videoId in ids }) _deleted.value = _deleted.value.filterNot { it.meta.videoId in ids }
            }
            updateTask(task.id) {
                it.copy(meta = result.meta, state = TaskState.DONE, progress = 1f, finishedAt = System.currentTimeMillis(),
                    savedUri = result.uri.toString(), qualityNote = result.qualityNote)
            }
            _libraryVersion.value++
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            // cancel() already marked it
        } catch (e: Throwable) {
            if (_tasks.value.firstOrNull { it.id == task.id }?.state == TaskState.CANCELED) return
            if (_paused.value) return
            val log = (e as? Downloader.Failure)?.log ?: e.stackTraceToString().take(4000)
            updateTask(task.id) {
                it.copy(state = TaskState.FAILED, error = friendlyError(e), finishedAt = System.currentTimeMillis(),
                    log = "LToTS ${appVersion()} · yt-dlp ${Engine.state.value.version ?: Engine.BUNDLED_YTDLP} · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL})\n" +
                        "${e.javaClass.simpleName}: ${e.message?.take(600)}\n\n$log")
            }
        } finally {
            processIds.remove(task.id)
            synchronized(running) { running.remove(task.id) }
            pump()
        }
    }

    private fun appVersion(): String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun friendlyError(e: Throwable): String {
        val text = e.message ?: e.javaClass.simpleName
        val m = text.lines().lastOrNull { it.contains("ERROR") } ?: text.lines().firstOrNull { it.isNotBlank() } ?: "Unknown error"
        return when {
            m.contains("Sign in to confirm", true) || m.contains("not a bot", true) ->
                "YouTube asked for a sign-in check. Try again later, or tap Update engine."
            m.contains("Video unavailable", true) || m.contains("not available in your country", true) ->
                "This song isn't available in your country or was removed."
            m.contains("Private video", true) -> "This video is private."
            m.contains("HTTP Error 403", true) || m.contains("nsig", true) || m.contains("n challenge", true) ||
                m.contains("Requested format is not available", true) ->
                "YouTube refused the download. Tap Update engine, then retry."
            m.contains("Unable to resolve host", true) || m.contains("timed out", true) ||
                m.contains("Network is unreachable", true) || m.contains("No address associated", true) ->
                "No internet connection."
            else -> m.removePrefix("ERROR: ").take(220)
        } + " (tap for details)"
    }

    // ---- foreground service ----------------------------------------------------------------

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }
    }

    /** Snapshot for the notification. */
    fun activeSummary(): Triple<Int, Int, DownloadTask?> {
        val l = _tasks.value
        val active = l.filter { it.state.active }
        val queued = l.count { it.state == TaskState.QUEUED }
        return Triple(active.size, queued, active.firstOrNull())
    }

    fun hasWork() = !_paused.value && _tasks.value.any { it.state == TaskState.QUEUED || it.state.active }

    fun uriFor(videoId: String): Uri? = _downloaded.value[videoId]?.let(Uri::parse)
}
