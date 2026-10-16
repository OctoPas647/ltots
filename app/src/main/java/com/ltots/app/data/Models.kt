package com.ltots.app.data

import com.ltots.app.audio.EqBand
import com.ltots.app.audio.EqDefaults
import com.ltots.app.audio.EqState
import com.ltots.app.audio.FilterType
import org.json.JSONArray
import org.json.JSONObject

/** Everything LToTS remembers about one song. Null fields fall back to the global defaults. */
data class SongProfile(
    val eq: EqState? = null,
    /** Pitch shift in cents (100 cents = 1 semitone). */
    val pitchCents: Int? = null,
    val speed: Float? = null,
    /** Speed and pitch linked like a record player. */
    val tape: Boolean? = null,
) {
    val isEmpty get() = eq == null && pitchCents == null && speed == null && tape == null
}

data class EqPreset(val name: String, val eq: EqState, val builtIn: Boolean = false)

enum class SpectrumStyle(val label: String) { BARS("Bars"), CURVE("Curve"), MIRROR("Mirror") }

data class ThemeConfig(
    val accent: Long = 0xFF8B6CFF,
    val gradientA: Long = 0xFF2A1260,
    val gradientB: Long = 0xFF0B3D63,
    val gradientC: Long = 0xFF5B1048,
    val animateBackground: Boolean = true,
    val animationSpeed: Float = 0.5f,
    /** Blend the current album art into the background. */
    val artworkBackground: Boolean = true,
    val blurDp: Float = 22f,
    /** Edge refraction strength of the glass, 0..1. */
    val refraction: Float = 0.55f,
    val chromaticAberration: Boolean = false,
    /** Tint over the glass, 0..1 (how frosted it looks). */
    val glassTint: Float = 0.14f,
    /** Brightness of the specular rim light, 0..1. */
    val rimLight: Float = 0.7f,
    val cornerDp: Float = 28f,
    val darkMode: Boolean = true,
    val spectrumStyle: SpectrumStyle = SpectrumStyle.CURVE,
    val spectrumOpacity: Float = 0.85f,
    val showPreEqGhost: Boolean = true,
    /** Visualizer refresh rate in frames per second (0 = off). Lower uses less battery. */
    val visualizerFps: Int = 60,
    /** AMOLED: pure #000 background — no colour blobs or artwork glow, so black pixels stay off. */
    val pureBlack: Boolean = false,
    /** Hide Android's navigation bar inside the app (swipe up from the bottom edge to reveal it). */
    val hideNavBar: Boolean = true,
    /** Hide the status bar (clock, battery) too, for fully full-screen. */
    val hideStatusBar: Boolean = false,
    /** Accent and background glow follow the current song's cover (like Spotify / YT Music). */
    val dynamicColors: Boolean = true,
)

data class AppSettings(
    /** Bypass Android's mixer on USB DACs (Android 14+). */
    val bitPerfectUsb: Boolean = false,
    /** Remember pitch & speed per song (otherwise they reset on every new song). */
    val perSongPitchSpeed: Boolean = true,
    val analyzerBands: Int = 96,
    /** Keep voices sounding natural when shifting pitch (off = classic key change). */
    val preserveFormants: Boolean = false,
    /** Use Rubber Band's lighter engine for pitch/speed (about half the battery, slightly less polish). */
    val efficientPitch: Boolean = false,
    /** Pitch/speed used for every song when [perSongPitchSpeed] is off. */
    val globalPitchCents: Int = 0,
    val globalSpeed: Float = 1f,
    val globalTape: Boolean = false,
    /**
     * When nothing needs processing (EQ off or flat, original pitch and speed), let the phone's
     * audio chip decode and play the music so the main processor can sleep.
     */
    val directPlayback: Boolean = true,
)

/** Hand-written JSON (de)serialisation — no reflection, so it survives R8 untouched. */
object Json {
    fun eq(e: EqState) = JSONObject().apply {
        put("enabled", e.enabled)
        put("preamp", e.preampDb.toDouble())
        put("auto", e.autoHeadroom)
        put("bands", JSONArray().apply {
            e.bands.forEach { b ->
                put(JSONObject().apply {
                    put("type", b.type.name)
                    put("f", b.freqHz.toDouble())
                    put("g", b.gainDb.toDouble())
                    put("q", b.q.toDouble())
                    put("on", b.enabled)
                })
            }
        })
    }

    fun eq(o: JSONObject): EqState {
        val arr = o.optJSONArray("bands") ?: JSONArray()
        val bands = List(arr.length()) { i ->
            val b = arr.getJSONObject(i)
            EqBand(
                id = EqDefaults.newId(),
                type = runCatching { FilterType.valueOf(b.optString("type")) }.getOrDefault(FilterType.PEAK),
                freqHz = b.optDouble("f", 1000.0).toFloat(),
                gainDb = b.optDouble("g", 0.0).toFloat(),
                q = b.optDouble("q", 1.0).toFloat(),
                enabled = b.optBoolean("on", true),
            )
        }
        return EqState(
            enabled = o.optBoolean("enabled", true),
            bands = bands,
            preampDb = o.optDouble("preamp", 0.0).toFloat(),
            autoHeadroom = o.optBoolean("auto", true),
        )
    }

