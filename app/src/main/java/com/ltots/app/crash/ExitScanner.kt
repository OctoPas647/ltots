package com.ltots.app.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The crashes LToTS can't catch itself: a crash in native code (the EQ, pitch/speed, the
 * download engine), a freeze that Android ended ("isn't responding"), and Android stopping
 * LToTS while it played (low memory, too much battery use, a battery saver or task killer).
 * In all of those the app is gone before any of its code can run — but Android keeps a record
 * of why every process ended (ApplicationExitInfo). Each start, LToTS reads the records it
 * hasn't seen yet and writes a report for each one that matters.
 */
object ExitScanner {
    private const val SEEN = "exit_seen"

    /** What happened and what to show. */
    data class Found(val file: File, val kind: CrashLog.Kind, val summary: String)

    /**
     * Reads new exit records and saves a report for each that matters. [lastTrail] is what the
     * previous run was doing when it ended (its saved steps), for the newest record.
     */
    fun scan(context: Context, lastTrail: String?): List<Found> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val prefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 32) }.getOrNull().orEmpty()
            .filter { it.processName == context.packageName } // the app itself, not the crash screen's process
            .sortedBy { it.timestamp }
        if (infos.isEmpty()) return emptyList()
        val fresh = infos.filter { it.timestamp > seen }
        prefs.edit().putLong(SEEN, infos.last().timestamp).apply()
        // Java crashes LToTS already reported itself (and the kill that ends them).
        val own = CrashLog.list(context).filter { it.kind == CrashLog.Kind.CRASH }.map { it.at }
        val newest = fresh.lastOrNull()
        val out = ArrayList<Found>()
        for (info in fresh) {
            val kind = kindOf(info) ?: continue
            if ((kind == CrashLog.Kind.CRASH || info.reason == ApplicationExitInfo.REASON_SIGNALED) &&
                own.any { kotlin.math.abs(it - info.timestamp) < 30_000L }
            ) continue
            val text = runCatching { report(context, info, kind, if (info === newest) lastTrail else null) }.getOrNull() ?: continue
            val file = runCatching { CrashLog.save(context, kind, text, info.timestamp) }.getOrNull() ?: continue
            if (kind == CrashLog.Kind.KILLED && cleaner(info)) {
                runCatching { com.ltots.app.playback.KeepAlive.onKilledByCleaner(context, info.timestamp) }
            }
            out += Found(file, kind, CrashLog.entry(file)?.summary ?: kind.title)
        }
        return out
    }

    /** Which kind of report this exit gets, or null when it was a normal end. */
    internal fun kindOf(info: ApplicationExitInfo): CrashLog.Kind? {
        // Did it matter? On screen, or playing in the background (the media service keeps the
        // app at "foreground service" importance while music plays).
        val mattered = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
        return when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> CrashLog.Kind.CRASH
            ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashLog.Kind.NATIVE
            ApplicationExitInfo.REASON_ANR -> CrashLog.Kind.FREEZE
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> CrashLog.Kind.CRASH
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            -> if (mattered) CrashLog.Kind.KILLED else null
            else -> null // EXIT_SELF (a normal close), PACKAGE_UPDATED (an update), USER_STOPPED…
        }
    }

    /** Stopped by the phone's battery manager or cleaner, not by the person. */
    private fun cleaner(info: ApplicationExitInfo): Boolean =
        com.ltots.app.playback.KeepAlive.isCleaner(info.description) ||
            info.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
            (info.reason == ApplicationExitInfo.REASON_USER_REQUESTED && com.ltots.app.playback.KeepAlive.isXiaomi && info.description.isNullOrBlank().not())

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native code)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE (too much CPU/battery in the background)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (the phone needed the memory)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE (an app component was switched on/off)"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by a signal)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (force stop / a task killer / the phone's battery manager)"
        else -> "reason $r"
    }

    private fun importanceName(i: Int): String = when {
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "in the background, playing (media service in the foreground)"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "in the background, noticeable"
        i <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "in the background (service)"
        else -> "in the background, idle"
    } + " ($i)"

    private fun report(context: Context, info: ApplicationExitInfo, kind: CrashLog.Kind, trail: String?): String {
        val trace: ByteArray? = runCatching {
            info.traceInputStream?.use { s ->
                // ANR traces can be several MB; the start (the main thread) is what matters.
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (buf.size() < 6 * 1024 * 1024) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
        }.getOrNull()
        val details: String
        val what: String
        when (kind) {
            CrashLog.Kind.NATIVE -> {
                val t = trace?.let { Tombstone.describe(it) }
                details = t ?: "(Android kept no readable native record.)"
                val sig = t?.lineSequence()?.firstOrNull()?.removePrefix("Signal: ") ?: "native crash"
                // The first frame outside Android's own libraries says whose code it was.
                val system = listOf("libc.so", "libart.so", "libbase.so", "libutils.so", "linker64", "libc++.so", "libandroid_runtime.so", "?")
                val frame = t?.lineSequence()?.filter { it.trimStart().startsWith("#") }
                    ?.firstOrNull { line -> system.none { s -> line.contains("  $s") || line.trimEnd().endsWith(" $s") } }
                    ?.substringAfter("  ", "")?.substringAfter("  ", "")?.trim()
                what = "Native crash: $sig" + (frame?.let { " in $it" } ?: "")
            }
            CrashLog.Kind.FREEZE -> {
                val text = trace?.toString(Charsets.UTF_8)
                details = text?.let { anrDetails(it) } ?: "(Android kept no thread dump.)"
                val subject = text?.lineSequence()?.firstOrNull { it.startsWith("Subject:") }?.removePrefix("Subject:")?.trim()
                val at = mainTop(text)
                what = "Froze: " + (subject ?: info.description ?: "not responding") + (at?.let { " — main thread at $it" } ?: "")
            }
            CrashLog.Kind.CRASH -> {
                details = info.description ?: ""
                what = "Crash: " + (info.description?.lineSequence()?.firstOrNull() ?: "exception")
            }
            else -> {
                details = ""
                what = "Stopped by Android: " + reasonName(info.reason).substringBefore(" (") +
                    (info.description?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
            }
        }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val summary = runCatching { info.processStateSummary?.toString(Charsets.UTF_8) }.getOrNull()
        return buildString {
            appendLine("LToTS — ${kind.title}")
            appendLine("What: $what")
            appendLine("When: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(info.timestamp))}")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}); LToTS now $version")
            appendLine("Android's reason: ${reasonName(info.reason)}")
            info.description?.takeIf { it.isNotBlank() }?.let { appendLine("Android's note: $it") }
            appendLine("LToTS was: ${importanceName(info.importance)}")
            appendLine("Memory then: PSS ${info.pss / 1024} MB, RSS ${info.rss / 1024} MB")
            if (!summary.isNullOrBlank()) appendLine("Doing: $summary")
            appendLine("Found by: Android's exit record (read when LToTS next started)")
            appendLine()
            if (kind == CrashLog.Kind.KILLED && cleaner(info)) {
                append(com.ltots.app.playback.KeepAlive.adviceText())
                appendLine()
            }
            if (details.isNotBlank()) {
                appendLine(details.trimEnd())
                appendLine()
            }
            if (!trail.isNullOrBlank()) {
                appendLine("Last saved app state (a few seconds before the end at most):")
                appendLine(trail.trimEnd())
            }
        }
    }

    /** The first LToTS (or else first) frame of the main thread in an ANR dump. */
    private fun mainTop(text: String?): String? {
        text ?: return null
        val main = threadBlocks(text).firstOrNull { it.startsWith("\"main\"") } ?: return null
        val frames = main.lineSequence().map { it.trim() }.filter { it.startsWith("at ") }.toList()
        return (frames.firstOrNull { "com.ltots" in it } ?: frames.firstOrNull())?.removePrefix("at ")
    }

    private fun threadBlocks(text: String): List<String> =
        text.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.startsWith("\"") }

    /** The header, the main thread, and the LToTS threads that were blocked or busy. */
    internal fun anrDetails(text: String): String {
        val header = text.lineSequence().takeWhile { !it.startsWith("\"") }.take(25)
            .filter { it.isNotBlank() && !it.startsWith("Build fingerprint") && !it.startsWith("ABI:") }.joinToString("\n")
        val blocks = threadBlocks(text)
        val main = blocks.firstOrNull { it.startsWith("\"main\"") }
        // Threads holding or waiting for a lock the main thread may be waiting for, and LToTS's own.
        val waitedLocks = Regex("""waiting to lock <(0x[0-9a-f]+)>""").findAll(main ?: "").map { it.groupValues[1] }.toSet()
        val related = blocks.filter { b ->
            b !== main && (
                waitedLocks.any { l -> "locked <$l>" in b } ||
                    ("com.ltots" in b && ("Blocked" in b.lineSequence().first() || "Runnable" in b.lineSequence().first() || "Native" in b.lineSequence().first()))
                )
        }.take(12)
        return buildString {
            if (header.isNotBlank()) appendLine(header).appendLine()
            if (main != null) appendLine(main).appendLine() else appendLine("(No main thread in the dump.)")
            for (b in related) appendLine(b.lineSequence().take(40).joinToString("\n")).appendLine()
        }
    }
}
