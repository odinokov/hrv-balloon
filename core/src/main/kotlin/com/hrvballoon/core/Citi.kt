package com.hrvballoon.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Numbers of the last decision that reached step 5 of spec 7.5; tests read them, nothing else does. */
internal class CitiTrace(val a: DoubleArray, val lambda: Double, val pmMinusPn: Double, val gain: Double, val correctedMs: Double)

/**
 * Citi, Brown, Barbieri 2012 shifted-R-peak label "m" (spec 2.10 section 7). One segment holds every measured RR in
 * seconds; the fit uses only normal intervals. Not thread-safe; the caller owns one instance per session.
 */
class CitiModel {
    private class Fit(val a: DoubleArray, val lambda: Double)

    private val r = ArrayList<Double>()       // raw RR, s
    private val end = ArrayList<Double>()     // cumulative raw RR, s: end of r_k counted from the start of the segment
    private val h = ArrayList<Double>()       // history value: raw until a confirmed correction replaces it
    private val normal = ArrayList<Boolean>()
    private val replaced = ArrayList<Boolean>()
    private var first = 0L
    private var fit: Fit? = null
    private var fitPos = -1
    private var prevA: DoubleArray? = null
    internal var trace: CitiTrace? = null
        private set
    internal val fitLambda: Double? get() = fit?.lambda

    /** Spec 7.2: a new segment; clears history and fit. */
    fun startSegment() {
        r.clear(); end.clear(); h.clear(); normal.clear(); replaced.clear()
        fit = null; fitPos = -1; prevA = null; trace = null
    }

    /** Every measured RR of the segment in arrival order, raw; [index] is the session-wide beat number. */
    fun push(index: Long, rrMs: Double) {
        if (r.isNotEmpty() && index != first + r.size) startSegment()
        if (r.isEmpty()) first = index
        val s = rrMs / 1000.0
        r += s; h += s; normal += false; replaced += false
        // a bad RR is never normal and adds no time, so one bad value cannot poison the segment clock
        end += (end.lastOrNull() ?: 0.0) + if (s.isFinite() && s > 0) s else 0.0
    }

