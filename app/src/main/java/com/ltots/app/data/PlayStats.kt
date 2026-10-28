package com.ltots.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * How many times each song has really been listened to — a play counts after 30 seconds of
 * listening (or half the song, if it's shorter), so skipped songs don't count. Keyed by the
 * song's [Track.profileKey], like the per-song EQ.
 */
object PlayStats {
    private const val LISTEN_MS = 30_000L

    private val counts = HashMap<String, Int>()
    private var loaded = false
    private val _plays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val plays: StateFlow<Map<String, Int>> = _plays

    private var current: String? = null
    private var durationMs = 0L
    private var listenedMs = 0L
    private var playingSince = 0L
    private var counted = false

    private fun file() = File(Store.rootDir, "play_counts.json")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val o = JSONObject(file().readText())
            for (k in o.keys()) counts[k] = o.getInt(k)
        }
        _plays.value = HashMap(counts)
    }

    /** A new song started (or nothing is loaded, when [key] is null). */
    @Synchronized
    fun onSong(key: String?, durationMs: Long, playing: Boolean) {
        ensureLoaded()
        settle()
        current = key; this.durationMs = durationMs; listenedMs = 0; counted = false
        playingSince = if (playing) System.currentTimeMillis() else 0L
    }

    @Synchronized
    fun onPlaying(playing: Boolean) {
        ensureLoaded()
        settle()
        playingSince = if (playing) System.currentTimeMillis() else 0L
    }

    /** Adds the time listened since the last event, and counts the play once it's long enough. */
    private fun settle() {
        if (playingSince > 0) listenedMs += System.currentTimeMillis() - playingSince
        playingSince = 0L
        val key = current ?: return
        val needed = if (durationMs in 1 until 2 * LISTEN_MS) durationMs / 2 else LISTEN_MS
        if (!counted && listenedMs >= needed) {
            counted = true
            counts[key] = (counts[key] ?: 0) + 1
            _plays.value = HashMap(counts)
            val json = JSONObject().apply { counts.forEach { (k, v) -> put(k, v) } }.toString()
            runCatching { Store.writeFileNow(file(), json) }
        }
    }

    /**
     * The songs among [tracks] the user plays most: played at least twice, and in the top
     * third of everything they've played.
     */
    fun mostPlayed(tracks: List<Track>, plays: Map<String, Int>): Set<Long> {
        val played = tracks.mapNotNull { t -> plays[t.profileKey]?.takeIf { it >= 2 }?.let { t.id to it } }
        if (played.isEmpty()) return emptySet()
        val sorted = played.map { it.second }.sortedDescending()
        val cut = sorted[((sorted.size - 1) / 3.0).toInt()]
        return played.filter { it.second >= cut }.map { it.first }.toSet()
    }
}
