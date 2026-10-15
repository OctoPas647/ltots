package com.ltots.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ltots.app.MainActivity
import com.ltots.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps downloads running with the screen off, with a progress notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of songs being downloaded"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            var idle = 0
            while (true) {
                delay(700)
                if (DownloadManager.hasWork()) {
                    idle = 0
                    nm.notify(ID, build())
                } else if (++idle >= 3) {
                    finish()
                    break
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        val done = DownloadManager.tasks.value.count { it.state == TaskState.DONE && System.currentTimeMillis() - it.finishedAt < 3600_000 }
        val failed = DownloadManager.tasks.value.count { it.state == TaskState.FAILED && System.currentTimeMillis() - it.finishedAt < 3600_000 }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (done + failed > 0) {
            nm.notify(
                DONE_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle("Downloads finished")
                    .setContentText(buildString {
                        append("$done saved to Music/LToTS")
                        if (failed > 0) append(" · $failed failed")
                    })
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
        stopSelf()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "DOWNLOADS"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(): Notification {
        val (active, queued, current) = DownloadManager.activeSummary()
        val title = current?.meta?.let { "${it.title} — ${it.artist}" } ?: "Preparing downloads"
        val text = buildString {
            append(current?.state?.label ?: "Waiting")
            if (active > 1) append(" · ${active} at once")
            if (queued > 0) append(" · $queued waiting")
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (current != null && current.state == TaskState.DOWNLOADING) b.setProgress(100, (current.progress * 100).toInt(), false)
        else b.setProgress(0, 0, current != null)
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 4201
        private const val DONE_ID = 4202
    }
}
