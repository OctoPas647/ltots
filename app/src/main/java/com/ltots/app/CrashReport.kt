package com.ltots.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ltots.app.crash.CrashLog
import com.ltots.app.crash.CrashNotify
import com.ltots.app.crash.ExitScanner
import com.ltots.app.crash.Watchdog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Crash reports, everywhere LToTS can end:
 *
 *  - **A Java/Kotlin crash on any thread** (screen on or off, app open or in the background):
 *    the report is saved, a notification is posted (Android doesn't let a background app open a
 *    screen) and, when LToTS is in front, the report screen opens at once.
 *  - **A native crash, a freeze Android closed, or Android stopping LToTS while it played**:
 *    no app code can run then, so the next start reads Android's own record of how the process
 *    ended ([ExitScanner]) and turns it into a report (notification + screen).
 *  - **A freeze that recovers**: the [Watchdog] writes down where the main thread was stuck.
 *
 * Every report is a text file (Profile › Crash reports, and Download/LToTS/Crash reports).
 * The report screen runs in its own small process, so it never crashes itself.
 */
object CrashReport {
    const val PROCESS_SUFFIX = ":crash"

    /** The newest report (kept for older callers). */
    fun file(context: Context): File? = CrashLog.list(context).firstOrNull()?.file

    // ---- what the UI was doing -----------------------------------------------------------------
    // A stack trace alone often can't say which screen or gesture led to a crash (Compose traces
    // are mostly framework code). The UI keeps these up to date, and every report includes them.

    private val state = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val TRAIL_SIZE = 30
    private val trail = ArrayDeque<String>(TRAIL_SIZE)
    private val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
    @Volatile private var dirty = false

    /** True while LToTS is on screen (MainActivity started): only then can a crash open its screen. */
    @Volatile var inFront = false
    @Volatile private var app: Application? = null

    /**
     * The current value of one piece of UI state ("tab" = "LIBRARY"). Cheap: call it whenever
     * the value changes, never every frame.
     */
    fun note(key: String, value: String) {
        if (state.put(key, value) != value) {
            dirty = true
            if (key in SUMMARY_KEYS) summaryDirty = true
        }
    }

    /** One step the person took or the UI went through ("tab HOME → LIBRARY (swipe)"); the last 30 are kept. */
    fun step(event: String) {
        synchronized(trail) {
            // SimpleDateFormat isn't thread-safe: only used under this lock.
            val line = runCatching { clock.format(java.util.Date()) }.getOrDefault("?") + "  " + event
            if (trail.size == TRAIL_SIZE) trail.removeFirst()
            trail.addLast(line)
        }
        dirty = true
    }

