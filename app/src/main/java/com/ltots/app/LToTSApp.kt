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
        // The crash report screen runs in its own small process: nothing else starts there.
        if (CrashReport.isCrashProcess(this)) return
        CrashReport.install(this)
        Store.init(this)
        // Screen on/off and charging, for everything that saves battery with the screen off.
        runCatching { com.ltots.app.playback.ScreenState.init(this) }
        com.ltots.app.ui.components.NetImages.init(this)
        com.ltots.app.recs.SongPreview.init(this)
        val pkg = packageManager.getPackageInfo(packageName, 0)
        ThemeState.init(freshInstall = pkg.firstInstallTime == pkg.lastUpdateTime)
        com.ltots.app.data.EqProfiles.load()
        com.ltots.app.audio.WaveIndex.init(this)
        com.ltots.app.audio.BeatIndex.init(this)
        com.ltots.app.lyrics.Lyrics.init(this)
        com.ltots.app.lyrics.AiSync.init(this)
        runCatching { com.ltots.app.audio.PowerStats.init(this) }
        AudioEngine.init(this)
        com.ltots.app.download.DownloadManager.init(this)
        com.ltots.app.identify.SongFinder.init(this)
        com.ltots.app.ui.IconColor.init(this)
        // Waveforms (while charging) and lyrics (when online) for the whole library. Not in the
        // desktop UI tests: there the background-job service keeps the simulated phone from
        // ever going idle.
        if (android.os.Build.FINGERPRINT != "robolectric") {
            com.ltots.app.prep.LibraryPrep.schedule(this)
            // The history and the listening numbers are read from storage here, in the background,
            // so the first screen that shows them (Home) doesn't stop to read them itself.
            Thread({
                runCatching { com.ltots.app.data.ListenHistory.ensureLoaded() }
                com.ltots.app.data.ListenStats.warm()
            }, "ltots-warm").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
        }
    }
}
