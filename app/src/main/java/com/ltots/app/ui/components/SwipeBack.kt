package com.ltots.app.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.ltots.app.CrashReport
import com.ltots.app.ui.rememberGestureNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A page you can leave like on iOS: swiping in from the left edge drags it away under your finger
 * and letting go past a third of the way closes it. With gesture navigation that swipe is
 * Android's own back gesture (the page follows it); with buttons it's a strip along the left
 * edge. Android's back button closes the page straight away.
 *
 * @param keepOffset the page disappears by itself after [onBack] (an animated page change), so it
 *   stays where it was dragged to; off, the same place shows something else and comes back.
 */
@Composable
fun SwipeBackPage(
    label: String,
    enabled: Boolean = true,
    keepOffset: Boolean = false,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    val back by rememberUpdatedState(onBack)
    val gestureNav = rememberGestureNav()
    val ease = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    fun close(how: String) {
        CrashReport.step("back: $label ($how)")
        back()
    }

    PredictiveBackHandler(enabled = enabled) { progress ->
        var dragged = false
        try {
            progress.collect { e ->
                if (e.swipeEdge == BackEventCompat.EDGE_LEFT && e.progress > 0f) {
                    dragged = true
                    offset.snapTo(e.progress * width)
                }
            }
            if (dragged) offset.animateTo(width, ease)
            close(if (dragged) "swipe" else "back button")
            if (!keepOffset) offset.snapTo(0f)
        } catch (e: CancellationException) {
            scope.launch { offset.animateTo(0f, ease) }
            throw e
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width.toFloat() }.graphicsLayer { translationX = offset.value }) {
        content()
        if (enabled && !gestureNav) {
            Box(
                Modifier.align(Alignment.CenterStart).width(20.dp).fillMaxHeight().pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value > width / 3f) {
                                    offset.animateTo(width, ease)
                                    close("swipe")
                                    if (!keepOffset) offset.snapTo(0f)
                                } else offset.animateTo(0f, ease)
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, ease) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + dx).coerceAtLeast(0f)) }
                    }
                },
            )
        }
    }
}
