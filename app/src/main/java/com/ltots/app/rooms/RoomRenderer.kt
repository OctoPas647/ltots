package com.ltots.app.rooms

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import com.ltots.app.CrashReport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Plays the room's music on this phone, exactly on time — through one output or several.
 *
 * Every piece of music arrives with the group time it's to be heard at. Each output this phone
 * plays to (its speaker, wired or USB headphones, a Bluetooth speaker, the earbuds…) is a
 * [Sink] with its own AudioTrack, sent to that output with [AudioTrack.setPreferredDevice]. Its
 * timestamps say when the frame it's about to write will come out (Android includes the
 * Bluetooth delay), and a [SyncController] keeps it on the timeline: big differences are fixed
 * by skipping or holding, small ones by playing a hair faster or slower. On top of that each
 * sink has its own adjustment (mic calibration, the delay slider) — a JBL Go 4 and the phone's
 * own speaker are each lined up separately.
 *
 * Several outputs at once work as far as the phone allows: Android often can play to its
 * speaker and a Bluetooth device together, or USB and Bluetooth, but most phones can't send to
 * two Bluetooth devices at the same time (Samsung's Dual Audio can). A sink checks where
 * Android really sent it ([Sink.routedOk]) so the room's screen can say so.
 */
class RoomRenderer(private val tag: String = "room") {
    /** Where one sink plays: [device] null = the phone's usual output. */
    data class Target(val key: String, val name: String, val device: AudioDeviceInfo?, val adjustNs: Long)

    /** What the room's screen shows about one sink. */
    data class SinkStatus(
        val key: String,
        val name: String,
        val rendering: Boolean,
        val syncErrorNs: Long,
        val latencyNs: Long,
        val stall: Double,
        val routedOk: Boolean,
        val routedName: String,
        val adjustNs: Long,
    )

    private val lock = Object()
    private val queue = ArrayList<AudioChunk>()
    /** Group times at which to play the calibration chirp, and on which sink (null: all). */
    private val chirps = ArrayList<Pair<Long, String?>>()

    /** Whether this phone should be heard now (Speakers mode, or the earbuds are on it). */
    @Volatile var wantPlay: Boolean = false
    /** Keep the outputs running (with silence) until this group time: calibration. */
    @Volatile var keepAliveUntilNs: Long = 0L

    private val sinks = LinkedHashMap<String, Sink>()
    @Volatile private var running = false

    // ---- status, read by the room -------------------------------------------------------------

    fun sinks(): List<SinkStatus> = synchronized(sinks) { sinks.values.map { it.status() } }

    /** Sound is coming out of at least one output. */
    val rendering: Boolean get() = synchronized(sinks) { sinks.values.any { it.rendering } }
    /** The worst timing error of the outputs playing (ns). */
    val syncErrorNs: Long get() = synchronized(sinks) { sinks.values.filter { it.rendering }.maxByOrNull { kotlin.math.abs(it.syncErrorNs) }?.syncErrorNs ?: 0L }
    /** 0..1: an output stalls (a Bluetooth link chokes). */
    val stall: Double get() = synchronized(sinks) { sinks.values.filter { it.rendering }.maxOfOrNull { it.stall } ?: 0.0 }
    /** The slowest output's delay (write → heard), ns. */
    val outputLatencyNs: Long get() = synchronized(sinks) { sinks.values.filter { it.rendering }.maxOfOrNull { it.latencyNs } ?: 0L }
    /**
     * The slowest output's delay last time it played (kept while resting): what an earbud phone
     * that isn't playing yet will need when it takes over. Bluetooth is usually 0.2–0.35 s.
     */
    val lastLatencyNs: Long get() = synchronized(sinks) { sinks.values.maxOfOrNull { it.lastLatencyNs } ?: 350_000_000L }

    private val margins = ArrayDeque<LongArray>()

    /** The tightest arrival margin of the last few seconds (ns), or null if no music came. */
    fun minMarginNs(): Long? {
        val now = GroupClock.now()
        synchronized(margins) {
            return margins.filter { it[0] >= now - 4_000_000_000L }.minOfOrNull { it[1] }
        }
    }

    // ---- control --------------------------------------------------------------------------------

    fun start() {
        running = true
    }

    fun stop() {
        running = false
        setTargets(emptyList())
        synchronized(lock) { queue.clear(); chirps.clear() }
    }

    /** The outputs to play to now. Sinks come and go to match; one already playing carries on. */
    fun setTargets(targets: List<Target>) {
        synchronized(sinks) {
            val keep = if (running) targets.associateBy { it.key } else emptyMap()
            val gone = sinks.keys.filter { it !in keep }
            for (k in gone) sinks.remove(k)?.stop()
            for ((k, t) in keep) {
                val s = sinks[k]
                if (s == null) sinks[k] = Sink(t).also { it.start() } else s.update(t)
            }
        }
    }

    fun offer(c: AudioChunk) {
        // How early this piece arrived compared to when this phone has to write it (its slowest
        // output's own delay before that): the room keeps that above zero by sending further ahead.
        val now = GroupClock.now()
        val worst = synchronized(sinks) {
            sinks.values.minOfOrNull { it.adjustNs - (if (it.rendering) it.latencyNs else it.lastLatencyNs) }
        } ?: -lastLatencyNs
        val margin = c.groupNs + worst - now
        // Only meaningful once this phone's clock is on the room's time.
        if (GroupClock.ready) synchronized(margins) {
            margins.addLast(longArrayOf(now, margin))
            while (margins.isNotEmpty() && margins.first()[0] < now - 4_000_000_000L) margins.removeFirst()
            while (margins.size > 400) margins.removeFirst()
        }
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
        }
        synchronized(sinks) { sinks.values.forEach { it.onCut(epoch, atNs) } }
    }

    fun clear() {
        synchronized(lock) { queue.clear() }
        synchronized(sinks) { sinks.values.forEach { it.restart() } }
    }

    /** Plays the calibration chirp so that it's heard at [atGroupNs] on output [key] (null: all). */
    fun chirpAt(atGroupNs: Long, key: String? = null) {
        synchronized(lock) { chirps.add(atGroupNs to key) }
    }

    /** Called by each sink: drops what nobody can use any more; the format of what's next. */
    private fun housekeep(now: Long): Pair<Int, Int>? = synchronized(lock) {
        while (queue.isNotEmpty() && queue[0].endNs < now - 200_000_000L) queue.removeAt(0)
        chirps.removeAll { it.first + 2_000_000_000L < now }
        queue.firstOrNull()?.let { it.sampleRate to it.channels }
    }

    // ---- one output ------------------------------------------------------------------------------

    private inner class Sink(@Volatile var target: Target) {
        val key: String get() = target.key
        val adjustNs: Long get() = target.adjustNs

        @Volatile var syncErrorNs = 0L
        @Volatile var stall = 0.0
        @Volatile var rendering = false
        @Volatile var latencyNs = 0L
        @Volatile var lastLatencyNs = 350_000_000L
        @Volatile var routedOk = true
        @Volatile var routedName = ""

        @Volatile private var alive = true
        private var thread: Thread? = null

        fun start() {
            thread = Thread({ loop() }, "LToTS-$tag-${target.name.take(12)}").apply { isDaemon = true; start() }
        }

        fun stop() {
            alive = false
            thread?.interrupt()
        }

        fun update(t: Target) {
            val moved = t.device?.id != target.device?.id
            target = t
            if (moved) restartRequested = true
        }

        fun restart() { restartRequested = true }

        fun onCut(epoch: Int, atNs: Long) {
            // Already written past the cut: the output is restarted so it stops now.
            if (writtenContentEpoch == epoch && cursorNs > atNs + 5_000_000L) restartRequested = true
        }

        fun status() = SinkStatus(key, target.name, rendering, syncErrorNs, latencyNs, stall, routedOk, routedName, target.adjustNs)

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
        private val ctl = SyncController(jumpNs = 25_000_000L, jumpHoldNs = 600_000_000L)
        private val recent = LongArray(9)
        private var recentAt = 0
        private var lastHz = 0
        private var openedAtNs = 0L
        private var dither: RateDither? = null
        private var lastCtlNs = 0L
        private var idleSinceNs = -1L
        private var wasWanted = false
        private val ts = AudioTimestamp()
        private var lastTsFrame = -1L
        private var lastTsNano = 0L
        private var stallEma = 0.0
        private var lastRouteCheckNs = 0L

        private fun loop() {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            while (alive && running) {
                try {
                    step()
                } catch (e: InterruptedException) {
                    if (!alive) break
                } catch (e: Throwable) {
                    CrashReport.handled("RoomRenderer.step", e)
                    releaseTrack()
                    sleep(200)
                }
            }
            releaseTrack()
            rendering = false
        }

        private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

        private fun step() {
            val now = GroupClock.now()
            val format = housekeep(now)
            val keepAlive = now < keepAliveUntilNs
            val want = (wantPlay && format != null) || keepAlive
            if (!want || !GroupClock.ready) {
                // Nothing to play: let the output go after a moment (so Bluetooth can sleep, and
                // the earbuds can follow the other phone).
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
            if (track == null || rate != trackRate || ch != trackChannels || restartRequested) openTrack(rate, ch)
            val t = track ?: run { sleep(50); return }

            // When will the next frame we write be heard?
            val heardAt = nextFrameHeardAt(t)
            if (heardAt == null) {
                // The output hasn't started reporting yet (Bluetooth takes a moment): silence.
                writeSilence(rate / 200)
                return
            }
            val g = GroupClock.toGroup(heardAt)
            val adj = target.adjustNs
            if (!aligned) {
                cursorNs = g - adj
                aligned = true
                recentAt = 0
                ctl.reset()
            }
            // Bluetooth timestamps arrive in jumps: the median of the last few readings ignores
            // the odd wild one (a single bad reading must never make the music skip or go silent).
            recent[recentAt++ % recent.size] = g - (cursorNs + adj)
            val filled = minOf(recentAt, recent.size)
            val error = recent.copyOf(filled).also { it.sort() }[filled / 2]
            val nowLocal = System.nanoTime()
            if (nowLocal - lastCtlNs >= 200_000_000L) {
                val dt = if (lastCtlNs == 0L) 0.2 else (nowLocal - lastCtlNs) / 1e9
                lastCtlNs = nowLocal
                when (val d = ctl.update(error, dt, nowLocal)) {
                    is SyncController.Decision.Jump -> {
                        recentAt = 0
                        if (d.ns > 0) cursorNs += d.ns // late: skip ahead in the music
                        else holdFrames += -d.ns * rate / 1_000_000_000L // early: hold with silence
                    }
                    is SyncController.Decision.Rate -> {
                        val hz = dither?.next(d.ppm) ?: rate
                        // Only when it changes: every change reconfigures the phone's resampler.
                        if (hz != lastHz) { runCatching { t.playbackRate = hz }; lastHz = hz }
                    }
                }
                syncErrorNs = ctl.errorNs
                updateStall(t, nowLocal)
                checkRoute(t, nowLocal)
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

        /** Did Android send this output where it was asked? (Two Bluetooth devices often can't.) */
        private fun checkRoute(t: AudioTrack, nowLocal: Long) {
            if (nowLocal - lastRouteCheckNs < 1_000_000_000L) return
            lastRouteCheckNs = nowLocal
            val r = runCatching { t.routedDevice }.getOrNull()
            routedName = r?.productName?.toString() ?: ""
            val want = target.device
            routedOk = want == null || r == null || r.id == want.id
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
            val minSize = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT).coerceAtLeast(4096)
            // About 120 ms of room: enough to ride out a busy moment, small enough to react fast.
            val size = max(minSize, rate * ch * 4 * 12 / 100)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            target.device?.let { runCatching { t.setPreferredDevice(it) } }
            t.play()
            track = t
            trackRate = rate
            trackChannels = ch
            written = 0L
            aligned = false
            holdFrames = 0L
            lastTsFrame = -1L
            lastCtlNs = 0L
            lastHz = rate
            recentAt = 0
            stallEma = 0.0
            routedOk = true
            lastRouteCheckNs = System.nanoTime() // give the route a second to settle
            openedAtNs = System.nanoTime()
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
            val r = (t.playbackRate.takeIf { it > 0 } ?: trackRate).toDouble()
            val at = ts.nanoTime + ((written - ts.framePosition) * 1e9 / r).roundToLong()
            latencyNs = at - System.nanoTime()
            if (latencyNs in 1..3_000_000_000L) lastLatencyNs = latencyNs
            return at
        }

        /** How badly the output is stalling: its position should advance with the clock. */
        private fun updateStall(t: AudioTrack, nowLocal: Long) {
            if (lastTsFrame < 0) {
                lastTsFrame = ts.framePosition; lastTsNano = nowLocal
                return
            }
            // Over 2 s, as Bluetooth reports its progress in bursts; and not in the first seconds,
            // while the Bluetooth stream is still starting up.
            val dt = (nowLocal - lastTsNano) / 1e9
            if (dt < 2.0) return
            val advanced = (ts.framePosition - lastTsFrame).toDouble()
            val expected = dt * (t.playbackRate.takeIf { it > 0 } ?: trackRate)
            val ratio = if (expected > 0) advanced / expected else 1.0
            val settled = nowLocal - openedAtNs > 4_000_000_000L
            val bad = if (settled) ((0.9 - ratio) / 0.3).coerceIn(0.0, 1.0) else 0.0
            stallEma += 0.5 * (bad - stallEma)
            stall = stallEma
            lastTsFrame = ts.framePosition
            lastTsNano = nowLocal
        }

        private var silence = FloatArray(0)
        private var buf = FloatArray(0)
        private var chirpRate = 0
        private var chirp = FloatArray(0)

        private fun writeSilence(frames: Int) {
            val t = track ?: return
            val n = frames * trackChannels.coerceAtLeast(1)
            if (silence.size < n) silence = FloatArray(n)
            val wrote = t.write(silence, 0, n, AudioTrack.WRITE_BLOCKING)
            if (wrote > 0) written += wrote / trackChannels.coerceAtLeast(1)
            // The music's time doesn't move: holding silence is how this output waits for it.
        }

        /** [frames] frames of music (or silence where none arrived) at [cursorNs], plus the chirp. */
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
                val mine = chirps.filter { it.second == null || it.second == key }
                if (mine.isNotEmpty() && chirpRate != rate) { chirp = Chirp.make(rate); chirpRate = rate }
                for ((at, _) in mine) {
                    val s = chirp
                    val contentAt = at - target.adjustNs
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
}
