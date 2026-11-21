package com.ltots.app.crash

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.StringBuilderPrinter
import java.io.File

/**
 * Watches the main thread (the one that draws the screen and runs the player). When it stops
 * answering for [FREEZE_MS], it writes down exactly where it is stuck — before Android decides
 * the app "isn't responding" and closes it. If it comes back, the report says for how long it
 * froze; if Android closes it instead, the next start finds Android's own record too.
 *
 * Three things used to look like a freeze and weren't (they filled the reports with "froze
 * 382234 ms" while LToTS sat in the background on HyperOS):
 *  - **The whole app paused by Android** (the cached-app freezer, HyperOS' own freezer): this
 *    watch is paused too, so when everything thaws it sees a long silence before the main
 *    thread had a chance to answer. Now: if this thread itself slept far longer than it asked
 *    to, the silence is Android's doing and nothing is reported.
 *  - **The main thread idle, but our question held back**: an ordinary message waits behind a
 *    "sync barrier" (put there when a window asks to be drawn, removed when Android gives it the
 *    next frame). With LToTS hidden — screen on, another app or the lock screen in front — some
 *    phones don't give the hidden window frames, so ordinary messages wait until they do. The
 *    question is now asked as an *asynchronous* message, which passes the barrier, so only a
 *    thread that's really busy counts as frozen.
 *  - Ordinary messages held back that long are still worth knowing about (the player's own
 *    callbacks are ordinary messages): that's measured separately and saved once as a
 *    "Held back" report with the main thread's message queue, never as a freeze.
 *
 * Uses uptime, which stops while the phone sleeps, so deep sleep never counts either.
 */
object Watchdog {
    const val FREEZE_MS = 4_000L
    private const val TICK_MS = 1_000L
    /** This watch slept this much longer than asked: Android paused the whole app. */
    private const val PAUSED_MS = 2_500L
    /** Ordinary messages held back this long (while the main thread answers) get a report. */
    private const val HELD_MS = 10_000L
    /** At most one "Held back" report per this long. */
    private const val HELD_REPORT_EVERY_MS = 6 * 60 * 60 * 1000L

