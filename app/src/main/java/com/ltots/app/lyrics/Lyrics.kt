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
    /** The AI match is running; [status] says what it's doing. */
    data class Matching(val status: String) : LyricsState
    /** The AI match didn't work; [message] says why. The previous lyrics (if any) are kept. */
    data class MatchFailed(val message: String, val previous: LyricsDoc?) : LyricsState
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
    /** Bumped when the search gets better, so songs it missed before are tried again at once. */
    private const val SEARCH_VERSION = 3

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
            if (o.optInt("v") < SEARCH_VERSION) return Unknown
            return if (System.currentTimeMillis() - o.optLong("at") > RETRY_AFTER_MS) Unknown else null
        }
        val doc = LyricsDoc.fromJson(o)
        synchronized(memory) { memory[key] = doc }
        return doc
    }

    object Unknown

    private val recheckMemo = HashMap<String, Boolean>()

    /**
     * Whether these saved lyrics came from an older, less careful search and should be checked
     * again the next time the song plays. An .lrc file the user picked is never touched.
     */
    fun needsRecheck(key: String): Boolean {
        synchronized(memory) { recheckMemo[key]?.let { return it } }
        val o = runCatching { JSONObject(file(key).readText()) }.getOrNull()
        val r = o != null && !o.optBoolean("none") && o.optInt("v") < SEARCH_VERSION && o.optString("source") != IMPORTED
        synchronized(memory) { recheckMemo[key] = r }
        return r
    }

    /** "No lyrics" saved after the AI had already tried this song too. */
    private fun aiTried(key: String): Boolean =
        runCatching { JSONObject(file(key).readText()).optBoolean("ai") }.getOrDefault(false)

    fun hasLooked(key: String): Boolean = saved(key) !== Unknown

    private fun save(key: String, doc: LyricsDoc?, aiTried: Boolean = false) {
        val f = file(key)
        val tmp = File(f.parentFile, f.name + ".tmp")
        val o = (doc?.toJson() ?: JSONObject().put("none", true).put("at", System.currentTimeMillis()).put("ai", aiTried)).put("v", SEARCH_VERSION)
        tmp.writeText(o.toString()); tmp.renameTo(f)
        synchronized(memory) { memory[key] = doc; recheckMemo[key] = false }
    }

    /**
     * Looks [q] up online and saves the result. Only lyrics that really are this song (artist,
     * title, length, and words that make sense). Throws [LrcLib.NetworkError] when offline.
     */
    suspend fun fetch(q: SongQuery): LyricsDoc? = lock.withLock {
        (saved(q.key) as? LyricsDoc)?.takeIf { !needsRecheck(q.key) }?.let { return it }
        val doc = lookup(q)
        // Being re-checked and nothing better found: keep them until the AI has had its turn.
        if (doc != null || !needsRecheck(q.key)) save(q.key, doc)
        doc
    }

    /**
     * Nothing trustworthy, and the AI couldn't tell either: the song's name alone as a last
     * resort (or what was saved before), marked as maybe wrong.
     */
    internal fun gaveUp(q: SongQuery, previous: LyricsDoc?) {
        val loose = runCatching { api.findLoose(q) }.getOrNull()?.takeIf { LyricsQuality.ok(q.copy(durationMs = 0L), it.first) }?.let { (c, asWhat) ->
            val sameLength = q.durationMs <= 0 || kotlin.math.abs(c.durationSec - q.durationMs / 1000.0) <= SongMatch.MAX_LENGTH_DIFF_SEC
            val synced = c.synced?.let { Lrc.parse(it) }?.takeIf { it.isNotEmpty() }
            LyricsDoc(
                synced = synced.takeIf { sameLength },
                plain = c.plain ?: synced?.joinToString("\n") { it.text },
                instrumental = c.instrumental, source = "LRCLIB", matchedAs = asWhat, note = LOOSE_NOTE,
            ).takeIf { !it.isEmpty }
        }
        val doc = loose ?: previous?.copy(note = LOOSE_NOTE)
        save(q.key, doc, aiTried = true)
        if (_current.value?.first == q.key) _current.value = q.key to (doc?.let { LyricsState.Found(it) } ?: LyricsState.NotFound)
    }

    /** Looks [q] up (exact name and length) without saving anything. */
    suspend fun lookup(q: SongQuery): LyricsDoc? {
        var doc: LyricsDoc? = null
        var unreachable: LrcLib.NetworkError? = null
        // LRCLIB first: exact lookups, then search. A synced result is preferred.
        val hit = try { api.find(q) } catch (e: LrcLib.NetworkError) { unreachable = e; null } catch (e: Exception) { null }
        if (hit != null) {
            val (c, asWhat) = hit
            doc = LyricsDoc(
                synced = c.synced?.let { Lrc.parse(it) }?.takeIf { it.isNotEmpty() },
                plain = c.plain, instrumental = c.instrumental, source = "LRCLIB", matchedAs = asWhat,
                recordSec = c.durationSec.takeIf { it > 0 },
            ).takeIf { !it.isEmpty }
        }
        // If LRCLIB only had plain (or nothing), try NetEase for a synced version.
        if (doc?.synced == null) {
            val ne = runCatching { netease.find(q) }.getOrNull()
            val neSynced = ne?.first?.synced?.takeIf { it.isNotBlank() }
            if (neSynced != null) {
                doc = LyricsDoc(
                    synced = LyricsQuality.withoutCredits(Lrc.parse(neSynced)).takeIf { it.isNotEmpty() },
                    plain = ne.first.plain, instrumental = ne.first.instrumental,
                    source = "NetEase", matchedAs = ne.second,
                    recordSec = ne.first.durationSec.takeIf { it > 0 },
                ).takeIf { !it.isEmpty } ?: doc
            }
        }
        // Couldn't ask at all is not the same as "there are none".
        if (doc == null) unreachable?.let { throw it }
        return doc
    }

    /**
     * Saves lyrics the AI match found for [key] (replacing what was there) and shows them. The
     * lyrics found the normal way are kept aside, so [revert] can bring them back if the AI
     * button was pressed by mistake. (A song that had no lyrics before has nothing to revert to.)
     */
    fun saveMatched(key: String, doc: LyricsDoc) {
        val before = saved(key) as? LyricsDoc
        // Pressing AI again keeps the original normal lyrics as the ones to go back to.
        if (before != null && !isAiMatch(before) && !backupFile(key).exists()) {
            runCatching { backupFile(key).writeText(before.toJson().toString()) }
        }
        save(key, doc.copy(note = doc.note.ifBlank { AI_NOTE }))
        if (_current.value?.first == key) _current.value = key to LyricsState.Found(saved(key) as? LyricsDoc ?: doc)
    }

    /** The note every AI match carries (how it's recognised as one). */
    private const val AI_NOTE = "AI recognised the song from its sound"
    private const val LOOSE_NOTE = "Found by the song's name"
    private const val IMPORTED = "Imported .lrc"

    /** Lyrics that weren't confirmed by artist, title and length: shown with a warning. */
    fun isUnsure(doc: LyricsDoc): Boolean = isAiMatch(doc) || doc.note.startsWith(LOOSE_NOTE)

    /** Whether [doc] came from the AI match (rather than the normal search, an .lrc or the AI timing). */
    fun isAiMatch(doc: LyricsDoc): Boolean = doc.note.startsWith("AI recognised") || doc.note.startsWith("AI matched")

    private fun backupFile(key: String) = File(file(key).path.removeSuffix(".json") + ".before-ai.json")

    /** Whether [key] has normal lyrics to go back to after an AI match. */
    fun canRevert(key: String): Boolean = backupFile(key).exists()

    /** Back to the lyrics found the normal way, before the AI match replaced them. */
    fun revert(key: String): Boolean {
        val f = backupFile(key)
        val doc = runCatching { LyricsDoc.fromJson(JSONObject(f.readText())) }.getOrNull() ?: return false
        save(key, doc)
        f.delete()
        if (_current.value?.first == key) _current.value = key to LyricsState.Found(doc)
        return true
    }

    /** Shows the AI match's progress for [key]. */
    fun showMatching(key: String, status: String) { _current.value = key to LyricsState.Matching(status) }

    fun showMatchFailed(key: String, message: String) {
        _current.value = key to LyricsState.MatchFailed(message, saved(key) as? LyricsDoc)
    }

    /**
     * For the lyrics view: saved lyrics at once, otherwise a careful lookup in the background.
     * When no database has this exact song, the AI recognises it from its sound ([trackId]).
     */
    fun show(context: Context, q: SongQuery, trackId: Long = -1L) {
        val s = saved(q.key)
        val recheck = s is LyricsDoc && needsRecheck(q.key)
        if (s is LyricsDoc && !recheck) { _current.value = q.key to LyricsState.Found(s); return }
        if (s == null && (trackId < 0 || aiTried(q.key))) { _current.value = q.key to LyricsState.NotFound; return }
        val previous = s as? LyricsDoc
        if (!online(context)) {
            _current.value = q.key to when {
                previous != null -> LyricsState.Found(previous)
                s == null -> LyricsState.NotFound
                else -> LyricsState.Offline("No internet — lyrics will be looked up when you're online.")
            }
            return
        }
        _current.value = q.key to LyricsState.Searching
        val app = context.applicationContext
        io.launch {
            val doc = try {
                // s == null: the databases were asked before and don't have it; on to the AI.
                if (s == null) null else fetch(q)
            } catch (e: LrcLib.NetworkError) {
                if (_current.value?.first == q.key) _current.value = q.key to (previous?.let { LyricsState.Found(it) }
                    ?: LyricsState.Offline("Couldn't reach the lyrics database. Try again later."))
                return@launch
            } catch (e: Exception) {
                null
            }
            when {
                doc != null -> if (_current.value?.first == q.key) _current.value = q.key to LyricsState.Found(doc)
                trackId >= 0 -> com.ltots.app.identify.LyricsMatch.auto(app, q, trackId, previous)
                else -> gaveUp(q, previous)
            }
        }
    }

    /**
     * Use an .lrc file the user picked for [key]: parse it and save it as the song's lyrics,
     * replacing whatever was found automatically. Returns false if no timed lines were found.
     */
    fun import(context: Context, key: String, lrcText: String): Boolean {
        val lines = Lrc.parse(lrcText).takeIf { it.isNotEmpty() } ?: return false
        val doc = LyricsDoc(synced = lines, plain = null, source = IMPORTED)
        save(key, doc)
        runCatching { backupFile(key).delete() }
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

    /** Saves lyrics whose timing the AI has checked against the song (once per song). */
    fun saveTuned(key: String, doc: LyricsDoc) {
        val t = doc.copy(tuned = true)
        save(key, t)
        if ((_current.value?.second as? LyricsState.Found) != null && _current.value?.first == key) _current.value = key to LyricsState.Found(t)
    }

    /** Forget the saved result for [key] and search again. */
    fun retry(context: Context, q: SongQuery, trackId: Long = -1L) {
        runCatching { file(q.key).delete() }
        runCatching { backupFile(q.key).delete() }
        synchronized(memory) { memory.remove(q.key); recheckMemo.remove(q.key) }
        show(context, q, trackId)
    }

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** How many of [keys] have lyrics saved (for the status in Sound). */
    fun countFound(keys: List<String>): Int = keys.count { saved(it) is LyricsDoc }
}
