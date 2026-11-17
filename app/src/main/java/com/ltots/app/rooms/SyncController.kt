package com.ltots.app.rooms

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Keeps one phone's sound exactly on the room's timeline.
 *
 * The renderer measures, from its AudioTrack's timestamps, when the next frame it writes will
 * actually be heard, and compares that with when the room wants it heard. The difference is
 * [update]'s error (positive: this phone is late).
 *
 *  - A big error (start, a Bluetooth hiccup, a new calibration) is fixed at once: frames are
 *    skipped (late) or silence is held (early) — a [Jump].
 *  - A small one is steered away by playing a hair faster or slower: a [Rate] in parts per
 *    million, applied as the AudioTrack's playback rate (Android's own high-quality resampler
 *    does the work, at no cost to the app). This also cancels the slow drift between phones'
 *    audio clocks, without a single click.
 *
 * The error readings are noisy (Bluetooth reports its timing in jumps), so they're smoothed
 * first, and the steering is gentle (a PI loop that settles within a few seconds).
 */
class SyncController(
    /** Errors beyond this are fixed by skipping / holding. */
    private val jumpNs: Long = 12_000_000L,
    /** A big error has to last this long before it's jumped on. */
    private val jumpHoldNs: Long = 300_000_000L,
    /** Never steer harder than this (0.2 %: inaudible). */
    private val maxPpm: Double = 2000.0,
    private val kp: Double = 120.0, // ppm per ms of error
    private val ki: Double = 12.0, // ppm per ms·s
) {
    sealed class Decision {
        data class Rate(val ppm: Double) : Decision()
        /** Skip ([ns] > 0) or hold ([ns] < 0) this much of the music. */
        data class Jump(val ns: Long) : Decision()
    }

    private var smoothed = 0.0 // ns
    private var integral = 0.0 // ms·s
    private var primed = false
    private var bigSince = -1L

    /** The smoothed error (ns), for the room's status. */
    val errorNs: Long get() = smoothed.roundToLong()

    fun reset() {
        smoothed = 0.0
        integral = 0.0
        primed = false
        bigSince = -1L
    }

    /** One measurement, [dtSec] after the last. [nowNs] is only used to time the big-error check. */
    fun update(errorNs: Long, dtSec: Double, nowNs: Long): Decision {
        if (!primed) {
            primed = true
            smoothed = errorNs.toDouble()
            if (abs(errorNs) > jumpNs) return jump(errorNs)
        } else {
            // About a 0.4 s time constant.
            val a = (dtSec / 0.4).coerceIn(0.05, 1.0)
            smoothed += a * (errorNs - smoothed)
        }
        if (abs(smoothed) > jumpNs) {
            // Only a lasting big error jumps (one wild Bluetooth timestamp doesn't).
            if (bigSince < 0) bigSince = nowNs
            if (nowNs - bigSince >= jumpHoldNs) return jump(smoothed.roundToLong())
        } else bigSince = -1L

        val ms = smoothed / 1e6
        integral = (integral + ms * dtSec).coerceIn(-maxPpm / ki, maxPpm / ki)
        val ppm = (kp * ms + ki * integral).coerceIn(-maxPpm, maxPpm)
        return Decision.Rate(ppm)
    }

    private fun jump(ns: Long): Decision {
        smoothed = 0.0
        bigSince = -1L
        // The drift the integral learned still holds after a jump.
        return Decision.Jump(ns)
    }
}

/**
 * Turns a playback-rate wish in ppm into the whole-Hertz rates AudioTrack accepts, carrying
 * the fraction over so the average is exact (48 kHz has 1 Hz = 21 ppm steps).
 */
class RateDither(private val baseHz: Int) {
    private var carry = 0.0

    fun next(ppm: Double): Int {
        val want = baseHz * (1.0 + ppm / 1e6) + carry
        val hz = Math.round(want).toInt()
        carry = want - hz
        return hz
    }
}
