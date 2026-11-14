package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A frozen projection: the filter's level plus its harmonic rotated to the pacer cycle count passed to [at]. */
class HrForecast(private val level: Double, private val c: Double, private val s: Double, private val cycles0: Double) {
    fun at(cycles: Double): Double {
        val a = 2 * PI * (cycles - cycles0)
        return level + c * cos(a) + s * sin(a)
    }
}

/**
 * Kalman filter behind the trace panel's forecast: HR = level + one harmonic of the pacer cycle, both random walks.
 * It steps by pacer phase, not clock time, so the harmonic stays locked through rate changes. Noise was tuned by
 * replaying a recorded session (q_level 1, q_harmonic 0.1 bpm²/s, r 1 bpm²): 0.4–3.1 bpm error 1–6 s ahead against a
 * 13-bpm swing, versus 1.4–4.3 for repeating the last breath. Display only: nothing scored reads it.
 */
class HrKalman(private val qLevel: Double = 1.0, private val qHarmonic: Double = 0.1, private val r: Double = 1.0) {
    private val x = DoubleArray(3)
    private val p = Array(3) { DoubleArray(3) }
    private var lastMs = Double.NaN
    private var lastCycles = 0.0
    private var firstCycles = 0.0

    fun reset() { lastMs = Double.NaN }

    fun update(tMs: Double, cycles: Double, hrBpm: Double) {
        if (lastMs.isNaN()) {
            x[0] = hrBpm; x[1] = 0.0; x[2] = 0.0
            for (i in 0 until 3) for (j in 0 until 3) p[i][j] = if (i == j) 100.0 else 0.0
            lastMs = tMs; lastCycles = cycles; firstCycles = cycles
            return
        }
        val dtS = ((tMs - lastMs) / 1000).coerceAtLeast(0.0)
        val a = 2 * PI * (cycles - lastCycles)
        val f = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(a), sin(a)), doubleArrayOf(0.0, -sin(a), cos(a)))
        // Predict: x = F x, P = F P Fᵀ + Q.
        val nx = DoubleArray(3) { i -> (0 until 3).sumOf { f[i][it] * x[it] } }
        val fp = Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { f[i][it] * p[it][j] } } }
        for (i in 0 until 3) for (j in 0 until 3) {
            p[i][j] = (0 until 3).sumOf { fp[i][it] * f[j][it] } + if (i != j) 0.0 else if (i == 0) qLevel * dtS else qHarmonic * dtS
        }
        // Update with the beat, observed as H x = level + cosine part.
        val ph = DoubleArray(3) { i -> p[i][0] + p[i][1] }
        val hp = DoubleArray(3) { j -> p[0][j] + p[1][j] }
        val sVar = ph[0] + ph[1] + r
        val innov = hrBpm - (nx[0] + nx[1])
        for (i in 0 until 3) x[i] = nx[i] + ph[i] / sVar * innov
        for (i in 0 until 3) for (j in 0 until 3) p[i][j] -= ph[i] * hp[j] / sVar
        lastMs = tMs; lastCycles = cycles
    }

    /** The current projection; null until a full breath of beats has been seen since the last reset. */
    fun forecast(): HrForecast? = if (lastMs.isNaN() || lastCycles - firstCycles < 1.0) null else HrForecast(x[0], x[1], x[2], lastCycles)
}
