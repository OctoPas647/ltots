package com.ltots.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ltots.app.data.Effects
import com.ltots.app.data.IconBars
import com.ltots.app.data.IconFiles
import com.ltots.app.data.IconFill
import com.ltots.app.data.IconIdle
import com.ltots.app.data.RingLayout
import com.ltots.app.data.SpectrumStyle
import com.ltots.app.data.ThemeConfig
import com.ltots.app.ui.LocalEffects
import com.ltots.app.ui.glass.glass
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Icon mode: safe space kept free at the edges of the icon's area (no bar reaches into it). */
val ICON_MARGIN = 16.dp
/** Distance between neighbouring bars along the icon's outline. */
private val ICON_SPACING = 3.dp
/** The widest a bar gets. */
private val ICON_MAX_BAR = 6.dp

/** How far the current icon is. */
sealed interface IconLoad {
    /** No icon picked, or it can't be read: the Playing tab shows the usual cover. */
    data object None : IconLoad
    data object Loading : IconLoad
    data class Ready(val shape: IconShape) : IconLoad
}

/**
 * Icon pictures and their shapes, worked out on a background thread and kept: changing the
 * threshold reuses the decoded picture, and the Playing tab and the Look tab's preview share it.
 */
object IconCache {
    private val lock = Any()
    private var sourceName: String? = null
    private var source: IconSource? = null
    private var shapeKey: List<Any>? = null
    private var shape: IconShape? = null

    suspend fun shape(context: Context, name: String, threshold: Float, invert: Boolean, minHole: Float): IconShape? =
        withContext(Dispatchers.Default) {
            val key = listOf(name, threshold, invert, minHole)
            synchronized(lock) { if (shapeKey == key) return@withContext shape }
            val src = synchronized(lock) { source.takeIf { sourceName == name } } ?: run {
                val file = IconFiles.file(context, name) ?: return@withContext null
                val bmp = IconFiles.decode(file) ?: return@withContext null
                IconMask.prepare(bmp).also {
                    bmp.recycle()
                    synchronized(lock) { sourceName = name; source = it }
                }
            }
            // A newer setting (the slider still moving) cancels this one between the steps.
            coroutineContext.ensureActive()
            val mask = IconMask.build(src, threshold, invert, minHole)
            coroutineContext.ensureActive()
            IconShape.of(mask).also { synchronized(lock) { shapeKey = key; shape = it } }
        }
}

/** The current look's icon, worked out in the background (the last one stays while a new one is made). */
@Composable
fun rememberIconLoad(cfg: ThemeConfig): IconLoad {
    val context = LocalContext.current
    val name = cfg.iconFile
    val state = produceState<IconLoad>(if (name == null) IconLoad.None else IconLoad.Loading,
        name, cfg.iconThreshold, cfg.iconInvert, cfg.iconMinHole) {
        if (name == null) { value = IconLoad.None; return@produceState }
        val s = IconCache.shape(context, name, cfg.iconThreshold, cfg.iconInvert, cfg.iconMinHole)
        value = if (s == null) IconLoad.None else IconLoad.Ready(s)
    }
    return state.value
}

/** An [IconRing] for one area size, with the dark parts as a path and a shape to clip to. */
class IconLayout(val ring: IconRing, val dark: Path, val darkShape: Shape, val areaWidth: Float, val areaHeight: Float) {
    companion object {
        fun build(shape: IconShape, width: Float, height: Float, density: Float, bands: Int): IconLayout? {
            val ring = IconRing.build(
                shape, width, height,
                marginPx = ICON_MARGIN.value * density,
                spacingPx = ICON_SPACING.value * density,
                minPoints = (bands * 2).coerceAtLeast(48),
                maxBarWidthPx = ICON_MAX_BAR.value * density,
            ) ?: return null
            val dark = Path().apply {
                fillType = PathFillType.EvenOdd
                for (l in ring.shapeLoops) {
                    moveTo(l[0], l[1])
                    for (i in 2 until l.size step 2) lineTo(l[i], l[i + 1])
                    close()
                }
            }
            val darkShape = GenericShape { _, _ -> addPath(dark); fillType = PathFillType.EvenOdd }
            return IconLayout(ring, dark, darkShape, width, height)
        }
    }
}

/** Builds the layout for this area's size in the background, once per icon and size. */
@Composable
private fun rememberIconLayout(shape: IconShape, size: IntSize, bands: Int): IconLayout? {
    val density = LocalDensity.current.density
    val layout by produceState<IconLayout?>(null, shape, size, bands, density) {
        if (size.width <= 0 || size.height <= 0) return@produceState
        value = withContext(Dispatchers.Default) {
            IconLayout.build(shape, size.width.toFloat(), size.height.toFloat(), density, bands)
        }
    }
    return layout
}

