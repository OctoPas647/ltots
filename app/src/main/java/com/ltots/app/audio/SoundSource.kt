package com.ltots.app.audio

import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * Where the audio thread gets its settings from. The processors never talk to the UI
 * directly: they ask this for "the EQ / pitch of song X" whenever [version] changes or a new
 * song starts, so every song gets exactly its own settings from its very first sample.
 */
interface SoundSource {
    /** Bumped whenever any EQ, pitch or option changes. */
    val version: Int
    fun eqFor(mediaId: String?): EqState
    /** Pitch as a frequency ratio (1.0 = original key). */
    fun pitchScaleFor(mediaId: String?): Double
    /** Keep the character of voices when shifting pitch. */
    val preserveFormants: Boolean
}

/** A fixed source for tests and for before the app is initialised. */
open class StaticSoundSource(
    var eq: EqState = EqState(enabled = false),
    var pitch: Double = 1.0,
    override var preserveFormants: Boolean = false,
) : SoundSource {
    @Volatile override var version: Int = 0
    override fun eqFor(mediaId: String?) = eq
    override fun pitchScaleFor(mediaId: String?) = pitch
    fun bump() { version++ }
}

@UnstableApi
object StreamIds {
    private val period = ThreadLocal.withInitial { Timeline.Period() }
    private val window = ThreadLocal.withInitial { Timeline.Window() }

    /** The media id of the song a flushed stream belongs to, or null if unknown. */
    fun mediaIdOf(meta: AudioProcessor.StreamMetadata): String? = runCatching {
        val uid = meta.periodUid ?: return null
        val tl = meta.timeline
        if (tl.isEmpty) return null
        val p = tl.getPeriodByUid(uid, period.get()!!)
        tl.getWindow(p.windowIndex, window.get()!!).mediaItem.mediaId.takeIf { it.isNotBlank() }
    }.getOrNull()
}
