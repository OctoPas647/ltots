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

data class EqPreset(
    val name: String,
    val eq: EqState,
    val builtIn: Boolean = false,
    /** Group shown in the EQ tab (built-in presets only). */
    val category: String = "",
    /** One line about what it does, shown when it's picked. */
    val hint: String = "",
)

enum class SpectrumStyle(val label: String) { BARS("Bars"), CURVE("Curve"), MIRROR("Mirror") }

/** How the spectrum runs around the cover. */
enum class RingLayout(val label: String) {
    /** One continuous loop: bass at the top, round clockwise to the treble. */
    LOOP("One loop"),
    /** Both sides the same: bass at the bottom, treble at the top. */
    MIRRORED("Mirrored"),
    /** Both sides the same, upside down: bass at the top, treble at the bottom. */
    MIRRORED_FLIPPED("Mirrored, flipped"),
}

/** Where the spectrum sits behind the full-size cover. */
enum class BehindLayout(val label: String) {
    /** Rising from the bottom, bass on the left (the original). */
    BELOW("From below"),
    /** Hanging down from the top, bass on the left. */
    ABOVE("From above"),
    /** Hanging down from the top, bass on the right. */
    ABOVE_FLIPPED("From above, flipped"),
    /** From below and from above at once; the top is a mirror image of the bottom (bass on the left on both). */
    BOTH_MIRRORED("Top & bottom, mirrored"),
    /** From below and from above at once; the top is the bottom turned round (bass on the right at the top). */
    BOTH("Top & bottom, not mirrored"),
    ;

    /** True when the spectrum shows at the top and the bottom at the same time. */
    val both: Boolean get() = this == BOTH_MIRRORED || this == BOTH
}

/** How much of the heavy visual work (glass, motion, visualizer rate) the app does. */
enum class Effects(val label: String) {
    /** Everything: the bars bend the page under them, real blur on every panel, all at full rate. */
    FULL("Full"),
    /**
     * Lighter: the bars bend the background instead of the whole page, panels bend without the
     * blur (the background is soft anyway), animations and visualizer at up to 60 fps.
     */
    BALANCED("Balanced"),
    /** Lightest: frosted panels with no blur or bending, animations and visualizer up to 30 fps. */
    LIGHT("Light"),
}

/** The Smoothness setting: let the app measure this phone (Automatic), or pick a level. */
/** Icon mode (Playing tab): how the icon's dark parts look. */
enum class IconFill(val label: String) {
    /** Left empty: the shape shows only through the bars round it. */
    DARK("Dark"),
    /** Frosted glass tinted with the accent (the play button's colour), using the Glass settings. */
    GLASS("Glass"),
    /** Solid accent, like the play button. */
    ACCENT("Accent"),
}

/** Icon mode: where the song's cover shows. */
enum class IconCover(val label: String) {
    OFF("Off"),
    /** Only inside the icon's dark parts. */
    INSIDE("Inside the icon"),
    /** The whole cover, full size, dimmed, behind the visualizer. */
    FULL("Full size"),
}

/** Icon mode: which way the bars grow from the outline of the dark parts. */
enum class IconBars(val label: String) { OUT("Outward"), IN("Inward"), BOTH("Both ways") }

/** Icon mode: what the icon's place shows while nothing is playing. */
enum class IconIdle(val label: String) { NOTHING("Show nothing"), SHAPE("Keep the icon"), COVER("Show the cover") }

enum class EffectsMode(val label: String) { AUTO("Automatic"), FULL("Full"), BALANCED("Balanced"), LIGHT("Light") }

/**
 * A colour as hue (0..360), saturation and brightness (0..1). Kept next to the colour itself so
 * the three sliders stay independent: rebuilding them from the colour loses the hue and
 * saturation of dark or grey colours, which made one slider move the others.
 */
