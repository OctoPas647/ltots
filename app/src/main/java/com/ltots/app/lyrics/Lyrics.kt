package com.ltots.app.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** What the lyrics view shows for the current song. */
sealed interface LyricsState {
    data object Idle : LyricsState
    data object Searching : LyricsState
    data class Found(val doc: LyricsDoc) : LyricsState
    data object NotFound : LyricsState
    data class Offline(val message: String) : LyricsState
}

/**
 * Lyrics for the songs on the phone, saved next to the app's other data so they're found once
 * and then work offline. Songs without lyrics in the database are remembered too, and only
 * looked up again after a couple of weeks (lyrics get added over time).
 */
object Lyrics {
    private lateinit var root: File
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val memory = HashMap<String, LyricsDoc?>()
    private val _current = MutableStateFlow<Pair<String, LyricsState>?>(null)
    /** (song key, state) for the song most recently asked about by the UI. */
    val current: StateFlow<Pair<String, LyricsState>?> = _current
    var api = LrcLib()
    private val netease = NetEase()

    const val RETRY_AFTER_MS = 14L * 24 * 3600 * 1000

    fun init(context: Context) {
        root = File(context.filesDir, "ltots/lyrics").apply { mkdirs() }
    }

    private fun file(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(root, "$h.json")
    }

    /**
     * Saved lyrics for [key]: the lyrics, or null if we looked and found none recently.
     * Returns [Unknown] when the song hasn't been looked up (or the "none" is old).
     */
    fun saved(key: String): Any? {
        synchronized(memory) { if (memory.containsKey(key)) return memory[key] }
        val f = file(key)
        if (!f.exists()) return Unknown
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return Unknown
        if (o.optBoolean("none")) {
            return if (System.currentTimeMillis() - o.optLong("at") > RETRY_AFTER_MS) Unknown else null
        }
        val doc = LyricsDoc.fromJson(o)
        synchronized(memory) { memory[key] = doc }
        return doc
    }

    object Unknown

    fun hasLooked(key: String): Boolean = saved(key) !== Unknown

    private fun save(key: String, doc: LyricsDoc?) {
        val f = file(key)
        val tmp = File(f.parentFile, f.name + ".tmp")
        val o = doc?.toJson() ?: JSONObject().put("none", true).put("at", System.currentTimeMillis())
        tmp.writeText(o.toString()); tmp.renameTo(f)
        synchronized(memory) { memory[key] = doc }
    }

    /** Looks [q] up online and saves the result. Throws [LrcLib.NetworkError] when offline. */
    suspend fun fetch(q: SongQuery): LyricsDoc? = lock.withLock {
        (saved(q.key) as? LyricsDoc)?.let { return it }
        var doc: LyricsDoc? = null
        // LRCLIB first: exact lookups, then search. A synced result is preferred.
        val hit = runCatching { api.find(q) }.getOrNull()
        if (hit != null) {
            val (c, asWhat) = hit
            doc = LyricsDoc(
                synced = c.synced?.let { Lrc.parse(it) }?.takeIf { it.isNotEmpty() },
                plain = c.plain, instrumental = c.instrumental, source = "LRCLIB", matchedAs = asWhat,
            ).takeIf { !it.isEmpty }
        }
        // If LRCLIB only had plain (or nothing), try NetEase for a synced version.
        if (doc?.synced == null) {
            val ne = runCatching { netease.find(q) }.getOrNull()
            val neSynced = ne?.first?.synced?.takeIf { it.isNotBlank() }
            if (neSynced != null) {
                doc = LyricsDoc(
                    synced = Lrc.parse(neSynced).takeIf { it.isNotEmpty() },
                    plain = ne.first.plain, instrumental = ne.first.instrumental,
                    source = "NetEase", matchedAs = ne.second,
                ).takeIf { !it.isEmpty } ?: doc
            }
        }
        save(q.key, doc)
        doc
    }

    /** For the lyrics view: show saved lyrics at once, otherwise look them up in the background. */
    fun show(context: Context, q: SongQuery) {
        val s = saved(q.key)
        when {
            s is LyricsDoc -> { _current.value = q.key to LyricsState.Found(s); return }
            s == null -> { _current.value = q.key to LyricsState.NotFound; return }
        }
        if (!online(context)) { _current.value = q.key to LyricsState.Offline("No internet — lyrics will be looked up when you're online."); return }
        _current.value = q.key to LyricsState.Searching
        io.launch {
            val st = try {
                fetch(q)?.let { LyricsState.Found(it) } ?: LyricsState.NotFound
            } catch (e: LrcLib.NetworkError) {
                LyricsState.Offline("Couldn't reach the lyrics database. Try again later.")
            } catch (e: Exception) {
                LyricsState.NotFound
            }
            if (_current.value?.first == q.key) _current.value = q.key to st
        }
    }

    /**
     * Use an .lrc file the user picked for [key]: parse it and save it as the song's lyrics,
     * replacing whatever was found automatically. Returns false if no timed lines were found.
     */
    fun import(context: Context, key: String, lrcText: String): Boolean {
        val lines = Lrc.parse(lrcText).takeIf { it.isNotEmpty() } ?: return false
        val doc = LyricsDoc(synced = lines, plain = null, source = "Imported .lrc")
        save(key, doc)
        if (_current.value?.first == key) _current.value = key to LyricsState.Found(doc)
        return true
    }

    /**
     * Saves lyrics that the on-device AI timed ([AiSync]): [doc]'s words with [lines]' times,
     * shown at once if it's the song on screen.
     */
    fun saveAiSynced(key: String, doc: LyricsDoc, lines: List<LyricLine>) {
        val synced = doc.copy(synced = lines, source = doc.source.ifBlank { "LRCLIB" } + " · timed by on-device AI")
        save(key, synced)
        if (_current.value?.first == key) _current.value = key to LyricsState.Found(synced)
    }

    /** Forget the saved result for [key] and search again. */
    fun retry(context: Context, q: SongQuery) {
        runCatching { file(q.key).delete() }
        synchronized(memory) { memory.remove(q.key) }
        show(context, q)
    }

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** How many of [keys] have lyrics saved (for the status in Sound). */
    fun countFound(keys: List<String>): Int = keys.count { saved(it) is LyricsDoc }
}
