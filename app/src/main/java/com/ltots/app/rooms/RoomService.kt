package com.ltots.app.rooms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import com.ltots.app.MainActivity
import com.ltots.app.R

/**
 * Keeps this phone in the room with the screen off: a notification, the processor awake (the
 * music has to be written on time) and the Wi-Fi in its fast mode (power saving would hold the
 * clock pings and the music back by up to a tenth of a second).
 */
@UnstableApi
class RoomService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var wifiFast: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            Rooms.leave()
            return START_NOT_STICKY
        }
        Rooms.init(this)
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        runCatching { ServiceCompat.startForeground(this, ID, build(this, Rooms.ui.value), types) }
            .onFailure {
                // Not allowed to start from the background right now (Android 12+ rules).
                com.ltots.app.CrashReport.handled("RoomService.startForeground", it)
                stopSelf()
                return START_NOT_STICKY
            }
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LToTS:room").apply { setReferenceCounted(false); acquire() }
        }
        if (wifi == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifi = runCatching { wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LToTS:room").apply { setReferenceCounted(false); acquire() } }.getOrNull()
            wifiFast = runCatching { wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "LToTS:room-fast").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        runCatching { wifiFast?.release() }
        wake = null; wifi = null; wifiFast = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "rooms"
        private const val ID = 4401
        private const val PAIR_ID = 4402
        private const val ACTION_LEAVE = "com.ltots.app.ROOM_LEAVE"
        @Volatile private var running = false
        @Volatile private var lastText = ""

        private fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Rooms", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Playing across your phones"
                    setShowBadge(false)
                }
            )
        }

        fun start(context: Context) {
            running = true
            runCatching { context.startForegroundService(Intent(context, RoomService::class.java)) }
                .onFailure { com.ltots.app.CrashReport.handled("RoomService.start", it) }
        }

        fun stop(context: Context) {
            running = false
            lastText = ""
            context.stopService(Intent(context, RoomService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(PAIR_ID)
        }

        private fun text(ui: Rooms.Ui): Pair<String, String> = when (ui.role) {
            Rooms.Role.MAIN -> "Main of the room · ${if (ui.mode == Rooms.Mode.EARBUDS) "Earbuds" else "Speakers"}" to
                "${(ui.members.size - 1).coerceAtLeast(0)} other phone(s) connected"
            Rooms.Role.NODE -> if (ui.mainName.isNotBlank()) "In ${ui.mainName}'s room" to
                (ui.members.firstOrNull { it.isMe }?.let { m -> if (m.rendering) "Playing here" else "Ready" } ?: "Ready")
            else "Rooms: available" to "Waiting for a room on this Wi-Fi"
            Rooms.Role.OFF -> "Rooms" to ""
        }

        private fun build(context: Context, ui: Rooms.Ui): Notification {
            val (title, body) = text(ui)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val leave = PendingIntent.getService(
                context, 1, Intent(context, RoomService::class.java).setAction(ACTION_LEAVE), PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rooms)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, if (ui.role == Rooms.Role.MAIN) "End room" else "Leave", leave)
                .build()
        }

        /** Refreshes the notification when what it says changed. */
        fun update(context: Context, ui: Rooms.Ui) {
            if (!running || ui.role == Rooms.Role.OFF) return
            val (t, b) = text(ui)
            if ("$t|$b" == lastText) return
            lastText = "$t|$b"
            runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, build(context, ui)) }
        }

        /** A phone wants to pair: show the code even if LToTS isn't on screen. */
        fun notifyPairing(context: Context, peer: String, code: String) {
            channel(context)
            val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(
                    PAIR_ID,
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_rooms)
                        .setContentTitle("Pair with $peer?")
                        .setContentText("Check that $peer shows the code $code, then confirm in LToTS.")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setTimeoutAfter(90_000)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build(),
                )
            }
        }
    }
}
