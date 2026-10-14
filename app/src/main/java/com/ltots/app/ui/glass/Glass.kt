package com.ltots.app.ui.glass

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import com.ltots.app.ui.theme.toColor
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

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
 */
@Composable
fun Modifier.glass(
    shape: Shape = glassShape(),
    strength: Float = 1f,
    tintBoost: Float = 0f,
    tint: Color? = null,
): Modifier {
    val backdrop = LocalBackdrop.current
    val cfg = LocalThemeConfig.current
    val pal = LocalPalette.current
    val surface = (tint ?: pal.glassTint).copy(alpha = (cfg.glassTint + tintBoost).coerceIn(0f, 0.95f))
    if (backdrop == null) return this.clip(shape).background(surface)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur((cfg.blurDp * strength).dp.toPx())
            val r = cfg.refraction * strength
            if (r > 0.01f) {
                val minSide = size.minDimension
                lens(
                    refractionHeight = (18.dp.toPx() * r).coerceAtMost(minSide / 2f),
                    refractionAmount = (40.dp.toPx() * r).coerceAtMost(minSide),
                    depthEffect = true,
                    chromaticAberration = cfg.chromaticAberration,
                )
            }
        },
        highlight = { if (cfg.rimLight > 0.01f) Highlight.Default.copy(alpha = cfg.rimLight) else null },
        shadow = { Shadow(radius = 20.dp, color = Color.Black.copy(alpha = if (pal.dark) 0.22f else 0.08f)) },
        onDrawSurface = { drawRect(surface) },
    )
}

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
    Box(modifier.glass(shape, strength, tintBoost), contentAlignment = contentAlignment, content = content)
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
            .glass(CircleShape, strength = 0.7f, tint = if (highlighted) pal.accent else null, tintBoost = if (highlighted) 0.45f else 0f)
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
    val a = cfg.gradientA.toColor()
    val b = cfg.gradientB.toColor()
    val c = cfg.gradientC.toColor()
    val base = if (pal.dark) Color(0xFF07060C) else Color(0xFFF4F2FA)

    val t by if (cfg.animateBackground) {
        val period = (60000 / max(0.05f, cfg.animationSpeed)).toInt()
        rememberInfiniteTransition(label = "bg").animateFloat(
            0f, (2 * Math.PI).toFloat(),
            infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
            label = "bgT",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0.8f) }
    }

    Box(modifier.background(base)) {
        Canvas(Modifier.fillMaxSize()) {
            blob(a, Offset(size.width * (0.25f + 0.15f * cos(t)), size.height * (0.22f + 0.1f * sin(t * 2))), size.maxDimension * 0.75f)
            blob(b, Offset(size.width * (0.8f + 0.12f * sin(t + 1f)), size.height * (0.55f + 0.14f * cos(t))), size.maxDimension * 0.7f)
            blob(c, Offset(size.width * (0.35f + 0.2f * sin(t * 1.5f + 2f)), size.height * (0.9f + 0.08f * cos(t + 3f))), size.maxDimension * 0.65f)
        }
        if (cfg.artworkBackground && artwork != null) {
            val img = remember(artwork) { artwork.asImageBitmap() }
            Image(
                img, null,
                Modifier.fillMaxSize().blur(70.dp).alpha(if (pal.dark) 0.5f else 0.35f),
                contentScale = ContentScale.Crop,
            )
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

private fun DrawScope.blob(color: Color, center: Offset, radius: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color, color.copy(alpha = 0.55f), Color.Transparent), center, radius),
        radius, center,
    )
}
