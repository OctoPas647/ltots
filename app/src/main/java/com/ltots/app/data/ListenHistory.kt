package com.ltots.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One song played, YouTube Music style: what it was, when, and how much of it was heard. */
data class Listen(
    /** [Track.profileKey] of the song. */
    val key: String,
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** When it started playing (ms since 1970). */
    val at: Long,
    /** How long it was really listened to (pauses not counted). */
    val listenedMs: Long = 0L,
) {
    /** Share of the song heard, 0..1 (1 when the length isn't known and it ran 30 s or more). */
    val completion: Float
        get() = if (durationMs > 0) (listenedMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else if (listenedMs >= 30_000) 1f else listenedMs / 30_000f

    /** Skipped within the first 20 seconds (or the first fifth of a short song). */
    val skipped: Boolean get() = listenedMs < minOf(20_000L, durationMs / 5).coerceAtLeast(3_000L)
}

/**
 * Everything the user played, newest first: the Playing tab shows it when nothing is playing
 * (like YouTube Music's history), and the Home tab's recommendations learn from it.
 *
 * A song is logged once it has really played for a moment (so flicking through a queue doesn't
 * fill the history), and how long it was listened to is kept, so finished songs count more than
 * skipped ones. Kept per user, the last [MAX] plays.
 */
object ListenHistory {
    const val MAX = 3000
    /** A song is logged after this much playing. */
    private const val LOG_AFTER_MS = 2_500L

    private val _items = MutableStateFlow<List<Listen>>(emptyList())
    /** Newest first. */
    val items: StateFlow<List<Listen>> = _items

    private var loaded = false

    // The song now loaded in the player.
    private var current: Listen? = null
    private var logged = false
    private var playingSince = 0L

    private fun file() = Store.userFile("history.json")

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _items.value = runCatching { parse(file().readText()) }.getOrDefault(emptyList())
    }

    /** After switching user: their own history. */
    @Synchronized
    fun reload() {
        loaded = false
        current = null; logged = false; playingSince = 0L
        ensureLoaded()
    }

    /** A new song was loaded in the player (null: nothing). */
    @Synchronized
    fun onSong(key: String?, trackId: Long, title: String, artist: String, album: String, durationMs: Long, playing: Boolean) {
        ensureLoaded()
        settle()
        current = key?.let { Listen(it, trackId, title, artist.takeIf { a -> a != Track.UNKNOWN } ?: "", album, durationMs, System.currentTimeMillis()) }
        logged = false
        playingSince = if (playing && current != null) System.currentTimeMillis() else 0L
    }

    @Synchronized
    fun onPlaying(playing: Boolean) {
        ensureLoaded()
        settle()
        playingSince = if (playing && current != null) System.currentTimeMillis() else 0L
    }

    /** Called now and then while playing, so a song gets logged without waiting for a pause. */
    @Synchronized
    fun tick() {
        // Only until the song is logged; after that, pauses and song changes update it.
        if (playingSince > 0 && !logged) { settle(); playingSince = System.currentTimeMillis() }
    }

    /** Adds the time played since the last event; logs the song once it has played a moment. */
    private fun settle() {
        val c = current ?: return
        val added = if (playingSince > 0) System.currentTimeMillis() - playingSince else 0L
        if (playingSince > 0) current = c.copy(listenedMs = c.listenedMs + added)
        playingSince = 0L
        val now = current ?: return
        if (!logged && now.listenedMs >= LOG_AFTER_MS) {
            logged = true
            publish(listOf(now) + _items.value)
            // The all-time numbers (Home's stats and Wrapped): one play, and the time so far.
            runCatching { ListenStats.addPlay(now.title, now.artist, now.album, now.listenedMs) }
        } else if (logged) {
            runCatching { ListenStats.addTime(now.title, now.artist, added) }
            // Update the listened time of the entry already logged (always the newest one).
            val list = _items.value
            if (list.isNotEmpty() && list[0].key == now.key && list[0].at == now.at) publish(listOf(now) + list.drop(1))
        }
    }

    private fun publish(list: List<Listen>) {
        val trimmed = if (list.size > MAX) list.take(MAX) else list
        _items.value = trimmed
        Store.writeLater(file()) { serialize(trimmed) }
    }

    /** Removes one entry (long-press > Remove from history). */
    @Synchronized
    fun remove(entry: Listen) {
        ensureLoaded()
        publish(_items.value.filterNot { it.key == entry.key && it.at == entry.at })
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        current = current?.copy(listenedMs = 0L, at = System.currentTimeMillis()); logged = false
        publish(emptyList())
    }

    // ---- grouping, as YouTube Music shows it -----------------------------------------------

    /** "Today", "Yesterday", "This week", "Last week", then month names. */
    fun groups(list: List<Listen>, now: Long = System.currentTimeMillis()): List<Pair<String, List<Listen>>> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        fun startOfDay(daysAgo: Int): Long = (cal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = startOfDay(0)
        val yesterday = startOfDay(1)
        val week = startOfDay(6)
        val lastWeek = startOfDay(13)
        val out = LinkedHashMap<String, MutableList<Listen>>()
        for (l in list) {
            val label = when {
                l.at >= today -> "Today"
                l.at >= yesterday -> "Yesterday"
                l.at >= week -> "This week"
                l.at >= lastWeek -> "Last week"
                else -> {
                    val c = Calendar.getInstance().apply { timeInMillis = l.at }
                    val month = c.getDisplayName(Calendar.MONTH, Calendar.LONG, java.util.Locale.getDefault()) ?: "Earlier"
                    if (c.get(Calendar.YEAR) == cal.get(Calendar.YEAR)) month else "$month ${c.get(Calendar.YEAR)}"
                }
            }
            out.getOrPut(label) { ArrayList() } += l
        }
        // YouTube Music lists a song once per day group: its latest play.
        return out.map { (k, v) -> k to v.distinctBy { it.key } }
    }

    // ---- file --------------------------------------------------------------------------------

    internal fun serialize(list: List<Listen>): String = JSONArray().apply {
        list.forEach { l ->
            put(JSONObject().put("k", l.key).put("id", l.trackId).put("t", l.title).put("a", l.artist).put("al", l.album)
                .put("d", l.durationMs).put("at", l.at).put("ms", l.listenedMs))
        }
    }.toString()

    internal fun parse(text: String): List<Listen> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Listen(
                key = o.optString("k").ifBlank { return@mapNotNull null },
                trackId = o.optLong("id", -1L),
                title = o.optString("t"),
                artist = o.optString("a"),
                album = o.optString("al"),
                durationMs = o.optLong("d"),
                at = o.optLong("at"),
                listenedMs = o.optLong("ms"),
            )
        }
    }
}
