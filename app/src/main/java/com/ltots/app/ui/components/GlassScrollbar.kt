package com.ltots.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ltots.app.data.ScrollbarShow
import com.ltots.app.ui.glass.glass
import com.ltots.app.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

/** What the bubble beside the held slider shows: a letter or word, or a colour. */
sealed interface ScrollLabel {
    data class Words(val text: String) : ScrollLabel
    data class Swatch(val argb: Int) : ScrollLabel
}

/** The first letter of [s] for the slider's bubble ("#" for numbers and symbols). */
fun initialOf(s: String): String {
    val c = s.trimStart().firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (c.isLetter()) c.uppercaseChar().toString() else "#"
}

/**
 * A list the slider drives: a [list] or a [grid], and what the bubble says for the item at a
 * given position (null: no bubble). [extraTop]: room to leave at the top of the slider for a
 * header the list has above it (a group's title row).
 */
@Stable
class ScrollTarget internal constructor(
    val list: LazyListState?,
    val grid: LazyGridState?,
    private val labelOf: () -> ((Int) -> ScrollLabel?)?,
    val extraTop: Dp,
) {
    fun label(index: Int): ScrollLabel? = labelOf()?.invoke(index)

    val scrolling: Boolean get() = list?.isScrollInProgress ?: grid?.isScrollInProgress ?: false
    val canScroll: Boolean get() = list?.let { it.canScrollForward || it.canScrollBackward }
        ?: grid?.let { it.canScrollForward || it.canScrollBackward } ?: false
    val firstIndex: Int get() = list?.firstVisibleItemIndex ?: grid?.firstVisibleItemIndex ?: 0

    /** How far down the list is (0..1) and how much of it fits on screen (0..1). */
    internal fun metrics(): Metrics = list?.let { listMetrics(it) } ?: grid?.let { gridMetrics(it) } ?: Metrics.NONE

    /** Jump to [f] (0..1) of the way down. */
    internal fun jumpTo(f: Float) {
        val m = metrics()
        if (m.total == 0) return
        if (f >= 0.999f) { scrollTo(m.total - 1, 0); return }
        val px = f * m.maxScroll
        val step = m.step.coerceAtLeast(1f)
        val row = (px / step).toInt()
        val index = (row * m.perRow).coerceIn(0, m.total - 1)
        scrollTo(index, (px - row * step).roundToInt().coerceAtLeast(0))
    }

    private fun scrollTo(index: Int, offset: Int) {
        list?.requestScrollToItem(index, offset)
        grid?.requestScrollToItem(index, offset)
    }

    internal class Metrics(
        val fraction: Float, val thumb: Float, val total: Int,
        /** Size of one row (item + gap), in px; [perRow] items in a row (1 in lists). */
        val step: Float, val perRow: Int, val maxScroll: Float,
    ) {
        companion object { val NONE = Metrics(0f, 1f, 0, 1f, 1, 0f) }
    }

    private fun listMetrics(s: LazyListState): Metrics {
        val info = s.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || info.totalItemsCount == 0) return Metrics.NONE
        val first = items.first(); val last = items.last()
        val step = ((last.offset + last.size - first.offset).toFloat() / items.size).coerceAtLeast(1f)
        val viewport = info.viewportSize.height.toFloat()
        val content = step * info.totalItemsCount + info.beforeContentPadding + info.afterContentPadding
        val max = (content - viewport).coerceAtLeast(1f)
        val scrolled = first.index * step - first.offset
        val f = when {
            !s.canScrollBackward -> 0f
            !s.canScrollForward -> 1f
            else -> (scrolled / max).coerceIn(0f, 1f)
        }
        return Metrics(f, (viewport / content).coerceIn(0f, 1f), info.totalItemsCount, step, 1, max)
    }

    private fun gridMetrics(s: LazyGridState): Metrics {
        val info = s.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty() || info.totalItemsCount == 0) return Metrics.NONE
        val perRow = (items.maxOf { it.column } + 1).coerceAtLeast(1)
        val first = items.first()
        val firstRow = items.minOf { it.row }; val lastRow = items.maxOf { it.row }
        val bottom = items.filter { it.row == lastRow }.maxOf { it.offset.y + it.size.height }
        val step = ((bottom - first.offset.y).toFloat() / (lastRow - firstRow + 1)).coerceAtLeast(1f)
        val rows = firstRow + ceil((info.totalItemsCount - first.index).toFloat() / perRow).toInt()
        val viewport = info.viewportSize.height.toFloat()
        val content = step * rows + info.beforeContentPadding + info.afterContentPadding
        val max = (content - viewport).coerceAtLeast(1f)
        val scrolled = firstRow * step - first.offset.y
        val f = when {
            !s.canScrollBackward -> 0f
            !s.canScrollForward -> 1f
            else -> (scrolled / max).coerceIn(0f, 1f)
        }
        return Metrics(f, (viewport / content).coerceIn(0f, 1f), info.totalItemsCount, step, perRow, max)
    }
}

/** The page's one slider shows whichever list registered last (the one on screen). */
@Stable
class ScrollbarHost {
    var target by mutableStateOf<ScrollTarget?>(null)
}

val LocalScrollbarHost = staticCompositionLocalOf<ScrollbarHost?> { null }

/** Put this list under the page's scroll slider while it's shown. */
@Composable
fun RegisterScroll(list: LazyListState, extraTop: Dp = 0.dp, label: ((Int) -> ScrollLabel?)? = null) {
    val l = rememberUpdatedState(label)
    Register(remember(list, extraTop) { ScrollTarget(list, null, { l.value }, extraTop) })
}

