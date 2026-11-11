package com.ltots.app.data

import com.ltots.app.download.Suggestions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** How much one song was played in a period. */
data class SongStat(
    val key: String, val title: String, val artist: String, val album: String, val plays: Int, val ms: Long,
    /** Times it was skipped within its first seconds. */
    val skips: Int = 0,
    /** Plays late at night with the phone long untouched (see [Awake]). */
    val night: Int = 0,
    /** Plays while the sleep timer was running. */
    val timer: Int = 0,
)

/** The most times one song was played back to back, and the day it happened. */
data class Streak(val title: String, val artist: String, val count: Int, val month: Int, val day: Int)

/** One play from another app's history. [ms]: how long it was heard (estimated when unknown). */
data class ImportedPlay(val title: String, val artist: String, val album: String, val at: Long, val ms: Long, val source: String) {
    /** The same play imported again gets the same id. */
    fun id(): Long {
        var h = 1125899906842597L
        for (ch in "$source|${ListenStats.key(title, artist)}|${at / 1000}") h = 31 * h + ch.code
        return h
    }
}

/** How much one artist was played in a period. */
data class ArtistStat(val name: String, val plays: Int, val ms: Long, val songs: Int)

/** A period's listening, ready for the Home card, the stats page and Wrapped. */
data class StatsSummary(
    val year: Int?,
    val totalMs: Long,
    val plays: Int,
    val songs: List<SongStat>,
    val artists: List<ArtistStat>,
    /** Listening per month (12) and per hour of the day (24), in ms. */
    val months: LongArray,
    val hours: LongArray,
    /** Days with any listening. */
    val activeDays: Int,
    /** The day listened most: (month 0..11, day of month, ms). */
    val topDay: Triple<Int, Int, Long>?,
    /** Share of the plays that came from imports (Spotify, Last.fm…), 0..1. */
    val importedShare: Double,
    /** The longest back-to-back run of one song. */
    val streak: Streak? = null,
) {
    val minutes: Long get() = totalMs / 60_000
}

/**
 * Every play, added up for good (the history keeps only the last few thousand): per year, how
 * many times and how long each song was played, and when (month, day, hour). Plays in LToTS
 * count as they happen; plays imported from other apps count too.
 *
 * Songs are matched by title and main artist, so a song played from the phone, streamed, or
 * imported from Spotify is one song.
 */
object ListenStats {
    private class Song(
        var title: String, var artist: String, var album: String, var plays: Int = 0, var ms: Long = 0, var imported: Int = 0,
        var skips: Int = 0, var night: Int = 0, var timer: Int = 0,
    )
    private class Year(
        val songs: HashMap<String, Song> = HashMap(),
        /** Day of year (1..366) → ms. */
        val days: HashMap<Int, Long> = HashMap(),
        val hours: LongArray = LongArray(24),
        val months: LongArray = LongArray(12),
        /** The longest back-to-back run of one song this year: its key, how many, the day of the year. */
        var streakKey: String? = null,
        var streakCount: Int = 0,
        var streakDay: Int = 0,
    )

    // The run of one song going on right now (plays in LToTS only).
    private var runKey: String? = null
    private var runCount = 0

    private val years = HashMap<Int, Year>()
    private var loadedFrom: String? = null
    private val _version = MutableStateFlow(0)
    /** Changes whenever the numbers do (for screens to refresh). */
    val version: StateFlow<Int> = _version

    private fun dir() = Store.userFile("stats").apply { mkdirs() }
    private fun file(y: Int) = File(dir(), "$y.json")

    @Synchronized
    private fun ensureLoaded() {
        val from = dir().path
        if (loadedFrom == from) return
        loadedFrom = from
        years.clear()
        dir().listFiles()?.forEach { f ->
            val y = f.nameWithoutExtension.toIntOrNull() ?: return@forEach
            runCatching { years[y] = parse(JSONObject(f.readText())) }
        }
    }

    fun key(title: String, artist: String): String = Suggestions.key(title, artist.takeIf { it != Track.UNKNOWN })

