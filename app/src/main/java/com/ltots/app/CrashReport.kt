package com.ltots.app

import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * When LToTS crashes, the error is saved and shown on a plain screen (in its own small process,
 * so it never crashes itself), with buttons to copy or share it. That report says exactly what
 * went wrong and where, so a crash can be fixed without a computer or a USB cable.
 */
object CrashReport {
    const val PROCESS_SUFFIX = ":crash"
    private const val FILE = "last_crash.txt"

    fun file(context: Context) = File(context.filesDir, FILE)

    /** Catches every crash in this process from now on. Call first thing in Application.onCreate. */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = report(app, thread, error)
                runCatching { file(app).writeText(text) }
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .putExtra("report", text)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private fun report(context: Context, thread: Thread, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return buildString {
            appendLine("LToTS $version crashed")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())}")
            appendLine()
            append(sw.toString())
        }
    }

    fun isCrashProcess(app: Application): Boolean =
        runCatching { Application.getProcessName().endsWith(PROCESS_SUFFIX) }.getOrDefault(false)
}

/** The crash report: what went wrong, with Copy, Share and Open LToTS. Plain views, no app code. */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra("report")
            ?: runCatching { CrashReport.file(this).readText() }.getOrNull()
            ?: "No report."
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(pad, pad * 3, pad, pad * 2)
        }
        root.addView(TextView(this).apply {
            text = "LToTS crashed"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Copy this report and send it to Claude so the crash can be fixed."
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
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LToTS crash", report))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        })
        buttons.addView(button("Share") {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report"))
            }
        })
        buttons.addView(button("Open LToTS") {
            startActivity(packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        })
        root.addView(buttons)
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
