package com.ltots.app.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Screen on, nobody touching: after a while (Profile > App settings · screen on) the app "rests":
 * the moving background stands still and the visualizer runs slower, so the graphics chip isn't
 * redrawing the whole glass screen 120 times a second for a phone lying on the desk. Any touch
 * wakes everything at once.
 */
object ScreenRest {
    private val _resting = MutableStateFlow(false)
    /** True while the screen has gone untouched for the chosen time. */
    val resting: StateFlow<Boolean> = _resting

    @Volatile private var lastTouchNs = System.nanoTime()

    /** Every touch on the app's window (cheap: called for each touch event). */
    fun touched() {
        lastTouchNs = System.nanoTime()
        com.ltots.app.data.Awake.mark()
        if (_resting.value) _resting.value = false
    }

    /** The app came back to the screen: awake, and the wait starts again. */
    fun wake() = touched()

    /**
     * Runs while the app is visible: flips [resting] on after [seconds] () without a touch.
     * [seconds] is read each round, so a changed setting takes effect at once; 0 = never rest.
     */
    suspend fun run(seconds: () -> Int): Nothing {
        while (true) {
            val s = seconds()
            if (s <= 0) {
                _resting.value = false
                delay(2_000)
                continue
            }
            if (_resting.value) {
                // Asleep until the next touch.
                _resting.first { !it }
                continue
            }
            val leftMs = s * 1000L - (System.nanoTime() - lastTouchNs) / 1_000_000L
            if (leftMs <= 0) _resting.value = true else delay(leftMs.coerceAtMost(2_000L))
        }
    }

    /** The slower of two frame-rate caps given as "frames per second, 0 = no cap". */
    fun minFrameNs(effectsNs: Long, fpsCap: Int): Long =
        maxOf(effectsNs, if (fpsCap > 0) 1_000_000_000L / fpsCap else 0L)
}
