package com.ltots.app

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.ltots.app.audio.AudioEngine
import com.ltots.app.data.Store
import com.ltots.app.ui.theme.ThemeState

@UnstableApi
class LToTSApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        ThemeState.init()
        AudioEngine.init(this)
    }
}
