package com.ltots.app

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.ltots.app.audio.AudioEngine
import com.ltots.app.data.Store
import com.ltots.app.ui.theme.ThemeState

@UnstableApi
class LToTSApp : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val pkg = packageManager.getPackageInfo(packageName, 0)
        ThemeState.init(freshInstall = pkg.firstInstallTime == pkg.lastUpdateTime)
        com.ltots.app.data.EqProfiles.load()
        com.ltots.app.audio.WaveIndex.init(this)
        com.ltots.app.lyrics.Lyrics.init(this)
        com.ltots.app.lyrics.AiSync.init(this)
        AudioEngine.init(this)
        com.ltots.app.download.DownloadManager.init(this)
        // Waveforms (while charging) and lyrics (when online) for the whole library. Not in the
        // desktop UI tests: there the background-job service keeps the simulated phone from
        // ever going idle.
        if (android.os.Build.FINGERPRINT != "robolectric") com.ltots.app.prep.LibraryPrep.schedule(this)
    }
}
