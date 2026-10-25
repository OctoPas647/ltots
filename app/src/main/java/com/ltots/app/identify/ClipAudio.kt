package com.ltots.app.identify

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import com.ltots.app.download.Engine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** The audio of a clip, ready for listening: 16 kHz mono, -1..1. */
class ClipAudio(
    val pcm: FloatArray,
    /** Song name and artist the video itself carries (TikTok's music label, YouTube's "Music" box). */
    val tag: ClipTag? = null,
    /** Title of the video / file, shown while working. */
    val label: String,
) {
    val seconds: Double get() = pcm.size / RATE.toDouble()

    companion object {
        const val RATE = 16000
        /** Longer clips are cut to this (from the start); a short-form video fits. */
        const val MAX_SECONDS = 180
    }
}

data class ClipTag(val title: String, val artist: String, val album: String? = null) {
    companion object {
        /** TikTok's "original sound - someone" means there is no real song tag. */
        fun isOriginalSound(title: String) = title.contains("original sound", true) ||
            title.contains("оригинален звук", true) || title.contains("sonido original", true) ||
            title.contains("son original", true) || title.contains("Originalton", true)
    }
}

object ClipLoader {

    /** Downloads the video at [url] (TikTok, YouTube Shorts, Instagram, …) and decodes its sound. */
    suspend fun fromLink(context: Context, url: String, onStatus: (String) -> Unit): ClipAudio {
        onStatus("Starting the download engine…")
        if (!Engine.ensureReady()) throw IllegalStateException(Engine.state.value.error ?: "The download engine couldn't start")
        val work = File(context.cacheDir, "identify/${System.nanoTime()}").apply { mkdirs() }
        try {
            onStatus("Getting the video's sound…")
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                // Audio alone when the site offers it (YouTube); TikTok only has small videos.
                addOption("-f", "ba[filesize<50M]/ba[filesize_approx<50M]/b[filesize<120M]/b[filesize_approx<120M]/ba/b")
                addOption("--write-info-json")
                addOption("--no-mtime")
                addOption("--retries", "3")
                addOption("-o", File(work, "clip.%(ext)s").absolutePath)
            }
            val res = YoutubeDL.execute(req, "identify-${work.name}")
            currentCoroutineContext().ensureActive()
            val media = work.listFiles()?.firstOrNull { it.name.startsWith("clip.") && !it.name.endsWith(".json") && !it.name.endsWith(".part") }
                ?: throw IllegalStateException("Couldn't get the video's sound. ${res.err.lines().lastOrNull { it.isNotBlank() } ?: ""}".trim())
            val info = work.listFiles()?.firstOrNull { it.name.endsWith(".info.json") }
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            onStatus("Reading the sound…")
            val pcm = decode(context, Uri.fromFile(media))
            return ClipAudio(pcm, info?.let(::tagFrom), info?.optString("title")?.ifBlank { null } ?: url)
        } finally {
            work.deleteRecursively()
        }
    }

    /** A video or audio file picked from the phone. */
    suspend fun fromFile(context: Context, uri: Uri, name: String?): ClipAudio {
        val pcm = decode(context, uri)
        return ClipAudio(pcm, null, name ?: "Your file")
    }

    /** What yt-dlp knows about the song in the video. */
    internal fun tagFrom(info: JSONObject): ClipTag? {
        val track = info.optString("track").trim()
        val artist = info.optString("artist").ifBlank { info.optString("artists") }.trim()
            .removePrefix("[\"").removeSuffix("\"]").replace("\",\"", ", ")
        if (track.isBlank() || ClipTag.isOriginalSound(track)) return null
        if (artist.isBlank()) return null
        return ClipTag(track, artist, info.optString("album").ifBlank { null })
    }

    /** Decodes any audio/video file Android can play to 16 kHz mono. */
    suspend fun decode(context: Context, uri: Uri, maxSeconds: Int = ClipAudio.MAX_SECONDS): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("This video has no sound")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val mono = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || ex.sampleTime > maxSeconds * 1_000_000L) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (floatPcm) {
                                val fb = buf.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += fb.get()
                                    mono.add(s / channels)
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                val frames = sb.remaining() / channels
                                for (f in 0 until frames) {
                                    var s = 0f
                                    for (c in 0 until channels) s += sb.get() / 32768f
                                    mono.add(s / channels)
                                }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (mono.size > (maxSeconds + 1) * rate) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val raw = mono.toArray()
            if (raw.isEmpty()) throw IllegalStateException("Couldn't read any sound from this file")
            return normalize(Dsp.resample(raw, ClipAudio.RATE.toDouble() / rate))
        } finally {
            ex.release()
        }
    }

    /** Peak to about -1 dBFS so quiet clips fingerprint as well as loud ones. */
    fun normalize(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) if (abs(v) > peak) peak = abs(v)
        if (peak < 1e-4f) return x
        val g = 0.89f / peak
        return FloatArray(x.size) { x[it] * g }
    }

    private class FloatList {
        var data = FloatArray(1 shl 16)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }
}

/** Classic Shazam mode: listens through the microphone. */
object MicListener {
    @SuppressLint("MissingPermission") // checked by the screen before calling
    suspend fun record(seconds: Int, onLevel: (Float) -> Unit): FloatArray {
        val rate = ClipAudio.RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("The microphone isn't available") }
        val out = ShortArray(rate * seconds)
        var pos = 0
        val buf = ShortArray(1600)
        try {
            rec.startRecording()
            while (pos < out.size) {
                currentCoroutineContext().ensureActive()
                val n = rec.read(buf, 0, minOf(buf.size, out.size - pos))
                if (n <= 0) continue
                System.arraycopy(buf, 0, out, pos, n)
                pos += n
                var s = 0.0
                for (i in 0 until n) s += buf[i].toDouble() * buf[i]
                onLevel((sqrt(s / n) / 8000.0).toFloat().coerceIn(0f, 1f))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return ClipLoader.normalize(FloatArray(pos) { out[it] / 32768f })
    }
}
