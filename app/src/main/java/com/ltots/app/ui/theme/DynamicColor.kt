package com.ltots.app.ui.theme

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** Colours taken from the current song's cover. */
data class CoverColors(
    /** Plain average of the whole cover (ARGB). */
    val average: Int,
    /** The average, lifted so it works as an accent on dark (or light) backgrounds. */
    val accentDark: Int,
    val accentLight: Int,
    /** Deep shades of the top, overall and bottom of the cover, for the background glow. */
    val glowTop: Int,
    val glowMid: Int,
    val glowBottom: Int,
)

/**
 * Spotify / YouTube Music style colours: the accent follows the average colour of the whole
 * cover. A raw average is often muddy, so it keeps the cover's hue and gets its brightness and
 * saturation lifted just enough to read as an accent.
 */
object DynamicColor {
    private val _colors = MutableStateFlow<CoverColors?>(null)
    val colors: StateFlow<CoverColors?> = _colors
    private var lastBitmap: Bitmap? = null

    suspend fun onArtwork(bitmap: Bitmap?) {
        if (bitmap === lastBitmap) return
        lastBitmap = bitmap
        if (bitmap == null) { _colors.value = null; return }
        _colors.value = withContext(Dispatchers.Default) {
            runCatching {
                val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
                val px = IntArray(48 * 48)
                small.getPixels(px, 0, 48, 0, 0, 48, 48)
                if (small !== bitmap) small.recycle()
                fromPixels(px, 48)
            }.getOrNull()
        }
    }

    // ---- pure colour maths (unit-tested) ---------------------------------------------------

    private fun toLinear(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    private fun toSrgb(l: Double): Int {
        val v = if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1 / 2.4) - 0.055
        return (v * 255).toInt().coerceIn(0, 255)
    }

    /** Average colour of [pixels] (ARGB), averaged in linear light so it matches what you see. */
    fun average(pixels: IntArray, from: Int = 0, to: Int = pixels.size): Int {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (i in from until to) {
            val p = pixels[i]
            if ((p ushr 24) < 16) continue // skip transparent
            r += toLinear((p shr 16) and 0xFF); g += toLinear((p shr 8) and 0xFF); b += toLinear(p and 0xFF); n++
        }
        if (n == 0) return 0xFF808080.toInt()
        return (0xFF shl 24) or (toSrgb(r / n) shl 16) or (toSrgb(g / n) shl 8) or toSrgb(b / n)
    }

    fun rgbToHsv(c: Int): FloatArray {
        val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d).mod(6f))
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f).mod(2f) - 1))
        val m = v - c
        val (r, g, b) = when ((h.mod(360f) / 60f).toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** Turns an average colour into an accent with enough brightness and colour. */
    fun liftForAccent(avg: Int, dark: Boolean): Int {
        val (h, s, v) = rgbToHsv(avg).let { Triple(it[0], it[1], it[2]) }
        val grey = s < 0.10f
        val sat = if (grey) s else (s * 1.35f).coerceIn(0.40f, 0.85f)
        val value = if (dark) v.coerceIn(0.80f, 1f) else v.coerceIn(0.38f, 0.55f)
        return hsvToRgb(h, sat, value)
    }

    private fun deep(avg: Int, v: Float): Int {
        val hsv = rgbToHsv(avg)
        return hsvToRgb(hsv[0], (hsv[1] * 1.1f).coerceIn(0f, 0.9f), v)
    }

    fun fromPixels(px: IntArray, width: Int): CoverColors {
        val avg = average(px)
        val half = (px.size / width / 2) * width
        val top = average(px, 0, half)
        val bottom = average(px, half, px.size)
        return CoverColors(
            average = avg,
            accentDark = liftForAccent(avg, dark = true),
            accentLight = liftForAccent(avg, dark = false),
            glowTop = deep(top, 0.42f),
            glowMid = deep(avg, 0.30f),
            glowBottom = deep(bottom, 0.36f),
        )
    }
}
