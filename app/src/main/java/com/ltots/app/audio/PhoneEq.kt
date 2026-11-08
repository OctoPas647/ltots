package com.ltots.app.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The phone's own EQ (Android's Equalizer effect) on LToTS's audio, for Battery EQ.
 *
 * It runs inside Android's audio system instead of inside LToTS. On many phones that EQ can run
 * on the audio chip itself, so the chip can keep playing the music on its own (the processor
 * sleeps) even with an EQ on. Whether this phone can do that shows in [AudioEngine.direct].
 */
class PhoneEq {
    sealed interface Status {
        /** Battery EQ is off. */
        data object Off : Status
        /** The phone has no EQ LToTS can use, or another app is in control of it. */
        data class Unavailable(val reason: String) : Status
        /** Attached; [levels] are what the phone's bands are set to (all 0 = switched off). */
        data class Ready(val layout: PhoneEqLayout, val levels: FloatArray, val bassDb: Float = 0f) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status

    private var effect: Equalizer? = null
    /** The phone's bass boost, used with the EQ for the deepest bass (null if there isn't one). */
    private var bass: BassBoost? = null
    private var bassApplied = 0f
    /** Whether the phone has a bass boost LToTS can use on this audio. */
    val bassAvailable: Boolean get() = bass != null
    private var session = 0
    var layout: PhoneEqLayout? = null
        private set
    private var applied: FloatArray? = null

    /** Connect to [audioSession] (LToTS's player). Returns false if the phone's EQ can't be used. */
    fun attach(audioSession: Int): Boolean {
        if (effect != null && session == audioSession) return layout != null
        release()
        if (audioSession <= 0) return false
        val eq = try {
            Equalizer(PRIORITY, audioSession)
        } catch (t: Throwable) {
            _status.value = Status.Unavailable("This phone has no built-in EQ that apps can use.")
            return false
        }
        val l = try {
            readLayout(eq)
        } catch (t: Throwable) {
            null
        }
        if (l == null || l.bandCount == 0) {
            runCatching { eq.release() }
            _status.value = Status.Unavailable("This phone's built-in EQ didn't report its bands.")
            return false
        }
        effect = eq
        session = audioSession
        layout = l
        applied = null
        bass = runCatching { BassBoost(PRIORITY, audioSession).takeIf { it.strengthSupported } }.getOrNull()
        bassApplied = 0f
        _status.value = Status.Ready(l, FloatArray(l.bandCount))
        return true
    }

    /** Set the phone's bands (dB). All zero switches the phone's EQ off so it costs nothing. */
    fun apply(levels: FloatArray) {
        val eq = effect ?: return
        val l = layout ?: return
        if (applied?.contentEquals(levels) == true) return
        try {
            val lo = (l.minDb * 100).roundToInt()
            val hi = (l.maxDb * 100).roundToInt()
            for (i in 0 until minOf(levels.size, l.bandCount)) {
                eq.setBandLevel(i.toShort(), (levels[i] * 100).roundToInt().coerceIn(lo, hi).toShort())
            }
            val on = levels.any { abs(it) >= 0.05f }
            if (eq.enabled != on) eq.enabled = on
            applied = levels.copyOf()
            _status.value = Status.Ready(l, levels.copyOf(), bassApplied)
        } catch (t: Throwable) {
            // Changing it fails when another app (a system sound app, say) has taken control.
            val other = runCatching { !eq.hasControl() }.getOrDefault(false)
            _status.value = Status.Unavailable(
                if (other) "Another app is using the phone's built-in EQ right now." else "The phone's built-in EQ stopped responding."
            )
        }
    }

    /**
     * The bass boost in dB (0 switches it off). Its strength (0..1000) is mapped evenly onto
     * 0..[BatteryEqFit.BASS_MAX_DB]; phones differ a little in exactly how much a strength boosts.
     */
    fun applyBass(db: Float) {
        val b = bass ?: return
        if (abs(db - bassApplied) < 0.05f) return
        runCatching {
            val strength = (db / BatteryEqFit.BASS_MAX_DB * 1000f).roundToInt().coerceIn(0, 1000)
            if (strength > 0) b.setStrength(strength.toShort())
            val on = strength > 0
            if (b.enabled != on) b.enabled = on
            bassApplied = db
            (_status.value as? Status.Ready)?.let { _status.value = it.copy(bassDb = db) }
        }.onFailure { runCatching { b.release() }; bass = null; bassApplied = 0f }
    }

    /** What the phone's EQ actually has right now: (switched on, band levels in dB), or null. */
    fun readBack(): Pair<Boolean, FloatArray>? {
        val eq = effect ?: return null
        val l = layout ?: return null
        return runCatching { eq.enabled to FloatArray(l.bandCount) { eq.getBandLevel(it.toShort()) / 100f } }.getOrNull()
    }

    /** Let go of the phone's EQ (Battery EQ off, or the player closed). */
    fun release(off: Boolean = false) {
        runCatching { bass?.enabled = false }
        runCatching { bass?.release() }
        bass = null
        bassApplied = 0f
        runCatching { effect?.enabled = false }
        runCatching { effect?.release() }
        effect = null
        session = 0
        layout = null
        applied = null
        if (off) _status.value = Status.Off
    }

    private fun readLayout(eq: Equalizer): PhoneEqLayout {
        val n = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        return PhoneEqLayout.sanitized(
            centersHz = List(n) { eq.getCenterFreq(it.toShort()) / 1000f },
            lowHz = List(n) { eq.getBandFreqRange(it.toShort())[0] / 1000f },
            highHz = List(n) { eq.getBandFreqRange(it.toShort())[1] / 1000f },
            minDb = range[0] / 100f,
            maxDb = range[1] / 100f,
        )
    }

    private companion object {
        /** Ahead of other apps' default priority (0), so LToTS keeps control of its own audio. */
        const val PRIORITY = 1000
    }
}
