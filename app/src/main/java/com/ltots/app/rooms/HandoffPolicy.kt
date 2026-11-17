package com.ltots.app.rooms

/**
 * What one phone reports about its link to the earbuds, once a second.
 */
data class LinkReport(
    val nodeId: String,
    /** The earbuds are connected to this phone (A2DP). */
    val connected: Boolean,
    /** Signal strength in dBm, when this phone can measure it (null: it can't). */
    val rssi: Int?,
    /**
     * 0 = the music flows to the earbuds perfectly, 1 = the link is choking (the Bluetooth
     * stream stalls, falls behind, or stops). Only the phone that's playing can tell.
     */
    val stall: Double,
    /** When the report was made (ms, any clock shared by all calls). */
    val atMs: Long,
)

/**
 * Decides which phone should be sending the music to the earbuds (Earbuds mode).
 *
 * The phone playing keeps the earbuds until its link is clearly getting worse — the stream
 * stalling, or a signal strength well below the other phone's — and only for more than a
 * moment, so walking past a doorway doesn't flip it back and forth. After a switch the new
 * phone keeps the earbuds for a while ([minDwellMs]) unless its link actually breaks.
 *
 * Pure logic: tested with simulated walks round the apartment.
 */
class HandoffPolicy(
    /** At least this long between two switches, unless the link breaks. */
    private val minDwellMs: Long = 8_000,
    /** A stall has to last this long to count. */
    private val stallHoldMs: Long = 1_200,
    /** The other phone must be this much stronger (dB), for [strongerHoldMs]. */
    private val strongerDb: Int = 10,
    private val strongerHoldMs: Long = 2_500,
    /** Below this (dBm) the link is about to drop. */
    private val weakDbm: Int = -84,
    private val weakHoldMs: Long = 1_500,
    /** Reports older than this are ignored. */
    private val staleMs: Long = 4_000,
) {
    private var lastSwitchMs = Long.MIN_VALUE / 2
    private var stallSince = -1L
    private var weakSince = -1L
    private var strongerSince = -1L
    private var strongerWho: String? = null

    /** Why the last switch happened (for the room's status line). */
    var lastReason: String = ""
        private set

    fun reset() {
        lastSwitchMs = Long.MIN_VALUE / 2
        stallSince = -1L; weakSince = -1L; strongerSince = -1L; strongerWho = null
    }

    /** Tell the policy about a switch made by hand. */
    fun switched(nowMs: Long) {
        lastSwitchMs = nowMs
        stallSince = -1L; weakSince = -1L; strongerSince = -1L; strongerWho = null
    }

    private fun score(r: LinkReport): Double = (r.rssi ?: -70).toDouble() - r.stall * 25.0

    /**
     * Which phone should have the earbuds now: [active] to keep it, another id to switch, or
     * null when no phone has them.
     */
    fun decide(nowMs: Long, active: String?, reports: Collection<LinkReport>): String? {
        val fresh = reports.filter { it.connected && nowMs - it.atMs <= staleMs }
        if (fresh.isEmpty()) return active.takeIf { a -> reports.any { it.nodeId == a && it.connected } }
        val cur = fresh.firstOrNull { it.nodeId == active }
        val others = fresh.filter { it.nodeId != active }
        if (cur == null) {
            // The phone that had them lost them (or nobody had them yet): the best one takes over.
            val best = fresh.maxBy { score(it) }
            if (best.nodeId != active) {
                lastReason = if (active == null) "started" else "link to the earbuds lost"
                switched(nowMs)
            }
            return best.nodeId
        }
        if (others.isEmpty()) {
            stallSince = -1L; weakSince = -1L; strongerSince = -1L
            return active
        }
        val best = others.maxBy { score(it) }

        // How long each warning sign has lasted.
        stallSince = if (cur.stall >= 0.5) (if (stallSince < 0) nowMs else stallSince) else -1L
        weakSince = if (cur.rssi != null && cur.rssi <= weakDbm) (if (weakSince < 0) nowMs else weakSince) else -1L
        val stronger = cur.rssi != null && best.rssi != null && best.rssi - cur.rssi >= strongerDb
        if (stronger) {
            if (strongerSince < 0 || strongerWho != best.nodeId) { strongerSince = nowMs; strongerWho = best.nodeId }
        } else { strongerSince = -1L; strongerWho = null }

        val dwellOk = nowMs - lastSwitchMs >= minDwellMs
        val stalling = stallSince >= 0 && nowMs - stallSince >= stallHoldMs
        val weak = weakSince >= 0 && nowMs - weakSince >= weakHoldMs
        val beaten = strongerSince >= 0 && nowMs - strongerSince >= strongerHoldMs
        // A stalling link may switch sooner than the dwell time: it's audible already.
        val canSwitch = dwellOk || (stalling && nowMs - lastSwitchMs >= minDwellMs / 2)
        val betterThanCur = score(best) > score(cur) + 3.0 || best.rssi == null || cur.rssi == null
        val reason = when {
            stalling && betterThanCur -> "the music was stalling"
            weak && betterThanCur -> "weak signal (${cur.rssi} dBm)"
            beaten -> "${best.rssi} dBm vs ${cur.rssi} dBm"
            else -> null
        }
        if (reason != null && canSwitch) {
            lastReason = reason
            switched(nowMs)
            return best.nodeId
        }
        return active
    }
}
