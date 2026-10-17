package com.ltots.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.ltots.app.ytm.Collection
import com.ltots.app.ytm.Covers
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.YtRef
import com.ltots.app.ytm.YtSong
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

/**
 * One song, start to finish:
 *  1. look for a legal free lossless FLAC on the Internet Archive that clearly matches;
 *  2. otherwise, look up the official metadata (album, track number, year) and the full-size cover,
 *     and swap a music video for its official-audio release when there is one;
 *  3. let yt-dlp fetch YouTube's best audio stream and keep it exactly as sent (no re-encode);
 *  4. let yt-dlp write the tags and embed the cover;
 *  5. save it to Music/LToTS/Artist/Album/ through MediaStore so every app can see it.
 */
class Downloader(private val context: Context) {

    /** Best audio without Premium: Opus (~130–160 kbps), else the best AAC. */
    private val formatSelector = FORMAT

    private val albumCache = Collections.synchronizedMap(object : LinkedHashMap<String, Collection>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Collection>?) = size > 32
    })

    class Result(val uri: Uri, val meta: TrackMeta, val qualityNote: String)

    /** A failed download, with yt-dlp's own output so the user can see (and send) what happened. */
    class Failure(message: String, val log: String, cause: Throwable? = null) : Exception(message, cause)

    // ---- 1. metadata -----------------------------------------------------------------------

    suspend fun enrich(meta: TrackMeta, preferOfficialAudio: Boolean, onStatus: (String) -> Unit): TrackMeta {
        if (meta.complete) return meta
        var m = meta
        if (m.isVideo && preferOfficialAudio) {
            onStatus("Looking for the official audio version")
            val video = YtSong(m.videoId, m.title, listOf(YtRef(m.artist, null)), durationSec = m.durationSec, isVideo = true)
            InnerTube.officialAudio(video)?.let { audio ->
                m = TrackMeta.from(audio).copy(album = audio.album?.name ?: m.album)
            }
        }
        val details = runCatching { InnerTube.song(m.videoId) }.getOrNull()
        if (details != null) {
            m = m.copy(
                title = details.title.ifBlank { m.title },
                artist = details.artistText.ifBlank { m.artist },
                album = details.album?.name ?: m.album,
                albumId = details.album?.id ?: m.albumId,
                year = details.year ?: m.year,
                coverUrl = details.thumbUrl ?: m.coverUrl,
                durationSec = details.durationSec ?: m.durationSec,
                isVideo = details.isVideo && m.isVideo,
            )
        }
        val albumId = m.albumId
        if (albumId != null && albumId.startsWith("MPREb")) {
            val album = albumCache[albumId] ?: runCatching { InnerTube.album(albumId) }.getOrNull()?.also { albumCache[albumId] = it }
            if (album != null) {
                val pos = album.tracks.firstOrNull { it.videoId == m.videoId }?.trackNumber
                    ?: album.tracks.indexOfFirst { it.videoId == m.videoId }.takeIf { it >= 0 }?.plus(1)
                m = m.copy(
                    album = album.title, albumArtist = album.artist ?: m.albumArtist,
                    year = m.year ?: album.year, trackNumber = pos ?: m.trackNumber,
                    coverUrl = album.thumbUrl ?: m.coverUrl,
                )
            }
        }
        val cover = Covers.highRes(m.coverUrl) ?: Covers.videoThumb(m.videoId)
        return m.copy(coverUrl = cover, complete = true)
    }

    // ---- 2–4. download, tag, save ----------------------------------------------------------

    /**
     * Downloads one song. A matching free lossless FLAC on the Internet Archive is tried first;
     * otherwise YouTube Music's address is tried, and if YouTube refuses it there, the same video
     * is fetched through the regular YouTube address (a different set of YouTube clients), which
     * often gets past temporary blocks.
     * [processId] names the yt-dlp process so the download can be cancelled.
     */
    fun download(processId: String, meta: TrackMeta, onProgress: (Float, String?) -> Unit): Result {
        // Free lossless first: only a close title/artist/length match is accepted.
        runCatching { FreeLossless().find(meta) }.getOrNull()?.let { hit ->
            try {
                return downloadLossless(hit, "$processId-lossless", meta, onProgress)
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                // No luck with the free lossless copy — use YouTube instead.
            }
        }
        val urls = listOf(
            "https://music.youtube.com/watch?v=${meta.videoId}",
            "https://www.youtube.com/watch?v=${meta.videoId}",
        )
        val log = StringBuilder()
        var last: Throwable? = null
        for ((i, url) in urls.withIndex()) {
            try {
                return downloadFrom(url, "$processId-$i", meta, log, onProgress)
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                last = e
                log.append("\n[LToTS] attempt ${i + 1} ($url) failed: ").append(e.javaClass.simpleName).append(": ")
                    .append(e.message?.takeLast(4000)).append('\n')
                if (e is Failure && !e.retryable) break
            }
        }
        val msg = last?.message ?: "Download failed"
        throw Failure(msg, log.toString().takeLast(12_000), last)
    }

    /** Saves a free lossless FLAC found on the Internet Archive. */
    private fun downloadLossless(hit: FreeLossless.Hit, pid: String, meta: TrackMeta, onProgress: (Float, String?) -> Unit): Result {
        val work = File(context.cacheDir, "dl/$pid").apply { deleteRecursively(); mkdirs() }
        try {
            onProgress(0.1f, "downloading free lossless")
            val bytes = java.net.URL(hit.fileUrl).openStream().use { it.readBytes() }
            val file = File(work, "audio.${hit.extension}")
            file.writeBytes(bytes)
            val m = meta.copy(
                title = hit.title.ifBlank { meta.title },
                artist = hit.creator.ifBlank { meta.artist },
            )
            onProgress(0.9f, "saving")
            val uri = saveToMusic(file, m)
            return Result(uri, m, "FLAC · Internet Archive · free lossless")
        } finally {
            work.deleteRecursively()
        }
    }

    private class StepFailure(message: String) : Exception(message)

    private val Failure.retryable get() = cause !is StepFailure

    private fun downloadFrom(url: String, pid: String, meta: TrackMeta, log: StringBuilder, onProgress: (Float, String?) -> Unit): Result {
        val work = File(context.cacheDir, "dl/$pid").apply { deleteRecursively(); mkdirs() }
        try {
            log.append("[LToTS] ").append(url).append('\n')
            // Fetch stream info, then replace YouTube's video metadata with the album metadata.
            val infoReq = YoutubeDLRequest(url).apply { infoArgs().forEach { (k, v) -> if (v == null) addOption(k) else addOption(k, v) } }
            val infoRes = YoutubeDL.execute(infoReq, "$pid-info")
            log.append(infoRes.err.takeLast(3000))
            val infoOut = infoRes.out
            val start = infoOut.indexOf('{')
            if (start < 0) throw IllegalStateException("yt-dlp returned no song information. ${infoRes.err.lines().lastOrNull { it.isNotBlank() } ?: ""}")
            val info = JSONObject(infoOut.substring(start))
            val m = fillFromInfo(meta, info)
            val cover = prepareCover(m, work)
            rewriteInfo(info, m, cover?.file)
            val infoFile = File(work, "info.json").apply { writeText(info.toString()) }

            val req = YoutubeDLRequest(emptyList()).apply { downloadArgs(infoFile, work).forEach { (k, v) -> if (v == null) addOption(k) else addOption(k, v) } }
            val dl = YoutubeDL.execute(req, pid) { progress, _, line ->
                onProgress((progress / 100f).coerceIn(0f, 1f), line)
            }
            log.append(dl.out.lines().filter { !it.startsWith("[download]") || "100%" in it }.joinToString("\n").takeLast(3000))
            log.append(dl.err.takeLast(3000))
            onProgress(1f, "tagging")

            val audio = work.listFiles()?.firstOrNull { it.extension in AUDIO_EXTENSIONS }
                ?: throw IllegalStateException("yt-dlp finished but produced no audio file (files: ${work.list()?.joinToString()})")
            val uri = try {
                saveToMusic(audio, m)
            } catch (e: Exception) {
                // Saving to Music isn't a YouTube problem; trying another address won't help.
                throw Failure("Couldn't save to Music/LToTS: ${e.message}", log.toString(), StepFailure(e.message ?: ""))
            }
            val codec = if (audio.extension == "opus") "Opus" else audio.extension.uppercase()
            val abr = info.optDouble("abr", 0.0).takeIf { it > 0 }?.let { " · ${it.toInt()} kbps" } ?: ""
            val coverNote = cover?.let { " · ${it.size}×${it.size} cover" } ?: ""
            return Result(uri, m, "$codec$abr$coverNote · original stream, not re-encoded")
        } finally {
            work.deleteRecursively()
        }
    }

    /** yt-dlp options for the first step: song information and the chosen audio stream. */
    internal fun infoArgs(): List<Pair<String, String?>> = listOf(
        "--dump-single-json" to null,
        "--no-playlist" to null,
        "-f" to formatSelector,
    )

    /** yt-dlp options for the second step: fetch that stream and tag it. */
    internal fun downloadArgs(infoFile: File, work: File): List<Pair<String, String?>> = listOf(
        "--load-info-json" to infoFile.absolutePath,
        "-f" to formatSelector,
        "-x" to null, // audio only, kept in its original codec (no re-encoding)
        "--embed-metadata" to null,
        "--embed-thumbnail" to null,
        "--enable-file-urls" to null, // the cover is prepared locally (see prepareCover)
        "--no-mtime" to null,
        "--newline" to null,
        "--retries" to "5",
        "--fragment-retries" to "5",
        "-o" to File(work, "%(id)s.%(ext)s").absolutePath,
    )

    /** If YouTube Music couldn't be asked (e.g. offline lookup failed), take names from yt-dlp. */
    internal fun fillFromInfo(m: TrackMeta, info: JSONObject): TrackMeta {
        val placeholder = m.title.isBlank() || m.title == PLACEHOLDER_TITLE
        val noArtist = m.artist.isBlank() || m.artist == "Unknown artist"
        if (!placeholder && !noArtist) return m
        val title = info.optString("track").ifBlank { info.optString("title") }.ifBlank { m.title }
        val artist = info.optString("artist").ifBlank { info.optString("creator") }
            .ifBlank { info.optString("channel").ifBlank { info.optString("uploader") }.removeSuffix(" - Topic") }
            .ifBlank { m.artist }
        return m.copy(
            title = if (placeholder) title else m.title,
            artist = if (noArtist) artist else m.artist,
            album = m.album ?: info.optString("album").ifBlank { null },
            year = m.year ?: info.optInt("release_year", 0).takeIf { it > 0 }?.toString(),
        )
    }

    private class Cover(val file: File, val size: Int)

    /**
     * Downloads the cover at full size. Square album art is kept byte-for-byte (no
     * recompression); 16:9 video thumbnails are centre-cropped to a square, high-quality JPEG.
     */
    private fun prepareCover(m: TrackMeta, work: File): Cover? {
        val candidates = listOfNotNull(
            m.coverUrl,
            Covers.videoThumb(m.videoId),
            "https://i.ytimg.com/vi/${m.videoId}/hqdefault.jpg",
        ).distinct()
        for (url in candidates) {
            val bytes = runCatching { fetch(url) }.getOrNull() ?: continue
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth; val h = bounds.outHeight
            if (w <= 0 || h <= 0) continue
            val out = File(work, "cover.jpg")
            if (kotlin.math.abs(w - h) <= 2 && bounds.outMimeType == "image/jpeg") {
                out.writeBytes(bytes)
                return Cover(out, w)
            }
            val full = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            val side = minOf(w, h)
            val square = android.graphics.Bitmap.createBitmap(full, (w - side) / 2, (h - side) / 2, side, side)
            out.outputStream().use { square.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
            if (square !== full) square.recycle()
            full.recycle()
            return Cover(out, side)
        }
        return null
    }

    private fun fetch(url: String): ByteArray {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
        return c.inputStream.use { it.readBytes() }
    }

    internal fun rewriteInfo(info: JSONObject, m: TrackMeta, cover: File?) {
        // Drop the video's description, keyword tags and upload date — they make messy tags.
        listOf(
            "description", "tags", "categories", "genre", "genres", "upload_date", "release_date",
            "timestamp", "release_timestamp", "chapters", "creator", "creators", "album_artists",
            "composer", "composers", "synopsis",
        ).forEach { info.remove(it) }
        info.put("title", m.title)
        info.put("track", m.title)
        info.put("fulltitle", m.title)
        info.put("artist", m.artist)
        info.put("artists", JSONArray(m.artist.split(", ").filter { it.isNotBlank() }))
        m.album?.let { info.put("album", it) } ?: info.remove("album")
        (m.albumArtist ?: m.artist.split(", ").first()).let { info.put("album_artist", it) }
        m.trackNumber?.let { info.put("track_number", it) } ?: info.remove("track_number")
        m.year?.toIntOrNull()?.let { info.put("release_year", it); info.put("meta_date", m.year) }
        info.put("meta_comment", "Downloaded with LToTS · https://music.youtube.com/watch?v=${m.videoId}")
        // Our prepared full-size cover first; YouTube's own thumbnails stay as fallbacks.
        val thumbs = info.optJSONArray("thumbnails") ?: JSONArray()
        for (i in 0 until thumbs.length()) thumbs.optJSONObject(i)?.put("preference", -100 + i)
        cover?.let { c ->
            val url = Uri.fromFile(c).toString()
            thumbs.put(JSONObject().put("url", url).put("id", "ltots_cover").put("preference", 1000))
            info.put("thumbnail", url)
        }
        info.put("thumbnails", thumbs)
    }

    private fun saveToMusic(file: File, m: TrackMeta): Uri {
        val resolver = context.contentResolver
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext == "opus") "audio/ogg" else "audio/mp4"
        val artistFolder = safe(m.albumArtist ?: m.artist.split(", ").first()).ifBlank { "Unknown artist" }
        val folder = buildString {
            append(Environment.DIRECTORY_MUSIC).append("/LToTS/").append(artistFolder).append('/')
            m.album?.let { append(safe(it).ifBlank { "Singles" }).append('/') }
        }
        val name = buildString {
            m.trackNumber?.let { append("%02d ".format(it)) }
            append(safe(m.title).ifBlank { m.videoId })
            append('.').append(ext)
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Couldn't create the file in Music")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw IllegalStateException("Couldn't write to Music")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    companion object {
        const val PLACEHOLDER_TITLE = "YouTube video"
        const val FORMAT = "bestaudio[acodec=opus]/bestaudio[ext=m4a]/bestaudio"
        private val AUDIO_EXTENSIONS = setOf("opus", "m4a", "webm", "ogg", "mp3", "aac", "flac")
        private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
        fun safe(s: String) = illegal.replace(s, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(120)
    }
}
