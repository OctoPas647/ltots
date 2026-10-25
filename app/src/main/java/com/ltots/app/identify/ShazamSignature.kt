package com.ltots.app.identify

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * Shazam's audio fingerprint ("signature"), made on the phone from 16 kHz mono audio.
 *
 * A port of the open-source reimplementation in shazamio-core (MIT), itself following SongRec:
 * a 2048-point spectrum every 128 samples, spread over neighbouring frequencies and time,
 * and the local peaks in four bands between 250 Hz and 5.5 kHz written in Shazam's binary format.
 * Only these peaks leave the phone, never the audio.
 */
class ShazamSignature private constructor(
    val sampleCount: Int,
    /** Peaks per band (0..3), in the order they were found. */
    val bands: Array<MutableList<Peak>>,
) {
    data class Peak(val fftPass: Int, val magnitude: Int, val bin: Int)

    val durationMs: Int get() = (sampleCount.toFloat() / SAMPLE_RATE * 1000f).toInt()
    val peakCount: Int get() = bands.sumOf { it.size }

    fun encode(): ByteArray {
        val body = ByteArrayOutputStream()
        fun u32(out: ByteArrayOutputStream, v: Long) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
            out.write(b)
        }
        fun u16(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }

        val head = ByteArrayOutputStream()
        u32(head, 0xcafe2580L) // magic 1
        u32(head, 0) // crc32, filled in below
        u32(head, 0) // size minus header, filled in below
        u32(head, 0x94119c00L) // magic 2
        u32(head, 0); u32(head, 0); u32(head, 0)
        u32(head, 3L shl 27) // 16 kHz
        u32(head, 0); u32(head, 0)
        u32(head, sampleCount + (SAMPLE_RATE * 0.24f).toLong())
        u32(head, (15L shl 19) + 0x40000)
        u32(head, 0x40000000L)
        u32(head, 0) // size minus header again

        for ((band, peaks) in bands.withIndex()) {
            if (peaks.isEmpty()) continue
            val p = ByteArrayOutputStream()
            var pass = 0
            for (pk in peaks) {
                if (pk.fftPass - pass >= 255) {
                    p.write(0xff)
                    u32(p, pk.fftPass.toLong())
                    pass = pk.fftPass
                }
                p.write(pk.fftPass - pass)
                u16(p, pk.magnitude)
                u16(p, pk.bin)
                pass = pk.fftPass
            }
            val pb = p.toByteArray()
            u32(body, 0x60030040L + band)
            u32(body, pb.size.toLong())
            body.write(pb)
            repeat((4 - pb.size % 4) % 4) { body.write(0) }
        }

        val all = head.toByteArray() + body.toByteArray()
        val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(8, all.size - 48)
        bb.putInt(52, all.size - 48)
        val crc = CRC32().apply { update(all, 8, all.size - 8) }.value
        bb.putInt(4, crc.toInt())
        return all
    }

    fun toUri(): String = DATA_URI_PREFIX + Base64.getEncoder().encodeToString(encode())

    companion object {
        const val SAMPLE_RATE = 16000
        private const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
        private val HANN = FloatArray(2048) { (0.5 - 0.5 * cos(2 * PI * (it + 1) / 2049)).toFloat() }
        private val NEIGHBOURS = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
        private val OTHER = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

        /** Fingerprint of [pcm] (16 kHz mono, -1..1). Use 3–12 s; Shazam reads at most ~12 s. */
        fun from(pcm: FloatArray): ShazamSignature = from(ShortArray(pcm.size) {
            (pcm[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        })

        fun from(samples: ShortArray): ShazamSignature {
            val ring = ShortArray(2048)
            var ringIdx = 0
            val fftOut = Array(256) { FloatArray(1025) }
            var fftIdx = 0
            val spread = Array(256) { FloatArray(1025) }
            var spreadIdx = 0
            var spreadDone = 0
            val fft = MixedFft(2048)
            val re = DoubleArray(2048)
            val im = DoubleArray(2048)
            val bands = Array(4) { mutableListOf<Peak>() }

            val chunks = samples.size / 128
            for (c in 0 until chunks) {
                // --- FFT of the newest 2048 samples
                System.arraycopy(samples, c * 128, ring, ringIdx, 128)
                ringIdx = (ringIdx + 128) and 2047
                for (i in 0 until 2048) {
                    // Rust multiplies in f32; keep that rounding.
                    re[i] = (ring[(i + ringIdx) and 2047].toFloat() * HANN[i]).toDouble()
                    im[i] = 0.0
                }
                fft.forward(re, im)
                val out = fftOut[fftIdx]
                for (i in 0..1024) {
                    val v = ((re[i] * re[i] + im[i] * im[i]) / (1 shl 17)).toFloat()
                    out[i] = max(v, 0.0000000001f)
                }
                fftIdx = (fftIdx + 1) and 255

                // --- spreading
                val last = fftOut[(fftIdx - 1) and 255]
                val sp = spread[spreadIdx]
                System.arraycopy(last, 0, sp, 0, 1025)
                for (p in 0..1022) sp[p] = max(max(sp[p], sp[p + 1]), sp[p + 2])
                val copy = sp.copyOf()
                for (former in intArrayOf(1, 3, 6)) {
                    val f = spread[(spreadIdx - former) and 255]
                    for (p in 0..1024) if (copy[p] > f[p]) f[p] = copy[p]
                }
                spreadIdx = (spreadIdx + 1) and 255
                spreadDone++

                // --- peaks
                if (spreadDone >= 46) {
                    val m46 = fftOut[(fftIdx - 46) and 255]
                    val m49 = spread[(spreadIdx - 49) and 255]
                    for (bin in 10..1014) {
                        val v = m46[bin]
                        if (v < 1f / 64f || v < m49[bin - 1]) continue
                        var maxN = 0f
                        for (o in NEIGHBOURS) maxN = max(maxN, m49[bin + o])
                        if (v <= maxN) continue
                        var maxOther = maxN
                        for (o in OTHER) maxOther = max(maxOther, spread[(spreadIdx + o) and 255][bin - 1])
                        if (v <= maxOther) continue

                        val pass = spreadDone - 46
                        val mag = max(ln(v), 1f / 64f) * 1477.3f + 6144f
                        val before = max(ln(m46[bin - 1]), 1f / 64f) * 1477.3f + 6144f
                        val after = max(ln(m46[bin + 1]), 1f / 64f) * 1477.3f + 6144f
                        val var1 = mag * 2f - before - after
                        val var2 = (after - before) * 32f / var1
                        val corrected = (bin * 64 + var2.toInt()) and 0xffff
                        val hz = corrected.toFloat() * (16000f / 2f / 1024f / 64f)
                        val band = when (hz.toInt()) {
                            in 250..519 -> 0
                            in 520..1449 -> 1
                            in 1450..3499 -> 2
                            in 3500..5500 -> 3
                            else -> -1
                        }
                        if (band < 0) continue
                        bands[band] += Peak(pass, mag.toInt() and 0xffff, corrected)
                    }
                }
            }
            return ShazamSignature(samples.size, bands)
        }
    }
}
