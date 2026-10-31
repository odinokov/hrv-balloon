package com.hrvballoon.core

/** One heartbeat on the session clock: [tEndMs] is when the RR interval ended, [arrivalMs] when its notification arrived. */
class Beat(val tEndMs: Double, val rrMs: Double, val arrivalMs: Long)

/** [gapMs] > 0 means notifications were lost before this one and the clock was re-anchored. */
class TimelineUpdate(val beats: List<Beat>, val gapMs: Double)

/**
 * Maps cumulative RR (raw 1/1024 s units, exact) onto the phone clock. The offset is the minimum
 * of (arrival − cumulative end) over the last 60 s of arrivals, i.e. the lowest-latency envelope,
 * so strap-vs-phone clock drift in either direction is tracked and beats never lead their arrival.
 */
class BeatTimeline {
    private var cum1024 = 0L
    private val window = ArrayDeque<Pair<Long, Double>>()

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
        if (excess > GAP_MS) {
            gap = excess
            window.clear()
            window.addLast(arrivalMs to sample)
            offset = sample
        }
        var c = start
        val beats = rr1024.map { rr -> c += rr; Beat(ms(c) + offset, ms(rr.toLong()), arrivalMs) }
        return TimelineUpdate(beats, gap)
    }

    private fun ms(units: Long) = units * 1000.0 / 1024

    private companion object {
        const val WINDOW_MS = 60_000L
        const val GAP_MS = 1500.0
    }
}
