package com.ltots.app.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ltots.app.CrashReport
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The springs the app's navigation moves with (no fixed-length tweens: they can be caught mid-way). */
object Springs {
    /**
     * How firm every spring is (Profile > App settings · motion): 1 = Firm, the default. Firmer
     * springs arrive sooner and feel tied to the finger; none of them has a fixed length, so each
     * can be caught, turned round or redirected at any point, carrying on from the speed it had.
     */
    @Volatile var firmness: Float = 1f

    /** The bottom bar and mini player shrinking and growing. */
    val bar get() = spring<Float>(dampingRatio = 0.9f, stiffness = 620f * firmness)
    /** Pages sliding sideways: settles without overshoot, like iOS paging. */
    val page get() = spring<Float>(dampingRatio = 1f, stiffness = 650f * firmness)
    /** A page coming in after a jump to a tab further away. */
    val enter get() = spring<Float>(dampingRatio = 0.92f, stiffness = 760f * firmness)
    /** A pushed page (EQ, Sound, a swipe-back page) sliding over or away. */
    val push get() = spring<Float>(dampingRatio = 1f, stiffness = 650f * firmness)
    val pushOffset get() = spring<IntOffset>(dampingRatio = 1f, stiffness = 650f * firmness, visibilityThreshold = IntOffset.VisibilityThreshold)
    /** A button giving under the finger: quick down, a slight spring back. */
    val press get() = spring<Float>(dampingRatio = 1f, stiffness = 1500f * firmness)
    val release get() = spring<Float>(dampingRatio = 0.72f, stiffness = 900f * firmness)

    /**
     * Where a flick would come to rest: [position] plus the distance its [velocity] (px/s) still
     * carries it, with the same deceleration iOS scrolling uses. Deciding by where the gesture is
     * *going* instead of where the finger was lifted is what lets a short quick flick count.
     */
    fun project(position: Float, velocity: Float, decelerationRate: Float = 0.998f): Float =
        position + velocity / 1000f * decelerationRate / (1f - decelerationRate)
}

/**
 * The thing under the finger gives a little the moment it's touched and springs back on release:
 * the answer to a touch comes before the tap is over. Drawn as a layer (no rebuilding), and a
 * new touch catches a spring that's still moving.
 */
fun Modifier.pressScale(source: androidx.compose.foundation.interaction.InteractionSource, pressed: Float = 0.94f): Modifier = composed {
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(source) {
        source.interactions.collect { i ->
            when (i) {
                is androidx.compose.foundation.interaction.PressInteraction.Press -> launch { scale.animateTo(pressed, Springs.press) }
                is androidx.compose.foundation.interaction.PressInteraction.Release,
                is androidx.compose.foundation.interaction.PressInteraction.Cancel -> launch { scale.animateTo(1f, Springs.release) }
            }
        }
    }
    graphicsLayer { val s = scale.value; scaleX = s; scaleY = s }
}

/**
 * How shrunk the bottom bar and the mini player are: 0 = full size (labels showing), 1 = compact.
 *
 * One [connection] at the app root sees every vertical scroll on every page (scrolls bubble up
 * through nested scrolling), so no page needs any code for this. While a finger drags a list the
 * bar follows it exactly; on release it springs to whichever end is closer (or the way the list
 * was flung), carrying the release speed. A finger touching a list while the bar is still
 * springing catches it where it is: the spring stops and the finger takes over from that value.
 *
 * [value] changes every frame while scrolling, so it must only be read in drawing and layout
 * (graphicsLayer {}, layout {}, drawBehind {}), never in composition: then scrolling costs no
 * recomposition at all, and the glass bars stay as cheap as before.
 */
@Stable
class BarCollapse internal constructor(private val scope: CoroutineScope, private val rangePx: Float) {
    private val state = mutableFloatStateOf(0f)

    /** 0 = full size, 1 = compact. Read only in draw / layout lambdas (see the class notes). */
    val value: Float get() = state.floatValue

    private var settle: Job? = null
    private var settleTarget = 0f
    private val settling get() = settle?.isActive == true

    /** [a] at full size, [b] when compact, in between while it changes. */
    fun lerp(a: Float, b: Float): Float = a + (b - a) * value

