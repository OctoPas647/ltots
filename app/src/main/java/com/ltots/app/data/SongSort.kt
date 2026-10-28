package com.ltots.app.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** How the Songs list (and so the play order) is sorted. */
enum class SongSort(val label: String) {
    TITLE("A–Z"), GENRE("Genre"), COLOR("Colour"), RANDOM("Random"),
}

/**
 * Sorting of the phone's songs, so that what plays next is like what plays now: the same genre
 * (related genres side by side), covers of the same colour (a rainbow from red to pink, then
 * black, grey and white), or a random order.
 *
 * The order in Profile > App settings is the usual one. The Colour and Genre buttons on the
 * Library page pick an order just for now: it stays until the app starts again (or the button
 * is tapped again) and never changes the setting, nor does the setting change it.
 */
object SongSorter {
    private val _quick = MutableStateFlow<SongSort?>(null)
    /** The order picked with a button on the Library page; null = the one from the settings. */
    val quick: StateFlow<SongSort?> = _quick

    fun setQuick(s: SongSort?) { _quick.value = s }

    /** Random stays the same order while the app runs, so the list doesn't jump on every change. */
    private val seed = System.nanoTime()

    fun sort(tracks: List<Track>, by: SongSort, colors: Map<Long, Int> = emptyMap(), seed: Long = this.seed): List<Track> = when (by) {
        SongSort.TITLE -> tracks.sortedWith(compareBy(TITLE_ORDER) { it.title })
        SongSort.GENRE -> tracks.sortedWith(
            compareBy<Track> { familyIndex(it.genre) }
                .thenBy(TITLE_ORDER) { genreName(it.genre) ?: "￿" }
                .thenBy(TITLE_ORDER) { it.artist }
                .thenBy(TITLE_ORDER) { it.album }
                .thenBy(TITLE_ORDER) { it.title }
        )
        SongSort.COLOR -> tracks.sortedWith(
            compareBy<Track> { colorKey(colors[it.id]) }
                .thenBy(TITLE_ORDER) { it.album }
                .thenBy(TITLE_ORDER) { it.title }
        )
        SongSort.RANDOM -> tracks.sortedBy { mix(it.id xor seed) }
    }

    private val TITLE_ORDER: Comparator<String> = compareBy { it.lowercase(Locale.ROOT) }

    // ---- genre -----------------------------------------------------------------------------

    /** The genre as written in the file, tidied; null when the file has none. */
    fun genreName(raw: String?): String? {
        val g = raw?.trim()?.takeIf { it.isNotEmpty() && it != Track.UNKNOWN } ?: return null
        // Old ID3 tags store a number like "(17)" or "17" (Rock); "(17)Rock" keeps the name.
        val num = Regex("^\\(?(\\d{1,3})\\)?(.*)$").find(g)
        if (num != null) {
            val rest = num.groupValues[2].trim()
            if (rest.isNotEmpty()) return rest
            return ID3.getOrNull(num.groupValues[1].toInt())
        }
        return g
    }

