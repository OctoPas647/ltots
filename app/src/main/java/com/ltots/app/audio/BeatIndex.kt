package com.ltots.app.audio

import android.content.Context
import android.net.Uri
import android.os.Process
import android.util.LruCache
import com.ltots.app.identify.AiModels
import com.ltots.app.identify.ClipLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * The beats of each song, worked out once and kept on the phone (like the waveforms), so the
 * cover moves exactly on the beat from the first second:
 *  - the playing song and the next two in the queue are prepared as soon as a song starts, so
 *    the next one is ready before it plays;
 *  - the rest of the library while the phone charges (see LibraryPrep);
 *  - with the beat AI downloaded, beats found the classic way are redone by the AI.
 * A song that isn't ready yet is done the fast classic way first (well under a second), then by
 * the AI if it's there.
 */
object BeatIndex {
    private const val MAGIC = 0x4C544254 // "LTBT"
    private const val VERSION = 1
    /** Longest audio looked at (an hour-long mix only gets its first 30 minutes). */
    private const val MAX_SECONDS = 30 * 60

    private lateinit var root: File
    private val memory = LruCache<String, BeatMap>(8)
    private val lock = Mutex()
    private val thread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, "ltots-beats").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private val pending = HashSet<String>()

    private val _ready = MutableStateFlow(0)
    /** Goes up whenever a song's beats become ready. */
    val ready: StateFlow<Int> = _ready

    fun init(context: Context) {
        root = File(context.filesDir, "ltots/beats").apply { mkdirs() }
    }

    val aiInstalled: Boolean
        get() = AiModels.isInstalled(AiModels.Model.BEAT_NET) && AiModels.isInstalled(AiModels.Model.BEAT_MEL)

    private fun key(uri: Uri) = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(uri: Uri) = File(root, key(uri) + ".beats")

    /** The song's beats if they're in memory (cheap, for every frame). */
    fun peek(uri: Uri): BeatMap? = memory.get(uri.toString())

    /** Whether the song is done — by the AI when [byAi]. */
    fun has(uri: Uri, byAi: Boolean = false): Boolean = (peek(uri) ?: load(uri))?.let { !byAi || it.byAi } ?: false

    fun load(uri: Uri): BeatMap? {
        memory.get(uri.toString())?.let { return it }
        val f = file(uri)
        if (!f.exists()) return null
        val m = runCatching {
            DataInputStream(f.inputStream().buffered()).use { s ->
                require(s.readInt() == MAGIC && s.readInt() == VERSION)
                val byAi = s.readBoolean()
                val n = s.readInt()
                fun floats() = FloatArray(n) { s.readFloat() }
                val times = floats(); val song = floats(); val lows = floats(); val mids = floats(); val highs = floats()
                val down = BooleanArray(n) { s.readBoolean() }
                BeatMap(times, song, lows, mids, highs, down, byAi)
            }
        }.getOrNull()
        if (m == null) { f.delete(); return null }
        memory.put(uri.toString(), m)
        return m
    }

    private fun save(uri: Uri, m: BeatMap) {
        val f = file(uri)
        val tmp = File(root, f.name + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { s ->
            s.writeInt(MAGIC); s.writeInt(VERSION); s.writeBoolean(m.byAi); s.writeInt(m.size)
            for (a in listOf(m.times, m.song, m.lows, m.mids, m.highs)) for (v in a) s.writeFloat(v)
            for (d in m.downbeat) s.writeBoolean(d)
        }
        tmp.renameTo(f)
    }

    /**
     * Prepares [uris] in the background, in order (the playing song first): all of them the fast
     * classic way first, so each is ready before it plays, then the AI pass when it's there.
     */
    fun request(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        for (useAi in listOf(false, true)) {
            if (useAi && !aiInstalled) break
            for (u in uris) {
                val tag = "$u|$useAi"
                synchronized(pending) { if (!pending.add(tag)) continue }
                scope.launch {
                    try { build(app, u, useAi) } finally { synchronized(pending) { pending.remove(tag) } }
                }
            }
        }
    }

    /**
     * Works out [uri]'s beats if they aren't there yet — and, with [useAi] and the AI downloaded,
     * redoes classic ones with the AI. Runs on the caller's coroutine; use [request] from the UI.
     */
    suspend fun build(context: Context, uri: Uri, useAi: Boolean = true): BeatMap? = lock.withLock {
        val have = load(uri)
        val ai = useAi && aiInstalled
        if (have != null && (have.byAi || !ai)) return have
        withContext(thread) {
            val pcm = runCatching {
                ClipLoader.decode(context, uri, maxSeconds = MAX_SECONDS, outRate = BeatTracker.RATE, normalized = false)
            }.getOrNull() ?: return@withContext have
            var map = have
            if (map == null) {
                map = runCatching { BeatTracker.classic(pcm) }.getOrNull()
                if (map != null) publish(uri, map)
            }
            if (ai) {
                val better = runCatching {
                    BeatTracker.Ai(AiModels.file(AiModels.Model.BEAT_MEL), AiModels.file(AiModels.Model.BEAT_NET)).use { BeatTracker.withAi(it, pcm) }
                }.getOrNull()
                if (better != null) { publish(uri, better); map = better }
            }
            map
        }
    }

    private fun publish(uri: Uri, m: BeatMap) {
        save(uri, m)
        memory.put(uri.toString(), m)
        _ready.value++
    }
}
