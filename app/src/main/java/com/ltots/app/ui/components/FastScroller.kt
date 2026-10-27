package com.ltots.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ltots.app.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A long list with a slider down its right edge: it shows where in the list you are, and
 * dragging it jumps anywhere at once, with a bubble saying what's there ("R · 214 of 313").
 *
 * The slider appears while the list scrolls and fades a moment after. Grabbing it works when
 * it's showing, or by pressing right on it; touches elsewhere go to the list as usual.
 */
@Composable
fun FastScrollBox(
    state: LazyListState,
    count: Int,
    top: Dp,
    bottom: Dp,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val labelNow by rememberUpdatedState(label)
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val listFrac by remember(count) {
        derivedStateOf {
            val visible = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val span = (count - visible).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / span).coerceIn(0f, 1f)
        }
    }
    val frac = if (dragging) dragFrac else listFrac
    var shown by remember { mutableStateOf(false) }
    val active = dragging || state.isScrollInProgress
    LaunchedEffect(active) { if (active) shown = true else { delay(1600); shown = false } }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scroller")
    val enabled = count >= 25
    val thumbH = 46.dp
    val thumbW by animateDpAsState(if (dragging) 8.dp else 5.dp, label = "thumb")
    val strip = 30.dp

    BoxWithConstraints(
        modifier.fillMaxSize().pointerInput(enabled, count) {
            if (!enabled) return@pointerInput
            val stripPx = strip.toPx(); val topPx = top.toPx(); val bottomPx = bottom.toPx(); val thumbPx = thumbH.toPx()
            awaitEachGesture {
                // Looks at the touch before the list does, and takes it only on the slider.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val trackLen = size.height - topPx - bottomPx - thumbPx
                if (trackLen <= 0f || down.position.x < size.width - stripPx) return@awaitEachGesture
                if (down.position.y < topPx || down.position.y > size.height - bottomPx) return@awaitEachGesture
                val thumbCenter = topPx + trackLen * listFrac + thumbPx / 2
                if (!shown && abs(down.position.y - thumbCenter) > thumbPx) return@awaitEachGesture
                down.consume()
                dragging = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                var lastIndex = -1
                fun moveTo(y: Float) {
                    dragFrac = ((y - topPx - thumbPx / 2) / trackLen).coerceIn(0f, 1f)
                    val index = (dragFrac * (count - 1)).roundToInt()
                    if (index != lastIndex) {
                        lastIndex = index
                        scope.launch { state.scrollToItem(index) }
                    }
                }
                moveTo(down.position.y)
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    c.consume()
                    moveTo(c.position.y)
                }
                dragging = false
            }
        }
    ) {
        content()
        if (!enabled) return@BoxWithConstraints
        val trackLen = (maxHeight - top - bottom - thumbH).coerceAtLeast(0.dp)
        val y = top + trackLen * frac
        // The thumb.
        Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, with(density) { y.roundToPx() }) }
                .padding(end = 3.dp).width(thumbW).height(thumbH).alpha(alpha)
                .clip(RoundedCornerShape(50)).background(if (dragging) pal.accent else pal.text.copy(alpha = 0.45f))
        )
        // What's there, while dragging.
        if (dragging) {
            val index = (dragFrac * (count - 1)).roundToInt().coerceIn(0, count - 1)
            Column(
                Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(-with(density) { 26.dp.roundToPx() }, with(density) { (y + thumbH / 2 - 26.dp).roundToPx() }) }
                    .clip(RoundedCornerShape(16.dp)).background(pal.accent).padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.End,
            ) {
                val l = labelNow(index)
                if (l.isNotBlank()) Text(l, color = pal.onAccent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${index + 1} of $count", color = pal.onAccent.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
