package com.ltots.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The very top of the screen, for a page that draws there with the status bar hidden: where the
 * camera is, and how round the screen's top corners are. All in dp, in the page's own coordinates
 * (LToTS always lays out behind the camera, from the screen's top-left corner).
 */
@Immutable
data class TopArea(
    /** The camera cutout at the top (punch hole or notch); null when there's none. */
    val cutoutLeft: Dp = 0.dp,
    val cutoutRight: Dp = 0.dp,
    val cutoutBottom: Dp = 0.dp,
    val hasCutout: Boolean = false,
    /** Radius of the screen's rounded top corners (0 for square ones). */
    val cornerRadius: Dp = 0.dp,
) {
    /** True when the camera lies over the horizontal span [from]..[to]. */
    fun cutoutOver(from: Dp, to: Dp): Boolean = hasCutout && cutoutLeft < to && cutoutRight > from

    /**
     * The highest an element can sit: [x] from the nearer side edge, [width] wide, with corners
     * of [corner] radius. It stays [gap] clear of the top, inside the screen's rounded corner,
     * and below the camera if the camera is right above it.
     */
    fun minTop(x: Dp, width: Dp, corner: Dp, gap: Dp = 6.dp, fromRight: Boolean = false, screenWidth: Dp = 0.dp): Dp {
        var y = gap.value
        val r = cornerRadius.value
        if (r > 0f) {
            // The element's own corner, as a circle: it has to fit inside the screen's corner
            // circle, with a little margin.
            val c = corner.value.coerceAtMost(width.value / 2f)
            val cx = x.value + c
            val allowed = r - c - 2f
            if (cx < r) {
                val dx = r - cx
                y = if (allowed > dx) max(y, r - sqrt(allowed * allowed - dx * dx) - c) else max(y, r)
            }
        }
        val left = if (fromRight) screenWidth - x - width else x
        if (cutoutOver(left, left + width)) y = max(y, cutoutBottom.value + 4f)
        return y.dp
    }
}

/** The camera and the screen's corners, read from the window (updates when they change). */
@Composable
fun rememberTopArea(): TopArea {
    val view = LocalView.current
    val density = LocalDensity.current
    // Read so the area is worked out again whenever the cutout changes (rotation, folding).
    val cutTop = WindowInsets.displayCutout.getTop(density)
    val conf = LocalConfiguration.current
    return remember(view, cutTop, conf.orientation, conf.screenWidthDp, density) {
        val insets = runCatching { view.rootWindowInsets }.getOrNull() ?: return@remember TopArea()
        fun dp(px: Int) = (px / density.density).dp
        // The cutout touching the top edge (a notch or punch hole), not one on a side.
        val cut = insets.displayCutout?.boundingRects.orEmpty()
            .filter { it.top < view.height / 8 && it.width() < view.width * 0.6f && it.height() > 0 }
            .minByOrNull { it.top }
        val corner = runCatching {
            insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius
        }.getOrNull() ?: 0
        TopArea(
            cutoutLeft = dp(cut?.left ?: 0), cutoutRight = dp(cut?.right ?: 0), cutoutBottom = dp(cut?.bottom ?: 0),
            hasCutout = cut != null, cornerRadius = dp(corner),
        )
    }
}