    @Volatile private var lastAnswer = SystemClock.uptimeMillis()
    @Volatile private var lastSync = SystemClock.uptimeMillis()
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val answer = Runnable { lastAnswer = SystemClock.uptimeMillis() }
        val syncAnswer = Runnable { lastSync = SystemClock.uptimeMillis() }
        // Asynchronous: passes a sync barrier, so it measures the thread, not the window's frames.
        fun ask() {
            val m = Message.obtain(main, answer)
            m.isAsynchronous = true
            main.sendMessage(m)
        }
        Thread({
            var reported: File? = null
            var stuckSince = 0L
            var heldSince = 0L
            var heldNoted = false
            var lastHeldReport = 0L
            while (true) {
                val before = SystemClock.uptimeMillis()
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = SystemClock.uptimeMillis()
                if (now - before > TICK_MS + PAUSED_MS) {
                    // This thread didn't run either: Android paused the whole app (or the CPU was
                    // starved). Not a freeze of LToTS's: start counting again from now.
                    if (now - before > 30_000L) com.ltots.app.CrashReport.step("paused by Android ${(now - before) / 1000} s")
                    lastAnswer = now
                    lastSync = now
                    heldSince = 0L
                    heldNoted = false
                    ask(); main.post(syncAnswer)
                    continue
                }
                val silent = now - lastAnswer
                if (silent < TICK_MS + 500) {
                    // Answering. If it had frozen, finish that report with how long it lasted.
                    reported?.let { f ->
                        val froze = now - stuckSince
                        runCatching {
                            f.writeText(
                                f.readText().replaceFirst("What: Froze", "What: Froze for ${"%.1f".format(froze / 1000.0)} s")
                                    .replaceFirst("Still frozen when written.", "It recovered after ${"%.1f".format(froze / 1000.0)} s."),
                            )
                        }
                        com.ltots.app.CrashReport.step("froze ${froze} ms (report saved)")
                    }
                    reported = null
                    ask()
                    // Ordinary messages: are they getting through too?
                    val held = now - lastSync
                    if (held < TICK_MS + 500) {
                        if (heldNoted) com.ltots.app.CrashReport.step("ordinary work held back ${now - heldSince} ms")
                        heldSince = 0L
                        heldNoted = false
                    } else {
                        if (heldSince == 0L) heldSince = lastSync
                        if (!heldNoted && held >= HELD_MS) {
                            heldNoted = true
                            if (now - lastHeldReport > HELD_REPORT_EVERY_MS) {
                                lastHeldReport = now
                                runCatching { CrashLog.save(app, CrashLog.Kind.STALL, heldReport(app, held)) }
                            }
                        }
                    }
                    main.post(syncAnswer)
                    continue
                }
                if (reported == null && silent >= FREEZE_MS) {
                    val stack = Looper.getMainLooper().thread.stackTrace
                    if (idle(stack)) {
                        // Waiting for its next message, not stuck: our question got lost. Ask again.
                        lastAnswer = now
                        ask()
                        continue
                    }
                    stuckSince = lastAnswer
                    reported = runCatching { CrashLog.save(app, CrashLog.Kind.FREEZE_RECOVERED, freezeReport(app, stack, silent)) }.getOrNull()
                }
                // Keep asking: the answer arrives as soon as the main thread is free again.
                if (silent % 3_000L < TICK_MS) ask()
            }
        }, "LToTS-watchdog").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        ask()
        main.post(syncAnswer)
    }

    /** The main thread is parked waiting for messages (nothing of LToTS's is running on it). */
    internal fun idle(stack: Array<StackTraceElement>): Boolean {
        val top = stack.firstOrNull() ?: return false
        return top.className == "android.os.MessageQueue" && top.methodName.startsWith("nativePollOnce")
    }

    /** The main thread's message queue, as Android prints it (a sync barrier shows as "target=null"). */
    private fun queueDump(): String = runCatching {
        val sb = StringBuilder()
        Looper.getMainLooper().dump(StringBuilderPrinter(sb), "  ")
        sb.lines().take(60).joinToString("\n")
    }.getOrDefault("(not available)")

    private fun heldReport(context: Context, heldMs: Long): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        return buildString {
            appendLine("LToTS $version — Android held back ordinary work")
            appendLine("What: Held back ${heldMs / 1000} s (the screen thread was free)")
            appendLine("When: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}")
            appendLine("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("The main thread answered, but ordinary messages waited ${heldMs} ms — usually behind a")
            appendLine("window waiting for a frame Android doesn't give a hidden app. Not a freeze.")
            appendLine("Found by: LToTS's own freeze watch")
            appendLine()
            appendLine("Main thread's queue:")
            appendLine(queueDump())
            appendLine()
            append(runCatching { com.ltots.app.CrashReport.uiSection() }.getOrDefault(""))
        }
    }

    private fun freezeReport(context: Context, stack: Array<StackTraceElement>, silentMs: Long): String {
        val top = stack.firstOrNull { it.className.startsWith("com.ltots") } ?: stack.firstOrNull()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        return buildString {
            appendLine("LToTS $version — the screen froze")
            appendLine("What: Froze at ${top ?: "?"}")
            appendLine("When: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}")
            appendLine("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Main thread not answering for ${silentMs} ms. Still frozen when written.")
            appendLine("Found by: LToTS's own freeze watch")
            appendLine()
            appendLine("Main thread:")
            stack.forEach { appendLine("    at $it") }
            appendLine()
            appendLine("Main thread's queue:")
            appendLine(queueDump())
            appendLine()
            // Who holds what the main thread waits for: every LToTS thread that's busy or blocked.
            val others = runCatching { Thread.getAllStackTraces() }.getOrNull().orEmpty()
                .filter { (t, s) -> t !== Looper.getMainLooper().thread && s.any { it.className.startsWith("com.ltots") } }
                .entries.sortedBy { it.key.name }
            for ((t, s) in others.take(25)) {
                appendLine("\"${t.name}\" ${t.state}")
                s.take(25).forEach { appendLine("    at $it") }
                appendLine()
            }
            append(runCatching { com.ltots.app.CrashReport.uiSection() }.getOrDefault(""))
        }
    }
}
