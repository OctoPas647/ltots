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
 *
 * Everything that belongs to a person (looks, EQs, settings, each song's own EQ, pitch and speed)
 * lives in that user's own folder, `users/<id>/`; things about the phone (Smoothness) and the
 * list of users stay at the top.
 */
object Store {
    private lateinit var root: File
    /** The folder of the user in use. */
    @Volatile private lateinit var userDir: File
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Pending(val file: File, val content: () -> String, val job: Job)
    private val pending = mutableMapOf<String, Pending>()
    private val profileCache = HashMap<String, SongProfile?>()

    /** Files that make up one user, besides the per-song profiles in `profiles/`. */
    val userFiles = listOf("theme.json", "settings.json", "default_eq.json", "look_profiles.json", "eq_profiles.json", "presets.json")

    fun init(context: Context) {
        root = File(context.filesDir, "ltots").apply { mkdirs() }
        val users = File(root, "users")
        if (!File(root, "users.json").exists() && !users.exists()) migrateToFirstUser()
        userDir = File(users, Users.loadActiveId(root)).apply { mkdirs() }
        File(userDir, "profiles").mkdirs()
    }

    /** The app's own folder (where the user list and the users' folders are). */
    val rootDir: File get() = root

    /** The folder of user [id]. */
    fun dirOf(id: String): File = File(File(root, "users"), id)

    /**
     * Before users existed everything lay at the top: it becomes the first user's, untouched.
     */
    private fun migrateToFirstUser() {
        val dir = File(File(root, "users"), Users.FIRST_ID).apply { mkdirs() }
        for (name in userFiles + "profiles") {
            val f = File(root, name)
            if (f.exists()) f.renameTo(File(dir, name))
        }
    }

    /**
     * Uses user [id]'s folder from now on. Changes still waiting to be saved go to the folder
     * they were made in.
     */
    fun useUser(id: String) {
        flush()
        userDir = dirOf(id).apply { mkdirs() }
        File(userDir, "profiles").mkdirs()
        synchronized(profileCache) { profileCache.clear() }
    }

    /** Writes every change still waiting, right now. */
    fun flush() {
        val all = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        for (p in all) {
            p.job.cancel()
            runCatching { writeNow(p.file, p.content()) }
        }
    }

    // ---- generic helpers -------------------------------------------------------------------

    private fun read(name: String): JSONObject? = readFile(File(userDir, name))

    private fun readFile(f: File): JSONObject? = runCatching {
        if (f.exists()) JSONObject(f.readText()) else null
    }.getOrNull()

    private fun writeNow(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp" + System.nanoTime())
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun writeDebounced(name: String, content: () -> String) = writeDebounced(File(userDir, name), content)

    private fun writeDebounced(f: File, content: () -> String) {
        synchronized(pending) {
            pending[f.path]?.job?.cancel()
            lateinit var job: Job
            job = io.launch {
                delay(350)
                writeNow(f, content())
                synchronized(pending) { if (pending[f.path]?.job === job) pending.remove(f.path) }
            }
            pending[f.path] = Pending(f, content, job)
        }
    }

    private fun deleteFile(name: String) {
        val f = File(userDir, name)
        synchronized(pending) { pending.remove(f.path)?.job?.cancel() }
        io.launch { f.delete() }
    }

    /** Writes [text] to [file] straight away (users' files, imports). */
    fun writeFileNow(file: File, text: String) = writeNow(file, text)

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

    // ---- named Look profiles and EQ profiles (up to 10 each) --------------------------------

    /**
     * The selected Look profile and the saved looks with their names; null before the first save.
     * Older saves (five fixed slots, some never used) keep the used ones, named Custom 1-5.
     */
    fun loadLookProfiles(): Pair<Int, List<Pair<String, ThemeConfig>>>? = read("look_profiles.json")?.let { o ->
        val items = o.optJSONArray("items")
        if (items != null) {
            val list = (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                val look = it.optJSONObject("look") ?: return@mapNotNull null
                it.optString("name", "").ifBlank { "Custom ${i + 1}" } to Json.theme(look)
            }
            o.optInt("active", 0) to list
        } else {
            val arr = o.optJSONArray("slots") ?: JSONArray()
            val oldActive = o.optInt("active", 0)
            var active = 0
            val list = ArrayList<Pair<String, ThemeConfig>>()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.let { Json.theme(it) } ?: continue
                if (i == oldActive) active = list.size
                list.add("Custom ${i + 1}" to t)
            }
            active to list
        }
    }

    fun saveLookProfiles(active: Int, items: List<Pair<String, ThemeConfig>>) = writeDebounced("look_profiles.json") {
        val arr = JSONArray().apply { items.forEach { (n, t) -> put(JSONObject().put("name", n).put("look", Json.theme(t))) } }
        JSONObject().put("v", 2).put("active", active).put("items", arr).toString()
    }

    /** Saved EQ profiles with their names, and the one picked last (null = none). */
    fun loadEqProfiles(): Pair<Int?, List<Pair<String, EqState>>> = read("eq_profiles.json")?.let { o ->
        val items = o.optJSONArray("items")
        if (items != null) {
            val list = (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                val eq = it.optJSONObject("eq") ?: return@mapNotNull null
                it.optString("name", "").ifBlank { "Custom ${i + 1}" } to Json.eq(eq)
            }
            o.optInt("active", -1).takeIf { it in list.indices } to list
        } else {
            // Older saves: five fixed slots (null = empty).
            val arr = o.optJSONArray("slots") ?: JSONArray()
            val oldActive = o.optInt("active", -1)
            var active: Int? = null
            val list = ArrayList<Pair<String, EqState>>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i)?.let { Json.eq(it) } ?: continue
                if (i == oldActive) active = list.size
                list.add("Custom ${i + 1}" to e)
            }
            active to list
        }
    } ?: (null to emptyList())

    fun saveEqProfiles(items: List<Pair<String, EqState>>, active: Int?) = writeDebounced("eq_profiles.json") {
        val arr = JSONArray().apply { items.forEach { (n, e) -> put(JSONObject().put("name", n).put("eq", Json.eq(e))) } }
        JSONObject().put("v", 2).put("items", arr).put("active", active ?: -1).toString()
    }

    // ---- smoothness (per phone) --------------------------------------------------------------

    /**
     * What Smoothness > Automatic settled on for this phone. Tied to the phone model, so a backup
     * restored onto another phone measures again instead of inheriting the old phone's result.
     */
    fun loadSmoothness(): String? = readFile(File(root, "smoothness.json"))?.let { o ->
        if (o.optString("device") == android.os.Build.MODEL) o.optString("auto", "FULL") else null
    }

    fun saveSmoothness(auto: String) = writeDebounced(File(root, "smoothness.json")) {
        JSONObject().put("auto", auto).put("device", android.os.Build.MODEL).toString()
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
        val f = File(userDir, "presets.json")
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
