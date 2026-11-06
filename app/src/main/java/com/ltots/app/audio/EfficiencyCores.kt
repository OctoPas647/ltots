package com.ltots.app.audio

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process

/**
 * Background playback on the phone's efficiency cores.
 *
 * While LToTS isn't on screen, the audio thread (where the EQ, pitch and speed run) is pinned
 * to the lowest-capacity cluster of cores: the work is a steady trickle that never needs a big
 * core, and a small core does the same work for less energy. With LToTS back on screen it may
 * use every core again, so slider moves stay instant.
 *
 * Two layers, both best-effort:
 *  - **Affinity** (native, every Android version): the thread may only be scheduled on those
 *    cores. Phones with one kind of core, or where the app isn't allowed, simply skip it.
 *  - **ADPF power-efficiency hint** (Android 15+): tells the system's power HAL this thread
 *    prefers efficiency over speed, which also keeps its clock low on the cores it does use.
 *
 * The audio thread applies the change itself, the next time it processes a block (a thread can
 * only be moved reliably from inside), so nothing here blocks the main thread. Pre-render and
 * indexing threads are pinned for good when they start ([NativeDsp.THREAD_BACKGROUND]).
 */
object EfficiencyCores {
    /** ADPF needs a target; the audio thread's real work per wake-up is far below this. */
    private const val TARGET_WORK_NS = 50_000_000L

    private var appContext: Context? = null
    @Volatile private var wanted = false
    @Volatile private var generation = 0

    // Only touched on the audio thread.
    private var appliedGen = -1
    private var appliedThread: Thread? = null
    private var hint: PerformanceHintManager.Session? = null

    /** What the last change returned (cores now usable, 0 nothing to do, < 0 refused). For the stats card and tests. */
    @Volatile var lastResult = 0; private set
    /** Whether the audio thread is on the efficiency cores right now. */
    @Volatile var audioThreadEfficient = false; private set

    /** How many efficiency cores the phone has (0: one kind of core, nothing to pin). */
    val efficiencyCores: Int by lazy { if (NativeDsp.available) runCatching { NativeDsp.efficiencyCoreCount() }.getOrDefault(0) else 0 }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Background playback (true) or LToTS on screen (false). Applied by the audio thread. */
    fun setEfficient(on: Boolean) {
        if (on == wanted) return
        wanted = on
        generation++
    }

    val isEfficientWanted: Boolean get() = wanted

    /**
     * Called by the player's first audio processor for every block: brings this thread in line
     * with [setEfficient]. Costs two field reads when nothing changed.
     */
    fun onAudioThread() {
        val g = generation
        val t = Thread.currentThread()
        if (g == appliedGen && t === appliedThread) return
        if (t !== appliedThread) closeHint() // a new player thread: the old session was for another
        appliedGen = g
        appliedThread = t
        val on = wanted
        if (NativeDsp.available) lastResult = runCatching { NativeDsp.setThreadEfficiency(on) }.getOrDefault(0)
        audioThreadEfficient = on && lastResult > 0
        powerHint(on)
    }

    private fun powerHint(on: Boolean) {
        if (Build.VERSION.SDK_INT < 35) return
        if (!on) { closeHint(); return }
        if (hint == null) {
            hint = runCatching {
                appContext?.getSystemService(PerformanceHintManager::class.java)
                    ?.createHintSession(intArrayOf(Process.myTid()), TARGET_WORK_NS)
            }.getOrNull()
        }
        runCatching { hint?.setPreferPowerEfficiency(true) }
    }

    private fun closeHint() {
        runCatching { hint?.close() }
        hint = null
    }
}
