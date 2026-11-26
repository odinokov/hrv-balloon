package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.sin

/** One accepted beat: end time on the session clock, instantaneous HR, the pacer rate then, and the gap-free run it belongs to. */
class CsBeat(val tMs: Double, val hrBpm: Double, val rateBpm: Double, val segment: Int)

/**
 * App-defined coherence score of a session: per 64 s window of the 4 Hz HR series, CS = ln(1 + P^2 / (L * H)), P the
 * power within +-0.015 Hz of the spectral peak (0.04-0.26 Hz), L and H the power below and above it inside 0.0033-0.4 Hz.
 * Only windows whose beats all share one pacer rate count; the value is their median, null under 10 of them.
 * Only game beats count (spec 5.1): [isGameRr] against [boundaryMs], the RR length rebuilt as 60000 / HR.
 */
fun sessionCoherence(beats: List<CsBeat>, boundaryMs: Double?): Double? =
    CoherenceMeter().also { m -> beats.forEach { if (isGameRr(it.tMs, 60_000 / it.hrBpm, boundaryMs)) m.add(it) } }.median()

/**
 * Incremental [sessionCoherence]: each window is scored by the add() that first puts a beat past its end, so one add()
 * costs at most about one window. Single-threaded; tMs strictly increasing; the caller offers game beats only (no time
 * cut here) and starts a new [CsBeat.segment] at every gap over 3 s, pause and signal loss.
 */
class CoherenceMeter {
    private val scores = ArrayList<Double>()
    private var segment = 0
    private var started = false
    private var t = ArrayList<Double>()   // current segment: beat times (s), HR, pacer rate
    private var hr = ArrayList<Double>()
    private var rate = ArrayList<Double>()
    private var x = ArrayList<Double>()   // the segment's 4 Hz series
    private var g0 = 0.0
    private var nextStart = 0
    private var winFirst = 0   // first beat inside the next window; only moves forward
    private var j = 0   // interpolation pointer: beat j is the last one at or before the next sample

    val windowCount get() = scores.size

    /** Beats arrive with strictly increasing tMs; a new [CsBeat.segment] value starts a fresh run. */
    fun add(beat: CsBeat) {
        if (!started || beat.segment != segment) {
            segment = beat.segment
            started = true
            t.clear(); hr.clear(); rate.clear(); x.clear()
            g0 = ceil(beat.tMs / 1000 * FS) / FS
            nextStart = 0
            j = 0
            winFirst = 0
        }
        t += beat.tMs / 1000; hr += beat.hrBpm; rate += beat.rateBpm
        while (t.size >= 2 && g0 + x.size / FS < t.last()) {
            val gt = g0 + x.size / FS
            while (t[j + 1] <= gt) j++
            x += hr[j] + (hr[j + 1] - hr[j]) * (gt - t[j]) / (t[j + 1] - t[j])
        }
        while (x.size >= nextStart + NW) {
            val end = g0 + (nextStart + NW - 1) / FS
            while (t[winFirst] <= end - NW / FS) winFirst++
            var last = winFirst
            while (last + 1 < t.size && t[last + 1] <= end) last++
            val pure = (winFirst..last).all { rate[it] == rate[last] }
            if (pure) windowScore(x, nextStart)?.let { scores += it }
            nextStart += STEP
        }
    }

    /** Median of the counted windows (mean of the middle two when even); null under 10. */
    fun median(): Double? {
        if (scores.size < MIN_WINDOWS) return null
        val s = scores.sorted()
        return (s[(s.size - 1) / 2] + s[s.size / 2]) / 2
    }
}

private const val FS = 4.0
private const val NW = 256
private const val STEP = 20
private const val NFFT = 4096
private const val MIN_WINDOWS = 10
private const val HALF_PEAK_HZ = 0.015
private const val GRID_HZ = FS / NFFT
private const val TOP_BIN = 410   // 0.4 Hz on the 1/1024 Hz grid, rounded up

private val HANN = DoubleArray(NW) { 0.5 - 0.5 * cos(2 * PI * it / NW) }
private val HANN_ENERGY = HANN.sumOf { it * it }
private val COS = DoubleArray(NFFT) { cos(2 * PI * it / NFFT) }
private val SIN = DoubleArray(NFFT) { sin(2 * PI * it / NFFT) }

/** CS of x[start, start + NW), or null when the peak window or either side band holds no power. */
private fun windowScore(x: List<Double>, start: Int): Double? {
    val mean = (0 until NW).sumOf { x[start + it] } / NW
    val tMean = (NW - 1) / 2.0
    val slope = (0 until NW).sumOf { (it - tMean) * (x[start + it] - mean) } / (0 until NW).sumOf { (it - tMean) * (it - tMean) }
    val y = DoubleArray(NW) { (x[start + it] - mean - slope * (it - tMean)) * HANN[it] }
    val psd = DoubleArray(TOP_BIN + 1) { k ->   // direct DFT: only the bins the integrals read
        var re = 0.0
        var im = 0.0
        for (i in 0 until NW) { val a = (k * i) and (NFFT - 1); re += y[i] * COS[a]; im -= y[i] * SIN[a] }
        (re * re + im * im) / (FS * HANN_ENERGY)
    }
    val peak = (ceil(0.04 / GRID_HZ).toInt()..floor(0.26 / GRID_HZ).toInt()).maxByOrNull { psd[it] }!! * GRID_HZ
    val p = integrate(psd, peak - HALF_PEAK_HZ, peak + HALF_PEAK_HZ)
    val l = integrate(psd, 0.0033, peak - HALF_PEAK_HZ)
    val h = integrate(psd, peak + HALF_PEAK_HZ, 0.4)
    return if (l > 0 && h > 0 && p > 0) ln1p(p * p / (l * h)) else null
}

/** Trapezoid area under the linearly interpolated PSD between lo and hi (Hz), grid points inside included. */
private fun integrate(psd: DoubleArray, lo: Double, hi: Double): Double {
    fun at(f: Double): Double {
        val k = minOf(floor(f / GRID_HZ).toInt(), TOP_BIN - 1)
        return psd[k] + (psd[k + 1] - psd[k]) * (f / GRID_HZ - k)
    }
    var area = 0.0
    var f0 = lo
    for (k in floor(lo / GRID_HZ).toInt() + 1..ceil(hi / GRID_HZ).toInt() - 1) {
        area += (at(f0) + psd[k]) / 2 * (k * GRID_HZ - f0)
        f0 = k * GRID_HZ
    }
    return area + (at(f0) + at(hi)) / 2 * (hi - f0)
}
