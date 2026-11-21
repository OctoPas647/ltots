package com.ltots.app.elsewhere

import android.service.notification.NotificationListenerService

/**
 * Android only lets an app see other apps' players (media sessions) while it's a notification
 * listener the person has allowed. LToTS reads no notifications: this service only exists so
 * [OtherApps] may follow what Spotify, YouTube Music and the rest are playing.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        OtherApps.attach(this)
    }

    override fun onListenerDisconnected() {
        OtherApps.detach()
        super.onListenerDisconnected()
    }
}
