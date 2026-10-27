package com.ltots.app.audio

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Shuffle that plays every song of the queue once before any comes back, like Spotify.
 *
 * The player's own shuffle picks a random order of the whole queue without knowing what you've
 * already heard: turn shuffle on halfway through an album (or with repeat on) and songs you've
 * just listened to come up before the ones you haven't. Here the order is always
 *   [songs already heard] · [the song playing now] · [the rest, shuffled],
 * so Next only reaches new songs, Previous goes back through what you heard, and once all of
 * them have played (with repeat on) a fresh shuffle of the whole queue starts.
 */
@OptIn(UnstableApi::class)
class ShuffleKeeper(private val player: ExoPlayer, private val random: Random = Random.Default) : Player.Listener {
    /** Songs heard in this round, by media id. */
    private val heard = LinkedHashSet<String>()
    private var ids: List<String> = emptyList()
    private var setting = false

    init { player.addListener(this) }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled) reorder()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (setting || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        val now = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }
        // A new shuffle order alone (same songs) isn't a new queue.
        if (now == ids) return
        // Songs added to the queue keep what was heard; a new queue starts afresh.
        if (!now.containsAll(ids)) heard.clear() else heard.retainAll(now.toSet())
        ids = now
        player.currentMediaItem?.mediaId?.let { heard += it }
        if (player.shuffleModeEnabled) reorder()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId ?: return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (id in heard && heard.size >= ids.size && ids.isNotEmpty()) {
            // Everything has played and the queue came round again: a new shuffle.
            heard.clear()
            heard += id
            if (player.shuffleModeEnabled) reorder()
            return
        }
        heard += id
    }

    private fun reorder() {
        val n = player.mediaItemCount
        if (n < 2) return
        val current = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val order = order(List(n) { player.getMediaItemAt(it).mediaId }, current, heard, random)
        setting = true
        try { player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, random.nextLong())) } finally { setting = false }
    }

    companion object {
        /**
         * The play order for a queue of [ids] with [current] playing: what was [heard] first
         * (shuffled, so Previous walks back through it), then the current song, then everything
         * not heard yet in a random order.
         */
        fun order(ids: List<String>, current: Int, heard: Set<String>, random: Random): IntArray {
            val before = ids.indices.filter { it != current && ids[it] in heard }.shuffled(random)
            val after = ids.indices.filter { it != current && ids[it] !in heard }.shuffled(random)
            return (before + current + after).toIntArray()
        }
    }
}
