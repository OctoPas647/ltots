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
    fun loadTheme(): ThemeConfig? = read("theme.json")?.let { o ->
        val t = Json.theme(o)
        // "Visualizer detail" used to live in the Sound tab's settings; it's part of the look now.
        if (!o.has("bands")) t.copy(analyzerBands = loadSettings().analyzerBands) else t
    }
    fun saveTheme(t: ThemeConfig) = writeDebounced("theme.json") { Json.theme(t).toString() }

    fun loadSettings(): AppSettings = read("settings.json")?.let { Json.settings(it) } ?: AppSettings()
    fun saveSettings(s: AppSettings) = writeDebounced("settings.json") { Json.settings(s).toString() }

    fun loadDefaultEq(): EqState = read("default_eq.json")?.let { Json.eq(it) } ?: EqState()
    fun saveDefaultEq(e: EqState) = writeDebounced("default_eq.json") { Json.eq(e).toString() }

    // ---- Custom 1-5: Look profiles and EQ profiles ------------------------------------------

    /** The selected Look profile and the saved looks (null = never used); null before the first save. */
    fun loadLookProfiles(): Pair<Int, List<ThemeConfig?>>? = read("look_profiles.json")?.let { o ->
        val arr = o.optJSONArray("slots") ?: JSONArray()
        o.optInt("active", 0) to List(arr.length()) { i -> arr.optJSONObject(i)?.let { Json.theme(it) } }
    }

    fun saveLookProfiles(active: Int, slots: List<ThemeConfig?>) = writeDebounced("look_profiles.json") {
        val arr = JSONArray().apply { slots.forEach { t -> put(if (t != null) Json.theme(t) else JSONObject.NULL) } }
        JSONObject().put("active", active).put("slots", arr).toString()
    }

    /** Saved EQ profiles (null = empty slot). */
    fun loadEqProfiles(): List<EqState?> = read("eq_profiles.json")?.optJSONArray("slots")?.let { arr ->
        List(arr.length()) { i -> arr.optJSONObject(i)?.let { Json.eq(it) } }
    } ?: emptyList()

    fun saveEqProfiles(slots: List<EqState?>) = writeDebounced("eq_profiles.json") {
        val arr = JSONArray().apply { slots.forEach { e -> put(if (e != null) Json.eq(e) else JSONObject.NULL) } }
        JSONObject().put("slots", arr).toString()
    }

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

    /** Groups of built-in presets, in the order the EQ tab shows them. */
    val presetCategories = listOf("Basics", "Genres", "Voice & places", "Precise")

    val builtInPresets: List<EqPreset> by lazy {
        // Graphic-EQ shapes on the 10 standard bands (31 Hz … 16 kHz).
        fun shaped(vararg gains: Float): EqState {
            val bands = EqDefaults.tenBand().mapIndexed { i, b -> b.copy(gainDb = gains.getOrElse(i) { 0f }) }
            return EqState(bands = bands)
        }
        fun b(type: FilterType, f: Float, g: Float = 0f, q: Float = 0.71f) = EqBand(EqDefaults.newId(), type, f, g, q)
        fun p(cat: String, name: String, hint: String, eq: EqState) = EqPreset(name, eq, builtIn = true, category = cat, hint = hint)
        val basics = "Basics"; val genres = "Genres"; val places = "Voice & places"; val precise = "Precise"
        listOf(
            // ---- basics (the original presets keep their names and shapes) ----
            p(basics, "Flat", "No change — the song exactly as it was mixed.", EqState()),
            p(basics, "Bass boost", "More low end, mids and highs untouched.", shaped(6f, 5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f)),
            p(basics, "Bass reducer", "Less boom; good for bass-heavy earbuds.", shaped(-6f, -5f, -3.5f, -1.5f, 0f, 0f, 0f, 0f, 0f, 0f)),
            p(basics, "Bright", "More sparkle and detail up top.", shaped(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 4f, 4.5f)),
            p(basics, "Treble reducer", "Softer highs; tames harsh or sibilant recordings.", shaped(0f, 0f, 0f, 0f, 0f, -0.5f, -1.5f, -3f, -4.5f, -5.5f)),
            p(basics, "Warm", "Fuller lows, gently rolled-off highs.", shaped(3f, 3f, 2f, 1f, 0f, -0.5f, -1f, -1.5f, -2f, -2.5f)),
            p(basics, "V-shape", "Punchy lows and crisp highs, mids pulled back.", shaped(5f, 4f, 2f, 0f, -2f, -2f, 0f, 2f, 4f, 5f)),
            p(basics, "Loudness", "Keeps lows and highs present at low volume.", shaped(5f, 4f, 2f, 0f, -1f, -1f, 0f, 1.5f, 3f, 3.5f)),
            // ---- genres ----
            p(genres, "Rock", "Driving low end and bite on guitars and cymbals.", shaped(4f, 3f, 1.5f, -0.5f, -1.5f, -0.5f, 1.5f, 3f, 3.5f, 3.5f)),
            p(genres, "Pop", "Forward vocals and upper bass, smooth top.", shaped(-1f, 0f, 1.5f, 2.5f, 3f, 2f, 0.5f, -0.5f, -0.5f, -1f)),
            p(genres, "Hip-hop", "Deep 808s with clear vocals on top.", shaped(5f, 4.5f, 2.5f, 1f, -0.5f, -1f, 0.5f, 1f, 1.5f, 2f)),
            p(genres, "R&B", "Rich, smooth bass and silky highs.", shaped(4f, 5f, 3f, 0f, -1.5f, -0.5f, 1f, 1.5f, 2f, 2.5f)),
            p(genres, "Electronic", "Sub-bass weight and sharp highs.", shaped(5f, 4f, 1.5f, 0f, -1.5f, 0f, 1f, 2.5f, 4f, 4f)),
            p(genres, "Dance", "Kick-drum punch and open highs.", shaped(4.5f, 5f, 2.5f, 0f, 0f, -1f, -1f, 0f, 2.5f, 3f)),
            p(genres, "Metal", "Tight lows, scooped low-mids, aggressive presence.", shaped(4f, 3f, 0f, -1.5f, -2f, 0f, 2f, 3.5f, 3f, 2f)),
            p(genres, "Jazz", "Warm double bass, natural horns and cymbals.", shaped(3f, 2f, 1f, 1.5f, -1f, -1f, 0f, 1f, 2f, 3f)),
            p(genres, "Classical", "Wide and natural, a little extra air.", shaped(3f, 2.5f, 1.5f, 0.5f, 0f, 0f, 0f, 1.5f, 2.5f, 3f)),
            p(genres, "Acoustic", "Body on guitars and voices, clear strings.", shaped(3f, 3f, 2f, 0.5f, 1f, 1f, 2f, 2.5f, 2f, 1.5f)),
            p(genres, "Piano", "Fuller middle register and clear attack.", shaped(1f, 0.5f, 0f, 1.5f, 2f, 1f, 2.5f, 3f, 2f, 1.5f)),
            p(genres, "Latin", "Percussion snap and bright brass.", shaped(2f, 1f, 0f, 0f, -1f, -1f, -1f, 0f, 2.5f, 3.5f)),
            p(genres, "Lo-fi", "Dusty, warm and dark — tape-like.", shaped(2f, 2.5f, 2f, 1f, 0f, -1f, -2f, -3.5f, -5f, -6f)),
            // ---- voice & places ----
            p(places, "Vocal", "Brings singers forward.", shaped(-2f, -1.5f, -1f, 0f, 1.5f, 3f, 3f, 1.5f, 0f, -1f)),
            p(places, "Podcast", "Clear speech, less rumble and hiss.", EqState(bands = listOf(
                b(FilterType.HIGH_PASS, 80f), b(FilterType.PEAK, 250f, -2f, 1f), b(FilterType.PEAK, 3000f, 3f, 0.9f), b(FilterType.HIGH_SHELF, 10000f, -2f),
            ))),
            p(places, "Late night", "Less boom and sharpness so you can listen quietly.", shaped(-3f, -2f, -1f, 0f, 0.5f, 1f, 1f, 0f, -1f, -2f)),
            p(places, "Phone speaker", "Skips bass the speaker can't play; clearer mids.", shaped(-8f, -6f, -2f, 1f, 2f, 2f, 1.5f, 1f, 0f, 0f)),
            p(places, "In the car", "Cuts the road rumble, lifts the vocals.", shaped(-2f, 0f, 1f, 0f, -1f, 1f, 2f, 2f, 1f, 0f)),
            p(places, "Workout", "Big beat and energy.", shaped(5f, 4f, 2f, 0f, -1f, 0f, 1f, 2f, 3f, 3f)),
            p(places, "Focus", "Calm and even, nothing jumps out.", shaped(1f, 1f, 0.5f, 0f, 0f, -0.5f, -1.5f, -2f, -2f, -2f)),
            // ---- precise (parametric) ----
            p(precise, "Smooth shelves", "Gentle low and high shelves with a dip at 3 kHz.", EqState(bands = listOf(
                b(FilterType.HIGH_PASS, 18f), b(FilterType.LOW_SHELF, 105f, 3f, 0.7f), b(FilterType.PEAK, 3200f, -1.5f, 1.4f), b(FilterType.HIGH_SHELF, 9000f, 1.5f, 0.7f),
            ))),
            p(precise, "Deep sub-bass", "A shelf under 60 Hz: weight you feel, mids untouched.", EqState(bands = listOf(
                b(FilterType.HIGH_PASS, 18f), b(FilterType.LOW_SHELF, 60f, 6f, 0.7f),
            ))),
            p(precise, "Air", "A high shelf from 11 kHz for openness.", EqState(bands = listOf(b(FilterType.HIGH_SHELF, 11000f, 4f, 0.7f)))),
            p(precise, "Clarity", "Less muddiness at 250 Hz, more presence at 3 kHz.", EqState(bands = listOf(
                b(FilterType.PEAK, 250f, -2.5f, 1f), b(FilterType.PEAK, 3000f, 2f, 1f), b(FilterType.HIGH_SHELF, 10000f, 1.5f, 0.7f),
            ))),
            p(precise, "De-ess", "Narrow cut at 7 kHz for harsh 's' sounds.", EqState(bands = listOf(b(FilterType.PEAK, 7000f, -4f, 4f)))),
            p(precise, "Rumble filter", "Removes inaudible sub-sonic energy below 30 Hz.", EqState(bands = listOf(b(FilterType.HIGH_PASS, 30f, q = 0.71f)))),
        )
    }
}
