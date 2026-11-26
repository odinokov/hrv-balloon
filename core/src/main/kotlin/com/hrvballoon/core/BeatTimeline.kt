package com.hrvballoon.core

/**
 * One heartbeat on the session clock: [tEndMs] is when the RR interval ended, [arrivalMs] when its notification arrived.
 * [paused] is set by the engine at arrival, [afterGap] by the timeline: the filter classifies a beat two beats later,
 * when the app may have changed side or the gap is already behind the beats it holds.
 */
class Beat(val tEndMs: Double, val rrMs: Double, val arrivalMs: Long, var paused: Boolean = false, var afterGap: Boolean = false,
    /** Session-wide number of the measured RR, +1 per beat in arrival order; set by the engine (spec 8.4). */
    var index: Long = -1,
)

/** [gapMs] > 0 means notifications were lost before this one and the clock was re-anchored. */
class TimelineUpdate(val beats: List<Beat>, val gapMs: Double)

/**
 * Maps cumulative RR (raw 1/1024 s units, exact) onto the phone clock. The offset is the minimum
 * of (arrival − cumulative end) over the last 60 s of arrivals, i.e. the lowest-latency envelope,
 * so strap-vs-phone clock drift in either direction is tracked and beats never lead their arrival.
 * A notification late by more than GAP_MS but at most SignalMonitor.GAP_MS keeps the old offset, as do those queued behind
 * it; the first on-time one (arrival at least half its own RR after the previous) decides: excess persists = loss, else delay.
 * Later than SignalMonitor.GAP_MS re-anchors at once. The late notification's first beat carries afterGap (a delay
 * costs one RMSSD difference), as does the first beat of a loss decided later. On a real loss the late notification is
 * stamped up to its lateness early (accepted: the measured recordings had no real losses) and the gap reported is its excess.
 */
class BeatTimeline {
    private var cum1024 = 0L
    private val window = ArrayDeque<Pair<Long, Double>>()
    private var pending = false // an elevated notification awaits its verdict: delay (queued burst) or loss
    private var prevArrival = 0L
    private var lateExcess = 0.0

    fun onNotification(arrivalMs: Long, rr1024: IntArray): TimelineUpdate {
        if (rr1024.isEmpty()) return TimelineUpdate(emptyList(), 0.0)
        val start = cum1024
        for (rr in rr1024) cum1024 += rr
        val sample = arrivalMs - ms(cum1024)
        while (window.isNotEmpty() && window.first().first < arrivalMs - WINDOW_MS) window.removeFirst()
        window.addLast(arrivalMs to sample)
        var offset = window.minOf { it.second }
        var gap = 0.0
        val excess = sample - offset
        var elevated = false
        var loss = false
        if (!pending) {
            elevated = excess > GAP_MS
            loss = excess > SignalMonitor.GAP_MS // beyond any stall the monitor tolerates: no deferral
            pending = elevated && !loss
            lateExcess = excess
        } else if (arrivalMs - prevArrival >= ms(cum1024 - start) / 2) { // on time: the queued burst has drained
            pending = false
            loss = excess > GAP_MS
        }
        if (loss) {
            gap = lateExcess // the late notification's excess, not the deciding one's
            window.clear()
            window.addLast(arrivalMs to sample)
            offset = sample
        }
        prevArrival = arrivalMs
        var c = start
        val beats = rr1024.mapIndexed { i, rr -> c += rr; Beat(ms(c) + offset, ms(rr.toLong()), arrivalMs, afterGap = (elevated || loss) && i == 0) }
        return TimelineUpdate(beats, gap)
    }

    private fun ms(units: Long) = units * 1000.0 / 1024

    private companion object {
        const val WINDOW_MS = 60_000L
        const val GAP_MS = 1500.0
    }
}