    /**
     * Genre families in an order where neighbours sound alike (metal next to rock, house next to
     * techno, soul next to R&B…), so the music changes gradually from one genre to the next.
     * Each family is matched by words in the genre name.
     */
    private val FAMILIES = listOf(
        listOf("metal", "hardcore", "deathcore", "metalcore", "grind", "thrash", "djent", "doom"),
        listOf("punk", "emo", "grunge", "screamo"),
        listOf("rock", "alternative", "alt", "indie", "shoegaze", "garage", "britpop", "new wave", "post-punk"),
        listOf("pop", "k-pop", "kpop", "j-pop", "jpop", "c-pop", "synth", "electropop", "disco", "schlager", "top 40", "europop"),
        listOf("dance", "edm", "house", "techno", "trance", "electro", "electronic", "electronica", "dubstep", "drum", "bass", "dnb", "hardstyle", "club", "phonk", "jungle", "garage house", "eurodance", "rave"),
        listOf("ambient", "chill", "lo-fi", "lofi", "downtempo", "trip", "new age", "meditat"),
        listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "drill", "grime"),
        listOf("r&b", "rnb", "soul", "funk", "neo soul", "gospel", "motown", "christian"),
        listOf("reggae", "dancehall", "ska", "afro", "reggaeton", "latin", "salsa", "bachata", "cumbia", "samba", "bossa", "world", "chalga", "pop-folk", "turbo"),
        listOf("jazz", "swing", "blues", "big band"),
        listOf("country", "folk", "americana", "bluegrass", "singer", "acoustic"),
        listOf("classical", "orchestra", "opera", "baroque", "piano", "soundtrack", "score", "instrumental", "film", "anime", "game", "musical", "showtunes"),
    )

    /** Each keyword matches at the start of a word ("synth" in "synthwave", not in "kasynth"). */
    private val FAMILY_PATTERNS = FAMILIES.map { words -> words.map { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it)) } }

    /** A name for each of [FAMILIES], for playlists. */
    private val FAMILY_NAMES = listOf(
        "Metal", "Punk", "Rock", "Pop", "Dance & electronic", "Chill", "Hip-hop", "R&B & soul",
        "Latin, reggae & world", "Jazz & blues", "Country & folk", "Classical & soundtracks",
    )

    /** The family [raw] belongs to (e.g. "Rock" for "Hard Rock"); null when it fits none. */
    fun familyName(raw: String?): String? = FAMILY_NAMES.getOrNull(familyIndex(raw))

    /**
     * Where a genre's family comes in [FAMILIES]; other genres after them, no genre last.
     *
     * In a genre name the *last* genre word says what it is: "Pop Rock" is rock, "Indie Pop" is
     * pop, "Folk Metal" is metal, "Alternative R&B" is R&B. So the keyword found furthest along
     * the name wins, ignoring keywords inside longer ones ("pop-folk" is its own genre, not
     * folk). Database names like "Hip-Hop/Rap" or "R&B/Soul" work the same way.
     */
    fun familyIndex(raw: String?): Int {
        val g = genreName(raw)?.lowercase(Locale.ROOT) ?: return FAMILIES.size + 1
        // Every keyword found: (start, end, family).
        val hits = ArrayList<Triple<Int, Int, Int>>()
        FAMILY_PATTERNS.forEachIndexed { i, pats ->
            pats.forEach { re -> re.findAll(g).forEach { m -> hits += Triple(m.range.first, m.range.last, i) } }
        }
        // A keyword inside a longer one ("folk" in "pop-folk", "house" in "garage house") doesn't count.
        val kept = hits.filter { h ->
            hits.none { o -> o !== h && o.first <= h.first && o.second >= h.second && (o.second - o.first) > (h.second - h.first) }
        }
        return kept.maxWithOrNull(compareBy<Triple<Int, Int, Int>> { it.first }.thenBy { it.second - it.first })?.third ?: FAMILIES.size
    }

    private val ID3 = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    // ---- colour ----------------------------------------------------------------------------

    /** No cover (or not looked at yet). */
    const val NO_COVER = 0

    /**
     * Order of a cover colour: colourful covers by hue from red round to pink, then the
     * colourless ones (black → grey → white), then songs without a cover.
     */
    fun colorKey(argb: Int?): Float {
        if (argb == null || argb == NO_COVER) return 3f
        val r = ((argb shr 16) and 0xFF) / 255f; val g = ((argb shr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        if (d < 0.12f || max < 0.12f) return 1f + max * 0.999f
        val h = when (max) {
            r -> 60f * (((g - b) / d).mod(6f))
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        // Deep reds and pinks (above 345°) count as red, so red starts the rainbow in one piece.
        val hh = if (h >= 345f) h - 360f else h
        return (hh + 15f) / 361f
    }

    /**
     * The colour a cover looks like: the hue most of its colourful pixels share (a raw average
     * of a red-and-blue cover would be a purple it doesn't have), or its average brightness as a
     * grey when hardly anything in it is colourful. [pixels] are ARGB.
     */
    fun mainColor(pixels: IntArray): Int {
        val bins = 24
        val weight = FloatArray(bins)
        val sumR = FloatArray(bins); val sumG = FloatArray(bins); val sumB = FloatArray(bins)
        var total = 0f; var light = 0f; var n = 0
        for (p in pixels) {
            if ((p ushr 24) < 16) continue
            val r = ((p shr 16) and 0xFF) / 255f; val g = ((p shr 8) and 0xFF) / 255f; val b = (p and 0xFF) / 255f
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
            light += max; n++
            if (d < 0.12f || max < 0.12f) continue
            val h = when (max) {
                r -> 60f * (((g - b) / d).mod(6f))
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            val w = d // the more colourful, the more it counts
            val i = (h / (360f / bins)).toInt().coerceIn(0, bins - 1)
            weight[i] += w; sumR[i] += r * w; sumG[i] += g * w; sumB[i] += b * w
            total += w
        }
        if (n == 0) return NO_COVER
        // Colourful enough: at least an eighth of the cover at full colour.
        if (total < n * 0.125f) {
            val v = (light / n * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        // The strongest hue, with its two neighbours (a hue split over two bins stays whole).
        var best = 0; var bestW = -1f
        for (i in 0 until bins) {
            val w = weight[(i + bins - 1) % bins] + weight[i] + weight[(i + 1) % bins]
            if (w > bestW) { bestW = w; best = i }
        }
        var w = 0f; var r = 0f; var g = 0f; var b = 0f
        for (k in -1..1) {
            val i = (best + k + bins) % bins
            w += weight[i]; r += sumR[i]; g += sumG[i]; b += sumB[i]
        }
        fun c(x: Float) = (x / w * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    // ---- cover colours of the library (kept in a file, read once per song) ---------------

    private val colorCache = HashMap<Long, Int>()
    private var colorsLoaded = false
    private val _colors = MutableStateFlow<Map<Long, Int>>(emptyMap())
    /** Cover colours found so far, by track id ([NO_COVER] = the song has none). */
    val colors: StateFlow<Map<Long, Int>> = _colors

    private fun colorFile() = File(Store.rootDir, "cover_colors.json")

    /**
     * Looks at the cover of every song in [tracks] that hasn't been looked at yet (a tiny
     * thumbnail each), publishing the colours as they come in so the list sorts itself while
     * it works. Songs already known cost nothing.
     */
    suspend fun findColors(context: Context, tracks: List<Track>) = withContext(Dispatchers.IO) {
        synchronized(colorCache) {
            if (!colorsLoaded) {
                colorsLoaded = true
                runCatching {
                    val o = JSONObject(colorFile().readText())
                    for (k in o.keys()) colorCache[k.toLong()] = o.getInt(k)
                }
            }
        }
        _colors.value = synchronized(colorCache) { HashMap(colorCache) }
        val missing = tracks.filter { synchronized(colorCache) { it.id !in colorCache } }
        if (missing.isEmpty()) return@withContext
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        missing.forEachIndexed { i, t ->
            val bmp = runCatching { context.contentResolver.loadThumbnail(ContentUris.withAppendedId(collection, t.id), Size(64, 64), null) }.getOrNull()
            val color = if (bmp == null) NO_COVER else runCatching {
                val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
                val px = IntArray(32 * 32)
                small.getPixels(px, 0, 32, 0, 0, 32, 32)
                if (small !== bmp) small.recycle()
                bmp.recycle()
                mainColor(px)
            }.getOrDefault(NO_COVER)
            synchronized(colorCache) { colorCache[t.id] = color }
            if (i % 40 == 39 || i == missing.lastIndex) {
                _colors.value = synchronized(colorCache) { HashMap(colorCache) }
            }
        }
        val json = synchronized(colorCache) { JSONObject().apply { colorCache.forEach { (k, v) -> put(k.toString(), v) } }.toString() }
        runCatching { Store.writeFileNow(colorFile(), json) }
    }

    /** A well-spread 64-bit hash (SplitMix64's finaliser), for the random order. */
    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
