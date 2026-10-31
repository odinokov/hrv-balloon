package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Pacer whose rate can change mid-session. Changes take effect at the next inhale start so the phase stays
 * continuous; the cycle count is piecewise linear in time. Phase 0 = inhale start, as in [Pacer].
 */
class PacerTrack(rateBpm: Double, val inhaleFraction: Double) {
    private val starts = ArrayList<Double>().apply { add(0.0) }
    private val rates = ArrayList<Double>().apply { add(rateBpm) }
    private val cycles0 = ArrayList<Double>().apply { add(0.0) }

    /** Rate of the latest segment (possibly one scheduled but not yet in effect). */
    val rateBpm get() = rates.last()

    private fun seg(tMs: Double): Int {
        var i = starts.size - 1
        while (i > 0 && starts[i] > tMs) i--
        return i
    }

    fun cycles(tMs: Double): Double {
        val i = seg(tMs)
        return cycles0[i] + (tMs - starts[i]) * rates[i] / 60_000
    }

    fun rateAt(tMs: Double) = rates[seg(tMs)]

    fun periodMs(tMs: Double) = 60_000 / rateAt(tMs)

    fun phase(tMs: Double): Double {
        val c = cycles(tMs)
        return c - floor(c)
    }

    /** Cycles − f·t within the current segment: lets a fixed-frequency fit stay phase-locked to inhale start. */
    fun cycleOffset(tMs: Double) = cycles(tMs) - rateAt(tMs) / 60_000 * tMs

    fun isInhale(tMs: Double) = phase(tMs) < inhaleFraction

    fun level(tMs: Double): Double {
        val p = phase(tMs)
        val u = if (p < inhaleFraction) p / inhaleFraction else 1 - (p - inhaleFraction) / (1 - inhaleFraction)
        return (1 - cos(PI * u)) / 2
    }

    /** Schedules [newRate] for the next inhale start after [nowMs] (replacing any pending change); returns that time. */
    fun changeRate(newRate: Double, nowMs: Double): Double {
        while (starts.size > 1 && starts.last() > nowMs) { starts.removeLast(); rates.removeLast(); cycles0.removeLast() }
        val i = seg(nowMs)
        val nextWhole = floor(cycles(nowMs) + 1e-9) + 1
        val at = starts[i] + (nextWhole - cycles0[i]) * 60_000 / rates[i]
        starts += at
        rates += newRate
        cycles0 += nextWhole
        return at
    }

    companion object {
        /**
         * Phase φ of the pacer curve's own fundamental, A·sin(2πθ + φ) fitted over one cycle (θ = 0 at inhale start):
         * at resonance HR is in phase with breathing, so this is where [Estimate.phaseDeg] should sit. −90° for 50:50, −72° for 40:60.
         */
        fun fundamentalPhaseDeg(inhaleFraction: Double): Double {
            var c = 0.0
            var d = 0.0
            for (i in 0 until 720) {
                val p = (i + 0.5) / 720
                val u = if (p < inhaleFraction) p / inhaleFraction else 1 - (p - inhaleFraction) / (1 - inhaleFraction)
                val level = (1 - cos(PI * u)) / 2
                c += level * cos(2 * PI * p)
                d += level * kotlin.math.sin(2 * PI * p)
            }
            return Math.toDegrees(kotlin.math.atan2(c, d))
        }
    }
}
