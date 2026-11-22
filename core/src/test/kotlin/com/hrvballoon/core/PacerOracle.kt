package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.cos

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