    /** Spec 7.5 and 8.1: 1, 0 or null (empty). Call once per index in index order; it also records normality and refits. */
    fun decide(index: Long, accepted: Boolean): Int? {
        trace = null
        val i = index - first
        if (r.isEmpty() || i < 0 || i >= r.size) return null
        val p = i.toInt()
        if (p <= fitPos) return null   // repeated or backwards: the model has moved on
        return try {
            val out = evaluate(p)
            normal[p] = (accepted && r[p].isFinite() && r[p] > 0) || replaced[p]
            fit = fitAt(p); fitPos = p
            out
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun evaluate(i: Int): Int? {
        val f = fit
        if (f == null || fitPos != i - 1 || i < ORDER || i + 2 >= r.size) return null
        if ((1..ORDER).any { !normal[i - it] }) return null
        val a = f.a; val lam = f.lambda
        val ri = r[i]; val rj = r[i + 1]; val rk = r[i + 2]
        if (!(ri > 0 && rj > 0 && rk > 0 && ri.isFinite() && rj.isFinite() && rk.isFinite())) return null
        val h1 = h[i - 1]; val h2 = h[i - 2]; val h3 = h[i - 3]; val h4 = h[i - 4]; val h5 = h[i - 5]
        val mu1 = a[0] * h1 + a[1] * h2 + a[2] * h3 + a[3] * h4 + a[4] * h5
        if (!(mu1 > 0) || sqrt(mu1 * mu1 * mu1 / lam) < MIN_SD_S) return null
        fun mu2(x: Double) = a[0] * x + a[1] * h1 + a[2] * h2 + a[3] * h3 + a[4] * h4
        val m2 = mu2(mu1)
        val mu12 = mu1 + m2
        val lam12 = lam * mu12 * mu12 * mu12 / ((1 + a[0]) * (1 + a[0]) * mu1 * mu1 * mu1 + m2 * m2 * m2)
        val s = ri + rj
        val pN = logf(ri, mu1, lam)
        val pm = logf(s, mu12, lam12)
        val pe = logf(s, mu1, lam)
        val ps = logf(ri, mu12, lam12)
        val pr = logf(rj, mu1, lam)
        if (!(pN.isFinite() && pm.isFinite() && pe.isFinite() && ps.isFinite() && pr.isFinite())) return null
        if (pm - pN <= T_M) return 0
        if (pm <= pe || pm <= ps || pr > max(max(pN, pe), max(ps, pm)) + T_R) return 0
        val x = bestSplit(s, lam, mu1, ::mu2)
        val orig = pN + logf(rj, mu2(ri), lam) + logf(rk, a[0] * rj + a[1] * ri + a[2] * h1 + a[3] * h2 + a[4] * h3, lam)
        val corr = logf(x, mu1, lam) + logf(s - x, mu2(x), lam) + logf(rk, a[0] * (s - x) + a[1] * x + a[2] * h1 + a[3] * h2 + a[4] * h3, lam)
        val gain = corr - orig
        if (!gain.isFinite()) return null
        trace = CitiTrace(a.copyOf(), lam, pm - pN, gain, x * 1000.0)
        if (gain <= T_CONFIRM) return 0
        h[i] = x; h[i + 1] = s - x
        replaced[i] = true; replaced[i + 1] = true
        normal[i] = true; normal[i + 1] = true
        return 1
    }

    /** Spec 7.5 step 4: x* on (0, s); a coarse grid for the global maximum, then golden section to 1e-7 s. */
    private fun bestSplit(s: Double, lam: Double, mu1: Double, mu2: (Double) -> Double): Double {
        fun g(x: Double) = logf(x, mu1, lam) + logf(s - x, mu2(x), lam)
        var best = 0
        var bv = Double.NEGATIVE_INFINITY
        for (m in 0 until GRID) {
            val v = g(s * (m + 0.5) / GRID)
            if (v > bv) { bv = v; best = m }
        }
        var lo = s * max(best - 0.5, 0.0) / GRID
        var hi = s * (best + 1.5).coerceAtMost(GRID.toDouble()) / GRID
        val phi = (sqrt(5.0) - 1) / 2
        while (hi - lo > 1e-7) {
            val c = hi - phi * (hi - lo); val d = lo + phi * (hi - lo)
            if (g(c) > g(d)) hi = d else lo = c
        }
        return (lo + hi) / 2
    }

    /** Spec 7.4: weighted ML at the moment R_n; null when unavailable or degenerate. */
    private fun fitAt(n: Int): Fit? {
        val tn = end[n]
        if (!(tn >= WINDOW_S)) return null
        val ks = ArrayList<Int>()
        var k = n
        while (k >= ORDER) {
            val start = if (k == ORDER) 0.0 else end[k - ORDER - 1]
            if (start < tn - WINDOW_S) break
            if ((0..ORDER).all { normal[k - it] }) ks += k
            k--
        }
        if (ks.size < MIN_RESPONSES) return null
        val m = ks.size
        val y = DoubleArray(m) { h[ks[it]] }
        val w = DoubleArray(m) { exp(-ALPHA * (tn - end[ks[it]])) }
        val x = Array(m) { q -> DoubleArray(ORDER) { j -> h[ks[q] - 1 - j] } }
        fun cost(a: DoubleArray): Double {
            var s = 0.0
            for (q in 0 until m) {
                val mu = dot(a, x[q])
                if (!(mu > 0)) return Double.POSITIVE_INFINITY
                s += w[q] * (y[q] - mu) * (y[q] - mu) / (mu * mu * y[q])
            }
            return if (s.isFinite()) s else Double.POSITIVE_INFINITY
        }
        var a = prevA?.takeIf { cost(it).isFinite() } ?: DoubleArray(ORDER) { 1.0 / ORDER }
        var s = cost(a)
        if (!s.isFinite()) return null
        // Levenberg-Marquardt on g_q = sqrt(w/y)(y/mu - 1), S = sum g^2; one pass is one iteration of the 50.
        var damp = 1e-3
        var converged = false
        for (iter in 0 until MAX_ITER) {
            val jtj = Array(ORDER) { DoubleArray(ORDER) }
            val jtg = DoubleArray(ORDER)
            for (q in 0 until m) {
                val mu = dot(a, x[q])
                val c = sqrt(w[q] / y[q])
                val g = c * (y[q] / mu - 1)
                val d = -c * y[q] / (mu * mu)
                for (p in 0 until ORDER) {
                    jtg[p] += d * x[q][p] * g
                    for (u in 0 until ORDER) jtj[p][u] += d * d * x[q][p] * x[q][u]
                }
            }
            val sys = Array(ORDER) { p -> DoubleArray(ORDER) { u -> jtj[p][u] + if (p == u) damp * jtj[p][p] else 0.0 } }
            val delta = solveLinear(sys, DoubleArray(ORDER) { -jtg[it] })
            if (delta == null) { damp *= 10; continue }
            val cand = DoubleArray(ORDER) { a[it] + delta[it] }
            val sc = cost(cand)
            val small = delta.all { abs(it) < STEP_TOL }
            if (sc < s) { a = cand; s = sc; damp = max(damp / 10, 1e-12) } else damp *= 10
            if (small) { converged = true; break }
        }
        if (!converged || !(s > 0)) return null
        val sw = w.sum()
        val lam = sw / s
        val mean = (0 until m).sumOf { w[it] * dot(a, x[it]) } / sw
        if (!(lam.isFinite() && lam > 0 && a.all { it.isFinite() } && mean > 0)) return null
        if (sqrt(mean * mean * mean / lam) < MIN_SD_S) return null
        prevA = a
        return Fit(a, lam)
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (j in a.indices) s += a[j] * b[j]
        return s
    }

    /** Spec 7.4: ln of the inverse-Gaussian density; -infinity outside its domain. */
    private fun logf(r: Double, mu: Double, lam: Double): Double {
        if (!(r > 0 && mu > 0 && lam > 0)) return Double.NEGATIVE_INFINITY
        return 0.5 * ln(lam / (2 * Math.PI * r * r * r)) - lam * (r - mu) * (r - mu) / (2 * mu * mu * r)
    }

    private companion object {
        const val ORDER = 5
        const val WINDOW_S = 60.0
        const val ALPHA = 0.02
        const val MIN_RESPONSES = 20
        const val MAX_ITER = 50
        const val STEP_TOL = 1e-9
        const val MIN_SD_S = 0.001
        const val T_M = 2.0
        const val T_CONFIRM = 7.0
        const val T_R = 6.0
        const val GRID = 400
    }
}
