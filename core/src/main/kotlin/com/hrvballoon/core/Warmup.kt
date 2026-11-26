package com.hrvballoon.core

const val WARMUP_MS = 60_000.0

/** Spec 3.1: the one game-RR check. Equality counts. No rounding. */
fun isGameRr(tEndMs: Double, rrMs: Double, boundaryMs: Double?): Boolean =
    boundaryMs != null && tEndMs - rrMs >= boundaryMs

/** Spec 3.3: the warm-up clock. T = t0 + WARMUP_MS; an event before T restarts it. */
class WarmupClock {
    var t0Ms: Double? = null
        private set
    private var ended = false

    val boundaryMs: Double? get() = t0Ms?.plus(WARMUP_MS)

    /** True when this call set t0 (the caller writes warmup_start). */
    fun onUsable(tEndMs: Double): Boolean {
        if (t0Ms != null) return false
        t0Ms = maxOf(0.0, tEndMs)
        return true
    }

    fun isGame(tEndMs: Double, rrMs: Double): Boolean = isGameRr(tEndMs, rrMs, boundaryMs)

    /** Pause, signal loss or confirmed gap at nowMs. True when it reset the clock.
     *  For a confirmed gap nowMs is the confirmation moment, not the gap start (spec 2, 3.3 item 4). */
    fun onBreak(nowMs: Double): Boolean {
        val t = boundaryMs ?: return false
        if (nowMs >= t) return false
        t0Ms = null
        return true
    }

    fun remainingMs(nowMs: Double): Double = boundaryMs?.let { maxOf(0.0, it - nowMs) } ?: WARMUP_MS

    /** True once per session, at the first call with nowMs >= T (the caller writes warmup_end). */
    fun takeEnd(nowMs: Double): Boolean {
        val t = boundaryMs ?: return false
        if (ended || nowMs < t) return false
        ended = true
        return true
    }
}