data class Hsv(val h: Float, val s: Float, val v: Float) {
    /** Opaque ARGB as a Long (the form the theme stores colours in). */
    fun toArgb(): Long {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f); val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1 - kotlin.math.abs((hh / 60f) % 2f - 1))
        val m = vv - c
        val (r, g, b) = when ((hh / 60f).toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = Math.round((f + m) * 255f).coerceIn(0, 255).toLong()
        return (0xFFL shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    companion object {
        fun of(argb: Long): Hsv {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
            val h = when {
                d == 0f -> 0f
                max == r -> 60f * (((g - b) / d + 6f) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            return Hsv(h, if (max == 0f) 0f else d / max, max)
        }

        /** The stored [hsv] if it still describes [argb], otherwise worked out from [argb]. */
        fun resolve(argb: Long, hsv: Hsv?): Hsv = hsv?.takeIf { it.toArgb() == (argb and 0xFFFFFFFFL) } ?: of(argb)
    }
}

data class ThemeConfig(
    val accent: Long = 0xFF8B6CFF,
    /** The accent as hue/saturation/brightness, exactly as last set (see [Hsv]). */
    val accentHsv: Hsv? = null,
    /**
     * The background glow: one colour the moving background is made from (lighter and darker
     * shades of it drift around). Not shown on the AMOLED themes, and the song's cover takes its
     * place while cover colours are on.
     */
    val glow: Long = 0xFF5A30D8,
    val glowHsv: Hsv? = null,
    val animateBackground: Boolean = true,
    val animationSpeed: Float = 0.5f,
    /** Blend the current album art into the background (off unless you turn it on). */
    val artworkBackground: Boolean = false,
    val blurDp: Float = 22f,
    /** Edge refraction strength of the glass, 0..1. */
    val refraction: Float = 0.55f,
    /** How solid the song's cover is on the Playing tab, 0.1..1 (lower shows the spectrum behind it). */
    val coverOpacity: Float = 1f,
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
    val visualizerFps: Int = 120,
    /** Playing tab: the visualizer wraps around the (smaller) cover; off = behind the cover. */
    val spectrumAroundCover: Boolean = true,
    val ringLayout: RingLayout = RingLayout.LOOP,
    val behindLayout: BehindLayout = BehindLayout.BELOW,
    /** How finely the live spectrum splits the sound (number of bars). */
    val analyzerBands: Int = 96,
    /** Run the app at the screen's highest refresh rate (120 Hz) instead of letting the phone pick. */
    val maxRefresh: Boolean = true,
    /** Smoothness: how much glass and motion to draw (Automatic adapts to how fast this phone is). */
    val effects: EffectsMode = EffectsMode.AUTO,
    /** AMOLED: pure #000 background — no colour blobs or artwork glow, so black pixels stay off. */
    val pureBlack: Boolean = false,
    /** Hide Android's navigation bar inside the app (swipe up from the bottom edge to reveal it). */
    val hideNavBar: Boolean = true,
    /** Hide the status bar (clock, battery) too, for fully full-screen. */
    val hideStatusBar: Boolean = false,
    /** Accent follows the current song's cover: the AMOLED Cover theme. */
    val dynamicColors: Boolean = false,
    /**
     * Your own colour on the AMOLED themes, kept while you use another theme so that going back
     * to AMOLED brings it back (null = never changed: AMOLED's own cyan).
     */
    val amoledAccent: Long? = null,
    val amoledAccentHsv: Hsv? = null,
    /** Playing tab: the visualizer grows out of an icon's dark parts, in the cover's place. */
    val iconMode: Boolean = false,
    /** The picture used in icon mode: a file in the app's own storage (null = none picked yet). */
    val iconFile: String? = null,
    /** Pixels darker than this (0..1) count as the icon's dark parts. */
    val iconThreshold: Float = 0.5f,
    /** Swap black and white before tracing the icon. */
    val iconInvert: Boolean = false,
    /** Enclosed white holes smaller than this share of the picture are filled in (eye highlights). */
    val iconMinHole: Float = 0.001f,
    val iconFill: IconFill = IconFill.DARK,
    /** Where the song's cover shows: not at all, inside the dark parts, or full size behind. */
    val iconCover: IconCover = IconCover.OFF,
    /** How much darker the full-size cover is behind the visualizer, 0 (as is) .. 0.9. */
    val iconCoverDim: Float = 0.6f,
    val iconBars: IconBars = IconBars.OUT,
    val iconIdle: IconIdle = IconIdle.NOTHING,
    /** Keep the visualizer frozen as it was when the music stops, instead of letting it fall away. */
    val freezeVisualizer: Boolean = false,
    /**
     * Gradient flow (Playing tab): light around the cover in the colours of its edges, like a TV's
     * backlight. On AMOLED it melts into your own colour; on the other themes it fades out.
     */
    val gradientFlow: Boolean = false,
    /** Playing tab: the cover moves with the beat, hardest on drops and big bass moments. */
    val coverBeat: Boolean = false,
    /** How much the cover moves with the beat, 0..1. */
    val beatStrength: Float = 0.5f,
    /**
     * With the visualizer behind the cover: how solid the cover is where the bars pass behind it,
     * 0..1, on its own. The rest of the cover keeps [coverOpacity]. Null = the same as
     * [coverOpacity] (nothing different where the bars are).
     */
    val coverBarsOpacity: Float? = null,
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
    /**
     * Battery EQ (experimental): play a simplified version of the EQ on the phone's own built-in
     * EQ, so the audio chip can keep playing by itself with an EQ on, on phones that allow it.
     */
    val batteryEq: Boolean = false,
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
        put("accent", t.accent); put("gA", t.glow)
        put("anim", t.animateBackground); put("animSpeed", t.animationSpeed.toDouble())
        put("art", t.artworkBackground); put("blur", t.blurDp.toDouble())
        put("refr", t.refraction.toDouble()); put("coverAlpha", t.coverOpacity.toDouble())
        put("tint", t.glassTint.toDouble()); put("rim", t.rimLight.toDouble())
        put("corner", t.cornerDp.toDouble()); put("dark", t.darkMode)
        put("specStyle", t.spectrumStyle.name); put("specOpacity", t.spectrumOpacity.toDouble())
        put("ghost", t.showPreEqGhost); put("vfps", t.visualizerFps); put("vfpsV", 3)
        put("black", t.pureBlack); put("hideNav", t.hideNavBar); put("hideStatus", t.hideStatusBar)
        put("dyn", t.dynamicColors)
        t.accentHsv?.let { put("aHsv", hsvJson(it)) }
        t.glowHsv?.let { put("gHsv", hsvJson(it)) }
        put("bands", t.analyzerBands); put("maxHz", t.maxRefresh); put("wrap", t.spectrumAroundCover)
        put("ring", t.ringLayout.name); put("behind", t.behindLayout.name)
        put("fx", t.effects.name)
        t.amoledAccent?.let { put("amAccent", it) }
        t.amoledAccentHsv?.let { put("amHsv", hsvJson(it)) }
        put("icon", t.iconMode); t.iconFile?.let { put("iconFile", it) }
        put("iconThr", t.iconThreshold.toDouble()); put("iconInv", t.iconInvert)
        put("iconHole", t.iconMinHole.toDouble()); put("iconFill", t.iconFill.name)
        put("iconCoverMode", t.iconCover.name); put("iconDim", t.iconCoverDim.toDouble()); put("iconBars", t.iconBars.name); put("iconIdle", t.iconIdle.name)
        put("freeze", t.freezeVisualizer); put("gFlow", t.gradientFlow)
        put("beat", t.coverBeat); put("beatK", t.beatStrength.toDouble()); t.coverBarsOpacity?.let { put("coverBars", it.toDouble()) }
    }

    private fun hsvJson(h: Hsv) = JSONArray().put(h.h.toDouble()).put(h.s.toDouble()).put(h.v.toDouble())
    private fun hsvOf(a: JSONArray?): Hsv? =
        if (a == null || a.length() < 3) null else Hsv(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat())

    fun theme(o: JSONObject): ThemeConfig {
        val d = ThemeConfig()
        return ThemeConfig(
            accent = o.optLong("accent", d.accent),
            // Older saves had three glow colours (gA, gB, gC); the first one becomes the glow.
            glow = o.optLong("gA", d.glow),
            animateBackground = o.optBoolean("anim", d.animateBackground),
            animationSpeed = o.optDouble("animSpeed", d.animationSpeed.toDouble()).toFloat(),
            artworkBackground = o.optBoolean("art", d.artworkBackground),
            blurDp = o.optDouble("blur", d.blurDp.toDouble()).toFloat(),
            refraction = o.optDouble("refr", d.refraction.toDouble()).toFloat(),
            coverOpacity = o.optDouble("coverAlpha", d.coverOpacity.toDouble()).toFloat().coerceIn(0.1f, 1f),
            glassTint = o.optDouble("tint", d.glassTint.toDouble()).toFloat(),
            rimLight = o.optDouble("rim", d.rimLight.toDouble()).toFloat(),
            cornerDp = o.optDouble("corner", d.cornerDp.toDouble()).toFloat(),
            darkMode = o.optBoolean("dark", d.darkMode),
            spectrumStyle = runCatching { SpectrumStyle.valueOf(o.optString("specStyle")) }.getOrDefault(d.spectrumStyle),
            spectrumOpacity = o.optDouble("specOpacity", d.spectrumOpacity.toDouble()).toFloat(),
            showPreEqGhost = o.optBoolean("ghost", d.showPreEqGhost),
            visualizerFps = o.optInt("vfps", d.visualizerFps).let { if (it in listOf(0, 30, 60, 120)) it else d.visualizerFps }
                // 30 fps was the default before the app aimed for 120: saves from then move up once
                // (a rate picked after this is kept).
                .let { if (it == 30 && o.optInt("vfpsV", 0) < 3) 120 else it },
            analyzerBands = o.optInt("bands", d.analyzerBands).let { if (it in listOf(48, 96, 160)) it else d.analyzerBands },
            maxRefresh = o.optBoolean("maxHz", d.maxRefresh),
            spectrumAroundCover = o.optBoolean("wrap", d.spectrumAroundCover),
            ringLayout = runCatching { RingLayout.valueOf(o.optString("ring")) }.getOrDefault(d.ringLayout),
            behindLayout = runCatching { BehindLayout.valueOf(o.optString("behind")) }.getOrDefault(d.behindLayout),
            effects = runCatching { EffectsMode.valueOf(o.optString("fx")) }.getOrDefault(d.effects),
            accentHsv = hsvOf(o.optJSONArray("aHsv")),
            glowHsv = hsvOf(o.optJSONArray("gHsv")),
            pureBlack = o.optBoolean("black", d.pureBlack),
            hideNavBar = o.optBoolean("hideNav", d.hideNavBar),
            hideStatusBar = o.optBoolean("hideStatus", d.hideStatusBar),
            // Cover colours belong to the AMOLED Cover theme only (the separate switch is gone).
            dynamicColors = o.optBoolean("dyn", d.dynamicColors) && o.optBoolean("black", d.pureBlack),
            amoledAccent = if (o.has("amAccent")) o.optLong("amAccent") else null,
            amoledAccentHsv = hsvOf(o.optJSONArray("amHsv")),
            iconMode = o.optBoolean("icon", d.iconMode),
            iconFile = o.optString("iconFile", "").ifEmpty { null },
            iconThreshold = o.optDouble("iconThr", d.iconThreshold.toDouble()).toFloat().coerceIn(0f, 1f),
            iconInvert = o.optBoolean("iconInv", d.iconInvert),
            iconMinHole = o.optDouble("iconHole", d.iconMinHole.toDouble()).toFloat().coerceIn(0f, 0.05f),
            iconFill = runCatching { IconFill.valueOf(o.optString("iconFill")) }.getOrDefault(d.iconFill),
            // Older saves had an on/off "cover behind the icon" (inside the dark parts).
            iconCover = runCatching { IconCover.valueOf(o.optString("iconCoverMode")) }.getOrNull()
                ?: if (o.optBoolean("iconCover", false)) IconCover.INSIDE else d.iconCover,
            iconCoverDim = o.optDouble("iconDim", d.iconCoverDim.toDouble()).toFloat().coerceIn(0f, 0.9f),
            iconBars = runCatching { IconBars.valueOf(o.optString("iconBars")) }.getOrDefault(d.iconBars),
            iconIdle = runCatching { IconIdle.valueOf(o.optString("iconIdle")) }.getOrDefault(d.iconIdle),
            freezeVisualizer = o.optBoolean("freeze", d.freezeVisualizer),
            gradientFlow = o.optBoolean("gFlow", d.gradientFlow),
            coverBeat = o.optBoolean("beat", d.coverBeat),
            beatStrength = o.optDouble("beatK", d.beatStrength.toDouble()).toFloat().coerceIn(0f, 1f),
            // 100% in version 0.1.48 meant "off": the same as the rest of the cover.
            coverBarsOpacity = if (o.has("coverBars")) o.optDouble("coverBars").toFloat().coerceIn(0f, 1f).takeIf { it < 0.999f } else null,
        )
    }

    fun settings(s: AppSettings) = JSONObject().apply {
        put("bitPerfect", s.bitPerfectUsb)
        put("perSongPS", s.perSongPitchSpeed)
        put("bands", s.analyzerBands)
        put("formants", s.preserveFormants); put("effPitch", s.efficientPitch)
        put("gPitch", s.globalPitchCents); put("gSpeed", s.globalSpeed.toDouble()); put("gTape", s.globalTape)
        put("direct", s.directPlayback)
        put("batteryEq", s.batteryEq)
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
        batteryEq = o.optBoolean("batteryEq", false),
    )
}
