package com.ltots.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The home-screen icon's trampoline opens MainActivity in its own task and goes away. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LaunchActivityTest {
    @Test fun opensMainActivityAndCloses() {
        val ctl = Robolectric.buildActivity(LaunchActivity::class.java).create()
        val a = ctl.get()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(next.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(Intent.ACTION_MAIN, next.action)
        assertTrue(a.isFinishing)
    }
}