    /**
     * For background work that outlives any screen (indexing, lyrics, downloads, saving settings):
     * an error nobody caught there used to take the whole app down, music included. With this on
     * the job's scope the one job stops, the error is kept as a step in the next report, and
     * everything else carries on.
     */
    fun guard(where: String) = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        handled(where, e)
        runCatching { android.util.Log.w("LToTS", "background job failed in $where", e) }
    }

    /** Something went wrong but was handled (the app carried on): kept as a step in the report. */
    fun handled(where: String, error: Throwable) {
        step("handled in $where: ${error.javaClass.simpleName}: ${error.message?.take(160)}")
    }

    internal fun uiSection(): String = buildString {
        val keys = state.keys.sorted()
        val steps = synchronized(trail) { trail.toList() }
        if (keys.isEmpty() && steps.isEmpty()) return@buildString
        appendLine("UI at the time:")
        keys.forEach { appendLine("  $it: ${state[it]}") }
        if (steps.isNotEmpty()) {
            appendLine("Last steps (oldest first):")
            steps.forEach { appendLine("  $it") }
        }
        appendLine()
    }

    /** Clears the UI state and steps (tests). */
    internal fun clearUi() {
        state.clear()
        synchronized(trail) { trail.clear() }
    }

    // ---- what Android keeps for us -----------------------------------------------------------------
    // Android stores up to 128 bytes with each process's exit record: what LToTS was doing in a
    // few words, so even a native crash or a kill says "playing, screen off, in a room".

    private val SUMMARY_KEYS = setOf("tab", "overlay", "playing", "screen", "room", "song")
    @Volatile private var summaryDirty = true

    private fun summary(): String {
        val parts = listOf("playing", "screen", "room", "tab", "overlay", "song").mapNotNull { k -> state[k]?.let { "$k=$it" } }
        var s = parts.joinToString(" ")
        while (s.toByteArray().size > 128) s = s.dropLast(1)
        return s
    }

    // ---- the trail on disk ---------------------------------------------------------------------------
    // For the endings no code can see (native crash, kill): the steps as they were a moment before.

    private fun trailFile(context: Context) = File(CrashLog.dir(context), "trail.log")

    private fun startPersister(app: Application) {
        Thread({
            val am = app.getSystemService(ActivityManager::class.java)
            while (true) {
                try { Thread.sleep(2_000) } catch (_: InterruptedException) { return@Thread }
                if (dirty) {
                    dirty = false
                    runCatching { trailFile(app).writeText(uiSection()) }
                }
                if (summaryDirty) {
                    summaryDirty = false
                    runCatching { am.setProcessStateSummary(summary().toByteArray()) }
                }
            }
        }, "LToTS-crash-trail").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    // ---- install ---------------------------------------------------------------------------------------

    /** Catches every crash in this process from now on. Call first thing in Application.onCreate. */
    fun install(app: Application) {
        this.app = app
        // What the previous run was doing when it ended (for a native crash or a kill).
        val lastTrail = runCatching {
            val f = trailFile(app)
            if (f.exists()) f.readText().also { f.delete() } else null
        }.getOrNull()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                onCrash(app, thread, error)
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
        startPersister(app)
        // Not in the desktop tests (their main thread only runs when the test lets it).
        if (Build.FINGERPRINT == "robolectric") return
        Watchdog.start(app)
        // How the last run ended, once the app is up (never in the way of starting).
        Thread({
            try { Thread.sleep(1_500) } catch (_: InterruptedException) {}
            runCatching { scanExits(app, lastTrail) }
            runCatching { CrashLog.exportPending(app) }
        }, "LToTS-crash-scan").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun onCrash(app: Application, thread: Thread, error: Throwable) {
        val text = report(app, thread, error)
        val file = runCatching { CrashLog.save(app, CrashLog.Kind.CRASH, text) }.getOrNull()
        if (file == null) {
            // Couldn't even save: still show it.
            runCatching { app.startActivity(Intent(app, CrashActivity::class.java).putExtra("report", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
            return
        }
        // Always a notification: in the background (screen off, another app) Android blocks the
        // screen from opening, and this is how the report still reaches you.
        CrashNotify.show(app, CrashLog.Kind.CRASH, file, whatLine(thread, error))
        if (inFront) {
            runCatching { app.startActivity(CrashNotify.openIntent(app, file, clearTask = true)) }
        }
    }

    private fun scanExits(app: Application, lastTrail: String?) {
        val found = ExitScanner.scan(app, lastTrail)
        if (found.isEmpty()) return
        val newest = found.last()
        CrashNotify.show(app, newest.kind, newest.file, newest.summary, extra = found.size - 1)
        // In front right now (you just opened LToTS after it crashed): show it straight away.
        val toShow = found.lastOrNull { it.kind.opensScreen }
        if (toShow != null && inFront) {
            CrashLog.markSeen(app, toShow.file)
            runCatching { app.startActivity(CrashNotify.openIntent(app, toShow.file, clearTask = false)) }
        }
    }

    private fun whatLine(thread: Thread, error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val at = (root.stackTrace.firstOrNull { it.className.startsWith("com.ltots") } ?: root.stackTrace.firstOrNull())?.let { " at $it" } ?: ""
        return "Crash on \"${thread.name}\": ${root.javaClass.simpleName}: ${root.message?.take(160) ?: ""}$at"
    }

    internal fun report(context: Context, thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val front = inFront
        return buildString {
            appendLine("LToTS $version crashed")
            appendLine("What: ${runCatching { whatLine(thread, error) }.getOrDefault(error.toString())}")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine("App: ${if (front) "on screen" else "in the background"}")
            appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}")
            appendLine()
            // Never let the extra section stop the report itself from being written.
            append(runCatching { uiSection() }.getOrDefault(""))
            append(sw.toString())
            // Crashed off the main thread: where the main thread was helps too.
            if (thread !== android.os.Looper.getMainLooper().thread) {
                runCatching {
                    appendLine()
                    appendLine("Main thread at the time:")
                    android.os.Looper.getMainLooper().thread.stackTrace.take(30).forEach { appendLine("    at $it") }
                }
            }
        }
    }

    fun isCrashProcess(app: Application): Boolean =
        runCatching { Application.getProcessName().endsWith(PROCESS_SUFFIX) }.getOrDefault(false)
}

/**
 * The crash report: what went wrong, with Copy, Share, Open LToTS and Delete. Plain views, no
 * app code. Opens a saved report ([EXTRA_FILE]) or a report passed as text ("report").
 */
class CrashActivity : Activity() {
    companion object {
        const val EXTRA_FILE = "file"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_FILE)?.let { File(it) }?.takeIf { it.exists() }
        val report = intent.getStringExtra("report")
            ?: file?.let { runCatching { it.readText() }.getOrNull() }
            ?: CrashLog.list(this).firstOrNull()?.file?.let { runCatching { it.readText() }.getOrNull() }
            ?: "No report."
        val entry = file?.let { CrashLog.entry(it) }
        file?.let { CrashLog.markSeen(this, it) }
        // Copies to Download/LToTS/Crash reports (off the main thread).
        Thread({ runCatching { CrashLog.exportPending(applicationContext) } }, "LToTS-crash-export").start()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(pad, pad * 3, pad, pad * 2)
        }
        root.addView(TextView(this).apply {
            text = entry?.kind?.title ?: "LToTS crashed"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Copy or share this report and send it to Claude so it can be fixed. It's also saved in " +
                "Download/${CrashLog.EXPORT_FOLDER} and in LToTS under Profile › Crash reports."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad / 2, 0, pad)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        buttons.addView(button("Copy") {
            // The clipboard has a size limit (~1 MB of binder space): very long reports are cut.
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LToTS crash", report.take(200_000)))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        buttons.addView(button("Share") {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report.take(200_000)), "Share crash report"))
            }
        })
        buttons.addView(button("Open LToTS") {
            startActivity(packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        })
        if (file != null) buttons.addView(button("Delete") {
            CrashLog.delete(this, file)
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            finish()
        })
        root.addView(HorizontalScrollView(this).apply { addView(buttons) })
        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                setTextIsSelectable(true)
                setPadding(0, pad, 0, 0)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
