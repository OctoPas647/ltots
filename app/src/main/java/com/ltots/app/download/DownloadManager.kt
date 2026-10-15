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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DownloadSettings(
    val concurrency: Int = 3,
    val preferOfficialAudio: Boolean = true,
)

/**
 * The download queue: runs up to N downloads at once, survives the app being closed (the
 * queue and history are saved), remembers what's already downloaded, and keeps synced
 * playlists up to date.
 */
object DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context
    private lateinit var downloader: Downloader
    private val root get() = File(app.filesDir, "ltots").apply { mkdirs() }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    /** Active and recent tasks, newest first. */
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    private val _downloaded = MutableStateFlow<Map<String, String>>(emptyMap())
    /** videoId → content URI of everything downloaded so far. */
    val downloaded: StateFlow<Map<String, String>> = _downloaded

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
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        downloader = Downloader(app)
        Engine.init(app)
        load()
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
                val sp = o.optJSONArray("synced") ?: JSONArray()
                _synced.value = List(sp.length()) { SyncedPlaylist.fromJson(sp.getJSONObject(it)) }
                o.optJSONObject("settings")?.let {
                    _settings.value = DownloadSettings(it.optInt("conc", 3).coerceIn(1, 6), it.optBoolean("official", true))
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
            o.put("synced", JSONArray().apply { _synced.value.forEach { put(it.toJson()) } })
            o.put("settings", JSONObject().put("conc", _settings.value.concurrency).put("official", _settings.value.preferOfficialAudio))
            val f = File(root, "downloads.json")
            val tmp = File(root, "downloads.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    @Synchronized
    private fun update(transform: (List<DownloadTask>) -> List<DownloadTask>) {
        _tasks.value = transform(_tasks.value)
        save()
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) =
        update { list -> list.map { if (it.id == id) transform(it) else it } }

    // ---- public API ------------------------------------------------------------------------

    fun isDownloaded(videoId: String) = _downloaded.value.containsKey(videoId)

    fun isQueued(videoId: String) = _tasks.value.any { it.meta.videoId == videoId && !it.state.finished }

    /** Adds songs to the queue. Returns how many were actually added (the rest are already here). */
    fun enqueue(songs: List<TrackMeta>, sourcePlaylist: String? = null): Int {
        val fresh = songs.distinctBy { it.videoId }.filter { !isDownloaded(it.videoId) && !isQueued(it.videoId) }
        if (fresh.isEmpty()) return 0
        val newTasks = fresh.map { DownloadTask(UUID.randomUUID().toString(), it, sourcePlaylist = sourcePlaylist) }
        update { newTasks.reversed() + it }
        pump()
        return fresh.size
    }

    fun enqueueSong(s: YtSong) = enqueue(listOf(TrackMeta.from(s)))

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
        return enqueue(p.tracks.map { TrackMeta.from(it) }, playlistId)
    }

    suspend fun syncAll(): Int = _synced.value.sumOf { runCatching { sync(it.playlistId) }.getOrDefault(0) }

    fun forgetPlaylist(playlistId: String) {
        _synced.value = _synced.value.filterNot { it.playlistId == playlistId }
        save()
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.destroyProcessById(id) }
        runCatching { YoutubeDL.destroyProcessById("$id-info") }
        synchronized(running) { running.remove(id)?.cancel() }
        updateTask(id) { if (it.state.finished) it else it.copy(state = TaskState.CANCELED, finishedAt = System.currentTimeMillis()) }
        pump()
    }

    fun retry(id: String) {
        updateTask(id) { it.copy(state = TaskState.QUEUED, progress = 0f, error = null) }
        pump()
    }

    fun retryFailed() {
        update { l -> l.map { if (it.state == TaskState.FAILED) it.copy(state = TaskState.QUEUED, progress = 0f, error = null) else it } }
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
                    runCatching { YoutubeDL.destroyProcessById(id) }
                    running.remove(id)?.cancel()
                }
            }
            update { l -> l.map { if (it.state.active) it.copy(state = TaskState.QUEUED, progress = 0f) else it } }
        } else pump()
    }

    fun updateSettings(transform: (DownloadSettings) -> DownloadSettings) {
        _settings.value = transform(_settings.value).let { it.copy(concurrency = it.concurrency.coerceIn(1, 6)) }
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
            val meta = downloader.enrich(task.meta, _settings.value.preferOfficialAudio) { }
            if (meta.videoId != task.meta.videoId && isDownloaded(meta.videoId)) {
                updateTask(task.id) { it.copy(meta = meta, state = TaskState.SKIPPED, finishedAt = System.currentTimeMillis()) }
                return
            }
            updateTask(task.id) { it.copy(meta = meta, state = TaskState.DOWNLOADING, progress = 0f) }
            var lastUi = 0L
            val result = downloader.download(task.id, meta) { p, line ->
                val now = System.currentTimeMillis()
                if (line == "tagging") updateTask(task.id) { it.copy(state = TaskState.TAGGING, progress = 1f) }
                else if (now - lastUi > 250) {
                    lastUi = now
                    _tasks.value = _tasks.value.map { if (it.id == task.id) it.copy(progress = p) else it }
                }
            }
            _downloaded.value = _downloaded.value + (meta.videoId to result.uri.toString()) + (task.meta.videoId to result.uri.toString())
            updateTask(task.id) {
                it.copy(state = TaskState.DONE, progress = 1f, finishedAt = System.currentTimeMillis(),
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
            updateTask(task.id) {
                it.copy(state = TaskState.FAILED, error = friendlyError(e), finishedAt = System.currentTimeMillis())
            }
        } finally {
            synchronized(running) { running.remove(task.id) }
            pump()
        }
    }

    private fun friendlyError(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName).lines().lastOrNull { it.contains("ERROR") } ?: e.message ?: "Unknown error"
        return when {
            m.contains("Sign in to confirm", true) || m.contains("bot", true) ->
                "YouTube asked for a sign-in check. Try again later, or tap Update engine."
            m.contains("not available", true) || m.contains("unavailable", true) -> "This song isn't available in your country or was removed."
            m.contains("Private video", true) -> "This video is private."
            m.contains("HTTP Error 403", true) || m.contains("nsig", true) || m.contains("signature", true) ->
                "YouTube changed something. Tap Update engine, then retry."
            m.contains("Unable to resolve host", true) || m.contains("timed out", true) || m.contains("Network", true) ->
                "No internet connection."
            else -> m.removePrefix("ERROR: ").take(220)
        }
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
