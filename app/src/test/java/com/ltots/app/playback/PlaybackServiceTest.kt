package com.ltots.app.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The playback service starts (media session, notification buttons, sleep timer) and stops cleanly. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PlaybackServiceTest {
    @Test fun startsAndStops() {
        val c = Robolectric.buildService(PlaybackService::class.java).create()
        ShadowLooper.idleMainLooper()
        SleepTimer.toggle(c.get())
        ShadowLooper.idleMainLooper()
        SleepTimer.toggle(c.get())
        ShadowLooper.idleMainLooper()
        c.destroy()
    }
}