    /** Back to full size (a new tab, a page that can't scroll, the top of a list). */
    fun expand() {
        if (value == 0f && !settling) return
        if (settling && settleTarget == 0f) return
        settleTo(0f, 0f)
    }

    /** Straight to full size without animating (tests, a fresh start). */
    fun reset() {
        settle?.cancel()
        state.floatValue = 0f
    }

    private fun settleTo(target: Float, velocity: Float) {
        settle?.cancel()
        settleTarget = target
        val from = value
        if (from == target && velocity == 0f) return
        settle = scope.launch {
            animate(from, target, initialVelocity = velocity, animationSpec = Springs.bar) { v, _ ->
                state.floatValue = v.coerceIn(0f, 1f)
            }
            CrashReport.note("bar", if (target == 0f) "full size" else "compact")
        }
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // A finger on a list catches the springing bar where it is: no jump.
            if (source == NestedScrollSource.UserInput && available.y != 0f) settle?.cancel()
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0.5f) {
                // Pulled down past the top of a list (or a fling ran into it): show everything.
                expand()
            } else if (consumed.y != 0f && !settling) {
                // The list moved up (consumed.y < 0): shrink; moved down: grow. 1:1 with the content.
                state.floatValue = (value - consumed.y / rangePx).coerceIn(0f, 1f)
            }
            // Only watches: the list keeps all of its scroll.
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val v = value
            if (v > 0f && v < 1f) {
                // Released half-way: a clear fling decides the direction, otherwise the closer end.
                // Positive y velocity moves the content down (towards the top): grow.
                val target = when {
                    abs(available.y) > FLING_DECIDES -> if (available.y > 0f) 0f else 1f
                    else -> if (v > 0.5f) 1f else 0f
                }
                settleTo(target, -available.y / rangePx)
            } else {
                CrashReport.note("bar", if (v >= 1f) "compact" else "full size")
            }
            return Velocity.Zero
        }
    }

    private companion object {
        /** px/s: faster than this, the fling's direction picks full size or compact. */
        const val FLING_DECIDES = 350f
    }
}

/** The app's [BarCollapse]; it lasts as long as the app's screen. */
@Composable
fun rememberBarCollapse(): BarCollapse {
    val scope = rememberCoroutineScope()
    // How far a list scrolls to take the bar from full size to compact.
    val range = with(LocalDensity.current) { 72.dp.toPx() }
    return remember(scope, range) { BarCollapse(scope, range) }
}

/**
 * Whether the phone uses gesture navigation. With it, Android's back gesture owns the left and
 * right edges of the screen, so swiping between tabs is always off (the swipes would fight).
 */
object NavMode {
    /**
     * From the phone's settings: true = gestures, false = buttons, null = the settings don't say
     * (then the screen's gesture areas decide). HyperOS / MIUI keep their own switch for gestures.
     */
    fun fromSettings(context: Context): Boolean? = try {
        val cr = context.contentResolver
        val android = Settings.Secure.getInt(cr, "navigation_mode", -1) // 0 = 3 buttons, 1 = 2 buttons, 2 = gestures
        val xiaomi = Settings.Global.getInt(cr, "force_fsg_nav_bar", -1) // 1 = full-screen gestures
        when {
            android == 2 || xiaomi == 1 -> true
            android == 0 || android == 1 || xiaomi == 0 -> false
            else -> null
        }
    } catch (e: Throwable) {
        CrashReport.handled("NavMode.fromSettings", e)
        null
    }
}

/** True while the phone uses gesture navigation; checked again each time the app comes back. */
@Composable
fun rememberGestureNav(): Boolean {
    val context = LocalContext.current
    var fromSettings by remember { mutableStateOf(NavMode.fromSettings(context)) }
    // The person may switch navigation mode while LToTS is in the background.
    LifecycleResumeEffect(Unit) {
        fromSettings = NavMode.fromSettings(context)
        onPauseOrDispose { }
    }
    // Fallback: with gesture navigation the left and right edges are gesture areas.
    val gestures = WindowInsets.systemGestures
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val fromInsets = gestures.getLeft(density, dir) > 0 || gestures.getRight(density, dir) > 0
    return fromSettings ?: fromInsets
}
