package com.ltots.app.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The download pipeline end to end against real YouTube, using the yt-dlp bundled in the app,
 * the exact options the app passes, and the app's own metadata rewriting (running on Android's
 * JSON library under Robolectric). Only Python/ffmpeg are the computer's instead of the phone's.
 * Runs only with -Dltots.live=true.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LiveDownloadPipelineTest {
    private val ytdlp = File("src/main/res/raw/ytdlp").absoluteFile

    private fun run(args: List<String>, ffmpegDir: File): Pair<Int, String> {
        val cmd = listOf("python3", "-I", ytdlp.path, "--no-cache-dir", "--js-runtimes", "node",
            "--ffmpeg-location", File(ffmpegDir, "libffmpeg.so").path) + args
        val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        System.err.println("yt-dlp exit $code\n" + err.takeLast(2000))
        return code to out
    }

    @Test fun downloadsATaggedSongWithCover() {
        assumeTrue(System.getProperty("ltots.live") == "true")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d = Downloader(ctx)
        val work = Files.createTempDirectory("ltots-dl").toFile()
        // Like on the phone: ffmpeg is a file called libffmpeg.so with no ffprobe next to it.
        val nl = File(work, "nativelib").apply { mkdirs() }
        Files.createSymbolicLink(File(nl, "libffmpeg.so").toPath(), File("/usr/bin/ffmpeg").toPath())

        val id = "dQw4w9WgXcQ"
        val (c1, out) = run(d.infoArgs().flatMap { listOfNotNull(it.first, it.second) } + "https://music.youtube.com/watch?v=$id", nl)
        assertEquals(0, c1)
        val info = JSONObject(out.substring(out.indexOf('{')))
        var meta = d.fillFromInfo(TrackMeta(id, Downloader.PLACEHOLDER_TITLE, "Unknown artist"), info)
        assertTrue("title from yt-dlp: ${meta.title}", meta.title.contains("Never Gonna", true))
        meta = meta.copy(album = "Whenever You Need Somebody", albumArtist = "Rick Astley", trackNumber = 1, year = "1987")

        val cover = File(work, "cover.jpg")
        assertEquals(0, ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=red:s=800x800", "-frames:v", "1", cover.path).start().waitFor())
        d.rewriteInfo(info, meta, cover)
        val infoFile = File(work, "info.json").apply { writeText(info.toString()) }

        val (c2, _) = run(d.downloadArgs(infoFile, work).flatMap { listOfNotNull(it.first, it.second) }, nl)
        assertEquals(0, c2)
        val audio = work.listFiles()!!.first { it.extension == "opus" }
        val probe = ProcessBuilder("ffprobe", "-hide_banner", audio.path).redirectErrorStream(true).start()
        val tags = probe.inputStream.bufferedReader().readText()
        probe.waitFor()
        println("PROBE\n$tags")
        assertTrue("codec copied", tags.contains("Audio: opus"))
        assertTrue("album tag", tags.contains("Whenever You Need Somebody"))
        assertTrue("artist tag", tags.contains("Rick Astley"))
        assertTrue("cover embedded", tags.contains("800x800"))
        assertTrue("no YouTube description tag", !tags.contains("description"))
        work.deleteRecursively()
    }
}
