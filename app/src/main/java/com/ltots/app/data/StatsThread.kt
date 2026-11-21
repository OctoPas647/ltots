package com.ltots.app.data

import java.util.concurrent.Executors

/**
 * The listening history and the play statistics are updated here, one after another, never on
 * the main thread. They read and write files and take locks that loading or Wrapped hold for a
 * while (a big imported history takes seconds to read); done on the main thread — as the
 * player's events used to — that froze the app, and long enough freezes make Android close it.
 */
object StatsThread {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LToTS-stats").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
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
