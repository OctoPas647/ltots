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

/**
 * Whether the screen is on and whether the phone is charging, for the whole app.
 *
 * Screen-off listening uses it to save battery: work that only feeds what's on screen (the
 * cover's beats, the launcher icon colour) waits for the screen, the audio buffer grows so the
 * processor wakes less often, and the EQ can hand over to the phone's own (Profile > Battery).
 * The always-on display counts as off.
 */
object ScreenState {
    private val _screenOn = MutableStateFlow(true)
    val screenOn: StateFlow<Boolean> = _screenOn

    private val _charging = MutableStateFlow(false)
    val charging: StateFlow<Boolean> = _charging

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
                    Intent.ACTION_SCREEN_ON -> _screenOn.value = true
                    Intent.ACTION_SCREEN_OFF -> _screenOn.value = false
                    Intent.ACTION_POWER_CONNECTED -> _charging.value = true
                    Intent.ACTION_POWER_DISCONNECTED -> _charging.value = false
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

    /** For tests: pretend the screen turned on or off. */
    fun setScreenOnForTest(on: Boolean) { _screenOn.value = on }
}