    /** A song started counting (it played a moment): one play, with the time heard so far. */
    @Synchronized
    fun addPlay(
        title: String, artist: String, album: String, ms: Long, at: Long = System.currentTimeMillis(), imported: Boolean = false,
        night: Boolean = false, timer: Boolean = false,
    ) {
        if (title.isBlank()) return
        ensureLoaded()
        val (y, cal) = yearOf(at)
        val k = key(title, artist)
        val s = y.songs.getOrPut(k) { Song(title, artist.takeIf { it != Track.UNKNOWN } ?: "", album) }
        s.plays++
        if (imported) s.imported++
        if (night) s.night++
        if (timer) s.timer++
        if (!imported) countRun(y, k, cal.get(Calendar.DAY_OF_YEAR))
        if (s.album.isBlank() && album.isNotBlank()) s.album = album
        addTime(y, s, cal, ms)
        changed(cal.get(Calendar.YEAR))
    }

    /** One more play of [k] in a row (or the start of a new run); the year keeps its longest. */
    private fun countRun(y: Year, k: String, day: Int) {
        if (k == runKey) runCount++ else { runKey = k; runCount = 1 }
        if (runCount > y.streakCount) { y.streakKey = k; y.streakCount = runCount; y.streakDay = day }
    }

    /** A song already counted was skipped within its first seconds. */
    @Synchronized
    fun addSkip(title: String, artist: String, at: Long = System.currentTimeMillis()) {
        if (title.isBlank()) return
        ensureLoaded()
        val (y, cal) = yearOf(at)
        val s = y.songs[key(title, artist)] ?: return
        s.skips++
        // A skipped song doesn't count towards playing it over and over.
        if (runKey == key(title, artist)) { runKey = null; runCount = 0 }
        changed(cal.get(Calendar.YEAR))
    }

    /** More of a song already counted was heard. */
    @Synchronized
    fun addTime(title: String, artist: String, ms: Long, at: Long = System.currentTimeMillis()) {
        if (ms <= 0 || title.isBlank()) return
        ensureLoaded()
        val (y, cal) = yearOf(at)
        val s = y.songs[key(title, artist)] ?: return
        addTime(y, s, cal, ms)
        changed(cal.get(Calendar.YEAR))
    }

    private fun addTime(y: Year, s: Song, cal: Calendar, ms: Long) {
        if (ms <= 0) return
        s.ms += ms
        y.days[cal.get(Calendar.DAY_OF_YEAR)] = (y.days[cal.get(Calendar.DAY_OF_YEAR)] ?: 0L) + ms
        y.hours[cal.get(Calendar.HOUR_OF_DAY)] += ms
        y.months[cal.get(Calendar.MONTH)] += ms
    }

    private fun yearOf(at: Long): Pair<Year, Calendar> {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        return years.getOrPut(cal.get(Calendar.YEAR)) { Year() } to cal
    }

    private fun changed(year: Int) {
        val y = years[year] ?: return
        Store.writeLater(file(year)) { synchronized(this) { write(y).toString() } }
        _version.value++
    }

    /** Years with any listening, newest first. */
    @Synchronized
    fun years(): List<Int> { ensureLoaded(); return years.filterValues { it.songs.isNotEmpty() }.keys.sortedDescending() }

    /** The listening of [year] (null: all time). */
    @Synchronized
    fun summary(year: Int?): StatsSummary {
        ensureLoaded()
        // Home, the listening card and Wrapped all ask for this, on the screen's own thread, each
        // time anything is played: worked out once per change, not once per asker.
        summaries[year]?.takeIf { it.first == _version.value }?.let { return it.second }
        return summarize(year).also { summaries[year] = _version.value to it }
    }

    private val summaries = HashMap<Int?, Pair<Int, StatsSummary>>()

    /** Reads the saved numbers ahead of the first screen that shows them (call off the main thread). */
    fun warm() { runCatching { summary(null) } }

