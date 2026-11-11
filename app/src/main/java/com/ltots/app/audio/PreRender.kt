package com.ltots.app.audio

import android.content.Context
import android.media.AudioFormat as PlatformFormat
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.ltots.app.data.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Pre-render (Profile > Screen off): with the screen off, a song that still needs LToTS's own
 * processing (an EQ the phone's EQ doesn't take over, a pitch change) is worked out ahead of
 * time — while the song before it plays — into a file the phone's audio chip can play by
 * itself. The processor does a few seconds of work per song and then sleeps, instead of being
 * kept awake for the whole song.
 *
 * The file is the song with its exact EQ and pitch baked in, encoded at a high bitrate in the
 * format the audio chip says it can play directly (Opus at 256 kb/s, else AAC at 320 kb/s).
 * Songs with a changed speed stay live (their timing has to match the seek bar and lyrics).
 * Files live in the app's cache (up to [CAP_BYTES], oldest first out) and are reused when the
 * same song comes round again with the same sound.
 */
@UnstableApi
object PreRender {
    /** Media item extras: marks a queue entry that plays a pre-rendered file. */
    const val EXTRA_RENDERED = "ltots.rendered"
    /** Media item extras: the song's own file, to switch back to. */
    const val EXTRA_ORIG_URI = "ltots.origUri"
    /** Media item extras: what was baked in (see [Job.key]). */
    const val EXTRA_KEY = "ltots.renderKey"
    /** Added to the media id the audio processors see, so they leave a rendered file alone. */
    const val ID_SUFFIX = "\u0000r"

    private const val CAP_BYTES = 300L shl 20
    private const val VERSION = 1
    /** Longest song worth rendering (a long mix plays live). */
    private const val MAX_SECONDS = 20 * 60

    /** What to bake into a song. */
    data class Job(
        val uri: Uri,
        val eq: EqState,
        val pitch: Double,
        val formants: Boolean,
        val efficient: Boolean,
    ) {
        val key: String by lazy {
            val eqPart = if (eq.enabled && !EqMath.isNeutral(eq)) Json.eq(eq).toString() else "-"
            // The engine used (efficient) isn't part of it: either one's file is the right sound.
            val pitchPart = if (kotlin.math.abs(pitch - 1.0) > 1e-6) "%.6f|$formants".format(java.util.Locale.ROOT, pitch) else "-"
            sha1("$VERSION|$uri|$eqPart|$pitchPart")
        }
    }

    enum class Codec(val mime: String, val encoding: Int, val muxer: Int, val ext: String, val bitrate: Int) {
        OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS, PlatformFormat.ENCODING_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, "ogg", 256_000),
        AAC(MediaFormat.MIMETYPE_AUDIO_AAC, PlatformFormat.ENCODING_AAC_LC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", 320_000),
    }

