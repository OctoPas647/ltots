package com.ltots.app.data

import java.util.concurrent.Executors

/**
 * The listening history and the play statistics are updated here, one after another, never on
 * the main thread. They read and write files and take locks that loading or Wrapped hold for a
 * while (a big imported history takes seconds to read); done on the main thread — as the
 * player's events used to — that froze the app, and long enough freezes make Android close it.
 */
object StatsThread {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LToTS-stats").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }

    /** How often the time being heard is added up while music plays. */
    private const val TICK_S = 20L

    @Volatile private var playing = false
    @Volatile private var ticking = false

    /**
     * The player started or stopped. While playing, the history and the play counts are brought
     * up to date every [TICK_S] seconds on this thread, screen on or off. A timer on the
     * monotonic clock never wakes a sleeping phone; while music plays the phone is awake anyway.
     */
    fun setPlaying(on: Boolean) {
        playing = on
        if (!on || ticking) return
        ticking = true
        runCatching {
            executor.scheduleWithFixedDelay({
                if (!playing) return@scheduleWithFixedDelay
                try {
                    ListenHistory.tick()
                    PlayStats.tick()
                } catch (e: Throwable) {
                    com.ltots.app.CrashReport.handled("stats tick", e)
                }
            }, TICK_S, TICK_S, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { ticking = false }
    }

    fun post(block: () -> Unit) {
        runCatching {
            executor.execute {
                try {
                    block()
                } catch (e: Throwable) {
                    com.ltots.app.CrashReport.handled("stats", e)
                }
            }
        }
    }
}
