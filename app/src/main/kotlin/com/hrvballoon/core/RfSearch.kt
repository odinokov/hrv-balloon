package com.hrvballoon.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Resonance-frequency search as 1-D Bayesian optimisation: a Gaussian process over breathing rates 4.0–7.0 /min
 * (RBF kernel, length 0.5 /min, ~15 % relative measurement noise) with an upper-confidence-bound acquisition.
 * Probe 1 is the current rate; probe 2 steps 0.7 /min in the direction the HR–pacer phase suggests; later probes
 * balance exploring unprobed rates against refining the best one. [best] is the posterior-mean argmax.
 */
class RfSearch(private val startRate: Double) {
    private val rates = ArrayList<Double>()
    private val scores = ArrayList<Double>()
    private var lastBest: Double? = null
    private var stable = 0

    val probeCount get() = rates.size

    /** (rate, score) in probe order. */
    val probes get() = rates.indices.map { rates[it] to scores[it] }

    var best: Double? = null
        private set

    val converged get() = rates.size >= 5 && stable >= 2

    /** [phaseHint] > 0 = HR leads the pacer (breathing slower than resonance), < 0 = lags, 0 = unknown. */
    fun next(phaseHint: Double): Double = when (rates.size) {
        0 -> startRate
        1 -> snap(startRate + if (phaseHint >= 0) 0.7 else -0.7)
        else -> {
            val beta = if (rates.size < 5) 1.5 else 0.8
            GRID.maxBy { g -> posterior(g).let { (m, v) -> m + beta * sqrt(v) } }
        }
    }

    fun report(rateBpm: Double, score: Double) {
        rates += rateBpm
        scores += score
        if (rates.size < 3) return
        val b = GRID.maxBy { posterior(it).first }
        stable = if (lastBest != null && abs(b - lastBest!!) <= 0.1 + 1e-9) stable + 1 else 0
        lastBest = b
        best = b
    }

    /** Posterior mean and variance of the normalised score at [rate]. */
    private fun posterior(rate: Double): Pair<Double, Double> {
        val n = rates.size
        val mean = scores.average()
        val y = DoubleArray(n) { if (mean > 0) scores[it] / mean - 1 else scores[it] }
        val k = Array(n) { i -> DoubleArray(n) { j -> kernel(rates[i], rates[j]) + if (i == j) NOISE else 0.0 } }
        val kg = DoubleArray(n) { kernel(rates[it], rate) }
        val alpha = solveLinear(k, y) ?: return 0.0 to 1.0   // degenerate kernel matrix: fall back to the prior
        val v = solveLinear(k, kg) ?: return 0.0 to 1.0
        var mu = 0.0
        var kv = 0.0
        for (i in 0 until n) { mu += kg[i] * alpha[i]; kv += kg[i] * v[i] }
        return mu to (1.0 - kv).coerceAtLeast(0.0)
    }

    private fun kernel(a: Double, b: Double) = exp(-(a - b) * (a - b) / (2 * LENGTH * LENGTH))

    private fun snap(rate: Double) = ((rate * 10).roundToInt() / 10.0).coerceIn(4.0, 7.0)

    private companion object {
        val GRID = DoubleArray(31) { (40 + it) / 10.0 }
        const val LENGTH = 0.5
        const val NOISE = 0.15 * 0.15
    }
}