@Composable
fun RegisterScroll(grid: LazyGridState, extraTop: Dp = 0.dp, label: ((Int) -> ScrollLabel?)? = null) {
    val l = rememberUpdatedState(label)
    Register(remember(grid, extraTop) { ScrollTarget(null, grid, { l.value }, extraTop) })
}

@Composable
private fun Register(target: ScrollTarget) {
    val host = LocalScrollbarHost.current ?: return
    DisposableEffect(host, target) {
        host.target = target
        onDispose { if (host.target === target) host.target = null }
    }
}

/**
 * The liquid-glass scroll slider: a slim glass rail down the right edge with the thumb in it.
 * Hold and drag anywhere on it (or tap) to jump; a glass bubble beside the thumb then says where
 * you are (the letter, genre or colour, depending on the order). Fills the height it's given.
 */
@Composable
fun GlassScrollbar(host: ScrollbarHost, show: ScrollbarShow, modifier: Modifier = Modifier) {
    val target = host.target ?: return
    if (show == ScrollbarShow.OFF) return
    // Everything about a held slider belongs to one list: a different list starts it afresh
    // (the drag used to stay "held" forever when the list changed under it).
    androidx.compose.runtime.key(target) { ScrollbarFor(target, show, modifier) }
}

@Composable
private fun ScrollbarFor(target: ScrollTarget, show: ScrollbarShow, modifier: Modifier) {
    val canScroll by remember(target) { derivedStateOf { target.canScroll } }
    if (!canScroll) return
    val pal = LocalPalette.current
    val density = LocalDensity.current

    var dragging by remember { mutableStateOf(false) }
    val scrolling by remember(target) { derivedStateOf { target.scrolling } }
    // While scrolling: shows as the list moves, stays a moment after it stops.
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) lingering = true
        else { delay(1500); lingering = false }
    }
    val wanted = show == ScrollbarShow.ALWAYS || scrolling || dragging || lingering
    val alpha by animateFloatAsState(if (wanted) 1f else 0f, tween(if (wanted) 150 else 350), label = "sbAlpha")
    if (alpha < 0.01f && !wanted) return
    val railWidth by animateDpAsState(if (dragging) 12.dp else 8.dp, label = "sbWidth")

    val metrics = remember(target) { derivedStateOf { target.metrics() } }
    // How much of the list fits on screen, rounded so the thumb doesn't resize while scrolling.
    val thumbShare by remember(target) { derivedStateOf { (metrics.value.thumb * 40f).roundToInt() / 40f } }
    val label by remember(target) { derivedStateOf { if (dragging) target.label(target.firstIndex) else null } }

    BoxWithConstraints(modifier.padding(top = target.extraTop).fillMaxHeight()) {
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = (trackPx * thumbShare).coerceIn(with(density) { 44.dp.toPx() }, trackPx)
        val thumbDp = with(density) { thumbPx.toDp() }
        val inset = 2.dp
        fun thumbTop(): Float = (trackPx - thumbPx) * metrics.value.fraction
        fun jump(y: Float) {
            val f = ((y - thumbPx / 2f) / (trackPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
            target.jumpTo(f)
        }
        val onTouch = rememberUpdatedState<(Float) -> Unit>(::jump)
        val layer = Modifier.graphicsLayer { this.alpha = alpha }

        // The rail: liquid glass, like the bars, following the Glass settings.
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 4.dp).width(railWidth).fillMaxHeight()
                .then(layer).glass(RoundedCornerShape(50), strength = 0.55f)
        )
        // The thumb: a solid shape in the rail (not glass on glass).
        Box(
            Modifier.align(Alignment.TopEnd).padding(end = 4.dp + inset)
                .offset { IntOffset(0, (thumbTop() + inset.toPx()).roundToInt()) }
                .width(railWidth - inset * 2).height(thumbDp - inset * 2)
                .then(layer).clip(RoundedCornerShape(50))
                .background(if (dragging) pal.accent else pal.text.copy(alpha = 0.55f))
        )
        // Where you are, beside the thumb, while it's held.
        label?.let { l ->
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 4.dp + railWidth + 14.dp)
                    .offset { IntOffset(0, (thumbTop() + thumbPx / 2f - 24.dp.toPx()).roundToInt()) }
                    .heightIn(min = 48.dp).widthIn(min = 48.dp, max = 220.dp)
                    .glass(RoundedCornerShape(24.dp), strength = 0.8f, tintBoost = 0.08f),
                contentAlignment = Alignment.Center,
            ) {
                when (l) {
                    is ScrollLabel.Words -> Text(
                        l.text, color = pal.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = if (l.text.length <= 2) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    is ScrollLabel.Swatch -> Box(Modifier.padding(10.dp).size(28.dp).clip(CircleShape).background(Color(l.argb)))
                }
            }
        }
        // Touch strip along the right edge: hold and drag (or tap) to jump.
        Box(
            Modifier.align(Alignment.TopEnd).width(28.dp).fillMaxHeight()
                .semantics { contentDescription = "Scroll slider" }
                .pointerInput(target) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> dragging = true; onTouch.value(o.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> change.consume(); onTouch.value(change.position.y) }
                }
                .pointerInput(target) { detectTapGestures { o -> onTouch.value(o.y) } },
        )
    }
}
