package com.ltots.app.lyrics

import android.content.ContentUris
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * On-device AI that times plain lyrics: a speech recognizer (Vosk, fully offline) listens to the
 * song, told to expect only the words of its lyrics, and [LyricAlign] lines what it heard up with
 * the lyrics. The result is saved like time-synced lyrics, so it's done once per song.
 *
 * The recognizer needs a speech model (English, about 40 MB), downloaded once on request.
 */
object AiSync {
    private const val MODEL = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL.zip"
    /** Shown before downloading. */
    const val MODEL_SIZE = "40 MB"
    private const val RATE = 16_000

    sealed interface Status {
        data object Idle : Status
        data class DownloadingModel(val progress: Float) : Status
        /** Listening to song [key]; [progress] 0..1. */
        data class Listening(val key: String, val progress: Float) : Status
        data class Failed(val key: String?, val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status
    private val _modelReady = MutableStateFlow(false)
    val modelReady: StateFlow<Boolean> = _modelReady

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    /** Songs waiting or being timed. */
    private val pending = HashSet<String>()
    /** Songs tried this run that couldn't be synced (not tried again until the app restarts). */
    private val failed = HashSet<String>()

    private fun dir(context: Context) = File(context.filesDir, "ltots/ai")
    private fun modelDir(context: Context) = File(dir(context), MODEL)

    fun init(context: Context) {
        _modelReady.value = File(modelDir(context), "conf").exists() || File(modelDir(context), "am").exists()
    }

    /** Removes the speech model (frees its space); it can be downloaded again. */
    fun removeModel(context: Context) {
        dir(context).deleteRecursively()
        _modelReady.value = false
    }

    /** Downloads and unpacks the speech model. Returns true when it's ready. */
    suspend fun downloadModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (_modelReady.value) return@withContext true
        _status.value = Status.DownloadingModel(0f)
        val ok = runCatching {
            val d = dir(context).apply { deleteRecursively(); mkdirs() }
            val zip = File(d, "model.zip")
            val c = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: 41_000_000L
            c.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); got += n
                        _status.value = Status.DownloadingModel((got.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
            }
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val f = File(d, e.name)
                    if (!f.canonicalPath.startsWith(d.canonicalPath + File.separator)) continue // stay inside the folder
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                }
            }
            zip.delete()
            File(modelDir(context), "am").exists()
        }.getOrDefault(false)
        _modelReady.value = ok
        _status.value = if (ok) Status.Idle else Status.Failed(null, "Couldn't download the speech model. Check your connection and try again.")
        ok
    }

    /**
     * Times the plain lyrics of song [key] (file [trackId]) in the background and saves them.
     * Downloads the model first when [download] allows. Does nothing for a song already tried
     * this run unless [again].
     */
    fun request(context: Context, key: String, trackId: Long, doc: LyricsDoc, durationMs: Long, download: Boolean = false, again: Boolean = false) {
        val app = context.applicationContext
        if (!again && synchronized(failed) { key in failed }) return
        if (!synchronized(pending) { pending.add(key) }) return
        scope.launch {
            try {
                if (!_modelReady.value && !(download && downloadModel(app))) return@launch
                // One song at a time ([sync] waits its turn).
                val lines = runCatching { sync(app, key, trackId, doc, durationMs) }.getOrNull()
                if (lines == null) {
                    synchronized(failed) { failed += key }
                    _status.value = Status.Failed(key, "Couldn't make out enough of the words to time these lyrics (the speech model is English).")
                } else _status.value = Status.Idle
            } finally {
                synchronized(pending) { pending -= key }
            }
        }
    }

    /**
     * Listens to the song and saves the timed lyrics. Returns the lines, or null when too little
     * could be matched (then nothing is saved). Used by the background preparation too.
     */
    suspend fun sync(context: Context, key: String, trackId: Long, doc: LyricsDoc, durationMs: Long): List<LyricLine>? = lock.withLock {
        val plain = doc.plain?.takeIf { it.isNotBlank() } ?: return null
        if (!_modelReady.value) return null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), trackId)
        _status.value = Status.Listening(key, 0f)
        try {
            val heard = withContext(Dispatchers.Default) { listen(context, uri, plain, durationMs) { p -> _status.value = Status.Listening(key, p) } }
            val result = LyricAlign.align(plain, heard, durationMs) ?: return null
            Lyrics.saveAiSynced(key, doc, result.lines)
            result.lines
        } finally {
            if (_status.value is Status.Listening) _status.value = Status.Idle
        }
    }

    /** Runs the recognizer over the whole song; the words it heard, in time order. */
    private suspend fun listen(context: Context, uri: Uri, plain: String, durationMs: Long, onProgress: (Float) -> Unit): List<HeardWord> {
        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
        val model = org.vosk.Model(modelDir(context).absolutePath)
        // Only the lyrics' words (plus "unknown" for everything else): far fewer ways to mishear.
        val grammar = JSONArray().apply { LyricAlign.vocabulary(plain).forEach { put(it) }; put("[unk]") }.toString()
        val rec = org.vosk.Recognizer(model, RATE.toFloat(), grammar)
        val heard = ArrayList<HeardWord>()
        fun collect(json: String) {
            val arr = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: continue
                heard += HeardWord(w.optString("word"), (w.optDouble("start") * 1000).toLong(), (w.optDouble("end") * 1000).toLong())
            }
        }
        try {
            rec.setWords(true)
            var fed = 0L
            val total = max(1L, durationMs * RATE / 1000)
            decode16k(context, uri) { chunk, n ->
                coroutineContext.ensureActive()
                if (rec.acceptWaveForm(chunk, n)) collect(rec.result)
                fed += n
                onProgress((fed.toFloat() / total).coerceIn(0f, 1f))
            }
            collect(rec.finalResult)
        } finally {
            rec.close(); model.close()
        }
        return heard.sortedBy { it.startMs }
    }

    /** Decodes [uri] to 16 kHz mono 16-bit, handing it over in chunks. */
    private suspend fun decode16k(context: Context, uri: Uri, onChunk: suspend (ShortArray, Int) -> Unit) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val out = ShortArray(8192); var outN = 0
            // Linear resampling to 16 kHz: [pos] is where the next output sample falls in the input.
            var pos = 0.0; var prev = 0f; var index = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false; var idle = 0
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    o >= 0 -> {
                        idle = 0
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset).limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val ch = max(1, channels)
                        val step = rate.toDouble() / RATE
                        val floats = encoding == AudioFormat.ENCODING_PCM_FLOAT
                        val fb = if (floats) buf.asFloatBuffer() else null
                        val sb = if (floats) null else buf.asShortBuffer()
                        val frames = (fb?.remaining() ?: sb!!.remaining()) / ch
                        for (f in 0 until frames) {
                            var m = 0f
                            for (c in 0 until ch) m += fb?.get() ?: (sb!!.get() / 32768f)
                            m /= ch
                            // Output every 16 kHz sample that falls between the previous input sample and this one.
                            while (pos <= index) {
                                val t = (pos - (index - 1)).toFloat().coerceIn(0f, 1f)
                                val v = prev + (m - prev) * t
                                out[outN++] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                if (outN == out.size) { onChunk(out, outN); outN = 0 }
                                pos += step
                            }
                            prev = m; index++
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    else -> if (inputDone && ++idle > 300) outputDone = true
                }
            }
            if (outN > 0) onChunk(out, outN)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
