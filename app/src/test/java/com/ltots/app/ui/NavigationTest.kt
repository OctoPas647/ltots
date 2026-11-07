package com.ltots.app.ui

import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class NavigationTest {
    /** Gesture navigation is read from Android's setting and from HyperOS / MIUI's own switch. */
    @Test fun gestureNavFromSettings() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cr = ctx.contentResolver
        Settings.Secure.putString(cr, "navigation_mode", null)
        Settings.Global.putString(cr, "force_fsg_nav_bar", null)
        assertNull(NavMode.fromSettings(ctx))
        Settings.Secure.putInt(cr, "navigation_mode", 0)
        assertEquals(false, NavMode.fromSettings(ctx))
        Settings.Secure.putInt(cr, "navigation_mode", 2)
        assertEquals(true, NavMode.fromSettings(ctx))
        // HyperOS: its full-screen gestures switch wins even if Android's setting says buttons.
        Settings.Secure.putInt(cr, "navigation_mode", 0)
        Settings.Global.putInt(cr, "force_fsg_nav_bar", 1)
        assertEquals(true, NavMode.fromSettings(ctx))
        Settings.Global.putInt(cr, "force_fsg_nav_bar", 0)
        assertEquals(false, NavMode.fromSettings(ctx))
    }

    /** While a finger drags a list, the bar follows the content 1:1 and stays between 0 and 1. */
    @Test fun barFollowsTheScroll() {
        val bar = BarCollapse(CoroutineScope(Dispatchers.Unconfined), rangePx = 100f)
        val c = bar.connection
        fun scroll(dy: Float, leftover: Float = 0f) =
            c.onPostScroll(Offset(0f, dy), Offset(0f, leftover), NestedScrollSource.UserInput)
        assertEquals(0f, bar.value)
        scroll(-25f) // content moves up: shrink
        assertEquals(0.25f, bar.value, 1e-4f)
        scroll(-500f)
        assertEquals(1f, bar.value, 1e-4f)
        scroll(40f) // content moves down: grow
        assertEquals(0.6f, bar.value, 1e-4f)
        assertEquals(1f + (0.86f - 1f) * 0.6f, bar.lerp(1f, 0.86f), 1e-4f)
        // Sideways scrolling (the pages) doesn't touch it.
        c.onPostScroll(Offset(300f, 0f), Offset.Zero, NestedScrollSource.UserInput)
        assertEquals(0.6f, bar.value, 1e-4f)
    }
}
