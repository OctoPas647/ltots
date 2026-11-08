package com.ltots.app.audio

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drives an [AudioProcessorChain] the way Media3's DefaultAudioSink does (see its handleBuffer):
 *  - a playback-parameter change is applied only after draining the pipeline to end of stream,
 *    then the processors are flushed;
 *  - a new playlist item reconfigures and flushes with that item's stream metadata;
 *  - end of stream drains everything.
 * Output is collected as interleaved floats (or shorts converted to floats).
 */
class SinkSimulator(private val chain: AudioProcessorChain, val sampleRate: Int = 48000, val channels: Int = 2) {
    private val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(chain.audioProcessors))
    private var pendingParams: PlaybackParameters? = null
    private var meta: AudioProcessor.StreamMetadata = AudioProcessor.StreamMetadata.DEFAULT
    var outputEncoding = C.ENCODING_INVALID; private set
    val out = FloatList()
    /** Output frame index at which each flush happened (for gap checks). */
    val flushPoints = mutableListOf<Int>()
    var params = PlaybackParameters.DEFAULT; private set

    fun start(item: AudioProcessor.StreamMetadata = AudioProcessor.StreamMetadata.DEFAULT, inputEncoding: Int = C.ENCODING_PCM_16BIT) {
        meta = item
        val f = pipeline.configure(AudioProcessor.AudioFormat(sampleRate, channels, inputEncoding))
        outputEncoding = if (f.encoding == C.ENCODING_INVALID || f == AudioProcessor.AudioFormat.NOT_SET) inputEncoding else f.encoding
        params = chain.applyPlaybackParameters(params)
        pipeline.flush(meta)
        flushPoints += out.size / channels
    }

    fun setPlaybackParameters(p: PlaybackParameters) { pendingParams = p }

    /** Next item in the playlist (gapless): drain, then flush with the new item's metadata. */
    fun nextItem(item: AudioProcessor.StreamMetadata) {
        drain()
        meta = item
        pipeline.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
        params = chain.applyPlaybackParameters(params)
        pipeline.flush(meta)
        flushPoints += out.size / channels
    }

    /** A seek: everything buffered is thrown away and the processors start fresh. */
    fun seek(positionUs: Long) {
        meta = meta.buildUpon().setPositionOffsetUs(positionUs).build()
        pipeline.flush(meta)
        flushPoints += out.size / channels
    }

    fun write(pcm16: ShortArray, framesPerBuffer: Int = 960) {
        var i = 0
        while (i < pcm16.size) {
            val n = minOf(framesPerBuffer * channels, pcm16.size - i)
            pendingParams?.let { p ->
                drain()
                params = chain.applyPlaybackParameters(p)
                pipeline.flush(meta)
                flushPoints += out.size / channels
                pendingParams = null
            }
            val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (k in 0 until n) b.putShort(pcm16[i + k])
            b.flip()
            if (!pipeline.isOperational) {
                // Nothing active: the sink writes the input straight out (16-bit here).
                while (b.hasRemaining()) out.add(b.short / 32768f)
            } else {
                while (b.hasRemaining()) {
                    pipeline.queueInput(b)
                    collect()
                }
                collect()
            }
            i += n
        }
    }

    fun drain() {
        if (!pipeline.isOperational) return
        pipeline.queueEndOfStream()
        var guard = 0
        // Like the real sink, keep asking until the pipeline says it's done. A processor that
        // never finishes draining freezes playback on the phone, so it must fail here too.
        while (!pipeline.isEnded) {
            check(guard++ < 10_000) { "audio pipeline never finished draining (playback would freeze)" }
            collect()
        }
        // Pipeline must be flushed before it accepts input again (the sink always does).
    }

    fun end() = drain()

    private fun collect() {
        val o = pipeline.output
        if (!o.hasRemaining()) return
        o.order(ByteOrder.nativeOrder())
        when (outputEncoding) {
            C.ENCODING_PCM_FLOAT -> while (o.hasRemaining()) out.add(o.float)
            C.ENCODING_PCM_16BIT -> while (o.hasRemaining()) out.add(o.short / 32768f)
            else -> error("unexpected encoding $outputEncoding")
        }
    }

    /** Left channel of the output. */
    fun left(): FloatArray = FloatArray(out.size / channels) { out[it * channels] }
}

class FloatList {
    private var a = FloatArray(1 shl 16)
    var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
}

/** A playlist timeline with one period per item, as ExoPlayer builds for local files. */
class PlaylistTimeline(private val ids: List<String>, private val durationUs: Long = 600_000_000L) : Timeline() {
    override fun getWindowCount() = ids.size
    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
        window.set(
            ids[windowIndex], MediaItem.Builder().setMediaId(ids[windowIndex]).build(), null,
            C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0, durationUs,
            windowIndex, windowIndex, 0,
        )
    override fun getPeriodCount() = ids.size
    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
        period.set("p_" + ids[periodIndex], "p_" + ids[periodIndex], periodIndex, durationUs, 0)
    override fun getIndexOfPeriod(uid: Any): Int = ids.indexOfFirst { "p_$it" == uid }
    override fun getUidOfPeriod(periodIndex: Int): Any = "p_" + ids[periodIndex]

    fun metaFor(index: Int, positionUs: Long = 0): AudioProcessor.StreamMetadata =
        AudioProcessor.StreamMetadata.Builder()
            .setTimeline(this)
            .setPeriodUid(getUidOfPeriod(index))
            .setPositionOffsetUs(positionUs)
            .build()
}