/**
 * Icon mode on the Playing tab, in the cover's place. The icon itself is never drawn: the live
 * spectrum grows out of the outline of its dark parts, so the shape shows through the bars. The
 * dark parts are left empty, glass or accent (Look), optionally with the song's cover inside.
 * While nothing plays: nothing, the icon without bars, or the cover (Look).
 */
@Composable
fun IconSpectrum(
    visual: LiveVisual,
    shape: IconShape,
    artwork: Bitmap?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val cfg = LocalThemeConfig.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layout = rememberIconLayout(shape, size, visual.analyzer.bands)
    Box(modifier.onSizeChanged { size = it }) {
        val l = layout ?: return@Box
        when {
            playing -> {
                IconFillLayer(l, artwork)
                if (visual.fps > 0) IconBarsCanvas(visual, l)
            }
            cfg.iconIdle == IconIdle.SHAPE -> IconFillLayer(l, artwork)
            cfg.iconIdle == IconIdle.COVER -> IdleCover(l, artwork)
            else -> Unit // Show nothing.
        }
    }
}

/**
 * Look tab preview: the icon as it will be on the Playing tab, with a still spectrum, so the
 * threshold, invert and hole settings can be judged while changing them.
 */
@Composable
fun IconPreview(shape: IconShape, artwork: Bitmap?, modifier: Modifier = Modifier) {
    val cfg = LocalThemeConfig.current
    val pal = LocalPalette.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val layout = rememberIconLayout(shape, size, 48)
    // A still spectrum with some movement in it: louder bass, a few peaks.
    val levels = remember { FloatArray(48) { i -> (0.75f - i / 96f + 0.2f * kotlin.math.sin(i * 0.55f)).coerceIn(0.08f, 1f) } }
    val path = remember { Path() }
    val band = remember { Path() }
    Box(modifier.onSizeChanged { size = it }) {
        val l = layout ?: return@Box
        IconFillLayer(l, artwork)
        Canvas(Modifier.matchParentSize()) {
            drawIconSpectrum(l.ring, levels, cfg.spectrumStyle, pal.accent.copy(alpha = cfg.spectrumOpacity), false,
                cfg.iconBars, cfg.ringLayout, path, band)
        }
    }
}

/** The dark parts: empty, glass or accent, with the song's cover inside if chosen. */
@Composable
private fun IconFillLayer(l: IconLayout, artwork: Bitmap?) {
    val cfg = LocalThemeConfig.current
    val pal = LocalPalette.current
    val withCover = cfg.iconCoverBehind && artwork != null
    if (withCover) CoverInShape(l, artwork, glass = cfg.iconFill == IconFill.GLASS)
    when (cfg.iconFill) {
        IconFill.DARK -> Unit
        // Like the play button: solid accent (see-through enough to show the cover, if on).
        IconFill.ACCENT -> Canvas(Modifier.fillMaxSize()) { drawPath(l.dark, pal.accent.copy(alpha = if (withCover) 0.5f else 1f)) }
        IconFill.GLASS -> if (withCover) {
            // Frosted cover: the cover blurred (above), tinted with the accent, with a rim light.
            Canvas(Modifier.fillMaxSize()) {
                drawPath(l.dark, pal.accent.copy(alpha = cfg.glassTint.coerceIn(0f, 0.95f)))
                if (cfg.rimLight > 0.01f) drawPath(l.dark, Color.White.copy(alpha = 0.45f * cfg.rimLight), style = Stroke(1.2.dp.toPx()))
            }
        } else {
            // The app's own glass in the shape of the dark parts, tinted with the accent. No
            // bending at the edges (it's made for rounded rectangles), and no corner roundness.
            key(l) { Box(Modifier.fillMaxSize().glass(l.darkShape, tint = pal.accent, refract = false)) }
        }
    }
}

/** The song's cover filling the icon's box, seen only through its dark parts. */
@Composable
private fun CoverInShape(l: IconLayout, artwork: Bitmap, glass: Boolean) {
    val cfg = LocalThemeConfig.current
    val img = remember(artwork) { artwork.asImageBitmap() }
    val blur = glass && LocalEffects.current == Effects.FULL && cfg.blurDp > 0.5f
    Box(Modifier.fillMaxSize().graphicsLayer { clip = true; shape = l.darkShape; alpha = cfg.coverOpacity }) {
        Canvas(Modifier.fillMaxSize().then(if (blur) Modifier.blur((cfg.blurDp * 0.5f).dp) else Modifier)) {
            drawCoverInBox(img, l.ring.iconLeft, l.ring.iconTop, l.ring.iconWidth, l.ring.iconHeight)
        }
    }
}

/** Draws [img] cropped to fill the box (like ContentScale.Crop). */
private fun DrawScope.drawCoverInBox(img: ImageBitmap, left: Float, top: Float, w: Float, h: Float) {
    if (w < 1f || h < 1f) return
    val scale = max(w / img.width, h / img.height)
    val sw = (w / scale).coerceAtMost(img.width.toFloat()); val sh = (h / scale).coerceAtMost(img.height.toFloat())
    drawImage(
        img,
        srcOffset = IntOffset(((img.width - sw) / 2).roundToInt(), ((img.height - sh) / 2).roundToInt()),
        srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
    )
}

