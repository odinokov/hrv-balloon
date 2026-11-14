package com.hrvballoon.core

import kotlin.math.abs

class ClassifiedBeat(val beat: Beat, val accepted: Boolean)

/**
 * Rejects RR outside 300–2000 ms, or more than 20% from the median of the centered 5-beat window
 * (in-range neighbours only, needs ≥ 3). Centered, not trailing: a trailing median lags the steep
 * part of large swings and rejects real beats exactly when amplitude is high. Beat i is therefore
 * classified once beat i+2 has arrived.
 */
class ArtifactFilter {
    private val buf = ArrayDeque<Beat>()
    private var unclassified = 0

    fun push(beat: Beat): List<ClassifiedBeat> {
        buf.addLast(beat)
        unclassified++
        if (unclassified < 3) return emptyList()
        val out = classifyAt(buf.size - 3)
        unclassified = 2
        while (buf.size > 5) buf.removeFirst()
        return listOf(out)
    }

    fun flush(): List<ClassifiedBeat> {
        val out = (buf.size - unclassified until buf.size).map { classifyAt(it) }
        unclassified = 0
        return out
    }

    fun reset() {
        buf.clear()
        unclassified = 0
    }

    private fun classifyAt(c: Int): ClassifiedBeat {
        val b = buf[c]
        if (b.rrMs !in RANGE) return ClassifiedBeat(b, false)
        val w = (maxOf(0, c - 2)..minOf(buf.size - 1, c + 2)).map { buf[it].rrMs }.filter { it in RANGE }.sorted()
        if (w.size < 3) return ClassifiedBeat(b, true)
        val med = if (w.size % 2 == 1) w[w.size / 2] else (w[w.size / 2 - 1] + w[w.size / 2]) / 2
        return ClassifiedBeat(b, abs(b.rrMs - med) <= TOLERANCE * med)
    }

    private companion object {
        val RANGE = 300.0..2000.0
        const val TOLERANCE = 0.20
    }
}
