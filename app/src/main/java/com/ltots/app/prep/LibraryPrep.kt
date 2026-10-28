package com.ltots.app.prep

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ltots.app.audio.WaveIndex
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.Track
import com.ltots.app.audio.AudioEngine
import com.ltots.app.lyrics.AiSync
import com.ltots.app.lyrics.LrcLib
import com.ltots.app.lyrics.Lyrics
import com.ltots.app.lyrics.SongQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Prepares the whole library in the background, like a search index:
 *  - **waveforms** and **beats** for every song — only while the phone is charging (decoding takes some
 *    processor time, so it's done when battery doesn't matter);
 *  - **lyrics** for every song — whenever there's internet and the battery isn't low (it's just
 *    a few small requests per song);
 *  - **moods** for every song (the on-device mood model listens to each once) — only while
 *    charging, once the model is downloaded; "Listen now" in the Library starts it right away.
 * The playing song never waits for this: its wave and lyrics are fetched on the spot.
 */
object LibraryPrep {
    private const val WAVES = "prep-waves"
    private const val LYRICS = "prep-lyrics"
    private const val MOODS = "prep-moods"

    private fun wm(context: Context) = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull()

    private val waveConstraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
    private val lyricsConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()

    /** Called at app start: one pass now-ish (when conditions allow), then daily for new songs. */
    fun schedule(context: Context) {
        val wm = wm(context) ?: return
        wm.enqueueUniqueWork(WAVES, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WaveWorker>().setConstraints(waveConstraints).build())
        wm.enqueueUniqueWork(LYRICS, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LyricsWorker>().setConstraints(lyricsConstraints).build())
        wm.enqueueUniquePeriodicWork("$WAVES-daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WaveWorker>(1, TimeUnit.DAYS).setConstraints(waveConstraints).build())
        wm.enqueueUniquePeriodicWork("$LYRICS-daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LyricsWorker>(1, TimeUnit.DAYS).setConstraints(lyricsConstraints).build())
        wm.enqueueUniquePeriodicWork("$MOODS-daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MoodWorker>(1, TimeUnit.DAYS).setConstraints(waveConstraints).build())
    }

    /** Listens to the library's moods: now, or (when [onlyCharging]) next time the phone charges. */
    fun runMoods(context: Context, onlyCharging: Boolean = false) {
        val wm = wm(context) ?: return
        val req = OneTimeWorkRequestBuilder<MoodWorker>()
        if (onlyCharging) req.setConstraints(waveConstraints)
        wm.enqueueUniqueWork(MOODS, if (onlyCharging) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, req.build())
    }

    /** "Prepare now" in Sound: run both passes right away, without waiting for the charger. */
    fun runNow(context: Context) {
        val wm = wm(context) ?: return
        wm.enqueueUniqueWork(WAVES, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WaveWorker>().build())
        wm.enqueueUniqueWork(LYRICS, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LyricsWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    /** Whether either pass is running at the moment (for the status line). */
    fun running(context: Context): Flow<Boolean> {
        val wm = wm(context) ?: return kotlinx.coroutines.flow.flowOf(false)
        return wm.getWorkInfosForUniqueWorkFlow(WAVES).map { a -> a.any { it.state == WorkInfo.State.RUNNING } }
    }

    fun hasAudioPermission(context: Context): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun query(t: Track) = SongQuery(
        key = t.profileKey,
        title = t.title,
        artist = t.artist.takeIf { it != Track.UNKNOWN },
        album = t.album.takeIf { it.isNotBlank() && !it.equals("Music", true) },
        durationMs = t.durationMs,
    )
}

class WaveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!LibraryPrep.hasAudioPermission(ctx)) return Result.success()
        val tracks = LocalLibrary.load(ctx)
        var done = 0
        for (t in tracks) {
            if (isStopped) return Result.retry()
            if (!WaveIndex.has(t.uri)) WaveIndex.build(ctx, t.uri)
            // Beats too (by the beat AI once it's downloaded), so the cover is in sync from the start.
            if (!com.ltots.app.audio.BeatIndex.has(t.uri, byAi = com.ltots.app.audio.BeatIndex.aiInstalled)) {
                runCatching { com.ltots.app.audio.BeatIndex.build(ctx, t.uri) }
            }
            // Plain lyrics get timed by the on-device AI too, when it's set up and switched on.
            if (AiSync.modelReady.value && AudioEngine.settings.value.aiLyrics) {
                val doc = Lyrics.saved(t.profileKey) as? com.ltots.app.lyrics.LyricsDoc
                if (doc != null && doc.synced == null && !doc.plain.isNullOrBlank()) runCatching { AiSync.sync(ctx, t.profileKey, t.id, doc, t.durationMs) }
            }
            done++
            if (done % 10 == 0) setProgress(workDataOf("done" to done, "total" to tracks.size))
        }
        return Result.success()
    }
}

class LyricsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!LibraryPrep.hasAudioPermission(ctx)) return Result.success()
        val tracks = LocalLibrary.load(ctx)
        var failures = 0
        for (t in tracks) {
            if (isStopped) return Result.retry()
            if (Lyrics.hasLooked(t.profileKey)) continue
            try {
                Lyrics.fetch(LibraryPrep.query(t))
                failures = 0
            } catch (e: LrcLib.NetworkError) {
                // Lost the connection or the service is busy: stop and try again later.
                if (++failures >= 3) return Result.retry()
            }
            delay(250) // be gentle with the free service
        }
        return Result.success()
    }
}

class MoodWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!LibraryPrep.hasAudioPermission(ctx)) return Result.success()
        if (!com.ltots.app.identify.AiModels.isInstalled(com.ltots.app.identify.AiModels.Model.MOODS)) return Result.success()
        val tracks = LocalLibrary.load(ctx)
        com.ltots.app.moods.MoodIndex.analyse(ctx, tracks) { !isStopped }
        return if (isStopped) Result.retry() else Result.success()
    }
}
