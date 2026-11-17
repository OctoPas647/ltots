package com.ltots.app.rooms

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.ltots.app.CrashReport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Plays the room's music on this phone, exactly on time.
 *
 * Every piece of music arrives with the group time it's to be heard at. This phone's own
 * AudioTrack says, through its timestamps, when the frame it's about to write will come out of
 * the speaker or earbuds (Android includes the Bluetooth delay in that). The difference between
 * the two is fed to a [SyncController]: big differences are fixed by skipping or holding,
 * small ones by playing a hair faster or slower, so every phone in the room stays within about
 * a millisecond of the timeline. On top of that, [adjustNs] shifts this phone by what the mic
 * calibration measured (Bluetooth speakers don't all report their delay honestly).
 *
 * Runs on its own high-priority thread; the network side only drops chunks in.
 */
class RoomRenderer(private val tag: String = "room") {
    private val lock = Object()
    private val queue = ArrayList<AudioChunk>()
    /** Group times at which to play the calibration chirp. */
    private val chirps = ArrayList<Long>()
    private var chirpRate = 0
    private var chirp = FloatArray(0)

    /** Whether this phone should be heard now (Speakers mode, or the earbuds are on it). */
    @Volatile var wantPlay: Boolean = false
    /** Extra delay for this phone's output (calibration and the manual slider), ns. */
    @Volatile var adjustNs: Long = 0L
    /** Route the sound to this device (the earbuds), or null for the phone's usual output. */
    @Volatile var preferredDevice: AudioDeviceInfo? = null
    /** Keep the output running (with silence) until this group time: calibration. */
    @Volatile var keepAliveUntilNs: Long = 0L

    // ---- status, read by the room ----
    /** The smoothed timing error (ns): positive = late. */
    @Volatile var syncErrorNs: Long = 0L; private set
    /** 0..1: the output stalls (the Bluetooth link chokes). */
    @Volatile var stall: Double = 0.0; private set
    /** Sound is coming out now. */
    @Volatile var rendering: Boolean = false; private set
    /** The output's own delay (write → heard), ns, as Android reports it. */
    @Volatile var outputLatencyNs: Long = 0L; private set
    /** Total skips and holds (for the status line). */
    @Volatile var corrections: Int = 0; private set

    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        val t = Thread({ loop() }, "LToTS-$tag").apply { isDaemon = true }
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        synchronized(lock) { queue.clear(); chirps.clear() }
    }

    fun offer(c: AudioChunk) {
        synchronized(lock) {
            // Sorted by time; nearly always appended at the end.
            var i = queue.size
            while (i > 0 && queue[i - 1].groupNs > c.groupNs) i--
            queue.add(i, c)
            // Never more than ~20 s queued (a stuck clock would otherwise grow it forever).
            while (queue.size > 1200) queue.removeAt(0)
        }
    }

    /** Drops the music of [epoch] from group time [atNs] on (pause, seek, skip, handoff). */
    fun cut(epoch: Int, atNs: Long) {
        synchronized(lock) {
            val it = queue.listIterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c.epoch != epoch) continue
                if (c.groupNs >= atNs) it.remove()
                else if (c.endNs > atNs) {
                    val keep = ((atNs - c.groupNs) * c.sampleRate / 1_000_000_000L).toInt().coerceIn(0, c.frames)
                    it.set(AudioChunk(c.epoch, c.startFrame, c.groupNs, c.sampleRate, c.channels, c.samples.copyOf(keep * c.channels)))
                }
            }
            // Already written past the cut: the output is restarted so it stops now.
            if (writtenContentEpoch == epoch && cursorNs > atNs + 5_000_000L) restartRequested = true
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            restartRequested = true
        }
    }

    /** Plays the calibration chirp so that it's heard at [atGroupNs] (with this phone's adjustment). */
    fun chirpAt(atGroupNs: Long) {
        synchronized(lock) { chirps.add(atGroupNs) }
    }

    // ---- the audio thread --------------------------------------------------------------------

    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0
    private var written = 0L
    /** Group time of the music content the next written frame carries. */
    @Volatile private var cursorNs = 0L
    private var aligned = false
    private var holdFrames = 0L
    @Volatile private var writtenContentEpoch = Int.MIN_VALUE
    @Volatile private var restartRequested = false
    private val ctl = SyncController()
    private var dither: RateDither? = null
    private var lastCtlNs = 0L
    private var idleSinceNs = -1L
    private var wasWanted = false
    private val ts = AudioTimestamp()
    private var lastTsFrame = -1L
    private var lastTsNano = 0L
    private var stallEma = 0.0
    private var routed: AudioDeviceInfo? = null

    private fun loop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val me = Thread.currentThread()
        // A stop() and start() in quick succession: the old thread bows out to the new one.
        while (running && thread === me) {
            try {
                step()
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                CrashReport.handled("RoomRenderer.step", e)
                releaseTrack()
                sleep(200)
            }
        }
        // Only the thread still in charge lets the output go.
        if (thread == null || thread === me) {
            releaseTrack()
            rendering = false
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun step() {
        val now = GroupClock.now()
        val format = synchronized(lock) {
            // Music whose time has fully passed is no use any more.
            while (queue.isNotEmpty() && queue[0].endNs < now - 200_000_000L) queue.removeAt(0)
            chirps.removeAll { it + 2_000_000_000L < now }
            queue.firstOrNull()?.let { it.sampleRate to it.channels }
        }
        val alive = now < keepAliveUntilNs
        val want = (wantPlay && format != null) || alive
        if (!want || !GroupClock.ready) {
            // Nothing to play: let the output go after a moment (so Bluetooth can sleep, and the
            // earbuds can follow the other phone).
            if (track != null) {
                if (idleSinceNs < 0) idleSinceNs = now
                val idleFor = now - idleSinceNs
                if (!wantPlay || idleFor > 1_500_000_000L) releaseTrack()
                else writeSilence(trackRate / 100)
            }
            rendering = false
            stall = 0.0
            wasWanted = false
            sleep(15)
            return
        }
        idleSinceNs = -1L
        // Coming back from a pause: line up afresh rather than steering from the old position.
        if (!wasWanted) { aligned = false; wasWanted = true }
        val rate = format?.first ?: trackRate.takeIf { it > 0 } ?: 48000
        val ch = format?.second ?: trackChannels.takeIf { it > 0 } ?: 2
        if (track == null || rate != trackRate || ch != trackChannels || restartRequested || routed?.id != preferredDevice?.id) {
            openTrack(rate, ch)
        }
        val t = track ?: run { sleep(50); return }

        // When will the next frame we write be heard?
        val heardAt = nextFrameHeardAt(t)
        if (heardAt == null) {
            // The output hasn't started reporting yet (Bluetooth takes a moment): silence.
            writeSilence(rate / 200)
            return
        }
        val g = GroupClock.toGroup(heardAt)
        if (!aligned) {
            cursorNs = g - adjustNs
            aligned = true
            ctl.reset()
        }
        val error = g - (cursorNs + adjustNs)
        val nowLocal = System.nanoTime()
        if (nowLocal - lastCtlNs >= 50_000_000L) {
            val dt = if (lastCtlNs == 0L) 0.05 else (nowLocal - lastCtlNs) / 1e9
            lastCtlNs = nowLocal
            when (val d = ctl.update(error, dt, nowLocal)) {
                is SyncController.Decision.Jump -> {
                    corrections++
                    if (d.ns > 0) cursorNs += d.ns // late: skip ahead in the music
                    else holdFrames += -d.ns * rate / 1_000_000_000L // early: hold with silence
                }
                is SyncController.Decision.Rate -> {
                    val hz = dither?.next(d.ppm) ?: rate
                    runCatching { t.playbackRate = hz }
                }
            }
            syncErrorNs = ctl.errorNs
            updateStall(t, nowLocal)
        }
        rendering = true

        // Write about 10 ms.
        val frames = rate / 100
        if (holdFrames > 0) {
            val n = min(holdFrames, frames.toLong()).toInt()
            writeSilence(n)
            holdFrames -= n
            return
        }
        writeContent(t, frames, rate, ch)
    }

    private fun openTrack(rate: Int, ch: Int) {
        releaseTrack()
        restartRequested = false
        val mask = when (ch) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(mask).build()
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(4096)
        // About 120 ms of room: enough to ride out a busy moment, small enough to react fast.
        val size = max(min, rate * ch * 4 * 12 / 100)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        preferredDevice?.let { runCatching { t.setPreferredDevice(it) } }
        routed = preferredDevice
        t.play()
        track = t
        trackRate = rate
        trackChannels = ch
        written = 0L
        aligned = false
        holdFrames = 0L
        lastTsFrame = -1L
        lastCtlNs = 0L
        dither = RateDither(rate)
        ctl.reset()
        // Prime with a little silence so the timestamps start flowing.
        writeSilence(rate / 50)
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { t.pause(); t.flush() }
        runCatching { t.release() }
        aligned = false
        trackRate = 0
        trackChannels = 0
        writtenContentEpoch = Int.MIN_VALUE
        rendering = false
    }

    /** Local time at which the next written frame will be heard, from the track's timestamp. */
    private fun nextFrameHeardAt(t: AudioTrack): Long? {
        if (!t.getTimestamp(ts)) return null
        if (ts.framePosition <= 0 || ts.nanoTime <= 0) return null
        val rate = (t.playbackRate.takeIf { it > 0 } ?: trackRate).toDouble()
        val at = ts.nanoTime + ((written - ts.framePosition) * 1e9 / rate).roundToLong()
        outputLatencyNs = at - System.nanoTime()
        return at
    }

    /** How badly the output is stalling: its position should advance with the clock. */
    private fun updateStall(t: AudioTrack, nowLocal: Long) {
        if (lastTsFrame < 0) {
            lastTsFrame = ts.framePosition; lastTsNano = nowLocal
            return
        }
        val dt = (nowLocal - lastTsNano) / 1e9
        if (dt < 0.4) return
        val advanced = (ts.framePosition - lastTsFrame).toDouble()
        val expected = dt * (t.playbackRate.takeIf { it > 0 } ?: trackRate)
        val ratio = if (expected > 0) advanced / expected else 1.0
        val bad = ((0.97 - ratio) / 0.25).coerceIn(0.0, 1.0)
        stallEma += 0.35 * (bad - stallEma)
        stall = stallEma
        lastTsFrame = ts.framePosition
        lastTsNano = nowLocal
    }

    private var silence = FloatArray(0)
    private var buf = FloatArray(0)

    private fun writeSilence(frames: Int) {
        val t = track ?: return
        val n = frames * trackChannels.coerceAtLeast(1)
        if (silence.size < n) silence = FloatArray(n)
        val wrote = t.write(silence, 0, n, AudioTrack.WRITE_BLOCKING)
        if (wrote > 0) written += wrote / trackChannels.coerceAtLeast(1)
        // The music's time doesn't move: holding silence is how this phone waits for it.
    }

    /** [frames] frames of music (or silence where none arrived) at [cursorNs], plus overlays. */
    private fun writeContent(t: AudioTrack, frames: Int, rate: Int, ch: Int) {
        val n = frames * ch
        if (buf.size < n) buf = FloatArray(n)
        java.util.Arrays.fill(buf, 0, n, 0f)
        val frameNs = 1_000_000_000.0 / rate
        val start = cursorNs
        val end = start + (frames * frameNs).roundToLong()
        synchronized(lock) {
            for (c in queue) {
                if (c.endNs <= start) continue
                if (c.groupNs >= end) break
                if (c.sampleRate != rate || c.channels != ch) continue
                // Frame of c that falls at our frame i: round((start + i·frameNs − c.groupNs) / frameNs).
                val offset = Math.round((start - c.groupNs) / frameNs)
                val from = max(0L, -offset).toInt()
                val to = min(frames.toLong(), c.frames - offset).toInt()
                for (i in from until to) {
                    val src = ((offset + i) * ch).toInt()
                    val dst = i * ch
                    for (k in 0 until ch) buf[dst + k] = c.samples[src + k]
                }
                writtenContentEpoch = c.epoch
            }
            if (chirps.isNotEmpty() && chirpRate != rate) { chirp = Chirp.make(rate); chirpRate = rate }
            for (at in chirps) {
                val s = chirp
                val contentAt = at - adjustNs
                val offset = Math.round((start - contentAt) / frameNs)
                if (offset >= s.size || offset + frames <= 0) continue
                for (i in 0 until frames) {
                    val j = offset + i
                    if (j < 0 || j >= s.size) continue
                    val v = s[j.toInt()]
                    for (k in 0 until ch) buf[i * ch + k] += v
                }
            }
        }
        val wrote = t.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
        if (wrote > 0) written += wrote / ch
        cursorNs = end
    }
}
