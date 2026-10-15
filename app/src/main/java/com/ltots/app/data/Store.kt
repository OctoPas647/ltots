package com.ltots.app.data

import android.content.Context
import com.ltots.app.audio.EqBand
import com.ltots.app.audio.EqDefaults
import com.ltots.app.audio.EqState
import com.ltots.app.audio.FilterType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Small JSON-file store in the app's private storage. Reads are synchronous (tiny files);
 * writes are debounced on a background thread so dragging a slider doesn't hammer the disk.
 */
object Store {
    private lateinit var root: File
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = mutableMapOf<String, Job>()
    private val profileCache = HashMap<String, SongProfile?>()

    fun init(context: Context) {
        root = File(context.filesDir, "ltots").apply { mkdirs() }
        File(root, "profiles").mkdirs()
    }

    // ---- generic helpers -------------------------------------------------------------------

    private fun read(name: String): JSONObject? = runCatching {
        val f = File(root, name)
        if (f.exists()) JSONObject(f.readText()) else null
    }.getOrNull()

    private fun writeDebounced(name: String, content: () -> String) {
        synchronized(pending) {
            pending[name]?.cancel()
            pending[name] = io.launch {
                delay(350)
                val f = File(root, name)
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(content())
                tmp.renameTo(f)
            }
        }
    }

    private fun deleteFile(name: String) {
        synchronized(pending) { pending.remove(name)?.cancel() }
        io.launch { File(root, name).delete() }
    }

    // ---- theme / settings / default EQ -----------------------------------------------------

    /** The saved theme, or null if none has been saved yet. */
    fun loadTheme(): ThemeConfig? = read("theme.json")?.let { Json.theme(it) }
    fun saveTheme(t: ThemeConfig) = writeDebounced("theme.json") { Json.theme(t).toString() }

    fun loadSettings(): AppSettings = read("settings.json")?.let { Json.settings(it) } ?: AppSettings()
    fun saveSettings(s: AppSettings) = writeDebounced("settings.json") { Json.settings(s).toString() }

    fun loadDefaultEq(): EqState = read("default_eq.json")?.let { Json.eq(it) } ?: EqState()
    fun saveDefaultEq(e: EqState) = writeDebounced("default_eq.json") { Json.eq(e).toString() }

    // ---- per-song profiles -----------------------------------------------------------------

    private fun profileFile(key: String): String {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "profiles/$hash.json"
    }

    /** Thread-safe (also called from the audio thread); "no profile" is cached too. */
    fun loadProfile(key: String): SongProfile? = synchronized(profileCache) {
        if (profileCache.containsKey(key)) return profileCache[key]
        val p = read(profileFile(key))?.let { Json.profile(it) }
        profileCache[key] = p
        p
    }

    fun saveProfile(key: String, p: SongProfile?) {
        val empty = p == null || p.isEmpty
        synchronized(profileCache) { profileCache[key] = if (empty) null else p }
        if (empty) deleteFile(profileFile(key))
        else writeDebounced(profileFile(key)) { Json.profile(p).toString() }
    }

    // ---- presets ---------------------------------------------------------------------------

    fun loadUserPresets(): List<EqPreset> = runCatching {
        val f = File(root, "presets.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            EqPreset(o.getString("name"), Json.eq(o.getJSONObject("eq")))
        }
    }.getOrDefault(emptyList())

    fun saveUserPresets(list: List<EqPreset>) = writeDebounced("presets.json") {
        JSONArray().apply {
            list.forEach { p -> put(JSONObject().put("name", p.name).put("eq", Json.eq(p.eq))) }
        }.toString()
    }

    val builtInPresets: List<EqPreset> by lazy {
        fun shaped(vararg gains: Float): EqState {
            val bands = EqDefaults.tenBand().mapIndexed { i, b -> b.copy(gainDb = gains.getOrElse(i) { 0f }) }
            return EqState(bands = bands)
        }
        listOf(
            EqPreset("Flat", EqState(), builtIn = true),
            EqPreset("Bass boost", shaped(6f, 5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f), builtIn = true),
            EqPreset("Warm", shaped(3f, 3f, 2f, 1f, 0f, -0.5f, -1f, -1.5f, -2f, -2.5f), builtIn = true),
            EqPreset("Vocal", shaped(-2f, -1.5f, -1f, 0f, 1.5f, 3f, 3f, 1.5f, 0f, -1f), builtIn = true),
            EqPreset("Bright", shaped(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 4f, 4.5f), builtIn = true),
            EqPreset("V-shape", shaped(5f, 4f, 2f, 0f, -2f, -2f, 0f, 2f, 4f, 5f), builtIn = true),
            EqPreset(
                "Smooth shelves",
                EqState(
                    bands = listOf(
                        EqBand(EqDefaults.newId(), FilterType.HIGH_PASS, 18f, q = 0.71f),
                        EqBand(EqDefaults.newId(), FilterType.LOW_SHELF, 105f, 3f, 0.7f),
                        EqBand(EqDefaults.newId(), FilterType.PEAK, 3200f, -1.5f, 1.4f),
                        EqBand(EqDefaults.newId(), FilterType.HIGH_SHELF, 9000f, 1.5f, 0.7f),
                    )
                ),
                builtIn = true,
            ),
        )
    }
}
