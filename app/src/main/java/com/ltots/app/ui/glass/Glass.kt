package com.ltots.app.ui.glass

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * True for glass that floats over the page (the bars at the bottom): it bends the page scrolling
 * under it, so it always does the real blur/refraction, also on the AMOLED themes.
 */
val LocalGlassOverContent = staticCompositionLocalOf { false }

/** The layer everything glass refracts. Provided by the app root. */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Default glass shape, following the user's corner-roundness setting. */
@Composable
fun glassShape(cornerDp: Dp? = null): RoundedCornerShape {
    val cfg = LocalThemeConfig.current
    return RoundedCornerShape(cornerDp ?: cfg.cornerDp.dp)
}

/**
 * Liquid glass: the backdrop behind this element is blurred, colour-boosted and bent at the
 * edges like a thick lens, with a light rim and soft shadow. Every knob comes from the theme.
 *
 * @param strength scales blur/refraction (use < 1 for small controls).
 * @param tintBoost extra tint for panels that need more contrast behind text.
 * @param refract false: no bending at the edges (for shapes that aren't rounded rectangles, such
 *   as an icon's outline, where the lens would look wrong).
 */
@Composable
fun Modifier.glass(
    shape: Shape = glassShape(),
    strength: Float = 1f,
    tintBoost: Float = 0f,
    tint: Color? = null,
    refract: Boolean = true,
): Modifier {
    val backdrop = LocalBackdrop.current
    val overContent = LocalGlassOverContent.current
    val cfg = LocalThemeConfig.current
    val pal = LocalPalette.current
    val surface = (tint ?: pal.glassTint).copy(alpha = (cfg.glassTint + tintBoost).coerceIn(0f, 0.95f))
    if (backdrop == null) return this.clip(shape).background(surface)
    val level = com.ltots.app.ui.LocalEffects.current
    if (level == com.ltots.app.data.Effects.LIGHT) {
        // Smoothness: Light. Plain frosted panels, no graphics-chip effects at all. The bars over
        // the page get a solid base so the page doesn't show through them.
        val base = if (overContent) Modifier.background(pageBase(cfg.pureBlack, pal.dark).copy(alpha = 0.92f)) else Modifier
        return this.clip(shape).then(base).background(surface)
    }
    // Balanced: bend without the blur. What's behind is the soft moving background (the bars
    // aren't over the page in Balanced), which looks nearly the same blurred or not.
    val withBlur = level == com.ltots.app.data.Effects.FULL
    // In Full the bars bend the page itself; otherwise they sit on the background like the panels.
    val bendsPage = overContent && withBlur

    // The glass library only redraws its glass when the glass is rebuilt, not when the values we
    // hand it change (that's why Frost / tint, Blur, Refraction and the play button's cover colour
    // used to wait until Corner roundness changed or you switched tabs). So the values that can
    // change are kept in a state that the glass reads while drawing: any change redraws it at once.
    val look = rememberUpdatedState(
        GlassLook(
            surface = surface,
            // AMOLED: what's behind the glass is plain black, and blurring, bending or boosting
            // black still gives black. So that work is skipped on the graphics chip entirely and
            // the black is painted directly; it looks exactly the same (rim light and shadow stay).
            plainBlack = cfg.pureBlack && !bendsPage,
            blurDp = if (withBlur) cfg.blurDp * strength else 0f,
            refraction = if (refract) cfg.refraction * strength else 0f,
            rimLight = cfg.rimLight,
            shadowAlpha = if (pal.dark) 0.22f else 0.08f,
        )
    )
    val effects: BackdropEffectScope.() -> Unit = remember(look) {
        {
            val l = look.value
            if (!l.plainBlack) {
                vibrancy()
                if (l.blurDp > 0.01f) blur(l.blurDp.dp.toPx())
                if (l.refraction > 0.01f) {
                    val minSide = size.minDimension
                    lens(
                        refractionHeight = (18.dp.toPx() * l.refraction).coerceAtMost(minSide / 2f),
                        refractionAmount = (40.dp.toPx() * l.refraction).coerceAtMost(minSide),
                        depthEffect = true,
                    )
                }
            }
        }
    }
    val onDrawSurface: DrawScope.() -> Unit = remember(look) {
        {
            val l = look.value
            if (l.plainBlack) drawRect(Color.Black)
            drawRect(l.surface)
        }
    }
    val highlight = remember(look) { { look.value.rimLight.let { if (it > 0.01f) Highlight.Default.copy(alpha = it) else null } } }
    val shadow = remember(look) { { Shadow(radius = 20.dp, color = Color.Black.copy(alpha = look.value.shadowAlpha)) } }
    val shapeOf = remember(shape) { { shape } }
    val source = if (cfg.pureBlack && !bendsPage) remember { emptyBackdrop() } else backdrop
    return this.drawBackdrop(
        backdrop = source,
        shape = shapeOf,
        effects = effects,
        highlight = highlight,
        shadow = shadow,
        onDrawSurface = onDrawSurface,
    )
}

