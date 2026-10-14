package com.ltots.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Connects the UI to the background playback service. */
@UnstableApi
object PlayerConnection {
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller
    private var connecting = false

    fun connect(context: Context) {
        if (_controller.value != null || connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context.applicationContext, token).buildAsync()
        future.addListener({
            connecting = false
            _controller.value = runCatching { future.get() }.getOrNull()
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        _controller.value?.release()
        _controller.value = null
    }
}
