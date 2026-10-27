package com.ltots.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import com.ltots.app.ui.theme.DynamicColor
import com.ltots.app.ui.theme.ThemeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * The home-screen icon follows the app's colour: the same accent the UI takes from the song's
 * cover (or your own accent when the cover doesn't set it).
 *
 * Android can't repaint a launcher icon, so the app ships the icon in 20 colours (one every 18°
 * of hue, res/mipmap-anydpi-v26/ic_launcher_cNN) as activity aliases and switches which one is
 * enabled. Switching while LToTS is open can make some launchers close it, so it only happens
 * while the app is in the background: when you leave it, and on every song change while you're
 * away. Grey covers keep the original icon.
 *
 * The song's colour comes from the playback service ([onSong]), so it's known even while the
 * app's screen is asleep, and the next song in the queue is read ahead ([prefetch]), so its
 * icon is ready the moment it starts. Fast skipping waits for the song you stay on.
 */
object IconColor {
    const val COUNT = 20
    private const val DEFAULT = "com.ltots.app.LauncherDefault"
    /** Skips through the queue settle first; then a switch at most this often (the last one wins). */
    private const val SETTLE_MS = 800L
    private const val MIN_GAP_MS = 5_000L
    private const val CACHE = 300

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _enabled = MutableStateFlow(true)
    /** "Icon follows the song colour" (Profile > App settings). */
    val enabled: StateFlow<Boolean> = _enabled
    private val inFront = MutableStateFlow(false)
    private var lastSwitch = 0L
    private var pending: kotlinx.coroutines.Job? = null

    /** The playing song's cover colours (dark accent, light accent), from the playback service. */
    private val song = MutableStateFlow<Pair<Int, Int>?>(null)
    private var songId: String? = null
    /** Colours already worked out, by song: repeats and read-ahead songs are instant. */
    private val known = object : LinkedHashMap<String, Pair<Int, Int>?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>?>?) = size > CACHE
    }

    private fun prefs() = app.getSharedPreferences("icon_color", Context.MODE_PRIVATE)

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        app = context.applicationContext
        _enabled.value = prefs().getBoolean("enabled", true)
        // Song changes while LToTS is in the background.
        scope.launch {
            combine(song, DynamicColor.colors, ThemeState.config, inFront, _enabled) { _, _, _, front, _ -> front }
                .debounce(SETTLE_MS)
                .collect { front -> if (!front) schedule() }
        }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs().edit().putBoolean("enabled", on).apply()
        if (!on && !inFront.value) apply()
    }

    /** Called by the activity: true while it's on screen. Leaving the app swaps the icon. */
    fun onForeground(front: Boolean) {
        inFront.value = front
        if (!front) { pending?.cancel(); scope.launch { apply() } }
    }

    /** Switches now, or as soon as the short gap since the last switch has passed. */
    @Synchronized
    private fun schedule() {
        pending?.cancel()
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastSwitch)
        pending = scope.launch {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            apply()
        }
    }

    /**
     * From the playback service: song [id] is playing, with cover [artwork] (its embedded
     * picture, or null while it's still being read).
     */
    fun onSong(id: String?, artwork: ByteArray?) {
        if (!::app.isInitialized) return
        scope.launch {
            val colors = synchronized(known) { id?.let { if (known.containsKey(it)) known[it] else null } }
                ?: colorsOf(artwork)?.also { c -> if (id != null) synchronized(known) { known[id] = c } }
            // A song whose cover hasn't arrived yet keeps the last colour until it does.
            if (colors == null && artwork == null && id == songId) return@launch
            songId = id
            song.value = colors
        }
    }

    /** From the playback service: [id] plays next; work out its colour now. */
    fun prefetch(id: String, uri: android.net.Uri) {
        if (!::app.isInitialized || !_enabled.value) return
        if (synchronized(known) { known.containsKey(id) }) return
        scope.launch(Dispatchers.IO) {
            val pic = runCatching {
                android.media.MediaMetadataRetriever().use { r -> r.setDataSource(app, uri); r.embeddedPicture }
            }.getOrNull()
            val c = colorsOf(pic)
            synchronized(known) { known[id] = c }
        }
    }

    /** Dark and light accents of a cover image, read from a small copy of it. */
    private fun colorsOf(bytes: ByteArray?): Pair<Int, Int>? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 48 && bounds.outHeight / (sample * 2) >= 48) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, 48, 48, true)
            val px = IntArray(48 * 48)
            small.getPixels(px, 0, 48, 0, 0, 48, 48)
            if (small !== bmp) small.recycle()
            bmp.recycle()
            val c = DynamicColor.fromPixels(px, 48)
            c.accentDark to c.accentLight
        }.getOrNull()
    }

    /** The colour the UI shows for the playing song, as the theme computes it. */
    private fun currentAccent(): Int {
        val cfg = ThemeState.config.value
        if (!cfg.dynamicColors) return cfg.accent.toInt()
        // The service's reading is current even while the app's screen is asleep.
        song.value?.let { return if (cfg.darkMode) it.first else it.second }
        val cover = DynamicColor.colors.value
        return cover?.let { if (cfg.darkMode) it.accentDark else it.accentLight } ?: cfg.accent.toInt()
    }

    /** Which icon fits [argb]: 0..19, or -1 for the original (grey or near-grey colours). */
    fun variantFor(argb: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV((argb shr 16) and 0xff, (argb shr 8) and 0xff, argb and 0xff, hsv)
        return variantFor(hsv[0], hsv[1], hsv[2])
    }

    fun variantFor(hue: Float, saturation: Float, value: Float): Int {
        if (saturation < 0.18f || value < 0.12f) return -1
        val step = 360f / COUNT
        return (Math.round(((hue % 360f) + 360f) % 360f / step) % COUNT)
    }

    fun componentFor(variant: Int): String = if (variant < 0) DEFAULT else "com.ltots.app.LauncherC%02d".format(variant)

    @Synchronized
    private fun apply() {
        if (!::app.isInitialized || inFront.value) return
        val want = if (_enabled.value) componentFor(variantFor(currentAccent())) else DEFAULT
        val pm = app.packageManager
        val all = listOf(DEFAULT) + (0 until COUNT).map { componentFor(it) }
        val on = all.filter { runCatching { isEnabled(pm, it) }.getOrDefault(false) }
        if (on == listOf(want)) return
        runCatching {
            // The new one first, so there's never a moment with no icon at all.
            pm.setComponentEnabledSetting(ComponentName(app, want), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            for (c in on) if (c != want) {
                pm.setComponentEnabledSetting(ComponentName(app, c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            lastSwitch = System.currentTimeMillis()
        }
    }

    private fun isEnabled(pm: PackageManager, cls: String): Boolean =
        when (pm.getComponentEnabledSetting(ComponentName(app, cls))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> cls == DEFAULT // as in the manifest
            else -> false
        }
}
