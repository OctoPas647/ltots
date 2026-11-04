package com.ltots.app.data

import com.ltots.app.download.Suggestions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** How much one song was played in a period. */
data class SongStat(val key: String, val title: String, val artist: String, val album: String, val plays: Int, val ms: Long)

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
    private class Song(var title: String, var artist: String, var album: String, var plays: Int = 0, var ms: Long = 0, var imported: Int = 0)
    private class Year(
        val songs: HashMap<String, Song> = HashMap(),
        /** Day of year (1..366) → ms. */
        val days: HashMap<Int, Long> = HashMap(),
        val hours: LongArray = LongArray(24),
        val months: LongArray = LongArray(12),
    )

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
    fun addPlay(title: String, artist: String, album: String, ms: Long, at: Long = System.currentTimeMillis(), imported: Boolean = false) {
        if (title.isBlank()) return
        ensureLoaded()
        val (y, cal) = yearOf(at)
        val s = y.songs.getOrPut(key(title, artist)) { Song(title, artist.takeIf { it != Track.UNKNOWN } ?: "", album) }
        s.plays++
        if (imported) s.imported++
        if (s.album.isBlank() && album.isNotBlank()) s.album = album
        addTime(y, s, cal, ms)
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
            }
            for (i in 0 until 12) months[i] += y.months[i]
            for (i in 0 until 24) hours[i] += y.hours[i]
            activeDays += y.days.count { it.value > 0 }
            if (year != null) y.days.maxByOrNull { it.value }?.let { (doy, ms) ->
                val c = Calendar.getInstance().apply { set(Calendar.YEAR, yr); set(Calendar.DAY_OF_YEAR, doy) }
                topDay = Triple(c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), ms)
            }
        }
        val songList = songs.map { (k, s) -> SongStat(k, s.title, s.artist, s.album, s.plays, s.ms) }
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
                put(JSONObject().put("k", k).put("t", s.title).put("a", s.artist).put("al", s.album).put("p", s.plays).put("ms", s.ms).put("i", s.imported))
            }
        })
        .put("days", JSONObject().apply { y.days.forEach { (d, ms) -> put(d.toString(), ms) } })
        .put("hours", JSONArray(y.hours.toList()))
        .put("months", JSONArray(y.months.toList()))

    private fun parse(o: JSONObject): Year {
        val y = Year()
        o.optJSONArray("songs")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                y.songs[s.optString("k")] = Song(s.optString("t"), s.optString("a"), s.optString("al"), s.optInt("p"), s.optLong("ms"), s.optInt("i"))
            }
        }
        o.optJSONObject("days")?.let { d -> d.keys().forEach { k -> k.toIntOrNull()?.let { y.days[it] = d.optLong(k) } } }
        o.optJSONArray("hours")?.let { a -> for (i in 0 until minOf(24, a.length())) y.hours[i] = a.optLong(i) }
        o.optJSONArray("months")?.let { a -> for (i in 0 until minOf(12, a.length())) y.months[i] = a.optLong(i) }
        return y
    }
}
