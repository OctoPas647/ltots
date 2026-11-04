package com.ltots.app.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import java.util.Locale

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val sampleRate: Int?,
    val bitsPerSample: Int?,
    val mime: String?,
    /** The genre tag of the file, if it has one. */
    val genre: String? = null,
    /** Release year, if the file says. */
    val year: Int? = null,
    /** Track number on its album (disc × 1000 + track, as Android stores it), if the file says. */
    val trackNo: Int? = null,
    /** When the file arrived on the phone (seconds since 1970), if known. */
    val addedAt: Long? = null,
    /** Folder on the phone, e.g. "Music/LToTS/" or "Recordings/Call/". */
    val folder: String? = null,
    /** File name, e.g. "PTT-20260101-WA0003.opus". */
    val fileName: String? = null,
    /** What Android's media database says the file is ([KIND_MUSIC], [KIND_RECORDING]…, combined). */
    val mediaFlags: Int = 0,
    /** Size in bytes and last change (s), to recognise the same file again. */
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
) {
    /**
     * Key used for this song's saved EQ / pitch / speed. Based on artist + title, so the same
     * song keeps its settings when it later comes from YouTube Music instead of a local file.
     */
    val profileKey: String
        get() = if (artist.isBlank() || artist == UNKNOWN) "local:$id"
        else "song:${norm(artist)}|${norm(title)}"

    val qualityLabel: String?
        get() {
            val fmt = mime?.substringAfter('/')?.uppercase(Locale.ROOT)?.replace("X-", "")
            val rate = sampleRate?.let { "%.1f kHz".format(it / 1000.0).replace(".0 ", " ") }
            val bits = bitsPerSample?.takeIf { it > 0 }?.let { "$it-bit" }
            return listOfNotNull(fmt, bits, rate).joinToString(" · ").ifBlank { null }
        }

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(profileKey)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setDurationMs(durationMs)
                .setExtras(Bundle().apply { putLong("trackId", id) })
                .build()
        )
        .build()

    /** Identifies this exact file (a re-saved or replaced file counts as new). */
    val fileKey: String get() = "$id:$sizeBytes:$modifiedAt"

    companion object {
        const val UNKNOWN = "<unknown>"
        const val KIND_MUSIC = 1
        const val KIND_RECORDING = 2
        const val KIND_RINGTONE = 4
        const val KIND_NOTIFICATION = 8
        const val KIND_ALARM = 16
        const val KIND_PODCAST = 32
        const val KIND_AUDIOBOOK = 64
        private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}

object LocalLibrary {
    @Volatile private var cache: List<Track>? = null
    @Volatile private var stale = true
    @Volatile private var cachedVersion = Int.MIN_VALUE
    private var watching = false

    /** The last loaded library (to show instantly when the Library tab opens again). */
    fun cached(): List<Track>? = cache

    /** The last loaded library's songs only (no recordings, voice notes, sounds…). */
    fun cachedSongs(): List<Track>? = cache?.let { SongKinds.load(); SongKinds.songs(it) }

    /**
     * The phone's songs: [load] without recordings, voice notes, ringtones and other audio
     * that isn't music (see [SongKinds]). What waveforms, lyrics, moods and suggestions use.
     */
    suspend fun loadSongs(context: Context, version: Int = 0): List<Track> {
        SongKinds.load()
        return SongKinds.songs(load(context, version))
    }

    /**
     * The phone's music. Kept in memory and only read again from Android's media database when
     * something actually changed there (or [version] changes, e.g. after a download), instead
     * of every time the Library tab is opened.
     */
    suspend fun load(context: Context, version: Int = 0): List<Track> {
        val c = cache
        if (c != null && !stale && version == cachedVersion) return c
        watch(context)
        stale = false // cleared before reading, so a change during the read marks it again
        val fresh = query(context)
        cache = fresh
        cachedVersion = version
        return fresh
    }

