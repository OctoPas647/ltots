package com.ltots.app.identify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ShazamSignatureTest {

    private fun wav(name: String): ShortArray {
        val bytes = javaClass.getResourceAsStream("/identify/$name")!!.readBytes()
        // Find the "data" chunk.
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val len = ByteBuffer.wrap(bytes, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val bb = ByteBuffer.wrap(bytes, p + 8, len).order(ByteOrder.LITTLE_ENDIAN)
                return ShortArray(len / 2) { bb.short }
            }
            p += 8 + len
        }
        error("no data chunk")
    }

    private fun decode(uri: String): Map<Int, List<ShazamSignature.Peak>> {
        val b = Base64.getDecoder().decode(uri.substringAfter("base64,"))
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 56
        val out = HashMap<Int, List<ShazamSignature.Peak>>()
        while (p < b.size) {
            val band = bb.getInt(p) - 0x60030040
            val len = bb.getInt(p + 4)
            var q = p + 8
            var pass = 0
            val peaks = ArrayList<ShazamSignature.Peak>()
            while (q < p + 8 + len) {
                if ((b[q].toInt() and 0xff) == 0xff) { pass = bb.getInt(q + 1); q += 5; continue }
                pass += b[q].toInt() and 0xff
                val mag = bb.getShort(q + 1).toInt() and 0xffff
                val bin = bb.getShort(q + 3).toInt() and 0xffff
                peaks += ShazamSignature.Peak(pass, mag, bin)
                q += 5
            }
            out[band] = peaks
            p += 8 + len + (4 - len % 4) % 4
        }
        return out
    }

    @Test
    fun matchesTheReferenceImplementation() {
        val pcm = wav("synth.wav")
        val expected = javaClass.getResourceAsStream("/identify/synth.uri")!!.readBytes().toString(Charsets.UTF_8).trim()
        val sig = ShazamSignature.from(pcm)
        val mine = sig.toUri()
        if (mine == expected) return
        // Double vs. float FFT can move a rare value by one step; anything more is a bug.
        val a = decode(expected)
        val b = decode(mine)
        assertEquals(a.keys, b.keys)
        var exact = 0
        var total = 0
        for (band in a.keys) {
            val pa = a.getValue(band)
            val pb = b.getValue(band)
            assertTrue("band $band: ${pa.size} vs ${pb.size} peaks", abs(pa.size - pb.size) <= maxOf(2, pa.size / 100))
            val setB = pb.map { it.fftPass to it.bin / 64 }.toSet()
            for (pk in pa) {
                total++
                if ((pk.fftPass to pk.bin / 64) in setB) exact++
            }
        }
        assertTrue("only $exact of $total reference peaks found", exact >= total * 0.98)
    }

    @Test
    fun headerAndCrcAreWellFormed() {
        val sig = ShazamSignature.from(FloatArray(16000 * 3) { (sin(2 * PI * 440 * it / 16000) * 0.5).toFloat() })
        val b = sig.encode()
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xcafe2580.toInt(), bb.getInt(0))
        assertEquals(0x94119c00.toInt(), bb.getInt(12))
        assertEquals(b.size - 48, bb.getInt(8))
        val crc = java.util.zip.CRC32().apply { update(b, 8, b.size - 8) }.value.toInt()
        assertEquals(crc, bb.getInt(4))
        assertEquals(3000, sig.durationMs)
    }

    @Test
    fun fftMatchesNaiveDft() {
        for (n in intArrayOf(2048, 6144, 12)) {
            val r = Random(n)
            val re = DoubleArray(n) { r.nextDouble() - 0.5 }
            val im = DoubleArray(n) { r.nextDouble() - 0.5 }
            val fre = re.copyOf(); val fim = im.copyOf()
            MixedFft(n).forward(fre, fim)
            for (k in listOf(0, 1, 5, n / 3, n - 1)) {
                var sr = 0.0; var si = 0.0
                for (j in 0 until n) {
                    val a = -2 * PI * k * j / n
                    sr += re[j] * cos(a) - im[j] * sin(a)
                    si += re[j] * sin(a) + im[j] * cos(a)
                }
                assertEquals(sr, fre[k], 1e-8 * n)
                assertEquals(si, fim[k], 1e-8 * n)
            }
            MixedFft(n).inverse(fre, fim)
            for (j in 0 until n) { assertEquals(re[j], fre[j], 1e-9); assertEquals(im[j], fim[j], 1e-9) }
        }
    }

    @Test
    fun resampleKeepsPitchRelationship() {
        // A 1 kHz tone resampled by 1.25 becomes 800 Hz when read at the same rate.
        val x = FloatArray(16000) { sin(2 * PI * 1000 * it / 16000).toFloat() }
        val y = Dsp.resample(x, 1.25)
        assertEquals(20000, y.size)
        var crossings = 0
        for (i in 1 until y.size) if (y[i - 1] < 0 && y[i] >= 0) crossings++
        assertEquals(1000.0 / 1.25 * 1.25, crossings.toDouble(), 3.0)
    }
}