    private fun summarize(year: Int?): StatsSummary {
        val ys = if (year == null) years.values.toList() else listOfNotNull(years[year])
        val songs = HashMap<String, Song>()
        val months = LongArray(12); val hours = LongArray(24)
        var activeDays = 0
        var topDay: Triple<Int, Int, Long>? = null
        for ((yr, y) in years) {
            if (year != null && yr != year) continue
            y.songs.forEach { (k, s) ->
                val m = songs.getOrPut(k) { Song(s.title, s.artist, s.album) }
                m.plays += s.plays; m.ms += s.ms; m.imported += s.imported
                m.skips += s.skips; m.night += s.night; m.timer += s.timer
            }
            for (i in 0 until 12) months[i] += y.months[i]
            for (i in 0 until 24) hours[i] += y.hours[i]
            activeDays += y.days.count { it.value > 0 }
            if (year != null) y.days.maxByOrNull { it.value }?.let { (doy, ms) ->
                val c = Calendar.getInstance().apply { set(Calendar.YEAR, yr); set(Calendar.DAY_OF_YEAR, doy) }
                topDay = Triple(c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), ms)
            }
        }
        val songList = songs.map { (k, s) -> SongStat(k, s.title, s.artist, s.album, s.plays, s.ms, s.skips, s.night, s.timer) }
            .sortedWith(compareByDescending<SongStat> { it.ms }.thenByDescending { it.plays })
        val artists = songList.filter { it.artist.isNotBlank() }
            .groupBy { com.ltots.app.lyrics.SongMatch.mainArtist(it.artist).trim() }
            .map { (a, g) -> ArtistStat(a, g.sumOf { it.plays }, g.sumOf { it.ms }, g.size) }
            .sortedByDescending { it.ms }
        val plays = songList.sumOf { it.plays }
        val imported = songs.values.sumOf { it.imported }
        if (ys.isEmpty()) return StatsSummary(year, 0, 0, emptyList(), emptyList(), months, hours, 0, null, 0.0)
        return StatsSummary(
            year, songList.sumOf { it.ms }, plays, songList, artists, months, hours, activeDays, topDay,
            if (plays > 0) imported.toDouble() / plays else 0.0,
            streak = years.filter { year == null || it.key == year }.maxByOrNull { it.value.streakCount }?.let { (yr, y) ->
                val s = y.streakKey?.let { songs[it] } ?: return@let null
                if (y.streakCount < 2) return@let null
                val c = Calendar.getInstance().apply { set(Calendar.YEAR, yr); set(Calendar.DAY_OF_YEAR, y.streakDay.coerceAtLeast(1)) }
                Streak(s.title, s.artist, y.streakCount, c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            },
        )
    }

    /** Play count and time for one song, all time. */
    @Synchronized
    fun of(title: String, artist: String): Pair<Int, Long> {
        ensureLoaded()
        val k = key(title, artist)
        return years.values.mapNotNull { it.songs[k] }.let { l -> l.sumOf { it.plays } to l.sumOf { it.ms } }
    }

    /**
     * Once per user: the plays already in the history (from before these numbers were kept)
     * are added, so Wrapped starts with them.
     */
    @Synchronized
    fun backfill(history: List<Listen>) {
        ensureLoaded()
        val flag = File(dir(), ".backfilled")
        if (flag.exists()) return
        for (l in history.asReversed()) {
            val (y, cal) = yearOf(l.at)
            val s = y.songs.getOrPut(key(l.title, l.artist)) { Song(l.title, l.artist, l.album) }
            s.plays++
            addTime(y, s, cal, l.listenedMs)
        }
        years.keys.forEach { changed(it) }
        runCatching { flag.writeText("1") }
    }

