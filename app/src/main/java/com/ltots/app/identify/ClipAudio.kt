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

/**
 * The song a video says it uses. [weak] for hints read from a caption or title ("♬ Song - Artist",
 * "Artist - Song (Official Video)"), which are often right but prove nothing.
 */
data class ClipTag(val title: String, val artist: String, val album: String? = null, val weak: Boolean = false) {
    companion object {
        /** TikTok's "original sound - someone" means there is no real song tag. */
        fun isOriginalSound(title: String) = title.contains("original sound", true) ||
            title.contains("оригинален звук", true) || title.contains("sonido original", true) ||
            title.contains("son original", true) || title.contains("Originalton", true)
    }
}

object ClipLoader {

    /** The site wants the user signed in before it shows this post. */
    class SignInNeeded(val platform: Platform, message: String) : IllegalStateException(message)

    /**
     * Gets the sound of the post at [url] — from any site the download engine knows (YouTube,
     * YouTube Music, TikTok videos and photo slideshows, Instagram reels and posts, Facebook,
     * Snapchat Spotlight, X, Reddit, Pinterest, Twitch, Vimeo, SoundCloud…) — and the song the
     * site says it uses:
     *  1. the link is cleaned up (short links followed, tracking removed),
     *  2. the download engine fetches it, signed in when the user signed in to that site,
     *  3. if that fails, once more asking for any format at all,
     *  4. if that fails too, the post's own web page is read for its video/sound file,
     *  5. the song tag comes from the engine, else the page (TikTok sound, Instagram music
     *     sticker), else a credit in the caption, else (YouTube) the video's title.
     */
    suspend fun fromLink(context: Context, rawUrl: String, onStatus: (String) -> Unit): ClipAudio {
        onStatus("Reading the link…")
        val url = Platforms.canonical(rawUrl)
        val platform = Platform.of(url)
        val work = File(context.cacheDir, "identify/${System.nanoTime()}").apply { mkdirs() }
        try {
            var media: File? = null
            var info: JSONObject? = null
            var engineError: String? = null
            var page: PageProbe.Page? = null

            onStatus("Starting the download engine…")
            if (Engine.ensureReady()) {
                val cookies = Platforms.cookiesFor(context, File(work, "cookies.txt"))
                for ((i, format) in listOf(FORMAT, "b/ba/bv*+ba/best").withIndex()) {
                    onStatus(if (i == 0) "Getting the sound from ${platform.label}…" else "Trying another way…")
                    val req = YoutubeDLRequest(url).apply {
                        addOption("--no-playlist")
                        addOption("-f", format)
                        addOption("--write-info-json")
                        addOption("--no-mtime")
                        addOption("--retries", "3")
                        addOption("--socket-timeout", "20")
                        // Photo slideshows and image posts: still get their sound.
                        addOption("--ignore-no-formats-error")
                        cookies?.let { addOption("--cookies", it.absolutePath) }
                        if (i == 1) addOption("--user-agent", Platforms.BROWSER_UA)
                        addOption("-o", File(work, "clip$i.%(ext)s").absolutePath)
                    }
                    val res = runCatching { YoutubeDL.execute(req, "identify-${work.name}-$i") }
                    currentCoroutineContext().ensureActive()
                    media = work.listFiles()?.firstOrNull { it.name.startsWith("clip$i.") && !it.name.endsWith(".json") && !it.name.endsWith(".part") && it.length() > 0 }
                    info = info ?: work.listFiles()?.firstOrNull { it.name.endsWith(".info.json") }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                    if (media != null) break
                    engineError = res.exceptionOrNull()?.message ?: res.getOrNull()?.err
                    engineError = engineError?.lines()?.lastOrNull { it.contains("ERROR") } ?: engineError?.lines()?.lastOrNull { it.isNotBlank() }
                }
            } else engineError = Engine.state.value.error ?: "The download engine couldn't start"

            // The page itself: a fallback for the sound, and a source of the song's name.
            var tag = info?.let(::tagFrom)
            if (media == null || (tag == null && platform in PAGE_TAGS)) {
                onStatus("Reading the ${platform.label} page…")
                page = PageProbe.read(context, url)
                if (tag == null) tag = page?.tag
                if (media == null && page != null && page.mediaUrls.isNotEmpty()) {
                    onStatus("Getting the sound from the page…")
                    media = PageProbe.download(page.mediaUrls, url, work, Platforms.cookieHeader(context, url))
                }
            }
            if (media == null) {
                val why = engineError?.removePrefix("ERROR:")?.trim().orEmpty()
                if (platform.needsLoginSometimes && !Platforms.isSignedIn(context, platform) && (why.isEmpty() || Platforms.wantsLogin(why))) {
                    throw SignInNeeded(platform, "${platform.label} didn't show this post to a guest. Sign in to ${platform.label} once and try again.")
                }
                throw IllegalStateException("Couldn't get the sound from ${platform.label}. $why".trim())
            }
            if (tag == null) tag = info?.let { PageProbe.creditIn(it.optString("description")) }
            if (tag == null && (platform == Platform.YOUTUBE || platform == Platform.YOUTUBE_MUSIC)) tag = PageProbe.titleHint(info?.optString("title"))

            onStatus("Reading the sound…")
            val pcm = decode(context, Uri.fromFile(media))
            val label = info?.optString("title")?.ifBlank { null } ?: page?.title ?: url
            return ClipAudio(pcm, tag, label)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Audio alone when the site offers it (YouTube); TikTok and Instagram only have small videos. */
    private const val FORMAT = "ba[filesize<50M]/ba[filesize_approx<50M]/b[filesize<120M]/b[filesize_approx<120M]/ba/b"

    /** Sites whose pages name the song when the download engine doesn't. */
    private val PAGE_TAGS = setOf(Platform.TIKTOK, Platform.INSTAGRAM, Platform.THREADS, Platform.FACEBOOK)

    /** A video or audio file picked from the phone. */
    suspend fun fromFile(context: Context, uri: Uri, name: String?): ClipAudio {
        val pcm = decode(context, uri)
        return ClipAudio(pcm, null, name ?: "Your file")
    }

    /** What yt-dlp knows about the song in the video. */
    internal fun tagFrom(info: JSONObject): ClipTag? {
        val track = info.optString("track").ifBlank { info.optString("alt_title") }.trim()
        val artist = info.optString("artist").ifBlank { info.optString("artists") }.trim()
            .removePrefix("[\"").removeSuffix("\"]").replace("\",\"", ", ")
        if (track.isBlank() || ClipTag.isOriginalSound(track)) return null
        if (artist.isBlank()) return null
        return ClipTag(track, artist, info.optString("album").ifBlank { null })
    }

    /**
     * Decodes any audio/video file Android can play to mono at [outRate] (16 kHz unless asked),
     * [maxSeconds] of it from [startSec] on, peak-normalised unless [normalized] is false.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        maxSeconds: Int = ClipAudio.MAX_SECONDS,
        startSec: Double = 0.0,
        outRate: Int = ClipAudio.RATE,
        normalized: Boolean = true,
    ): FloatArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("This video has no sound")
            ex.selectTrack(track)
            val startUs = (startSec * 1_000_000).toLong()
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
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
                            if (n < 0 || ex.sampleTime > startUs + maxSeconds * 1_000_000L) {
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
            val out = if (rate == outRate) raw else Dsp.resample(raw, outRate.toDouble() / rate)
            return if (normalized) normalize(out) else out
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