    private lateinit var root: File
    private val thread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            // Bounded batch work with the screen off: the efficiency cores, always.
            NativeDsp.markCurrentThread(NativeDsp.THREAD_BACKGROUND)
            r.run()
        }, "ltots-prerender").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread + com.ltots.app.CrashReport.guard("pre-render"))
    private var running: Pair<String, Job>? = null
    private var runningJob: kotlinx.coroutines.Job? = null
    /** Files the queue points at right now: never removed to make room. */
    private val inUse = HashSet<String>()

    /** Stats for the battery card: songs rendered, and the processing time they took. */
    @Volatile var rendered = 0; private set
    @Volatile var renderMs = 0L; private set

    fun init(context: Context) {
        root = File(context.cacheDir, "ltots-render").apply { mkdirs() }
        // Half-written files from a run that was cut off.
        root.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Which format the audio chip plays by itself at [sampleRate] (stereo), or null if it plays
     * none that can be made here. Opus needs 48 kHz (all YouTube audio is).
     */
    fun codecFor(context: Context, sampleRate: Int, attrs: android.media.AudioAttributes): Codec? {
        for (c in Codec.entries) {
            if (c == Codec.OPUS && sampleRate != 48000) continue
            val fmt = runCatching {
                PlatformFormat.Builder().setEncoding(c.encoding).setSampleRate(sampleRate)
                    .setChannelMask(PlatformFormat.CHANNEL_OUT_STEREO).build()
            }.getOrNull() ?: continue
            val ok = runCatching { AudioManager.getPlaybackOffloadSupport(fmt, attrs) != AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED }
                .getOrDefault(false)
            if (ok && encoderExists(c.mime)) return c
        }
        return null
    }

    private fun encoderExists(mime: String): Boolean = runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .any { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
    }.getOrDefault(false)

    /** The finished file for [job], if it's been made before. */
    fun cached(job: Job): File? {
        if (!::root.isInitialized) return null
        return Codec.entries.map { File(root, "${job.key}.${it.ext}") }.firstOrNull { it.exists() && it.length() > 0 }
            ?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /** Keep these files (the queue's rendered entries); everything else may make room. */
    fun setInUse(keys: Collection<String>) = synchronized(inUse) { inUse.clear(); inUse.addAll(keys) }

    /**
     * Makes [job]'s file in the background and calls [onReady] on the main thread when it's
     * there (straight away if it already is). A newer request replaces one still running.
     */
    fun request(context: Context, job: Job, attrs: android.media.AudioAttributes, onReady: (File) -> Unit) {
        cached(job)?.let { onReady(it); return }
        synchronized(this) {
            if (running?.first == job.key && runningJob?.isActive == true) return
            runningJob?.cancel()
            running = job.key to job
            runningJob = scope.launch {
                // Short, bounded work: keep the processor awake to finish it in one go (it may
                // otherwise doze off while the audio chip plays the song before).
                val wake = runCatching {
                    context.getSystemService(android.os.PowerManager::class.java)
                        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LToTS:prerender")
                        .apply { setReferenceCounted(false); acquire(90_000) }
                }.getOrNull()
                try {
                    val file = runCatching { render(context.applicationContext, job, attrs) }.getOrNull()
                    if (file != null) withContext(Dispatchers.Main) { onReady(file) }
                } finally {
                    runCatching { if (wake?.isHeld == true) wake.release() }
                }
            }
        }
    }

    fun cancel() = synchronized(this) { runningJob?.cancel(); runningJob = null; running = null }

    /** A queue entry that plays [file] in place of [item]'s own file. */
    fun renderedItem(item: MediaItem, file: File, key: String): MediaItem {
        val orig = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
        val uri = Uri.fromFile(file)
        val extras = Bundle().apply {
            putBoolean(EXTRA_RENDERED, true)
            putString(EXTRA_ORIG_URI, orig?.toString())
            putString(EXTRA_KEY, key)
        }
        return item.buildUpon()
            .setUri(uri)
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(uri).setExtras(extras).build())
            .build()
    }

    fun isRendered(item: MediaItem?): Boolean = item?.requestMetadata?.extras?.getBoolean(EXTRA_RENDERED) == true
    fun keyOf(item: MediaItem?): String? = item?.requestMetadata?.extras?.getString(EXTRA_KEY)
    fun originalUri(item: MediaItem?): Uri? = item?.requestMetadata?.extras?.getString(EXTRA_ORIG_URI)?.let(Uri::parse)

    /** [item] back on its own file. */
    fun originalItem(item: MediaItem): MediaItem {
        val orig = originalUri(item) ?: return item
        return item.buildUpon()
            .setUri(orig)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(orig).build())
            .build()
    }

    // ---- the work ----------------------------------------------------------------------------

    private suspend fun render(context: Context, job: Job, attrs: android.media.AudioAttributes): File? {
        val started = android.os.SystemClock.elapsedRealtime()
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, job.uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val inFmt = ex.getTrackFormat(track)
            val sr = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (ch !in 1..2) return null
            if (inFmt.containsKey(MediaFormat.KEY_DURATION) && inFmt.getLong(MediaFormat.KEY_DURATION) > MAX_SECONDS * 1_000_000L) return null
            val codec = codecFor(context, sr, attrs) ?: return null
            ex.selectTrack(track)
            makeRoom()
            val out = File(root, "${job.key}.${codec.ext}")
            val part = File(root, "${job.key}.${codec.ext}.part")
            part.delete()
            val ok = Pipeline(ex, inFmt, sr, ch, codec, job, part).run()
            if (!ok) { part.delete(); return null }
            if (!part.renameTo(out)) { part.delete(); return null }
            rendered++
            renderMs += android.os.SystemClock.elapsedRealtime() - started
            return out
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun makeRoom() {
        val files = root.listFiles()?.filter { !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total < CAP_BYTES * 9 / 10) return
        val keep = synchronized(inUse) { inUse.toSet() }
        for (f in files.sortedBy { it.lastModified() }) {
            if (total < CAP_BYTES * 7 / 10) break
            if (f.nameWithoutExtension in keep) continue
            total -= f.length()
            f.delete()
        }
    }

    /**
     * LToTS's own EQ and pitch engine, run on a whole song instead of live: the very same
     * processors the player uses, so a pre-rendered song sounds exactly like the live one.
     * Takes interleaved PCM in [encoding] and hands back interleaved float blocks.
     */
    class OfflineChain(sampleRate: Int, channels: Int, encoding: Int, job: Job) {
        private val source = StaticSoundSource(eq = job.eq, pitch = job.pitch, preserveFormants = job.formants, efficientPitch = job.efficient)
        private val eq = EqProcessor(AnalyzerTap(), source)
        private val pitch = TimePitchProcessor(source)
        private val usePitch = kotlin.math.abs(job.pitch - 1.0) > 1e-6

        init {
            eq.configure(AudioProcessor.AudioFormat(sampleRate, channels, encoding))
            eq.flush(AudioProcessor.StreamMetadata.DEFAULT)
            if (usePitch) {
                pitch.setSpeed(1.0)
                pitch.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT))
                pitch.flush(AudioProcessor.StreamMetadata.DEFAULT)
            }
        }

        /** Runs [input] (native byte order) through; every finished block goes to [out]. */
        fun process(input: ByteBuffer, out: (FloatArray) -> Unit) {
            eq.queueInput(input)
            pass(eq.output, out)
        }

        /** End of the song: whatever the engine still holds. */
        fun finish(out: (FloatArray) -> Unit) {
            eq.queueEndOfStream()
            var guard = 0
            while (!eq.isEnded && guard++ < 1000) pass(eq.output, out)
            if (usePitch) {
                pitch.queueEndOfStream()
                guard = 0
                while (!pitch.isEnded && guard++ < 1000) emit(pitch.output, out)
            }
        }

        private fun pass(eqOut: ByteBuffer, out: (FloatArray) -> Unit) {
            if (!eqOut.hasRemaining()) return
            if (!usePitch) { emit(eqOut, out); return }
            pitch.queueInput(eqOut)
            emit(pitch.output, out)
        }

        private fun emit(b: ByteBuffer, out: (FloatArray) -> Unit) {
            val n = b.remaining() / 4
            if (n == 0) return
            val f = FloatArray(n)
            b.order(ByteOrder.nativeOrder()).asFloatBuffer().get(f)
            b.position(b.limit())
            out(f)
        }
    }

    /** Decode → EQ → pitch → encode → file, a block at a time (never the whole song in memory). */
    private class Pipeline(
        val ex: MediaExtractor,
        val inFmt: MediaFormat,
        val sr: Int,
        val ch: Int,
        val codec: Codec,
        val job: Job,
        val file: File,
    ) {
        private var chain: OfflineChain? = null
        private lateinit var dec: MediaCodec
        private lateinit var enc: MediaCodec
        private var muxer: MediaMuxer? = null
        private var muxTrack = -1
        private var framesOut = 0L
        private var pcm = ShortArray(0)
        private var staging: ByteBuffer = ByteBuffer.allocateDirect(0)
        private var decEncoding = C.ENCODING_PCM_16BIT

        suspend fun run(): Boolean {
            val mime = inFmt.getString(MediaFormat.KEY_MIME) ?: return false
            dec = MediaCodec.createDecoderByType(mime)
            enc = MediaCodec.createEncoderByType(codec.mime)
            try {
                inFmt.setInteger(MediaFormat.KEY_PCM_ENCODING, PlatformFormat.ENCODING_PCM_FLOAT)
                dec.configure(inFmt, null, null, 0)
                dec.start()
                val ef = MediaFormat.createAudioFormat(codec.mime, sr, ch).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, codec.bitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384 * ch)
                    if (codec == Codec.AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                muxer = MediaMuxer(file.path, codec.muxer)
                return loop()
            } finally {
                runCatching { dec.stop() }; runCatching { dec.release() }
                runCatching { enc.stop() }; runCatching { enc.release() }
                runCatching { muxer?.stop() }; runCatching { muxer?.release() }
            }
        }

        private var processorsReady = false
        private var encEnded = false

        private fun setUpProcessors(encoding: Int) {
            decEncoding = encoding
            chain = OfflineChain(sr, ch, encoding, job)
            processorsReady = true
        }

        private suspend fun loop(): Boolean {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false
            var processorsEnded = false
            var encodeDone = false
            var guard = 0
            while (!encodeDone) {
                coroutineContext.ensureActive()
                if (++guard > 50_000_000) return false
                var progressed = false
                // 1. file → decoder
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(0)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                        progressed = true
                    }
                }
                // 2. decoder → processors → encoder
                if (!decodeDone) {
                    val o = dec.dequeueOutputBuffer(info, 1_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = dec.outputFormat
                            val e = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == PlatformFormat.ENCODING_PCM_FLOAT)
                                C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
                            setUpProcessors(e)
                            progressed = true
                        }
                        o >= 0 -> {
                            if (!processorsReady) setUpProcessors(C.ENCODING_PCM_16BIT)
                            if (info.size > 0) {
                                val b = dec.getOutputBuffer(o)!!
                                b.position(info.offset); b.limit(info.offset + info.size)
                                feed(b)
                            }
                            dec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                            progressed = true
                        }
                    }
                }
                if (decodeDone && !processorsEnded) {
                    if (!processorsReady) setUpProcessors(C.ENCODING_PCM_16BIT)
                    finishProcessors()
                    queueEncoderEnd()
                    processorsEnded = true
                }
                // 3. encoder → file
                val e = enc.dequeueOutputBuffer(info, if (progressed) 0 else 1_000)
                when {
                    e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxTrack < 0) { muxTrack = muxer!!.addTrack(enc.outputFormat); muxer!!.start() }
                    }
                    e >= 0 -> {
                        val b = enc.getOutputBuffer(e)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxTrack >= 0) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            muxer!!.writeSampleData(muxTrack, b, info)
                        }
                        enc.releaseOutputBuffer(e, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encodeDone = true
                    }
                }
                if (encEnded) encodeDone = true
            }
            return muxTrack >= 0 && framesOut > 0
        }

        /** Decoded audio into the EQ (and pitch), whatever comes out on to the encoder. */
        private suspend fun feed(b: ByteBuffer) {
            if (staging.capacity() < b.remaining()) staging = ByteBuffer.allocateDirect(b.remaining() * 2).order(ByteOrder.nativeOrder())
            staging.clear()
            staging.put(b)
            staging.flip()
            val out = ArrayList<FloatArray>(2)
            chain!!.process(staging) { out += it }
            for (f in out) encode(f)
        }

        private suspend fun finishProcessors() {
            val out = ArrayList<FloatArray>(2)
            chain!!.finish { out += it }
            for (f in out) encode(f)
        }

        /** Float samples → 16-bit PCM into the encoder, waiting for room as needed. */
        private suspend fun encode(floats: FloatArray) {
            val n = floats.size
            if (n == 0) return
            if (pcm.size < n) pcm = ShortArray(n)
            for (i in 0 until n) {
                val v = floats[i]
                pcm[i] = (if (v.isFinite()) (v * 32767f).roundToInt().coerceIn(-32768, 32767) else 0).toShort()
            }
            var at = 0
            val info = MediaCodec.BufferInfo()
            while (at < n) {
                coroutineContext.ensureActive()
                val i = enc.dequeueInputBuffer(2_000)
                if (i < 0) { drainEncoder(info); continue }
                val buf = enc.getInputBuffer(i)!!
                buf.clear()
                val take = minOf(n - at, buf.capacity() / 2 / ch * ch)
                buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, at, take)
                val pts = framesOut * 1_000_000L / sr
                enc.queueInputBuffer(i, 0, take * 2, pts, 0)
                framesOut += take / ch
                at += take
                drainEncoder(info)
            }
        }

        private fun queueEncoderEnd() {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val i = enc.dequeueInputBuffer(2_000)
                if (i >= 0) {
                    enc.queueInputBuffer(i, 0, 0, framesOut * 1_000_000L / sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return
                }
                drainEncoder(info)
            }
        }

        /** Whatever the encoder has ready, into the file (without blocking). */
        private fun drainEncoder(info: MediaCodec.BufferInfo) {
            while (true) {
                val e = enc.dequeueOutputBuffer(info, 0)
                when {
                    e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxTrack < 0) { muxTrack = muxer!!.addTrack(enc.outputFormat); muxer!!.start() }
                    }
                    e >= 0 -> {
                        val b = enc.getOutputBuffer(e)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxTrack >= 0) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            muxer!!.writeSampleData(muxTrack, b, info)
                        }
                        enc.releaseOutputBuffer(e, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encEnded = true; return }
                    }
                    else -> return
                }
            }
        }
    }
}