/** While nothing plays ("Show the cover"): the cover, as a square over the icon's place. */
@Composable
private fun IdleCover(l: IconLayout, artwork: Bitmap?) {
    val pal = LocalPalette.current
    val cfg = LocalThemeConfig.current
    val density = LocalDensity.current
    val side = max(l.ring.iconWidth, l.ring.iconHeight)
    val left = (l.areaWidth - side) / 2; val top = (l.areaHeight - side) / 2
    val corner = RoundedCornerShape(24.dp)
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { side.toDp() })
            .graphicsLayer { alpha = cfg.coverOpacity }
            .shadow(28.dp, corner, ambientColor = pal.accent, spotColor = pal.accent)
            .clip(corner).background(pal.text.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(remember(artwork) { artwork.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = pal.textFaint, modifier = Modifier.size(72.dp))
        }
    }
}

/** The live bars, redrawn on every visualizer frame in their own layer (never recomposed). */
@Composable
private fun IconBarsCanvas(visual: LiveVisual, l: IconLayout) {
    val cfg = LocalThemeConfig.current
    val pal = LocalPalette.current
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        @Suppress("UNUSED_VARIABLE") val t = visual.tick // redraw on every new frame
        val f = visual.frame
        val alpha = cfg.spectrumOpacity * 0.9f
        if (cfg.showPreEqGhost && !visual.preMirrorsPost) {
            drawIconSpectrum(l.ring, f.pre, SpectrumStyle.CURVE, pal.text.copy(alpha = 0.25f * alpha), ghost = true,
                cfg.iconBars, cfg.ringLayout, visual.ghostCurve, visual.band)
        }
        drawIconSpectrum(l.ring, f.post, cfg.spectrumStyle, pal.accent.copy(alpha = alpha), ghost = false,
            cfg.iconBars, cfg.ringLayout, visual.curve, visual.band)
    }
}

/**
 * One spectrum along the icon's outline, in the chosen style, out of the shape, into it or both.
 * Allocation-free: only the precomputed arrays are read, into reused paths, with one or two
 * drawPath calls.
 */
fun DrawScope.drawIconSpectrum(
    ring: IconRing,
    levels: FloatArray,
    style: SpectrumStyle,
    color: Color,
    ghost: Boolean,
    bars: IconBars,
    layout: RingLayout,
    path: Path,
    band: Path,
) {
    if (levels.isEmpty() || levels.all { it < 0.01f }) return
    val out = bars != IconBars.IN
    val inn = bars != IconBars.OUT
    path.reset()
    if (ghost || style == SpectrumStyle.CURVE) {
        if (out) path.addOutlineCurve(ring, levels, layout)
        if (inn) path.addOutlineCurve(ring, levels, layout, inward = true)
        if (ghost) {
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
            return
        }
        // Fill only the band between the outline and the curve (or between the two curves).
        band.reset()
        band.fillType = PathFillType.EvenOdd
        if (out) band.addOutlineCurve(ring, levels, layout)
        if (inn) band.addOutlineCurve(ring, levels, layout, inward = true)
        if (!(out && inn)) band.addOutline(ring)
        drawPath(band, color.copy(alpha = color.alpha * 0.35f))
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        return
    }
    // Bars (Mirror: centred halfway along their reach, pulsing both ways), each a thin quad so
    // every bar can have its own width; all in one path, one draw call.
    val mirror = style == SpectrumStyle.MIRROR
    for (k in 0 until ring.count) {
        val v = ring.levelAt(k, levels, layout)
        if (out) path.addBar(ring, k, v, ring.reachOutPx[k], ring.halfWidthOut[k], mirror, 1f)
        if (inn) path.addBar(ring, k, v, ring.reachInPx[k], ring.halfWidthIn[k], mirror, -1f)
    }
    drawPath(path, color)
}

private fun Path.addBar(o: IconRing, k: Int, level: Float, reach: Float, half: Float, mirror: Boolean, sign: Float) {
    if (reach < 0.75f) return
    val len = max(level * reach, 1f).coerceAtMost(reach)
    val from = if (mirror) reach / 2 - len / 2 else 0f
    val dx = o.nx[k] * sign; val dy = o.ny[k] * sign
    val x0 = o.x[k] + dx * from; val y0 = o.y[k] + dy * from
    val x1 = x0 + dx * len; val y1 = y0 + dy * len
    val tx = -dy * half; val ty = dx * half
    moveTo(x0 + tx, y0 + ty)
    lineTo(x1 + tx, y1 + ty)
    lineTo(x1 - tx, y1 - ty)
    lineTo(x0 - tx, y0 - ty)
    close()
}
