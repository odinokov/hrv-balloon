package com.hrvballoon.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class SynthBeats(val t0Ms: Double, val rrMs: DoubleArray) {
    /** Beats on the session clock; each notification is assumed to arrive [lagMs] after its beat ends. */
    fun beats(lagMs: Long = 500): List<Beat> {
        var t = t0Ms
        return rrMs.map { rr -> t += rr; Beat(t, rr, (t + lagMs).toLong()) }
    }
}

/** Integrate-and-fire beats from HR(t) = mean + drift·t + p2t/2·sin(2π·rate/60·t + phase0), 1 ms steps. */
fun synthBeats(
    meanHr: Double, p2t: Double, rateBpm: Double, durS: Double,
    noiseMs: Double = 3.0, seed: Long = 0, phase0: Double = 0.0, driftBpmPerS: Double = 0.0,
): SynthBeats {
    val rnd = Random(seed)
    val f = rateBpm / 60
    val times = ArrayList<Double>()
    var phase = 0.0
    var next = 1
    var i = 0L
    while (i * 1e-3 < durS) {
        val t = i * 1e-3
        phase += (meanHr + driftBpmPerS * t + p2t / 2 * sin(2 * PI * f * t + phase0)) / 60 * 1e-3
        if (phase >= next) { times += t; next++ }
        i++
    }
    val rr = DoubleArray(times.size - 1) { (times[it + 1] - times[it]) * 1000 + noiseMs * rnd.nextGaussian() }
    return SynthBeats(times[0] * 1000, rr)
}

/** Multiplies rr[i] by [prem] and rr[i+1] by [comp] for i = from, from+every, …; returns the touched indices. */
fun injectPairs(rr: DoubleArray, prem: Double, comp: Double, from: Int = 25, every: Int = 30): Set<Int> {
    val bad = HashSet<Int>()
    var i = from
    while (i + 1 < rr.size - 25) {
        rr[i] *= prem; rr[i + 1] *= comp; bad += i; bad += i + 1
        i += every
    }
    return bad
}

fun injectMissed(rr: DoubleArray, from: Int = 25, every: Int = 30): Set<Int> {
    val bad = HashSet<Int>()
    var i = from
    while (i < rr.size - 25) { rr[i] *= 2; bad += i; i += every }
    return bad
}
