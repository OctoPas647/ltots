package com.ltots.app.crash

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * Watches the main thread (the one that draws the screen and runs the player). When it stops
 * answering for [FREEZE_MS], it writes down exactly where it is stuck — before Android decides
 * the app "isn't responding" and closes it. If it comes back, the report says for how long it
 * froze; if Android closes it instead, the next start finds Android's own record too.
 *
 * Uses uptime, which stops while the phone sleeps, so deep sleep never counts as a freeze (and
 * a frozen-in-the-background app has this thread frozen too).
 */
object Watchdog {
    const val FREEZE_MS = 4_000L
    private const val TICK_MS = 1_000L

    @Volatile private var lastAnswer = SystemClock.uptimeMillis()
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val answer = Runnable { lastAnswer = SystemClock.uptimeMillis() }
        Thread({
            var reported: File? = null
            var stuckSince = 0L
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = SystemClock.uptimeMillis()
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
                    main.post(answer)
                    continue
                }
                if (reported == null && silent >= FREEZE_MS) {
                    stuckSince = lastAnswer
                    val stack = Looper.getMainLooper().thread.stackTrace
                    reported = runCatching { CrashLog.save(app, CrashLog.Kind.FREEZE_RECOVERED, freezeReport(app, stack, silent)) }.getOrNull()
                }
                // Keep asking: the answer arrives as soon as the main thread is free again.
                if (silent % 3_000L < TICK_MS) main.post(answer)
            }
        }, "LToTS-watchdog").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        main.post(answer)
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
