package com.hrvballoon.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Resonance search that straddles the peak instead of hunting for its top. At the top the swing barely changes with
 * rate, so probes there cannot say where it is; the information is on the flanks. Probe 1 is [startRate]; then probes
 * come in pairs at the current answer ± [DELTA] in the order + − − + + − …, so a steady drift in swing cancels.
 * From the third probe, after each pair, ln(score) is fitted by a bump ln score = k − (rate − centre)² / 2w²: for a trial
 * width w that is a straight line, centre = w² × slope of (ln score + rate²/2w²) on rate, with standard error
 * w² × [NOISE_SD] / √Σ(rate − mean)². The width with the least residual wins, and the answer moves from the start only
 * as far as that error allows: best = start + (centre − start) × [startSd]² / ([startSd]² + SE²). A broad or flat curve
 * gives a large SE and the answer stays near the start. The HR–pacer phase is not used: at resonance it can sit about
 * 70° from the textbook value (Lehrer, Vaschillo & Vidali 2020).
 */
class RfSearch(private val startRate: Double, private val startSd: Double = START_SD_NONE) {
    private val rates = ArrayList<Double>()
    private val scores = ArrayList<Double>()
    private var centre = startRate
    private var previous = Double.NaN
    private var stopped = false

    val probeCount get() = rates.size

    /** (rate, score) in probe order. */
    val probes get() = rates.indices.map { rates[it] to scores[it] }

    /** The answer so far; the start until the first fit. */
    var best = startRate
        private set

    /** Standard error of the fitted peak rate, /min; infinite before the first fit. */
    var se = Double.POSITIVE_INFINITY
        private set

    /** The answer stayed within 0.1 /min over two consecutive fits, from [MIN_PROBES] probes on. */
    var converged = false
        private set

    /** The fit places the peak no better than the start already does, so the answer stays near the start. */
    val noPeak get() = rates.size >= MIN_PROBES && se > startSd

    /** Converged on a peak the data can place. */
    val found get() = converged && !noPeak

    val finished get() = converged || stopped

    fun next(): Double {
        val n = rates.size
        return if (n == 0) startRate else straddle(n)
    }

    /**
     * The rate [next] will give once the probe in progress is reported, or null when a fit follows that probe and decides
     * it (the third, fifth, …): lets the pacer move on as the probe ends instead of a breath later.
     */
    fun following(): Double? {
        val k = rates.size + 1
        return if (k >= 3 && k % 2 == 1) null else straddle(k)
    }

    /** The probe after [n] reported: the answer ± [DELTA] in the order + − − + + − …. */
    private fun straddle(n: Int) = snap(centre + if ((n - 1) % 4 == 0 || (n - 1) % 4 == 3) DELTA else -DELTA)

    fun report(rateBpm: Double, score: Double) {
        rates += rateBpm
        scores += score
        val n = rates.size
        if (n < 3 || n % 2 == 0) return
        val fit = rfFit(probes) ?: return
        se = fit.se
        val s2 = startSd * startSd
        best = snap(startRate + (fit.centre - startRate) * s2 / (s2 + se * se))
        converged = n >= MIN_PROBES && abs(best - previous) <= 0.1 + 1e-9
        previous = best
        centre = best.coerceIn(MIN_RATE + DELTA, MAX_RATE - DELTA)
    }

    /** No time for another probe: without a peak the answer is [best], near the start; otherwise there is none (null). */
    fun conclude(): Double? {
        stopped = true
        return if (noPeak) best else null
    }

    private fun snap(rate: Double) = ((rate * 10).roundToInt() / 10.0).coerceIn(MIN_RATE, MAX_RATE)

    companion object {
        /** How far a height-and-sex estimate is off, /min (Hasuo et al. 2023: adjusted R² about 0.5). */
        const val START_SD_ESTIMATE = 0.4
        /** How far a start without an estimate (the user's own rate) may be off, /min. */
        const val START_SD_NONE = 0.6
        const val MIN_PROBES = 5
        /** The slowest and fastest rate a search gives, /min. */
        const val MIN_RATE = 4.0
        const val MAX_RATE = 7.0
        private const val DELTA = 0.5
        /** A score below this fraction of the best one is a failed probe. */
        const val FLOOR = 0.25
        /** SD of ln(score) between probes at one rate. */
        const val NOISE_SD = 0.25   // probe-to-probe spread of 18–36 % at 9 breaths on a recorded session
        internal val WIDTHS = doubleArrayOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0)
    }
}

/** The bump [RfSearch] fits: ln score = [lnPeak] − (rate − [centre])² / 2[width]²; [se] = standard error of [centre], /min. */
class RfFit(val centre: Double, val width: Double, val lnPeak: Double, val se: Double) {
    fun scoreAt(rateBpm: Double) = exp(lnPeak - (rateBpm - centre) * (rateBpm - centre) / (2 * width * width))
}

/**
 * The fit [RfSearch] makes after the third probe and after each pair: over all [probes] (rate, score) when their count is odd,
 * all but the last when even. Null with fewer than three probes or no spread in rate.
 */
fun rfFit(probes: List<Pair<Double, Double>>): RfFit? {
    val n = if (probes.size % 2 == 0) probes.size - 1 else probes.size
    if (n < 3) return null
    val rates = DoubleArray(n) { probes[it].first }
    // Swing does not fall by three quarters within half a breath per minute; a lower score is a failed probe. The floor
    // keeps one from steering the log-scale fit (in simulation: reruns apart 34-39% -> 12-20% with 10% failed probes).
    val floor = ((0 until n).maxOf { probes[it].second } * RfSearch.FLOOR).coerceAtLeast(1e-6)
    val logs = DoubleArray(n) { ln(probes[it].second.coerceAtLeast(floor)) }
    val rm = rates.average()
    val sxx = rates.sumOf { (it - rm) * (it - rm) }
    if (sxx <= 0) return null
    var fit: RfFit? = null
    var leastSse = Double.MAX_VALUE
    for (w in RfSearch.WIDTHS) {
        val w2 = w * w
        val y = DoubleArray(n) { logs[it] + rates[it] * rates[it] / (2 * w2) }
        val ym = y.average()
        val slope = rates.indices.sumOf { (rates[it] - rm) * (y[it] - ym) } / sxx
        val c = (w2 * slope).coerceIn(3.0, 8.0)   // a peak outside 3–8 /min is held at the edge and scored there
        val res = DoubleArray(n) { logs[it] + (rates[it] - c) * (rates[it] - c) / (2 * w2) }
        val mean = res.average()
        val sse = res.sumOf { (it - mean) * (it - mean) }
        if (sse < leastSse) { leastSse = sse; fit = RfFit(c, w, mean, w2 * RfSearch.NOISE_SD / sqrt(sxx)) }
    }
    return fit
}
