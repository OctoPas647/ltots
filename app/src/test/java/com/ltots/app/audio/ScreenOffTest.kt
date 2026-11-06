package com.ltots.app.audio

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ltots.app.data.AppSettings
import com.ltots.app.data.Json
import com.ltots.app.data.ScreenOffEq
import com.ltots.app.data.ScreenOffPitch
import com.ltots.app.data.SongProfile
import com.ltots.app.data.Store
import com.ltots.app.playback.ScreenState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Screen-off listening in the real engine: what the EQ, pitch and speed do with the screen off
 * (Same / 5-band / Lighter / Off), when they switch, and pre-rendered queue entries.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ScreenOffTest {
    private lateinit var ctx: Context
    private lateinit var player: ExoPlayer

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Store.init(ctx)
        ScreenState.setScreenOnForTest(true)
        AudioEngine.init(ctx)
        AudioEngine.setAppInFront(true)
        player = AudioEngine.buildPlayer(ctx)
        settle()
    }

    @After fun tearDown() {
        ScreenState.setScreenOnForTest(true)
        AudioEngine.setAppInFront(true)
        AudioEngine.updateSettings { AppSettings() }
        AudioEngine.updateEq { EqState(enabled = false) }
        AudioEngine.releasePlayer()
    }

    private fun settle() {
        repeat(5) {
            ShadowLooper.idleMainLooper()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            Thread.sleep(20)
        }
    }

    private fun offloadMode() = player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode
    private val rock get() = Store.builtInPresets.first { it.name == "Rock" }.eq

    /** The user leaves LToTS and the screen turns off (paused, so modes switch at once). */
    private fun screenOff() {
        AudioEngine.setAppInFront(false)
        screen(false)
    }

    private fun screen(on: Boolean) {
        ScreenState.setScreenOnForTest(on)
        AudioEngine.onScreen(on) // what the screen-state listener does in the app
        settle()
    }

    @Test fun eqOffWithTheScreenOff() {
        AudioEngine.updateEq { rock }
        AudioEngine.updateSettings { it.copy(screenOffEq = ScreenOffEq.OFF) }
        settle()
        assertTrue(AudioEngine.eqFor(null).enabled)
        assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED, offloadMode())

        screenOff()
        assertTrue(AudioEngine.screenOffModes.value)
        assertFalse("no EQ with the screen off", AudioEngine.eqFor(null).enabled)
        assertEquals("the chip plays by itself", AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED, offloadMode())

        // Opening LToTS brings the EQ straight back.
        AudioEngine.setAppInFront(true)
        settle()
        assertFalse(AudioEngine.screenOffModes.value)
        assertTrue(AudioEngine.eqFor(null).enabled)
        assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED, offloadMode())
    }

    @Test fun fiveBandHandsTheEqToThePhone() {
        AudioEngine.updateEq { rock }
        AudioEngine.updateSettings { it.copy(screenOffEq = ScreenOffEq.FIVE_BAND, batteryEq = false) }
        settle()
        assertFalse(AudioEngine.batteryEqActive.value)

        screenOff()
        assertTrue("phone EQ: ${AudioEngine.phoneEq.status.value}", AudioEngine.batteryEqActive.value)
        assertFalse(AudioEngine.eqFor(null).enabled)
        assertEquals(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED, offloadMode())

        // Screen back on (still on the lock screen, paused): LToTS's own EQ again.
        screen(true)
        assertFalse(AudioEngine.batteryEqActive.value)
        assertTrue(AudioEngine.eqFor(null).enabled)
    }

    @Test fun sameKeepsTheExactEq() {
        AudioEngine.updateEq { rock }
        AudioEngine.updateSettings { it.copy(screenOffEq = ScreenOffEq.SAME, screenOffPitch = ScreenOffPitch.SAME) }
        screenOff()
        assertFalse(AudioEngine.screenOffModes.value)
        assertTrue(AudioEngine.eqFor(null).enabled)
        assertFalse(AudioEngine.batteryEqActive.value)
    }

    @Test fun pitchLighterAndOff() {
        AudioEngine.updateSettings { it.copy(perSongPitchSpeed = false, screenOffPitch = ScreenOffPitch.LIGHTER) }
        AudioEngine.setPitch(300)
        AudioEngine.previewSpeed(1.25f)
        AudioEngine.commit()
        settle()
        assertFalse(AudioEngine.efficientPitch)
        screenOff()
        assertTrue("lighter engine with the screen off", AudioEngine.efficientPitch)
        assertNotEquals(1.0, AudioEngine.pitchScaleFor(null), 1e-9)

        AudioEngine.setAppInFront(true); settle()
        AudioEngine.updateSettings { it.copy(screenOffPitch = ScreenOffPitch.OFF) }
        screenOff()
        assertEquals("original pitch", 1.0, AudioEngine.pitchScaleFor(null), 1e-9)
        assertEquals("original speed", 1f, player.playbackParameters.speed, 1e-6f)

        AudioEngine.setAppInFront(true); settle()
        assertEquals(1.25f, player.playbackParameters.speed, 1e-6f)
        AudioEngine.resetPitchSpeed()
    }

    @Test fun preRenderedEntriesAreLeftAlone() {
        AudioEngine.updateEq { rock }
        settle()
        assertTrue(AudioEngine.eqFor("song:a").enabled)
        assertFalse("a rendered file has its EQ in it", AudioEngine.eqFor("song:a" + PreRender.ID_SUFFIX).enabled)
        assertEquals(1.0, AudioEngine.pitchScaleFor("song:a" + PreRender.ID_SUFFIX), 1e-9)

        // Queue entries: the rendered one keeps the song's own file for waveforms and lyrics.
        val songFile = File(ctx.cacheDir, "s.wav").apply { writeBytes(ByteArray(10)) }
        val orig = MediaItem.Builder().setMediaId("song:a").setUri(android.net.Uri.fromFile(songFile)).build()
        val rendered = PreRender.renderedItem(orig, File(ctx.cacheDir, "x.ogg"), "k1")
        assertTrue(PreRender.isRendered(rendered))
        assertEquals("k1", PreRender.keyOf(rendered))
        assertEquals(android.net.Uri.fromFile(songFile), PreRender.originalUri(rendered))
        val back = PreRender.originalItem(rendered)
        assertFalse(PreRender.isRendered(back))
        assertEquals(android.net.Uri.fromFile(songFile), back.localConfiguration?.uri)
        assertEquals("song:a", back.mediaId)
    }

    @Test fun editingTheEqPutsRenderedSongsBack() {
        val f1 = File(ctx.cacheDir, "a.wav"); com.ltots.app.audio.RealPlayerTest.writeWav(f1, 2)
        val f2 = File(ctx.cacheDir, "b.wav"); com.ltots.app.audio.RealPlayerTest.writeWav(f2, 2)
        val a = MediaItem.Builder().setMediaId("song:a").setUri(android.net.Uri.fromFile(f1)).build()
        val b = MediaItem.Builder().setMediaId("song:b").setUri(android.net.Uri.fromFile(f2)).build()
        AudioEngine.updateEq { rock }
        player.setMediaItems(listOf(a, PreRender.renderedItem(b, File(ctx.cacheDir, "b.ogg"), "stale")))
        settle()
        assertTrue(PreRender.isRendered(player.getMediaItemAt(1)))
        // Any edit: the rendered entry doesn't match any more, so it's back on its own file.
        AudioEngine.updateEq { Store.builtInPresets.first { it.name == "Bass boost" }.eq }
        settle()
        assertFalse(PreRender.isRendered(player.getMediaItemAt(1)))
        assertEquals("song:b", player.getMediaItemAt(1).mediaId)
    }

    @Test fun powerGovernorFollowsTheScreenAndCharger() {
        ScreenState.setChargingForTest(false)
        // LToTS on screen: short buffers, everything running, any core.
        var s = AudioEngine.powerState
        assertFalse(s.longBuffers)
        assertEquals(NativeDsp.SHORT_BLOCK_FRAMES, s.blockFrames)
        assertFalse(s.visualsSuspended); assertFalse(s.beatsSuspended); assertFalse(s.efficiencyCores)

        // Screen off: straight from the broadcast, before any coroutine runs.
        AudioEngine.setAppInFront(false)
        ScreenState.setScreenOnForTest(false)
        s = AudioEngine.powerState
        assertTrue(s.longBuffers)
        assertEquals(NativeDsp.LONG_BLOCK_FRAMES, s.blockFrames)
        assertTrue(s.visualsSuspended); assertTrue(s.beatsSuspended); assertTrue(s.efficiencyCores)
        assertEquals(NativeDsp.SUSPEND_VISUALS or NativeDsp.SUSPEND_BEATS, NativeDsp.suspendMask())
        val release = AudioEngine.tap.watch()
        assertFalse("nothing copied for the live view", AudioEngine.tap.watching)
        release()

        // Charging: library prep may work out beats; the visuals stay off.
        ScreenState.setChargingForTest(true)
        assertTrue(AudioEngine.powerState.visualsSuspended)
        assertFalse(AudioEngine.powerState.beatsSuspended)
        ScreenState.setChargingForTest(false)
        assertTrue(AudioEngine.powerState.beatsSuspended)

        // Screen on, another app in front: DSP back, still background playback.
        ScreenState.setScreenOnForTest(true)
        s = AudioEngine.powerState
        assertFalse(s.visualsSuspended); assertFalse(s.beatsSuspended)
        assertTrue(s.longBuffers); assertTrue(s.efficiencyCores)

        // LToTS opened: everything as normal.
        AudioEngine.setAppInFront(true)
        s = AudioEngine.powerState
        assertFalse(s.longBuffers); assertFalse(s.efficiencyCores)
        assertEquals(NativeDsp.SHORT_BLOCK_FRAMES, s.blockFrames)
        assertEquals(0, NativeDsp.suspendMask())
    }

    @Test fun renderKeys() {
        val u = android.net.Uri.parse("content://media/1")
        val flat = EqState(enabled = false)
        val k1 = PreRender.Job(u, rock, 1.0, false, false).key
        assertEquals(k1, PreRender.Job(u, rock, 1.0, false, true).key)
        assertNotEquals(k1, PreRender.Job(u, Store.builtInPresets.first { it.name == "Bass boost" }.eq, 1.0, false, false).key)
        assertNotEquals(k1, PreRender.Job(u, rock, 1.05, false, false).key)
        assertEquals("the engine doesn't change which file is right",
            PreRender.Job(u, flat, 1.1, false, false).key, PreRender.Job(u, flat, 1.1, false, true).key)
        assertNull(PreRender.cached(PreRender.Job(u, rock, 1.0, false, false)))
    }

    @Test fun settingsSurviveSaving() {
        val s = AppSettings(
            screenOffEq = ScreenOffEq.OFF, screenOffPitch = ScreenOffPitch.SAME, preRender = false,
            sleepPresets = listOf(10, 20), sleepMinutes = 20, sleepFade = false, sleepFadeSeconds = 45,
            sleepFinishSong = true, batteryStats = false,
        )
        assertEquals(s, Json.settings(Json.settings(s)))
        // Older saves get the battery-first defaults.
        val old = Json.settings(org.json.JSONObject("{}"))
        assertEquals(ScreenOffEq.FIVE_BAND, old.screenOffEq)
        assertEquals(ScreenOffPitch.LIGHTER, old.screenOffPitch)
        assertTrue(old.preRender)
    }
}
