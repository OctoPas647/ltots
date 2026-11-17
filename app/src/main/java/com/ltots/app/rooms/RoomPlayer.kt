package com.ltots.app.rooms

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.PreRender
import com.ltots.app.data.Json
import com.ltots.app.data.Store
import com.ltots.app.playback.PlayerConnection
import org.json.JSONArray
import org.json.JSONObject

/**
 * The room's hands on this phone's player (always on the main thread, where ExoPlayer lives):
 * what's playing, remote play/pause/skip from the other phones, and handing the whole queue
 * over when another phone becomes the main.
 */
@UnstableApi
object RoomPlayer {
    private val main = Handler(Looper.getMainLooper())

    private val player: ExoPlayer? get() = AudioEngine.player.value

    fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Title, artist and whether it plays, for the other phones' screens. */
    fun nowPlaying(): JSONObject {
        val p = player ?: return JSONObject().put("playing", false)
        val md = p.mediaMetadata
        return JSONObject()
            .put("title", md.title?.toString() ?: "")
            .put("artist", md.artist?.toString() ?: "")
            .put("playing", p.isPlaying)
    }

    @Volatile private var cachedNow: JSONObject = JSONObject().put("playing", false)

    /** [nowPlaying], safe from any thread (refreshed on the main thread). */
    fun nowPlayingAnyThread(): JSONObject {
        onMain { cachedNow = nowPlaying() }
        return cachedNow
    }

    fun control(cmd: String) = onMain {
        val p = player ?: return@onMain
        when (cmd) {
            "toggle" -> if (p.isPlaying) p.pause() else {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                p.play()
            }
            "pause" -> p.pause()
            "play" -> p.play()
            "next" -> p.seekToNextMediaItem()
            "prev" -> p.seekToPrevious()
        }
    }

    /** True while the room paused the music (calibration), so it can resume it after. */
    @Volatile private var pausedForCalibration = false

    fun pauseForCalibration() = onMain {
        val p = player ?: return@onMain
        pausedForCalibration = p.playWhenReady
        p.pause()
    }

    fun resumeAfterCalibration() = onMain {
        if (pausedForCalibration) player?.play()
        pausedForCalibration = false
    }

    /**
     * Everything the new main needs to carry on exactly where this one will be at [atNs]:
     * the queue (songs lent through [RoomMediaServer]), position, shuffle, repeat, and the
     * songs' own EQ, pitch and speed. Runs [done] on the main thread.
     */
    fun snapshot(context: Context, host: String, atNs: Long, done: (JSONObject) -> Unit) = onMain {
        val p = player
        val out = JSONObject().put("at", atNs)
        if (p == null || p.mediaItemCount == 0) {
            done(out.put("items", JSONArray()))
            return@onMain
        }
        val port = RoomMediaServer.start(context)
        val items = JSONArray()
        val profiles = JSONObject()
        for (i in 0 until p.mediaItemCount) {
            val it = p.getMediaItemAt(i)
            val id = it.mediaId.substringBefore(PreRender.ID_SUFFIX)
            val uri = PreRender.originalUri(it) ?: it.requestMetadata.mediaUri ?: it.localConfiguration?.uri ?: continue
            val lent = when (uri.scheme) {
                "content", "file", "android.resource" -> {
                    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    val ext = mime?.let { m -> MimeTypeMap.getSingleton().getExtensionFromMimeType(m) }
                        ?: uri.lastPathSegment?.substringAfterLast('.', "")?.take(5) ?: ""
                    "${RoomDataSource.SCHEME}://$host:$port${RoomMediaServer.share(uri, ext)}"
                }
                else -> uri.toString()
            }
            val md = it.mediaMetadata
            items.put(
                JSONObject().put("id", id).put("uri", lent)
                    .put("title", md.title?.toString() ?: "").put("artist", md.artist?.toString() ?: "")
                    .put("album", md.albumTitle?.toString() ?: "").put("dur", md.durationMs ?: 0L)
            )
            Store.loadProfile(id)?.let { prof -> profiles.put(id, Json.profile(prof)) }
        }
        val now = GroupClock.now()
        val ahead = ((atNs - now) / 1_000_000L).coerceAtLeast(0)
        val pos = p.currentPosition + if (p.isPlaying) (ahead * p.playbackParameters.speed).toLong() else 0L
        out.put("items", items)
            .put("index", p.currentMediaItemIndex)
            .put("pos", pos)
            .put("play", p.playWhenReady)
            .put("shuffle", p.shuffleModeEnabled)
            .put("repeat", p.repeatMode)
            .put("profiles", profiles)
            .put("eq", Json.eq(AudioEngine.defaultEq.value))
        done(out)
    }

    /** The old main stops at the handoff moment (the new one carries on from there). */
    fun stopAt(atNs: Long, after: () -> Unit) {
        val ms = ((atNs - GroupClock.now()) / 1_000_000L).coerceAtLeast(0)
        main.postDelayed({
            player?.pause()
            after()
        }, ms)
    }

    /** This phone takes over: loads the queue and starts so the first sound lands at "at". */
    fun takeOver(context: Context, h: JSONObject) = onMain {
        PlayerConnection.connect(context)
        val start = System.currentTimeMillis()
        fun attempt() {
            val p = player
            if (p == null) {
                if (System.currentTimeMillis() - start < 4000) main.postDelayed({ attempt() }, 50)
                return
            }
            val arr = h.optJSONArray("items") ?: JSONArray()
            if (arr.length() == 0) return
            val profiles = h.optJSONObject("profiles")
            if (profiles != null) for (k in profiles.keys()) runCatching { Store.saveProfile(k, Json.profile(profiles.getJSONObject(k))) }
            h.optJSONObject("eq")?.let { runCatching { AudioEngine.defaultEq.value = Json.eq(it) } }
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val uri = Uri.parse(o.getString("uri"))
                MediaItem.Builder()
                    .setMediaId(o.optString("id"))
                    .setUri(uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(o.optString("title"))
                            .setArtist(o.optString("artist"))
                            .setAlbumTitle(o.optString("album"))
                            .setDurationMs(o.optLong("dur").takeIf { it > 0 })
                            .setExtras(Bundle().apply { putLong("trackId", -1L) })
                            .build()
                    )
                    .build()
            }
            // Whatever this phone was playing stops first, so nothing else can take the start moment.
            p.playWhenReady = false
            p.stop()
            AudioEngine.roomChanged()
            RoomStream.pendingAnchorNs = h.optLong("at")
            p.shuffleModeEnabled = h.optBoolean("shuffle")
            p.repeatMode = h.optInt("repeat", Player.REPEAT_MODE_OFF)
            p.setMediaItems(items, h.optInt("index").coerceIn(0, items.size - 1), h.optLong("pos").coerceAtLeast(0L))
            p.prepare()
            p.playWhenReady = h.optBoolean("play")
        }
        attempt()
    }

    /** Leaving the room: this phone's own default EQ comes back (a handed-over one was only lent). */
    fun restoreOwnEq() = onMain { runCatching { AudioEngine.defaultEq.value = Store.loadDefaultEq() } }
}
