package com.ltots.app.download

import com.ltots.app.ytm.YtSong
import org.json.JSONObject

/** Everything that gets written into the downloaded file's tags. */
data class TrackMeta(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val trackNumber: Int? = null,
    val coverUrl: String? = null,
    val durationSec: Int? = null,
    val isVideo: Boolean = false,
    /** True once album, track number and HQ cover have been looked up. */
    val complete: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", videoId); put("t", title); put("a", artist)
        album?.let { put("al", it) }; albumId?.let { put("ali", it) }; albumArtist?.let { put("aa", it) }
        year?.let { put("y", it) }; trackNumber?.let { put("n", it) }; coverUrl?.let { put("c", it) }
        durationSec?.let { put("d", it) }; put("vid", isVideo); put("ok", complete)
    }

    companion object {
        fun fromJson(o: JSONObject) = TrackMeta(
            videoId = o.getString("v"), title = o.optString("t"), artist = o.optString("a"),
            album = o.optString("al").ifBlank { null }, albumId = o.optString("ali").ifBlank { null },
            albumArtist = o.optString("aa").ifBlank { null }, year = o.optString("y").ifBlank { null },
            trackNumber = if (o.has("n")) o.getInt("n") else null, coverUrl = o.optString("c").ifBlank { null },
            durationSec = if (o.has("d")) o.getInt("d") else null, isVideo = o.optBoolean("vid"),
            complete = o.optBoolean("ok"),
        )

        fun from(s: YtSong, albumArtist: String? = null, cover: String? = null, complete: Boolean = false) = TrackMeta(
            videoId = s.videoId, title = s.title, artist = s.artistText.ifBlank { albumArtist ?: "Unknown artist" },
            album = s.album?.name, albumId = s.album?.id, albumArtist = albumArtist,
            year = s.year, trackNumber = s.trackNumber, coverUrl = cover ?: s.thumbUrl,
            durationSec = s.durationSec, isVideo = s.isVideo, complete = complete,
        )
    }
}

enum class TaskState(val label: String) {
    QUEUED("Waiting"),
    PREPARING("Finding the best version"),
    DOWNLOADING("Downloading"),
    TAGGING("Adding cover & tags"),
    SAVING("Saving"),
    DONE("Done"),
    FAILED("Failed"),
    CANCELED("Canceled"),
    SKIPPED("Already downloaded");

    val active get() = this == PREPARING || this == DOWNLOADING || this == TAGGING || this == SAVING
    val finished get() = this == DONE || this == FAILED || this == CANCELED || this == SKIPPED
}

data class DownloadTask(
    val id: String,
    val meta: TrackMeta,
    val state: TaskState = TaskState.QUEUED,
    /** 0..1 while downloading. */
    val progress: Float = 0f,
    val error: String? = null,
    /** Playlist this came from (for "sync playlist"). */
    val sourcePlaylist: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0L,
    /** Content URI of the saved file. */
    val savedUri: String? = null,
    /** e.g. "Opus · 48 kHz · 1400 px cover". */
    val qualityNote: String? = null,
    /** For failed downloads: what yt-dlp said, so it can be read and copied. */
    val log: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("m", meta.toJson()); put("s", state.name); put("e", error)
        put("p", sourcePlaylist); put("at", addedAt); put("ft", finishedAt); put("u", savedUri); put("q", qualityNote)
        log?.let { put("log", it.takeLast(8000)) }
    }

    companion object {
        fun fromJson(o: JSONObject) = DownloadTask(
            id = o.getString("id"), meta = TrackMeta.fromJson(o.getJSONObject("m")),
            state = runCatching { TaskState.valueOf(o.getString("s")) }.getOrDefault(TaskState.QUEUED),
            error = o.optString("e").ifBlank { null }.takeIf { it != "null" },
            sourcePlaylist = o.optString("p").ifBlank { null }.takeIf { it != "null" },
            addedAt = o.optLong("at"), finishedAt = o.optLong("ft"),
            savedUri = o.optString("u").ifBlank { null }.takeIf { it != "null" },
            qualityNote = o.optString("q").ifBlank { null }.takeIf { it != "null" },
            log = o.optString("log").ifBlank { null },
        )
    }
}

/** A playlist the user downloaded and can re-sync to pick up new songs. */
data class SyncedPlaylist(val playlistId: String, val title: String, val thumbUrl: String?, val lastSync: Long, val trackCount: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", playlistId).put("t", title).put("th", thumbUrl).put("ls", lastSync).put("n", trackCount)
    companion object {
        fun fromJson(o: JSONObject) = SyncedPlaylist(
            o.getString("id"), o.optString("t"), o.optString("th").ifBlank { null }.takeIf { it != "null" },
            o.optLong("ls"), o.optInt("n"),
        )
    }
}
