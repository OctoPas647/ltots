package com.ltots.app.identify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the real separation model and asks the real Shazam. Only with
 *   -Dltots.live=true -Dltots.models=<dir with the .onnx> -Dltots.clips=<dir with test .wav>
 * (not in CI: needs the network, a 67 MB model and music files that aren't in the repo).
 */
class LiveIdentifyTest {
    private val models = System.getProperty("ltots.models")?.let(::File)
    private val clips = System.getProperty("ltots.clips")?.let(::File)

    private fun live() = assumeTrue(System.getProperty("ltots.live") == "true" && clips?.isDirectory == true)

    companion object {
        fun readWav(f: File): FloatArray {
            val bytes = f.readBytes()
            var p = 12
            while (p + 8 <= bytes.size) {
                val id = String(bytes, p, 4)
                val len = ByteBuffer.wrap(bytes, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == "data") {
                    val bb = ByteBuffer.wrap(bytes, p + 8, minOf(len, bytes.size - p - 8)).order(ByteOrder.LITTLE_ENDIAN)
                    // 16-bit or 32-bit float
                    val fmtFloat = bytes[20].toInt() == 3
                    return if (fmtFloat) FloatArray(bb.remaining() / 4) { bb.float }
                    else FloatArray(bb.remaining() / 2) { bb.short / 32768f }
                }
                p += 8 + len
            }
            error("no data chunk in $f")
        }
    }

    @Test fun shazamKnowsACleanClip() {
        live()
        val pcm = readWav(File(clips, "Sneaky_Snitch.clean.wav"))
        val hit = Shazam.recognize(ShazamSignature.from(pcm))
        println("LIVE clean: $hit")
        assertNotNull(hit)
        assertEquals("Sneaky Snitch", hit!!.title)
    }

    @Test fun separationRecoversMusicUnderTalking() {
        live()
        val model = File(models, "UVR-MDX-NET-Inst_HQ_3.onnx")
        assumeTrue(model.isFile)
        MusicSeparator(model).use { sep ->
            for (name in listOf("Sneaky_Snitch", "Monkeys_Spinning_Monkeys", "Fluffing_a_Duck")) {
                val talk = readWav(File(clips, "$name.talk.wav"))
                val before = Shazam.recognize(ShazamSignature.from(talk))
                val t0 = System.nanoTime()
                val stems = sep.separate(talk)
                val ms = (System.nanoTime() - t0) / 1_000_000
                val after = Shazam.recognize(ShazamSignature.from(ClipLoader.normalize(stems.music)))
                println("LIVE $name: before=${before?.title} after=${after?.title} separation=${ms} ms")
                // Compare with the Python reference of the same model, when present.
                val ref = File(clips, "$name.talk.sep.wav").takeIf { it.isFile }?.let(::readWav)
                if (ref != null) {
                    val n = minOf(ref.size, stems.music.size)
                    var xy = 0.0; var xx = 0.0; var yy = 0.0
                    for (i in 0 until n) { xy += ref[i] * stems.music[i]; xx += ref[i] * ref[i]; yy += stems.music[i] * stems.music[i] }
                    val corr = xy / Math.sqrt(xx * yy)
                    println("LIVE $name: correlation with the Python reference = $corr")
                    assertTrue(corr > 0.95)
                }
                assertNotNull("$name not found after separation", after)
            }
        }
    }

    @Test fun wholePipelineRanksTheRightSong() = kotlinx.coroutines.runBlocking {
        live()
        val model = File(models, "UVR-MDX-NET-Inst_HQ_3.onnx").takeIf { it.isFile }
        for ((file, want) in listOf("Sneaky_Snitch.fast.wav" to "Sneaky Snitch", "Monkeys_Spinning_Monkeys.talk.wav" to "Monkeys Spinning Monkeys")) {
            val pcm = readWav(File(clips, file))
            var last: IdentifyProgress? = null
            val t0 = System.nanoTime()
            val rows = Identifier(model, null) { last = it }.run(ClipAudio(pcm, null, file))
            println("LIVE pipeline $file in ${(System.nanoTime() - t0) / 1_000_000} ms")
            last?.let { p -> Step.entries.forEach { println("LIVE   ${it.label}: ${p.steps[it]} ${p.details[it] ?: ""}") } }
            rows.forEachIndexed { i, r -> println("LIVE   #${i + 1} ${(r.confidence * 100).toInt()}% ${r.title} — ${r.artist} [${r.version}] yt=${r.song?.videoId} ${r.reasons}") }
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.first().title.contains(want, ignoreCase = true))
        }
    }

    @Test fun lyricsListenerWritesDownWords() {
        live()
        assumeTrue(System.getProperty("ltots.whisperlib") != null)
        val model = File(models, "ggml-small-q5_1.bin")
        assumeTrue(model.isFile)
        val speech = readWav(File(clips, "speech.wav"))
        val t0 = System.nanoTime()
        val heard = Lyricist.open(model)!!.use { it.listen(speech) }
        println("LIVE whisper (${(System.nanoTime() - t0) / 1_000_000} ms): $heard")
        assertNotNull(heard)
        assertTrue(heard!!.text.split(' ').size > 8)
    }
}