/** The colour of the page itself under everything (pure black on the AMOLED themes). */
fun pageBase(pureBlack: Boolean, dark: Boolean): Color = when {
    pureBlack -> Color.Black
    dark -> Color(0xFF07060C)
    else -> Color(0xFFF4F2FA)
}

/** Everything about the glass that can change while it is on screen. */
private data class GlassLook(
    val surface: Color,
    val plainBlack: Boolean,
    val blurDp: Float,
    val refraction: Float,
    val rimLight: Float,
    val shadowAlpha: Float,
)

/** A glass panel with content. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = glassShape(),
    strength: Float = 1f,
    tintBoost: Float = 0f,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    // The glass sits behind the content as its own layer. The glass library keeps the clip shape
    // it was created with, so it is rebuilt whenever the corner shape changes (Corner roundness):
    // the corners then always follow the setting. The content gets an ordinary clip.
    Box(modifier, propagateMinConstraints = true) {
        key(shape) { Box(Modifier.matchParentSize().glass(shape, strength, tintBoost)) }
        Box(Modifier.clip(shape), contentAlignment = contentAlignment, content = content)
    }
}

/** Round glass button. */
@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val pal = LocalPalette.current
    Box(
        modifier
            .glass(CircleShape, strength = 0.7f)
            .then(if (highlighted) Modifier.background(pal.accent, CircleShape) else Modifier)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * The moving colour field behind everything: three soft gradient blobs drifting slowly, with
 * the album art melted in when enabled.
 */
@Composable
fun AppBackground(artwork: Bitmap?, modifier: Modifier = Modifier) {
    val cfg = LocalThemeConfig.current
    val pal = LocalPalette.current
    // The glow (Look tab), or the song's cover colours while cover colours are on.
    val (a, b, c) = pal.glows
    val base = pageBase(cfg.pureBlack, pal.dark)
    if (cfg.pureBlack) {
        // AMOLED: true black only, so the screen's pixels stay switched off behind the glass.
        Box(modifier.background(Color.Black))
        return
    }

    // The colour blobs (and the album-art glow) drift with every frame the screen shows, so the
    // motion is smooth. Only while the app is on screen, and not with Battery Saver on;
    // "Moving colours" off stops it completely. Smoothness: Balanced moves it up to 60 times a
    // second, Light keeps it still (every move re-renders the glass on top of it).
    val phase = remember { androidx.compose.runtime.mutableFloatStateOf(0.8f) }
    val saver by com.ltots.app.ui.rememberBatterySaver()
    val effects = com.ltots.app.ui.LocalEffects.current
    if (backgroundMoves(cfg, saver, effects)) {
        val minFrameNs = if (effects == com.ltots.app.data.Effects.BALANCED) 16_000_000L else 0L
        com.ltots.app.ui.WhileOnScreen(cfg.animationSpeed, minFrameNs) {
            val periodNs = motionPeriodNs(cfg.animationSpeed)
            var last = 0L
            while (true) {
                if (minFrameNs > 0 && last != 0L) {
                    val wait = (last + minFrameNs - System.nanoTime()) / 1_000_000L
                    if (wait > 1) kotlinx.coroutines.delay(wait - 1)
                }
                androidx.compose.animation.core.withInfiniteAnimationFrameNanos { now ->
                    if (last != 0L) {
                        val step = (now - last).coerceAtMost(100_000_000L) / periodNs * TWO_PI
                        phase.floatValue = (phase.floatValue + step) % TWO_PI
                    }
                    last = now
                }
            }
        }
    }

    Box(modifier.background(base)) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val t = phase.floatValue // read while drawing: a new phase only redraws, never recomposes
            val w = size.width; val h = size.height; val m = size.maxDimension
            blob(a, Offset(w * (0.3f + 0.28f * cos(t)), h * (0.25f + 0.18f * sin(t * 2))), m * 0.75f)
            blob(b, Offset(w * (0.75f + 0.25f * sin(t + 1f)), h * (0.55f + 0.22f * cos(t))), m * 0.7f)
            blob(c, Offset(w * (0.4f + 0.3f * sin(t * 1.5f + 2f)), h * (0.85f + 0.15f * cos(t + 3f))), m * 0.65f)
        }
        if (cfg.artworkBackground && artwork != null) {
            // A tiny, already-blurred copy of the cover, scaled up smoothly: looks the same as a
            // big blur but costs almost nothing to draw, every frame, under every piece of glass.
            val glow by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, artwork) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { softGlow(artwork).asImageBitmap() }.getOrNull()
                }
            }
            glow?.let { img ->
                Image(
                    img, null,
                    Modifier.fillMaxSize()
                        // Drifts and breathes with the colours (moved as a layer: no redrawing).
                        .graphicsLayer {
                            val t = phase.floatValue
                            val k = 1.25f + 0.06f * sin(t * 2)
                            scaleX = k; scaleY = k
                            translationX = size.width * 0.09f * cos(t)
                            translationY = size.height * 0.05f * sin(t + 1f)
                        }
                        .alpha(if (pal.dark) 0.5f else 0.35f),
                    contentScale = ContentScale.Crop,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                )
            }
        }
        // Gentle vignette so text on glass always reads well.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(base.copy(alpha = 0.05f), base.copy(alpha = 0.25f), base.copy(alpha = 0.55f))
                )
            )
        )
    }
}


