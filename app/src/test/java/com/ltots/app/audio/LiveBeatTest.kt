package com.ltots.app.audio

import androidx.media3.common.C
import com.ltots.app.data.BeatSource
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Move with the beat on real music, through the real analyser at 60 fps. Only with
 * -Dltots.clips=<dir with *.st.wav> (44.1 kHz stereo 16-bit; not in the repo). Prints how often
 * each choice pulses, how big, and how its pulses line up with the bars.
 */
class LiveBeatTest {
    private val clips = System.getProperty("ltots.clips")?.let(::File)

    private fun stereo(f: File): Pair<FloatArray, FloatArray> {
        val b = f.readBytes()
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4)
            val len = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val bb = ByteBuffer.wrap(b, p + 8, minOf(len, b.size - p - 8)).order(ByteOrder.LITTLE_ENDIAN)
                val n = bb.remaining() / 4
                val l = FloatArray(n); val r = FloatArray(n)
                for (i in 0 until n) { l[i] = bb.short / 32768f; r[i] = bb.short / 32768f }
                return l to r
            }
            p += 8 + len
        }
        error("no data")
    }

    @Test fun realMusic() {
        assumeTrue(clips?.isDirectory == true)
        val files = clips!!.listFiles { f -> f.name.endsWith(".st.wav") }!!.sorted()
        for (f in files) {
            val (l, r) = stereo(f)
            val sr = 44100
            for (src in BeatSource.entries) {
                val tap = AnalyzerTap()
                tap.onFormat(sr, 2, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
                tap.onFlush(0)
                val a = Analyzer(tap, bands = 96)
                var clock = 1_000_000_000L
                a.clock = { clock }
                val det = BeatDetector(src)
                val fps = 60
                val per = sr / fps
                var written = 0
                val pulses = ArrayList<Float>()
                val rangeLevels = ArrayList<Float>()
                while (written + per <= l.size) {
                    for (k in 0 until per) tap.write(l[written + k], r[written + k])
                    tap.commit(per)
                    written += per
                    clock += 1_000_000_000L / fps
                    val frame = a.update(written * 1_000_000L / sr - 100_000L, true, pre = false, wave = false)
                    pulses += det.update(frame.post, 1f / fps)
                    var s = 0f; var c = 0
                    for (i in 0 until 96) {
                        val hz = SpectrumFrame.bandFreq(i, 96)
                        if (hz >= src.loHz && hz <= src.hiHz) { s += frame.post[i]; c++ }
                    }
                    rangeLevels += s / c
                }
                val secs = pulses.size / fps.toFloat()
                val onsets = (1 until pulses.size).filter { pulses[it] > 0.3f && pulses[it - 1] < 0.15f }
                // Frames from a pulse's start to the next local peak of the bars it listens to.
                val lags = onsets.mapNotNull { i ->
                    (i until minOf(i + 10, rangeLevels.size - 1)).firstOrNull { j -> rangeLevels[j] >= rangeLevels[j + 1] }?.minus(i)
                }.sorted()
                val active = pulses.count { it > 0.2f } / pulses.size.toFloat()
                println("BEAT %-28s %-5s hits/s %.2f  mean %.2f  max %.2f  >0.2 %.0f%%  lag(med) %s frames".format(
                    f.name, src.label, onsets.size / secs, pulses.average(), pulses.maxOrNull(), active * 100,
                    lags.getOrNull(lags.size / 2)))
            }
        }
    }
}
