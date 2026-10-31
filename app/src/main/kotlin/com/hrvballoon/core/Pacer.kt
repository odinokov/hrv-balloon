package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/** Breathing pacer: phase 0 = inhale start; [level] rises 0→1 over the inhale and falls back over the exhale, cosine-eased. */
class Pacer(val rateBpm: Double, val inhaleFraction: Double) {
    val periodMs = 60000 / rateBpm

    fun phase(tMs: Double): Double {
        val p = (tMs % periodMs) / periodMs
        return if (p < 0) p + 1 else p
    }

    fun isInhale(tMs: Double) = phase(tMs) < inhaleFraction

    fun level(tMs: Double): Double {
        val p = phase(tMs)
        val u = if (p < inhaleFraction) p / inhaleFraction else 1 - (p - inhaleFraction) / (1 - inhaleFraction)
        return (1 - cos(PI * u)) / 2
    }
}

/**
 * Balloon height 0..1 from peak-to-trough HR: grounded below the lift threshold, at the top from the ceiling up.
 * The ceiling caps the reward so deeper breathing isn't paid out forever; the threshold sets how hard lift-off is.
 */
fun balloonTarget(p2tBpm: Double?, floorBpm: Double, ceilingBpm: Double) =
    if (p2tBpm == null) 0.0 else ((p2tBpm - floorBpm) / (ceilingBpm - floorBpm)).coerceIn(0.0, 1.0)

/**
 * Credit 0..1 from the oscillation's sinusoidality (R² at the pacer rate): nothing below 0.40 (breathing a full
 * breath/min off the pacer scores ~0.5 on real data), full from 0.85 (a settled paced session sits ~0.85). Lets depth
 * without rhythm go unrewarded without punishing ordinary beat-to-beat irregularity.
 */
fun shapeGate(shape: Double) = ((shape - 0.40) / 0.45).coerceIn(0.0, 1.0)

/** First-order smoothing independent of frame rate: value → target with time constant [tauMs]. */
class Smoother(val tauMs: Double = 1000.0) {
    var value = 0.0

    fun step(target: Double, dtMs: Double): Double {
        value += (target - value) * (1 - exp(-dtMs / tauMs))
        return value
    }
}

/** Breathing rate in tenths of a breath/min, clamped to 4.0–7.0. */
fun stepRateTenths(cur: Int, delta: Int) = (cur + delta).coerceIn(40, 70)