    private fun watch(context: Context) {
        synchronized(this) {
            if (watching) return
            watching = true
        }
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { stale = true }
        }
        runCatching {
            context.applicationContext.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer,
            )
        }.onFailure { synchronized(this) { watching = false } }
    }

    private suspend fun query(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.GENRE)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.RELATIVE_PATH)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.IS_MUSIC)
            add(MediaStore.Audio.Media.IS_RECORDING)
            add(MediaStore.Audio.Media.IS_RINGTONE)
            add(MediaStore.Audio.Media.IS_NOTIFICATION)
            add(MediaStore.Audio.Media.IS_ALARM)
            add(MediaStore.Audio.Media.IS_PODCAST)
            add(MediaStore.Audio.Media.IS_AUDIOBOOK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                add(MediaStore.Audio.Media.SAMPLERATE)
                add(MediaStore.Audio.Media.BITS_PER_SAMPLE)
            }
        }.toTypedArray()
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            collection, projection,
            // Every audio file: the Library's "Songs" view leaves out what isn't a song
            // (recordings, voice notes, ringtones…), sorted by [SongKinds]; "All" shows them too.
            "${MediaStore.Audio.Media.DURATION} > 0",
            null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iGenre = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            val iYear = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val iTrackNo = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val iAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val iModified = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val iPath = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val iName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val flagCols = listOf(
                MediaStore.Audio.Media.IS_MUSIC to Track.KIND_MUSIC,
                MediaStore.Audio.Media.IS_RECORDING to Track.KIND_RECORDING,
                MediaStore.Audio.Media.IS_RINGTONE to Track.KIND_RINGTONE,
                MediaStore.Audio.Media.IS_NOTIFICATION to Track.KIND_NOTIFICATION,
                MediaStore.Audio.Media.IS_ALARM to Track.KIND_ALARM,
                MediaStore.Audio.Media.IS_PODCAST to Track.KIND_PODCAST,
                MediaStore.Audio.Media.IS_AUDIOBOOK to Track.KIND_AUDIOBOOK,
            ).map { (col, bit) -> c.getColumnIndex(col) to bit }
            val iRate = c.getColumnIndex("samplerate")
            val iBits = c.getColumnIndex("bits_per_sample")
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                tracks += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(iTitle) ?: "Untitled",
                    artist = c.getString(iArtist) ?: Track.UNKNOWN,
                    album = c.getString(iAlbum) ?: "",
                    albumId = c.getLong(iAlbumId),
                    durationMs = c.getLong(iDur),
                    sampleRate = if (iRate >= 0 && !c.isNull(iRate)) c.getInt(iRate) else null,
                    bitsPerSample = if (iBits >= 0 && !c.isNull(iBits)) c.getInt(iBits) else null,
                    mime = c.getString(iMime),
                    genre = if (iGenre >= 0) c.getString(iGenre) else null,
                    year = if (iYear >= 0 && !c.isNull(iYear)) c.getInt(iYear).takeIf { it > 0 } else null,
                    trackNo = if (iTrackNo >= 0 && !c.isNull(iTrackNo)) c.getInt(iTrackNo).takeIf { it > 0 } else null,
                    addedAt = if (iAdded >= 0 && !c.isNull(iAdded)) c.getLong(iAdded).takeIf { it > 0 } else null,
                    folder = if (iPath >= 0) c.getString(iPath) else null,
                    fileName = if (iName >= 0) c.getString(iName) else null,
                    mediaFlags = flagCols.fold(0) { acc, (i, bit) -> if (i >= 0 && !c.isNull(i) && c.getInt(i) != 0) acc or bit else acc },
                    sizeBytes = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L,
                    modifiedAt = if (iModified >= 0 && !c.isNull(iModified)) c.getLong(iModified) else 0L,
                )
            }
        }
        tracks
    }

    private val artCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val noArt = HashSet<Long>()

    /**
     * Album art for a track, cached per size: list rows keep small thumbnails (little memory,
     * quick to draw) and the player gets a sharp large one, instead of whichever size happened
     * to be loaded first. Returns null if the file has none.
     */
    /**
     * The cover if it's already in memory at [px] or bigger (a bigger one draws fine scaled
     * down): to show it in the first frame instead of a placeholder that then flips.
     */
    fun peekArtwork(trackId: Long, px: Int): Bitmap? {
        artCache.get("$trackId@$px")?.let { return it }
        val bigger = synchronized(sizes) { sizes[trackId] }?.filter { it >= px }?.minOrNull() ?: return null
        return artCache.get("$trackId@$bigger")
    }
    /** The sizes cached for each track. */
    private val sizes = HashMap<Long, MutableSet<Int>>()

    suspend fun artwork(context: Context, trackId: Long, px: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        peekArtwork(trackId, px)?.let { return@withContext it }
        if (synchronized(noArt) { trackId in noArt }) return@withContext null
        // A few covers at a time: flinging through the library used to start a decode for every
        // row that flew past (dozens at once), which stuttered the scroll and cost battery. A
        // row that's gone by the time its turn comes is skipped.
        decodeGate.withPermit {
            kotlin.coroutines.coroutineContext.ensureActive()
            peekArtwork(trackId, px)?.let { return@withPermit it }
            decodeArtwork(context, trackId, px)
        }
    }

    private val decodeGate = kotlinx.coroutines.sync.Semaphore(4)

    private fun decodeArtwork(context: Context, trackId: Long, px: Int): Bitmap? {
        val key = "$trackId@$px"
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), trackId)
        val bmp = runCatching { context.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
        if (bmp != null) {
            bmp.prepareToDraw() // upload to the graphics chip now, not on the first frame that shows it
            artCache.put(key, bmp)
            synchronized(sizes) { sizes.getOrPut(trackId) { HashSet() } += px }
        } else synchronized(noArt) { noArt += trackId }
        return bmp
    }
}
