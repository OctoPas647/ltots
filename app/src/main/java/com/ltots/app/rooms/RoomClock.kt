package com.ltots.app.rooms

/**
 * The room's shared clock.
 *
 * Every phone keeps its own monotonic clock ([System.nanoTime], the same one AudioTrack and
 * AudioRecord timestamps use). The room runs on one "group time": the main phone's clock. Each
 * other phone pings the main over UDP a few times a second and works out how its own clock
 * maps onto group time: an offset and a drift (two crystals never tick at exactly the same
 * rate; a few parts per million adds up to milliseconds within minutes).
 *
 * Wi-Fi delays are uneven (power saving can hold a packet back for 100 ms), so only the pings
 * that came back fastest are trusted: a ping that took the shortest round trip is the one
 * whose two halves were most nearly equal. A straight line fitted through those gives the
 * mapping, typically to well under a millisecond on home Wi-Fi.
 */
data class ClockSample(
    /** Node clock when the ping left. */
    val t0: Long,
    /** Group clock when the main received it. */
    val t1: Long,
    /** Group clock when the main answered. */
    val t2: Long,
    /** Node clock when the answer arrived. */
    val t3: Long,
) {
    /** Time on the wire, both ways (the main's own handling time taken out). */
    val rtt: Long get() = (t3 - t0) - (t2 - t1)
    /** Group minus local, at [mid]. */
    val offset: Long get() = ((t1 - t0) + (t2 - t3)) / 2
    /** The local moment the [offset] belongs to. */
    val mid: Long get() = t0 + (t3 - t0) / 2
}

/**
 * A straight-line map between a phone's own clock and group time:
 * group = local + offset + drift * (local - ref).
 */
data class ClockMap(val ref: Long = 0L, val offset: Long = 0L, val drift: Double = 0.0) {
    fun toGroup(local: Long): Long = local + offset + (drift * (local - ref)).toLong()

    fun toLocal(group: Long): Long {
        // Solve group = local (1 + drift) + offset - drift*ref for local.
        val l = (group - offset + drift * ref) / (1.0 + drift)
        // The division loses precision far from ref; one correction step makes it exact.
        val guess = l.toLong()
        return guess + (group - toGroup(guess))
    }

    companion object {
        val IDENTITY = ClockMap()
    }
}

/**
 * Works out the [ClockMap] from the pings. Pure logic (no Android), so it's tested on the
 * desktop with simulated clocks and networks.
 */
class ClockEstimator(
    /** Pings older than this are forgotten (drift changes slowly with temperature). */
    private val windowNs: Long = 90_000_000_000L,
    /** At most this many pings are kept. */
    private val maxSamples: Int = 400,
) {
    private val samples = ArrayDeque<ClockSample>()

    @Volatile var map: ClockMap = ClockMap.IDENTITY
        private set

    /** Enough good pings for the music to rely on. */
    @Volatile var ready: Boolean = false
        private set

    /** Shortest round trip seen recently (ns): half of it bounds the error. */
    @Volatile var bestRttNs: Long = Long.MAX_VALUE
        private set

    /** How many pings are in the window. */
    val count: Int get() = synchronized(samples) { samples.size }

    fun reset() = synchronized(samples) {
        samples.clear()
        map = ClockMap.IDENTITY
        ready = false
        bestRttNs = Long.MAX_VALUE
    }

    fun add(s: ClockSample) {
        if (s.rtt < 0 || s.t3 < s.t0) return
        synchronized(samples) {
            samples.addLast(s)
            while (samples.size > maxSamples) samples.removeFirst()
            val oldest = s.t3 - windowNs
            while (samples.isNotEmpty() && samples.first().t3 < oldest) samples.removeFirst()
            refit()
        }
    }

    private fun refit() {
        val n = samples.size
        if (n == 0) return
        val rtts = samples.map { it.rtt }.sorted()
        val minRtt = rtts.first()
        bestRttNs = minRtt
        // The fastest quarter of the pings, plus anything within 0.3 ms of the fastest.
        val cut = maxOf(rtts[(n - 1) / 4], minRtt + 300_000L)
        val good = samples.filter { it.rtt <= cut }
        val first = good.first().mid
        val last = good.last().mid
        val spanNs = last - first
        if (good.size >= 8 && spanNs > 8_000_000_000L) {
            // Least squares: offset against time, relative to the newest good ping.
            val ref = last
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            // y relative to a base so the doubles keep their precision.
            val base = good.last().offset
            for (g in good) {
                val x = (g.mid - ref).toDouble()
                val y = (g.offset - base).toDouble()
                sx += x; sy += y; sxx += x * x; sxy += x * y
            }
            val m = good.size.toDouble()
            val den = m * sxx - sx * sx
            var slope = if (den != 0.0) (m * sxy - sx * sy) / den else 0.0
            // Real crystals differ by tens of ppm at most; anything wilder is noise.
            slope = slope.coerceIn(-MAX_DRIFT, MAX_DRIFT)
            val intercept = (sy - slope * sx) / m
            map = ClockMap(ref, base + intercept.toLong(), slope)
        } else {
            // Too few or too close together for a slope: the median offset of the good ones.
            val offs = good.map { it.offset }.sorted()
            map = ClockMap(good.last().mid, offs[offs.size / 2], map.drift)
        }
        ready = good.size >= 4
    }

    companion object {
        /** 500 ppm: far beyond any real clock pair. */
        const val MAX_DRIFT = 500e-6
    }
}

/**
 * This phone's view of group time. On the main it's its own clock (or, after it took over as
 * main, the mapping it had when it took over, frozen, so the room's time carries on unbroken).
 * On every other phone it follows the [ClockEstimator].
 */
object GroupClock {
    @Volatile private var fixed: ClockMap? = ClockMap.IDENTITY
    @Volatile private var estimator: ClockEstimator? = null

    /** The main: group time is this phone's clock through [map]. */
    fun lead(map: ClockMap = ClockMap.IDENTITY) {
        fixed = map
        estimator = null
    }

    /** A relay: group time comes from the pings to the main. */
    fun follow(e: ClockEstimator) {
        estimator = e
        fixed = null
    }

    val map: ClockMap get() = fixed ?: estimator?.map ?: ClockMap.IDENTITY

    /** True when group time can be trusted (always on the main). */
    val ready: Boolean get() = fixed != null || estimator?.ready == true

    fun now(): Long = map.toGroup(System.nanoTime())
    fun toGroup(local: Long): Long = map.toGroup(local)
    fun toLocal(group: Long): Long = map.toLocal(group)
}
