package com.hrvballoon.core

import kotlin.math.abs

/** Solves a·x = b by Gaussian elimination with partial pivoting on copies of the inputs; null when (near) singular. */
internal fun solveLinear(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
    val n = b0.size
    val a = Array(n) { a0[it].copyOf() }
    val b = b0.copyOf()
    for (col in 0 until n) {
        var p = col
        for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[p][col])) p = r
        if (abs(a[p][col]) < 1e-12) return null
        if (p != col) {
            a[p] = a[col].also { a[col] = a[p] }
            b[p] = b[col].also { b[col] = b[p] }
        }
        for (r in col + 1 until n) {
            val s = a[r][col] / a[col][col]
            for (k in col until n) a[r][k] -= s * a[col][k]
            b[r] -= s * b[col]
        }
    }
    val x = DoubleArray(n)
    for (r in n - 1 downTo 0) {
        var s = b[r]
        for (k in r + 1 until n) s -= a[r][k] * x[k]
        x[r] = s / a[r][r]
    }
    return x
}
