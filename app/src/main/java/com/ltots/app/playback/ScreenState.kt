package com.ltots.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether the screen is on and whether the phone is charging, for the whole app.
 *
 * Screen-off listening uses it to save battery: work that only feeds what's on screen (the
 * cover's beats, the launcher icon colour) waits for the screen, the audio buffer grows so the
 * processor wakes less often, and the EQ can hand over to the phone's own (Profile > Battery).
 * The always-on display counts as off.
 *
 * Two ways to follow it:
 *  - the [screenOn] / [charging] flows, for anything that can wait a moment;
 *  - [addListener], called synchronously inside the system's broadcast, the moment the screen
 *    goes dark or lights up. The audio power governor uses it, so the visual and beat DSP stop,
 *    the buffer grows and the audio thread moves to the efficiency cores before anything else
 *    runs. Listeners must be quick (no I/O): they run on the main thread.
 */
object ScreenState {
    private val _screenOn = MutableStateFlow(true)
    val screenOn: StateFlow<Boolean> = _screenOn

    private val _charging = MutableStateFlow(false)
    val charging: StateFlow<Boolean> = _charging

    /** Called (on the main thread) after the screen or charging state changed; flows are already updated. */
    fun interface Listener {
        fun onPowerStateChanged(screenOn: Boolean, charging: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        _screenOn.value = runCatching { app.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
        _charging.value = runCatching { app.getSystemService(BatteryManager::class.java).isCharging }.getOrDefault(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    Intent.ACTION_SCREEN_ON -> setScreen(true)
                    Intent.ACTION_SCREEN_OFF -> setScreen(false)
                    Intent.ACTION_POWER_CONNECTED -> setCharging(true)
                    Intent.ACTION_POWER_DISCONNECTED -> setCharging(false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    /** Follows screen and charging changes from now on (and is told the current state at once). */
    fun addListener(l: Listener) {
        if (listeners.addIfAbsent(l)) runCatching { l.onPowerStateChanged(_screenOn.value, _charging.value) }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun setScreen(on: Boolean) {
        if (_screenOn.value == on) return
        if (on) com.ltots.app.data.Awake.mark()
        _screenOn.value = on
        notifyListeners()
    }

    private fun setCharging(on: Boolean) {
        if (_charging.value == on) return
        _charging.value = on
        notifyListeners()
    }

    private fun notifyListeners() {
        val on = _screenOn.value
        val ch = _charging.value
        for (l in listeners) runCatching { l.onPowerStateChanged(on, ch) }
    }

    /** For tests: pretend the screen turned on or off (exactly what the broadcast does). */
    fun setScreenOnForTest(on: Boolean) = setScreen(on)

    /** For tests: pretend the charger was plugged in or out. */
    fun setChargingForTest(on: Boolean) = setCharging(on)
}
