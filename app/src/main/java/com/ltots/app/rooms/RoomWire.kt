package com.ltots.app.rooms

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the phones say to each other over the room's TCP link: length-prefixed frames, each a
 * one-byte type and a payload. Control messages are small JSON objects; audio is binary
 * ([AudioPacket]).
 */
object RoomWire {
    const val VERSION = 1

    // Handshake
    const val HELLO = 1
    const val PAIR_OK = 3
    const val PAIR_NO = 4
    const val AUTH = 5
    const val WELCOME = 6

    // Running room
    /** Main → all: the room's state (mode, members, who plays, now playing). */
    const val STATE = 10
    /** Node → main: this phone's status (earbuds link, output, sync). */
    const val REPORT = 11
    /** Main → all: a piece of the music. */
    const val AUDIO = 20
    /** Main → all: forget queued music from this group time on (pause, seek, skip). */
    const val CUT = 21
    /** Node → main: play / pause / next / previous / switch earbuds now / settings. */
    const val CONTROL = 30
    /** Main → all: the calibration's schedule of chirps. */
    const val CAL_PLAN = 40
    /** Listening phone → main: what it heard. */
    const val CAL_RESULT = 42
    /** Main → one node: change your latency adjustment by this much / to this value. */
    const val ADJUST = 43
    /** Main → one node: play to these outputs (empty: the phone's usual one). */
    const val OUTPUTS = 44
    /** Main → one phone: record the music from / to these group times and compare (no whistle). */
    const val MUSIC_CHECK = 45
    /** That phone → main: the speakers it heard in the music. */
    const val MUSIC_RESULT = 46
    /** Node → main: "make me the main". */
    const val MAIN_REQUEST = 50
    /** Old main → new main: everything needed to carry on playing. */
    const val MAIN_HANDOFF = 51
    /** Old main → everyone: the main is moving to this phone at this time. */
    const val NEW_MAIN = 52
    /** New main → old main: ready to start at this group time (the old one plays until then). */
    const val MAIN_READY = 53
    const val BYE = 60

    /** Frames bigger than this are refused (a bad or hostile peer). */
    const val MAX_FRAME = 4 * 1024 * 1024

    fun write(out: DataOutputStream, type: Int, payload: ByteArray) {
        synchronized(out) {
            out.writeByte(type)
            out.writeInt(payload.size)
            out.write(payload)
            out.flush()
        }
    }

    fun writeJson(out: DataOutputStream, type: Int, json: JSONObject) =
        write(out, type, json.toString().toByteArray(Charsets.UTF_8))

    class Frame(val type: Int, val payload: ByteArray) {
        fun json(): JSONObject = JSONObject(String(payload, Charsets.UTF_8))
    }

    /** The next frame, or null at the end of the stream. */
    fun read(input: DataInputStream): Frame? {
        val type = try {
            input.readUnsignedByte()
        } catch (e: EOFException) {
            return null
        }
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME) throw IOException("Bad frame length $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        return Frame(type, buf)
    }
}

/**
 * One piece of the music on its way to the other phones: 24-bit PCM (more than any Bluetooth
 * link carries) with the group time its first frame is to be heard at.
 */
class AudioChunk(
    /** Changes at every flush (seek, skip, new main): older pieces are stale. */
    val epoch: Int,
    /** Frame number of the first frame within its epoch. */
    val startFrame: Long,
    /** Group time (ns) at which the first frame is to be heard. */
    val groupNs: Long,
    val sampleRate: Int,
    val channels: Int,
    /** Interleaved samples, frames × channels. */
    val samples: FloatArray,
) {
    val frames: Int get() = samples.size / channels
    /** Group time just after the last frame. */
    val endNs: Long get() = groupNs + frames * 1_000_000_000L / sampleRate
}

object AudioPacket {
    private const val HEADER = 4 + 8 + 8 + 4 + 1 + 4

    /** [frames] frames from [src] at [offset] (in samples), scaled by [gain]. */
    fun encode(
        epoch: Int, startFrame: Long, groupNs: Long, sampleRate: Int, channels: Int,
        src: FloatArray, offset: Int, frames: Int, gain: Float = 1f,
    ): ByteArray {
        val n = frames * channels
        val out = ByteBuffer.allocate(HEADER + n * 3).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(epoch).putLong(startFrame).putLong(groupNs).putInt(sampleRate).put(channels.toByte()).putInt(frames)
        val bytes = out.array()
        var p = HEADER
        for (i in 0 until n) {
            val v = (src[offset + i] * gain).coerceIn(-1f, 1f)
            val s = Math.round(v * 8_388_608f).coerceIn(-8_388_608, 8_388_607)
            bytes[p] = s.toByte()
            bytes[p + 1] = (s shr 8).toByte()
            bytes[p + 2] = (s shr 16).toByte()
            p += 3
        }
        return bytes
    }

    fun decode(bytes: ByteArray): AudioChunk {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val epoch = b.int
        val startFrame = b.long
        val groupNs = b.long
        val rate = b.int
        val channels = b.get().toInt() and 0xFF
        val frames = b.int
        require(channels in 1..8 && rate in 8000..384000 && frames >= 0) { "Bad audio header" }
        val n = frames * channels
        require(bytes.size >= HEADER + n * 3) { "Short audio packet" }
        val out = FloatArray(n)
        var p = HEADER
        for (i in 0 until n) {
            // Sign-extend the 24-bit sample.
            val s = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)
            out[i] = s / 8_388_608f
            p += 3
        }
        return AudioChunk(epoch, startFrame, groupNs, rate, channels, out)
    }
}

/** The clock ping over UDP (see [ClockEstimator]). */
object ClockPacket {
    const val MAGIC = 0x4C54434B // "LTCK"
    const val REQUEST_SIZE = 4 + 8 + 4 + 8
    const val REPLY_SIZE = REQUEST_SIZE + 16

    fun request(token: Long, seq: Int, t0: Long): ByteArray =
        ByteBuffer.allocate(REQUEST_SIZE).putInt(MAGIC).putLong(token).putInt(seq).putLong(t0).array()

    /** The main's answer: the request with its receive and send times appended. */
    fun reply(request: ByteArray, length: Int, token: Long, t1: Long, t2: Long): ByteArray? {
        if (length < REQUEST_SIZE) return null
        val b = ByteBuffer.wrap(request, 0, length)
        if (b.int != MAGIC || b.long != token) return null
        return ByteBuffer.allocate(REPLY_SIZE).put(request, 0, REQUEST_SIZE).putLong(t1).putLong(t2).array()
    }

    class Reply(val seq: Int, val t0: Long, val t1: Long, val t2: Long)

    fun parseReply(bytes: ByteArray, length: Int, token: Long): Reply? {
        if (length < REPLY_SIZE) return null
        val b = ByteBuffer.wrap(bytes, 0, length)
        if (b.int != MAGIC || b.long != token) return null
        val seq = b.int
        val t0 = b.long
        return Reply(seq, t0, b.long, b.long)
    }
}
