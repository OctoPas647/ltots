package com.ltots.app.crash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.ltots.app.CrashActivity
import java.io.File

/**
 * The crash notification: Android doesn't let an app open a screen while it's in the
 * background (music playing with the screen off, another app in front), so a crash there
 * shows up as a notification; tapping it opens the report.
 */
object CrashNotify {
    private const val CHANNEL = "crash_reports"

    private fun channel(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Crash reports", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When LToTS crashes, freezes or is stopped while playing"
                },
            )
        }
    }

    /** [clearTask]: the app is going away (a crash), so the report replaces it on screen. */
    fun openIntent(context: Context, file: File, clearTask: Boolean = true): Intent =
        Intent(context, CrashActivity::class.java)
            .putExtra(CrashActivity.EXTRA_FILE, file.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (clearTask) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0))

    fun show(context: Context, kind: CrashLog.Kind, file: File, summary: String, extra: Int = 0) {
        runCatching {
            channel(context)
            val pi = PendingIntent.getActivity(
                context, file.name.hashCode(), openIntent(context, file),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val more = if (extra > 0) " (+$extra more in Profile › Crash reports)" else ""
            val n = android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(kind.title)
                .setContentText(summary.take(200) + more)
                .setStyle(android.app.Notification.BigTextStyle().bigText(summary.take(600) + more + "\nTap to see the report."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_ERROR)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(ID_BASE + (file.name.hashCode() and 0xFF), n)
        }
    }

    private const val ID_BASE = 7700
}