    /**
     * Once per user: skips, late-night plays and back-to-back runs for the plays already in the
     * history, from before those were counted (the history keeps when each song played and how
     * much of it, which is enough to work them out roughly).
     */
    @Synchronized
    fun backfillExtras(history: List<Listen>) {
        ensureLoaded()
        val flag = File(dir(), ".extras")
        if (flag.exists() || history.isEmpty()) return
        var lastKey: String? = null
        var count = 0
        for (l in history.asReversed()) {
            val (y, cal) = yearOf(l.at)
            val k = key(l.title, l.artist)
            val s = y.songs[k] ?: continue
            if (l.skipped) { s.skips++; lastKey = null; count = 0; continue }
            // Played through in the small hours: most likely to someone asleep.
            if (Awake.isNight(l.at) && l.completion >= 0.9f) s.night++
            if (k == lastKey) count++ else { lastKey = k; count = 1 }
            if (count > y.streakCount) { y.streakKey = k; y.streakCount = count; y.streakDay = cal.get(Calendar.DAY_OF_YEAR) }
        }
        years.keys.forEach { changed(it) }
        runCatching { flag.writeText("1") }
    }

    /**
     * Plays from another app (Spotify, Last.fm, YouTube Music…). [seen] is a set of ids of plays
     * already imported, so importing the same file twice doesn't count them twice. Returns how
     * many plays were new.
     */
    @Synchronized
    fun import(plays: List<ImportedPlay>): Int {
        ensureLoaded()
        val seenFile = File(dir(), ".imported")
        val seen = HashSet<Long>()
        runCatching { seenFile.readLines().forEach { it.toLongOrNull()?.let(seen::add) } }
        val touched = HashSet<Int>()
        var added = 0
        val newIds = StringBuilder()
        for (p in plays) {
            if (p.title.isBlank()) continue
            val id = p.id()
            if (!seen.add(id)) continue
            val (y, cal) = yearOf(p.at)
            val s = y.songs.getOrPut(key(p.title, p.artist)) { Song(p.title, p.artist, p.album) }
            s.plays++; s.imported++
            if (s.album.isBlank() && p.album.isNotBlank()) s.album = p.album
            addTime(y, s, cal, p.ms)
            touched += cal.get(Calendar.YEAR)
            newIds.append(id).append('\n')
            added++
        }
        if (added > 0) {
            runCatching { seenFile.appendText(newIds.toString()) }
            touched.forEach { changed(it) }
        }
        return added
    }

    /** After switching user: their own numbers. */
    @Synchronized
    fun reload() { loadedFrom = null; ensureLoaded(); _version.value++ }

    // ---- file --------------------------------------------------------------------------------

    private fun write(y: Year) = JSONObject()
        .put("songs", JSONArray().apply {
            y.songs.forEach { (k, s) ->
                put(JSONObject().put("k", k).put("t", s.title).put("a", s.artist).put("al", s.album).put("p", s.plays).put("ms", s.ms).put("i", s.imported).apply {
                    if (s.skips > 0) put("sk", s.skips)
                    if (s.night > 0) put("n", s.night)
                    if (s.timer > 0) put("tm", s.timer)
                })
            }
        })
        .put("days", JSONObject().apply { y.days.forEach { (d, ms) -> put(d.toString(), ms) } })
        .put("hours", JSONArray(y.hours.toList()))
        .put("months", JSONArray(y.months.toList()))
        .apply { y.streakKey?.let { put("streak", JSONObject().put("k", it).put("c", y.streakCount).put("d", y.streakDay)) } }

    private fun parse(o: JSONObject): Year {
        val y = Year()
        o.optJSONArray("songs")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                y.songs[s.optString("k")] = Song(
                    s.optString("t"), s.optString("a"), s.optString("al"), s.optInt("p"), s.optLong("ms"), s.optInt("i"),
                    s.optInt("sk"), s.optInt("n"), s.optInt("tm"),
                )
            }
        }
        o.optJSONObject("days")?.let { d -> d.keys().forEach { k -> k.toIntOrNull()?.let { y.days[it] = d.optLong(k) } } }
        o.optJSONArray("hours")?.let { a -> for (i in 0 until minOf(24, a.length())) y.hours[i] = a.optLong(i) }
        o.optJSONArray("months")?.let { a -> for (i in 0 until minOf(12, a.length())) y.months[i] = a.optLong(i) }
        o.optJSONObject("streak")?.let { st -> y.streakKey = st.optString("k").ifBlank { null }; y.streakCount = st.optInt("c"); y.streakDay = st.optInt("d") }
        return y
    }
}
