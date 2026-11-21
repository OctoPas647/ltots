package com.ltots.app.cast

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build

/**
 * Android's own output picker (the one in the notification shade's media player): Bluetooth
 * devices, wired and USB outputs, and cast-ready speakers, for LToTS's sound.
 */
object OutputSwitcher {
    fun show(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
            if (ok) return true
        }
        // Android 11–13, and skins that only answer the older way.
        val i = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
            .setPackage("com.android.systemui")
            .putExtra("package_name", context.packageName)
        return runCatching { context.sendBroadcast(i); true }.getOrDefault(false)
    }
}