    fun profile(p: SongProfile) = JSONObject().apply {
        p.eq?.let { put("eq", eq(it)) }
        p.pitchCents?.let { put("pitch", it) }
        p.speed?.let { put("speed", it.toDouble()) }
        p.tape?.let { put("tape", it) }
    }

    fun profile(o: JSONObject) = SongProfile(
        eq = o.optJSONObject("eq")?.let { eq(it) },
        pitchCents = if (o.has("pitch")) o.getInt("pitch") else null,
        speed = if (o.has("speed")) o.getDouble("speed").toFloat() else null,
        tape = if (o.has("tape")) o.getBoolean("tape") else null,
    )

    fun theme(t: ThemeConfig) = JSONObject().apply {
        put("accent", t.accent); put("gA", t.gradientA); put("gB", t.gradientB); put("gC", t.gradientC)
        put("anim", t.animateBackground); put("animSpeed", t.animationSpeed.toDouble())
        put("art", t.artworkBackground); put("blur", t.blurDp.toDouble())
        put("refr", t.refraction.toDouble()); put("chroma", t.chromaticAberration)
        put("tint", t.glassTint.toDouble()); put("rim", t.rimLight.toDouble())
        put("corner", t.cornerDp.toDouble()); put("dark", t.darkMode)
        put("specStyle", t.spectrumStyle.name); put("specOpacity", t.spectrumOpacity.toDouble())
        put("ghost", t.showPreEqGhost); put("vfps", t.visualizerFps)
        put("black", t.pureBlack); put("hideNav", t.hideNavBar); put("hideStatus", t.hideStatusBar)
        put("dyn", t.dynamicColors)
    }

    fun theme(o: JSONObject): ThemeConfig {
        val d = ThemeConfig()
        return ThemeConfig(
            accent = o.optLong("accent", d.accent),
            gradientA = o.optLong("gA", d.gradientA),
            gradientB = o.optLong("gB", d.gradientB),
            gradientC = o.optLong("gC", d.gradientC),
            animateBackground = o.optBoolean("anim", d.animateBackground),
            animationSpeed = o.optDouble("animSpeed", d.animationSpeed.toDouble()).toFloat(),
            artworkBackground = o.optBoolean("art", d.artworkBackground),
            blurDp = o.optDouble("blur", d.blurDp.toDouble()).toFloat(),
            refraction = o.optDouble("refr", d.refraction.toDouble()).toFloat(),
            chromaticAberration = o.optBoolean("chroma", d.chromaticAberration),
            glassTint = o.optDouble("tint", d.glassTint.toDouble()).toFloat(),
            rimLight = o.optDouble("rim", d.rimLight.toDouble()).toFloat(),
            cornerDp = o.optDouble("corner", d.cornerDp.toDouble()).toFloat(),
            darkMode = o.optBoolean("dark", d.darkMode),
            spectrumStyle = runCatching { SpectrumStyle.valueOf(o.optString("specStyle")) }.getOrDefault(d.spectrumStyle),
            spectrumOpacity = o.optDouble("specOpacity", d.spectrumOpacity.toDouble()).toFloat(),
            showPreEqGhost = o.optBoolean("ghost", d.showPreEqGhost),
            visualizerFps = o.optInt("vfps", d.visualizerFps).let { if (it in listOf(0, 30, 60, 120)) it else d.visualizerFps },
            pureBlack = o.optBoolean("black", d.pureBlack),
            hideNavBar = o.optBoolean("hideNav", d.hideNavBar),
            hideStatusBar = o.optBoolean("hideStatus", d.hideStatusBar),
            dynamicColors = o.optBoolean("dyn", d.dynamicColors),
        )
    }

    fun settings(s: AppSettings) = JSONObject().apply {
        put("bitPerfect", s.bitPerfectUsb)
        put("perSongPS", s.perSongPitchSpeed)
        put("bands", s.analyzerBands)
        put("formants", s.preserveFormants); put("effPitch", s.efficientPitch)
        put("gPitch", s.globalPitchCents); put("gSpeed", s.globalSpeed.toDouble()); put("gTape", s.globalTape)
        put("direct", s.directPlayback)
    }

    fun settings(o: JSONObject) = AppSettings(
        bitPerfectUsb = o.optBoolean("bitPerfect", false),
        perSongPitchSpeed = o.optBoolean("perSongPS", true),
        analyzerBands = o.optInt("bands", 96),
        preserveFormants = o.optBoolean("formants", false),
        efficientPitch = o.optBoolean("effPitch", false),
        globalPitchCents = o.optInt("gPitch", 0),
        globalSpeed = o.optDouble("gSpeed", 1.0).toFloat(),
        globalTape = o.optBoolean("gTape", false),
        directPlayback = o.optBoolean("direct", true),
    )
}
