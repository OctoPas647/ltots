package com.ltots.app.moods

import android.content.Context
import com.ltots.app.data.Store
import com.ltots.app.data.Track
import com.ltots.app.identify.AiModels
import com.ltots.app.identify.ClipLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the mood model heard in each song, kept on the phone so each song is listened to once.
 * Three 10-second moments (a quarter, half and three quarters through) are averaged, so a
 * quiet intro or a breakdown doesn't decide the whole song.
 */
object MoodIndex {
    private val data = HashMap<String, FloatArray>()
    private var loaded = false
    private val lock = Mutex()

    private val _embeddings = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    /** CLAP embeddings by song ([Track.profileKey]). */
    val embeddings: StateFlow<Map<String, FloatArray>> = _embeddings

    data class Progress(val done: Int, val total: Int)
    private val _progress = MutableStateFlow<Progress?>(null)
    /** Set while songs are being listened to. */
    val progress: StateFlow<Progress?> = _progress

    @Volatile private var text: ClapText? = null

    private fun file() = File(Store.rootDir, "moods.bin")

    /** Loads what's saved (once). */
    fun load() {
        synchronized(data) {
            if (loaded) return
            loaded = true
            runCatching {
                DataInputStream(file().inputStream().buffered()).use { inp ->
                    val n = inp.readInt()
                    repeat(n) {
                        val key = inp.readUTF()
                        val dim = inp.readInt()
                        data[key] = FloatArray(dim) { inp.readFloat() }
                    }
                }
            }
            _embeddings.value = HashMap(data)
        }
    }

    private fun save() {
        val copy = synchronized(data) { HashMap(data) }
        runCatching {
            val tmp = File(file().path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(copy.size)
                for ((k, v) in copy) { out.writeUTF(k); out.writeInt(v.size); for (x in v) out.writeFloat(x) }
            }
            tmp.renameTo(file())
        }
    }

    fun missing(tracks: List<Track>): List<Track> {
        load()
        return synchronized(data) { tracks.filter { it.profileKey !in data } }.distinctBy { it.profileKey }
    }

    /** The themes' and album names' side of CLAP, from the app's assets. */
    fun text(context: Context): ClapText = text ?: synchronized(this) {
        text ?: runCatching {
            val o = JSONObject(context.assets.open("clap_text.json").bufferedReader().readText())
            fun vec(b64: String): FloatArray {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
            val th = o.getJSONArray("themes").let { a -> List(a.length()) { a.getJSONObject(it) } }
                .map { Prompt(it.getString("id"), it.getString("label"), vec(it.getString("vec"))) }
            val al = o.getJSONArray("albums").let { a -> List(a.length()) { a.getJSONObject(it) } }
                .map { val l = it.getString("label"); Prompt(l.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), l, vec(it.getString("vec"))) }
            ClapText(th, al)
        }.getOrElse { ClapText(emptyList(), emptyList()) }.also { text = it }
    }

    /**
     * Listens to every song in [tracks] that hasn't been listened to yet. Needs the mood model
     * downloaded. [keepGoing] is asked between songs (e.g. still charging).
     */
    suspend fun analyse(context: Context, tracks: List<Track>, keepGoing: () -> Boolean = { true }) = withContext(Dispatchers.Default) {
        if (!AiModels.isInstalled(AiModels.Model.MOODS)) return@withContext
        lock.withLock {
            val todo = missing(tracks)
            if (todo.isEmpty()) return@withLock
            _progress.value = Progress(0, todo.size)
            try {
                Clap(AiModels.file(AiModels.Model.MOODS)).use { clap ->
                    todo.forEachIndexed { i, t ->
                        currentCoroutineContext().ensureActive()
                        if (!keepGoing()) return@use
                        val e = runCatching { listen(context, clap, t) }.getOrNull()
                        // A song that can't be read gets an empty entry, so it isn't retried forever.
                        synchronized(data) { data[t.profileKey] = e ?: FloatArray(0) }
                        _progress.value = Progress(i + 1, todo.size)
                        if (i % 10 == 9) { publish(); save() }
                    }
                }
            } finally {
                publish(); save()
                _progress.value = null
            }
        }
    }

    private fun publish() {
        _embeddings.value = synchronized(data) { data.filterValues { it.isNotEmpty() } }
    }

    private suspend fun listen(context: Context, clap: Clap, t: Track): FloatArray? {
        val dur = t.durationMs / 1000.0
        val points = if (dur > 40) listOf(0.25, 0.5, 0.75).map { dur * it - 5 } else listOf(0.0)
        val sum = FloatArray(512)
        var n = 0
        for (start in points) {
            val pcm = ClipLoader.decode(context, t.uri, maxSeconds = 10, startSec = start.coerceAtLeast(0.0), outRate = Clap.RATE, normalized = false)
            if (pcm.size < Clap.RATE * 2) continue
            val e = clap.embed(if (pcm.size > Clap.SAMPLES) pcm.copyOf(Clap.SAMPLES) else pcm)
            for (j in e.indices) sum[j] += e[j]
            n++
        }
        return if (n == 0) null else Clap.normalize(sum)
    }
}
