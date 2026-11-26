package com.hrvballoon.core

import java.util.Locale

/**
 * Debug-only timing of onPacket (spec 7.7). Percentiles are nearest rank: the value at rank ceil(p * n) of the sorted
 * samples, so 1..100 ms gives median 50 and p99 99. One long per packet; no other allocation while recording.
 */
class PacketTimer {
    private var nanos = LongArray(2048)
    var count = 0
        private set

    fun record(durationNanos: Long) {
        if (count == nanos.size) nanos = nanos.copyOf(count * 2)
        nanos[count++] = durationNanos
    }

    fun reset() { count = 0 }

    val medianMs: Double? get() = percentileMs(50)
    val p99Ms: Double? get() = percentileMs(99)

    private fun percentileMs(p: Int): Double? {
        if (count == 0) return null
        val sorted = nanos.copyOf(count).also { it.sort() }
        return sorted[(p * count + 99) / 100 - 1] / 1e6
    }

    /** The one log line, or null when nothing was timed. */
    fun line(): String? = medianMs?.let { String.format(Locale.ROOT, "onPacket n=%d median=%.3f ms p99=%.3f ms", count, it, p99Ms) }
}
