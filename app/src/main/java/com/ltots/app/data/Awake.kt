package com.ltots.app.data

import java.util.Calendar

/**
 * When the phone was last clearly in someone's hands: a touch in LToTS, or the screen coming on.
 * Wrapped's "sleep track" uses it: music that plays on late at night long after the last sign
 * of life was most likely playing to someone asleep.
 */
object Awake {
    /** Untouched for this long (ms) counts as "nobody's there". */
    const val GONE_AFTER_MS = 20 * 60_000L

    @Volatile private var lastAt = System.currentTimeMillis()

    fun mark() { lastAt = System.currentTimeMillis() }

    /** Between midnight and 5 am, with the phone untouched for 20 minutes or more. */
    fun asleepAt(at: Long = System.currentTimeMillis()): Boolean = isNight(at) && at - lastAt >= GONE_AFTER_MS

    fun isNight(at: Long): Boolean = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY) in 0..4
}
