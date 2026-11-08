package com.ltots.app.playback

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.audio.AudioEngine
import com.ltots.app.data.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** The sleep timer: one alarm (no ticking), at the fade's start; stopping clears everything. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SleepTimerTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Store.init(ctx)
        AudioEngine.init(ctx)
        AudioEngine.buildPlayer(ctx)
    }

    @After fun tearDown() {
        SleepTimer.cancel()
        AudioEngine.updateSettings { com.ltots.app.data.AppSettings() }
        AudioEngine.releasePlayer()
    }

    private fun alarms() = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Test fun oneAlarmAtTheFadesStart() {
        AudioEngine.updateSettings { it.copy(sleepFade = true, sleepFadeSeconds = 30) }
        val t0 = SystemClock.elapsedRealtime()
        SleepTimer.start(ctx, 15)
        val s = SleepTimer.state.value
        assertNotNull(s)
        assertEquals((t0 + 15 * 60_000L).toDouble(), s!!.endsAt.toDouble(), 50.0)
        assertEquals("exactly one alarm", 1, alarms().size)
        assertEquals("due when the fade starts", (s.endsAt - 30_000L).toDouble(), alarms().first().triggerAtTime.toDouble(), 50.0)
    }

    @Test fun cancelClearsTheAlarm() {
        SleepTimer.start(ctx, 30)
        SleepTimer.cancel()
        assertNull(SleepTimer.state.value)
        assertTrue(alarms().isEmpty())
    }

    @Test fun nothingPlayingWhenDueJustEnds() {
        AudioEngine.updateSettings { it.copy(sleepFade = false) }
        SleepTimer.start(ctx, 1)
        ShadowLooper.idleMainLooper(61, java.util.concurrent.TimeUnit.SECONDS)
        SleepTimer.onDue()
        assertNull(SleepTimer.state.value)
    }

    @Test fun notificationMoonUsesTheUsualLength() {
        AudioEngine.updateSettings { it.copy(sleepMinutes = 45) }
        val t0 = SystemClock.elapsedRealtime()
        SleepTimer.toggle(ctx)
        assertEquals((t0 + 45 * 60_000L).toDouble(), SleepTimer.state.value!!.endsAt.toDouble(), 50.0)
        SleepTimer.toggle(ctx)
        assertNull(SleepTimer.state.value)
    }
}