/** The cover shrunk to 40×40 and box-blurred (3 passes ≈ Gaussian), for the background glow. */
private fun softGlow(src: Bitmap): Bitmap {
    val n = 40
    val small = Bitmap.createScaledBitmap(src, n, n, true)
    val px = IntArray(n * n)
    small.getPixels(px, 0, n, 0, 0, n, n)
    if (small !== src) small.recycle()
    val r = 4
    val tmp = IntArray(n * n)
    repeat(3) {
        boxPass(px, tmp, n, r, horizontal = true)
        boxPass(tmp, px, n, r, horizontal = false)
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
}

private fun boxPass(src: IntArray, dst: IntArray, n: Int, r: Int, horizontal: Boolean) {
    for (line in 0 until n) {
        for (i in 0 until n) {
            var a = 0; var rr = 0; var g = 0; var b = 0; var c = 0
            for (k in -r..r) {
                val j = (i + k).coerceIn(0, n - 1)
                val p = if (horizontal) src[line * n + j] else src[j * n + line]
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; c++
            }
            val v = ((a / c) shl 24) or ((rr / c) shl 16) or ((g / c) shl 8) or (b / c)
            if (horizontal) dst[line * n + i] = v else dst[i * n + line] = v
        }
    }
}
private const val TWO_PI = (2 * Math.PI).toFloat()

/** Whether the background is moving right now (see Moving colours on the Look tab). */
fun backgroundMoves(cfg: com.ltots.app.data.ThemeConfig, batterySaver: Boolean, effects: com.ltots.app.data.Effects): Boolean =
    cfg.animateBackground && !cfg.pureBlack && !batterySaver && effects != com.ltots.app.data.Effects.LIGHT

/** One full loop of the moving colours: 20 s at 1×, so 40 s at the default 0.5×, about 7 s at 3×. */
fun motionPeriodNs(speed: Float): Float = 20e9f / max(0.05f, speed)

private fun DrawScope.blob(color: Color, center: Offset, radius: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color, color.copy(alpha = 0.55f), Color.Transparent), center, radius),
        radius, center,
    )
}
