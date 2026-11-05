package com.ltots.app.audio

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import com.ltots.app.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * How efficiently a listening session played (Profile > the battery card): how much of the
 * time the audio chip played by itself, from a pre-rendered file, or with LToTS processing the
 * sound, how much of it was with the screen off, and how much battery the phone used meanwhile.
 *
 * A session starts with the first song played after [GAP_MS] of quiet. The battery figure is
 * the whole phone's, from Android's battery gauge; it's left out when the phone charged.
 */
@UnstableApi
object PowerStats {
    private const val GAP_MS = 10 * 60 * 1000L

    data class Session(
        /** When it started (wall clock). */
        val startedAt: Long = 0,
        /** Time spent playing, in total and split by how it was played. */
        val playedMs: Long = 0,
        val directMs: Long = 0,
        val renderedMs: Long = 0,
        val processedMs: Long = 0,
        /** Playing time with the screen off. */
        val screenOffMs: Long = 0,
        /** Battery level at the start and now, in percent. */
        val batteryStart: Int = -1,
        val batteryNow: Int = -1,
        /** Charge used meanwhile, in µAh (from the battery gauge), or -1 if the phone can't tell. */
        val usedMicroAh: Long = -1,
        /** True if the phone was charging at some point (then there's no battery figure). */
        val charged: Boolean = false,
        /** Songs pre-rendered and the processing time that took. */
        val renders: Int = 0,
        val renderMs: Long = 0,
    ) {
        val directShare: Float get() = if (playedMs > 0) directMs.toFloat() / playedMs else 0f
        val renderedShare: Float get() = if (playedMs > 0) renderedMs.toFloat() / playedMs else 0f
        val processedShare: Float get() = if (playedMs > 0) processedMs.toFloat() / playedMs else 0f
        /** mAh per hour of listening, when known. */
        val mAhPerHour: Float? get() = if (charged || usedMicroAh <= 0 || playedMs < 5 * 60_000L) null
            else usedMicroAh / 1000f / (playedMs / 3_600_000f)
    }

    private lateinit var battery: BatteryManager
    private val _session = MutableStateFlow<Session?>(null)
    /** The current (or last) listening session. */
    val session: StateFlow<Session?> = _session

    private var playing = false
    private var direct = false
    private var rendered = false
    private var screenOn = true
    private var since = 0L
    private var lastPlayedAt = 0L
    private var chargeStart = Long.MIN_VALUE
    private var renderBase = 0
    private var renderMsBase = 0L

    fun init(context: Context) {
        battery = context.applicationContext.getSystemService(BatteryManager::class.java)
        _session.value = runCatching { parse(JSONObject(file().readText())) }.getOrNull()
    }

    private fun file() = Store.userFile("power_session.json")

    @Synchronized fun onPlaying(on: Boolean) {
        account()
        if (on && !playing) {
            val now = SystemClock.elapsedRealtime()
            if (_session.value == null || lastPlayedAt == 0L || now - lastPlayedAt > GAP_MS) startSession()
        }
        playing = on
        if (!on) {
            lastPlayedAt = SystemClock.elapsedRealtime()
            save()
        }
    }

    @Synchronized fun onDirect(on: Boolean) { account(); direct = on }
    @Synchronized fun onRendered(on: Boolean) { account(); rendered = on }
    @Synchronized fun onScreen(on: Boolean) {
        account(); screenOn = on
        if (on) save()
    }

    private fun startSession() {
        val pct = level()
        chargeStart = charge()
        renderBase = PreRender.rendered
        renderMsBase = PreRender.renderMs
        _session.value = Session(
            startedAt = System.currentTimeMillis(),
            batteryStart = pct, batteryNow = pct,
            charged = com.ltots.app.playback.ScreenState.charging.value,
        )
    }

    /** Adds the time since the last change to the right bucket. */
    private fun account() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (since == 0L) 0L else now - since
        since = now
        val s = _session.value ?: return
        if (!playing || dt <= 0) return
        val c = charge()
        val used = if (chargeStart != Long.MIN_VALUE && c != Long.MIN_VALUE) (chargeStart - c).coerceAtLeast(0) else -1
        _session.value = s.copy(
            playedMs = s.playedMs + dt,
            directMs = s.directMs + if (direct && !rendered) dt else 0,
            renderedMs = s.renderedMs + if (direct && rendered) dt else 0,
            processedMs = s.processedMs + if (!direct) dt else 0,
            screenOffMs = s.screenOffMs + if (!screenOn) dt else 0,
            batteryNow = level(),
            usedMicroAh = used,
            charged = s.charged || com.ltots.app.playback.ScreenState.charging.value,
            renders = PreRender.rendered - renderBase,
            renderMs = PreRender.renderMs - renderMsBase,
        )
    }

    private fun level(): Int = if (!::battery.isInitialized) -1
        else runCatching { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)

    private fun charge(): Long {
        if (!::battery.isInitialized) return Long.MIN_VALUE
        val v = runCatching { battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE || v <= 0) Long.MIN_VALUE else v.toLong()
    }

    private fun save() {
        val s = _session.value ?: return
        runCatching { Store.writeLater(file()) { json(s).toString() } }
    }

    private fun json(s: Session) = JSONObject().apply {
        put("start", s.startedAt); put("played", s.playedMs); put("direct", s.directMs)
        put("rendered", s.renderedMs); put("processed", s.processedMs); put("off", s.screenOffMs)
        put("b0", s.batteryStart); put("b1", s.batteryNow); put("used", s.usedMicroAh)
        put("charged", s.charged); put("renders", s.renders); put("renderMs", s.renderMs)
    }

    private fun parse(o: JSONObject) = Session(
        startedAt = o.optLong("start"), playedMs = o.optLong("played"), directMs = o.optLong("direct"),
        renderedMs = o.optLong("rendered"), processedMs = o.optLong("processed"), screenOffMs = o.optLong("off"),
        batteryStart = o.optInt("b0", -1), batteryNow = o.optInt("b1", -1), usedMicroAh = o.optLong("used", -1),
        charged = o.optBoolean("charged"), renders = o.optInt("renders"), renderMs = o.optLong("renderMs"),
    )
}
