package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Peak-to-trough HR oscillation at the pacer frequency, its phase relative to pacer t = 0 (inhale start), and
 * [shape]: the share of the detrended variance explained by that sinusoid (R², 0..1) — a coherence-style
 * measure of how pacer-locked and regular the oscillation is, independent of its size.
 */
class Estimate(val p2tBpm: Double, val phaseDeg: Double, val shape: Double)

/**
 * Pacer-locked amplitude: beat-to-beat HR (midpoint-stamped) is resampled at 4 Hz over a window of
 * two breath periods and fitted jointly to a + b·(t − t̄) + c·cos(2πft) + d·sin(2πft). Joint, not
 * detrend-then-project: over two cycles a sinusoid is not orthogonal to a linear trend, and separate
 * detrending removes part of the signal. P = 2·√(c²+d²); φ = atan2(c, d) so the fit is A·sin(2πft + φ).
 */
class AmplitudeEstimator {
    private val tMid = ArrayList<Double>()
    private val hr = ArrayList<Double>()

    internal val retainedCount get() = tMid.size

    /** Time covered by the retained beats, ms — how much of the window has been filled. */
    val spanMs get() = if (tMid.size < 2) 0.0 else tMid.last() - tMid.first()

    fun add(tMidMs: Double, hrBpm: Double) {
        if (tMid.isNotEmpty() && tMidMs <= tMid.last()) return
        tMid += tMidMs
        hr += hrBpm
        prune(tMidMs - KEEP_MS)
    }

    fun reset() {
        tMid.clear()
        hr.clear()
    }

    /** [cycleOffset] (cycles) shifts the fit's phase reference so φ stays relative to the pacer's inhale start after rate changes. */
    fun estimate(rateBpm: Double, cycleOffset: Double = 0.0): Estimate? {
        if (tMid.size < 2) return null
        val n = (2 * 60000 / rateBpm / STEP_MS).roundToInt()
        val end = floor(tMid.last() / STEP_MS) * STEP_MS
        val start = end - (n - 1) * STEP_MS
        if (start < tMid.first()) return null
        prune(start)
        val f = rateBpm / 60
        val tBar = (start + end) / 2 / 1000
        val m = Array(4) { DoubleArray(4) }
        val v = DoubleArray(4)
        val ys = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val t = start + k * STEP_MS
            while (tMid[j + 1] < t) j++
            val w = (t - tMid[j]) / (tMid[j + 1] - tMid[j])
            val y = hr[j] + w * (hr[j + 1] - hr[j])
            ys[k] = y
            val phi = basis(t / 1000, tBar, f, cycleOffset)
            for (r in 0 until 4) {
                v[r] += phi[r] * y
                for (c in 0 until 4) m[r][c] += phi[r] * phi[c]
            }
        }
        val x = solveLinear(m, v) ?: return null
        var ssRes = 0.0
        var ssTot = 0.0
        for (k in 0 until n) {
            val phi = basis((start + k * STEP_MS) / 1000, tBar, f, cycleOffset)
            val trend = x[0] * phi[0] + x[1] * phi[1]
            val model = trend + x[2] * phi[2] + x[3] * phi[3]
            ssRes += (ys[k] - model) * (ys[k] - model)
            ssTot += (ys[k] - trend) * (ys[k] - trend)
        }
        val shape = if (ssTot > 0) (1 - ssRes / ssTot).coerceIn(0.0, 1.0) else 0.0
        return Estimate(2 * hypot(x[2], x[3]), Math.toDegrees(atan2(x[2], x[3])), shape)
    }

    private fun basis(ts: Double, tBar: Double, f: Double, cycleOffset: Double) =
        doubleArrayOf(1.0, ts - tBar, cos(2 * PI * (f * ts + cycleOffset)), sin(2 * PI * (f * ts + cycleOffset)))

    /** Drop beats older than [startMs], keeping one before it for interpolation. */
    private fun prune(startMs: Double) {
        var drop = 0
        while (drop + 1 < tMid.size && tMid[drop + 1] <= startMs) drop++
        if (drop > 0) {
            tMid.subList(0, drop).clear()
            hr.subList(0, drop).clear()
        }
    }


    private companion object {
        const val STEP_MS = 250.0
        const val KEEP_MS = 60_000.0
    }
}
