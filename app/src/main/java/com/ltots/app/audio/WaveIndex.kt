package com.ltots.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.util.LruCache
import com.ltots.app.data.AppVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Wave(val colRate: Int, val durationMs: Long, val lo: ByteArray, val hi: ByteArray) {
    val columns: Int get() = lo.size

    fun fill(endMs: Long, spanMs: Long, outMin: FloatArray, outMax: FloatArray) {
        val n = outMin.size
        val startCol = (endMs - spanMs) * colRate / 1000.0
        val perOut = spanMs * colRate / 1000.0 / n
        for (i in 0 until n) {
            val a = startCol + i * perOut
            val c0 = Math.floor(a).toLong()
            var c1 = Math.floor(a + perOut).toLong()
            if (c1 <= c0) c1 = c0 + 1
            var mn = 0; var mx = 0
            var c = max(c0, 0L)
            val end = min(c1, columns.toLong())
            while (c < end) {
                val ci = c.toInt()
                if (lo[ci] < mn) mn = lo[ci].toInt()
                if (hi[ci] > mx) mx = hi[ci].toInt()
                c++
            }
            outMin[i] = mn / 127f
            outMax[i] = mx / 127f
        }
    }
}

class WaveBuilder(private val sampleRate: Int, private val colRate: Int = WaveIndex.COL_RATE) {
    private var lo = ByteArray(1024)
    private var hi = ByteArray(1024)
    private var cols = 0
    private var curMin = 0f
    private var curMax = 0f
    private var colStart = 0L
    private var frames = 0L

    fun add(v: Float) {
        if (v < curMin) curMin = v
        if (v > curMax) curMax = v
        frames++
        val colEnd = (cols + 1).toLong() * sampleRate / colRate
        if (frames >= colEnd) push()
    }

    private fun push() {
        if (cols == lo.size) { lo = lo.copyOf(cols * 2); hi = hi.copyOf(cols * 2) }
        lo[cols] = (curMin * 127f).roundToInt().coerceIn(-127, 127).toByte()
        hi[cols] = (curMax * 127f).roundToInt().coerceIn(-127, 127).toByte()
        cols++
        curMin = 0f; curMax = 0f
        colStart = frames
    }

    fun finish(): Wave {
        if (frames > colStart) push()
        val durationMs = frames * 1000 / sampleRate
        return Wave(colRate, durationMs, lo.copyOf(cols), hi.copyOf(cols))
    }
}

object WaveIndex {
    const val COL_RATE = 100
    private const val MAGIC = 0x4C545756
    private const val VERSION = 1

    private lateinit var root: File
    private val memory = LruCache<String, Wave>(6)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val buildLock = Mutex()
    private val decodeThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, "ltots-wave-index").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val pending = HashSet<String>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _ready = MutableStateFlow(0)
    val ready: StateFlow<Int> = _ready

    fun init(context: Context) {
        root = File(context.filesDir, "ltots/waves").apply { mkdirs() }
        scope.launch {
            AppVisibility.foreground.collect { visible ->
                if (!visible) cancelPending()
            }
        }
    }

    private fun cancelPending() {
        synchronized(pending) { pending.clear() }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun key(uri: Uri): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun file(uri: Uri) = File(root, key(uri) + ".wave")

    fun has(uri: Uri): Boolean = memory.get(uri.toString()) != null || file(uri).exists()

    fun peek(uri: Uri): Wave? = memory.get(uri.toString())

    fun load(uri: Uri): Wave? {
        memory.get(uri.toString())?.let { return it }
        val f = file(uri)
        if (!f.exists()) return null
        val w = runCatching {
            DataInputStream(f.inputStream().buffered()).use { s ->
                require(s.readInt() == MAGIC && s.readInt() == VERSION)
                val rate = s.readInt()
                val dur = s.readLong()
                val n = s.readInt()
                val lo = ByteArray(n).also { s.readFully(it) }
                val hi = ByteArray(n).also { s.readFully(it) }
                Wave(rate, dur, lo, hi)
            }
        }.getOrNull()
        if (w == null) { f.delete(); return null }
        memory.put(uri.toString(), w)
        return w
    }

    private fun save(uri: Uri, w: Wave) {
        val f = file(uri)
        val tmp = File(root, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { s ->
            s.writeInt(MAGIC); s.writeInt(VERSION); s.writeInt(w.colRate); s.writeLong(w.durationMs)
            s.writeInt(w.columns); s.write(w.lo); s.write(w.hi)
        }
        tmp.renameTo(f)
    }

    fun request(context: Context, uris: List<Uri>) {
        if (!AppVisibility.foreground.value) return
        val app = context.applicationContext
        for (u in uris) {
            if (!AppVisibility.foreground.value) return
            synchronized(pending) { if (!pending.add(u.toString())) continue }
            val job = scope.launch {
                try {
                    if (!AppVisibility.foreground.value) return@launch
                    if (load(u) == null) build(app, u)
                    _ready.value++
                } finally {
                    synchronized(pending) { pending.remove(u.toString()) }
                    jobs.remove(u.toString())
                }
            }
            jobs[u.toString()] = job
        }
    }

    suspend fun build(context: Context, uri: Uri): Wave? = buildLock.withLock {
        if (!AppVisibility.foreground.value) return null
        load(uri)?.let { return it }
        val w = withContext(decodeThread) { runCatching { decode(context, uri) }.getOrNull() } ?: return null
        if (!AppVisibility.foreground.value) return null
        save(uri, w)
        memory.put(uri.toString(), w)
        w
    }

    private fun decode(context: Context, uri: Uri): Wave? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            runCatching { fmt.setInteger(MediaFormat.KEY_PRIORITY, 1) }
            codec.configure(fmt, null, null, 0)
            codec.start()

            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var builder: WaveBuilder? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                if (!AppVisibility.foreground.value) return null
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    o >= 0 -> {
                        idle = 0
                        val b = builder ?: WaveBuilder(rate).also { builder = it }
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset).limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val ch = max(1, channels)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            for (f in 0 until frames) {
                                var m = 0f
                                for (c in 0 until ch) m += fb.get()
                                b.add(m / ch)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            val frames = sb.remaining() / ch
                            for (f in 0 until frames) {
                                var m = 0
                                for (c in 0 until ch) m += sb.get()
                                b.add(m / (32768f * ch))
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    else -> if (inputDone && ++idle > 300) outputDone = true
                }
            }
            return builder?.finish()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    fun countReady(uris: List<Uri>): Int = uris.count { has(it) }
}
