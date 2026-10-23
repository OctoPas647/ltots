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

    companion object {
        const val UNKNOWN = "<unknown>"
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
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                add(MediaStore.Audio.Media.SAMPLERATE)
                add(MediaStore.Audio.Media.BITS_PER_SAMPLE)
            }
        }.toTypedArray()
        val tracks = mutableListOf<Track>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000",
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
    suspend fun artwork(context: Context, trackId: Long, px: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$trackId@$px"
        artCache.get(key)?.let { return@withContext it }
        if (synchronized(noArt) { trackId in noArt }) return@withContext null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), trackId)
        val bmp = runCatching { context.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
        if (bmp != null) {
            bmp.prepareToDraw() // upload to the graphics chip now, not on the first frame that shows it
            artCache.put(key, bmp)
        } else synchronized(noArt) { noArt += trackId }
        bmp
    }
}
