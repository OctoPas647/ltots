package com.ltots.app.rooms

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * What the earbuds see of a room phone that isn't the main (Earbuds mode).
 *
 * Multipoint earbuds pick which of their two phones to listen to partly by what each phone tells
 * them over Bluetooth (AVRCP): the one that says "playing" wins, the one that says "paused" lets
 * go. A room phone that isn't the main has no player of its own, so it said nothing — and the
 * earbuds stayed with the phone that had them before, while this one played to nobody. This
 * session says "playing" exactly while this phone sends the music to the earbuds, and "paused"
 * the moment it hands them on. Taps on the earbuds (play / pause / next) work the room.
 */
class EarbudSession(context: Context, private val control: (String) -> Unit) {
    private val session = MediaSession(context.applicationContext, "LToTS room").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = control("play")
            override fun onPause() = control("pause")
            override fun onSkipToNext() = control("next")
            override fun onSkipToPrevious() = control("prev")
            override fun onStop() = control("pause")
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }
    /** What the session shows: 0 not holding the earbuds, 1 holding (paused), 2 playing. */
    private var shown = -1
    private var shownTitle: String? = null

    /** [holding]: this phone sends the music to the earbuds now. */
    fun update(holding: Boolean, playing: Boolean, title: String, artist: String) {
        if (shownTitle != "$title|$artist") {
            shownTitle = "$title|$artist"
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .build()
            )
        }
        val shown = if (holding) (if (playing) 2 else 1) else 0
        if (this.shown == shown) return
        this.shown = shown
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(if (shown == 2) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
        // Active only while it has something to say: an inactive session never takes the media
        // buttons from the phone's other apps.
        session.isActive = holding
    }

    fun release() {
        runCatching { session.isActive = false }
        runCatching { session.release() }
    }
}
