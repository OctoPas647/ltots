package com.ltots.app.audio

import android.content.Context
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.data.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Battery EQ in the real audio engine and player: the phone's own EQ gets the fitted curve,
 * LToTS's EQ steps aside, and the player is allowed to hand the music to the audio chip.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BatteryEqEngineTest {
    private lateinit var player: ExoPlayer

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Store.init(ctx)
        AudioEngine.init(ctx)
        player = AudioEngine.buildPlayer(ctx)
        settle()
        assertTrue("player has an audio session", player.audioSessionId > 0)
    }

    @After fun tearDown() {
        AudioEngine.updateSettings { it.copy(batteryEq = false) }
        AudioEngine.releasePlayer()
    }

    private fun settle() {
        repeat(5) {
            ShadowLooper.idleMainLooper()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            Thread.sleep(30)
        }
    }

    private fun offloadMode() = player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode

    @Test fun phoneEqTakesOverAndTheChipMayPlay() {
        val rock = Store.builtInPresets.first { it.name == "Rock" }.eq
        AudioEngine.updateEq { rock }
        settle()
        // LToTS's own EQ is working: no direct playback.
        assertFalse(AudioEngine.batteryEqActive.value)
        assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED, offloadMode())
        assertTrue(AudioEngine.eqFor(null).enabled)

        AudioEngine.updateSettings { it.copy(batteryEq = true) }
        settle()
        assertTrue("Battery EQ active: ${AudioEngine.phoneEq.status.value}", AudioEngine.batteryEqActive.value)
        val status = AudioEngine.phoneEq.status.value as PhoneEq.Status.Ready
        val expected = BatteryEqFit.fit(rock, status.layout)
        println("PHONE EQ layout=${status.layout} levels=${status.levels.joinToString()}")
        assertTrue(status.levels.contentEquals(expected))
        // The phone's EQ really got those levels and is switched on.
        val (on, actual) = AudioEngine.phoneEq.readBack()!!
        assertTrue(on)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 0.011f)
        // LToTS's EQ steps aside, and the audio chip may play the music by itself.
        assertFalse(AudioEngine.eqFor(null).enabled)
        assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED, offloadMode())

        // An EQ edit reaches the phone's EQ at once.
        val bass = Store.builtInPresets.first { it.name == "Bass boost" }.eq
        AudioEngine.updateEq { bass }
        settle()
        val now = (AudioEngine.phoneEq.status.value as PhoneEq.Status.Ready).levels
        assertTrue(now.contentEquals(BatteryEqFit.fit(bass, status.layout)))

        // Off again: LToTS's own EQ is back and the processor is needed.
        AudioEngine.updateSettings { it.copy(batteryEq = false) }
        settle()
        assertFalse(AudioEngine.batteryEqActive.value)
        assertTrue(AudioEngine.eqFor(null).enabled)
        assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED, offloadMode())
        assertEquals(PhoneEq.Status.Off, AudioEngine.phoneEq.status.value)
    }

    @Test fun flatEqSwitchesThePhoneEqOff() {
        AudioEngine.updateEq { EqState() }
        AudioEngine.updateSettings { it.copy(batteryEq = true) }
        settle()
        assertTrue(AudioEngine.batteryEqActive.value)
        val levels = (AudioEngine.phoneEq.status.value as PhoneEq.Status.Ready).levels
        assertTrue(levels.all { it == 0f })
        assertFalse("nothing to do: the phone's EQ stays off", AudioEngine.phoneEq.readBack()!!.first)
    }
}
