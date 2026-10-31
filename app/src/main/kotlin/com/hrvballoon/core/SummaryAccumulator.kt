package com.hrvballoon.core

import kotlin.math.sqrt

/** End-of-session statistics; null means not enough data. */
class Summary(
    val durationMs: Long, val beats: Int, val rejectedPct: Double?, val meanHr: Double?,
    val meanP: Double?, val meanShape: Double?, val rmssd: Double?, val sdnn: Double?, val stars: Int,
    /** Time with a live estimate (signal OK and window full), ms. */
    val validMs: Long,
    /** Resonance-search result (posterior-mean best rate), when the session was a search. */
    val rfBest: Double?,
    val rfConverged: Boolean,
    val rfProbes: List<Pair<Double, Double>>,
)

/**
 * Streaming, O(1) memory. RMSSD uses only differences between two consecutive accepted beats:
 * a rejected beat or [breakChain] (signal loss) breaks the chain. SDNN is the sample SD (n−1) over accepted RR.
 */
class SummaryAccumulator {
    private var beats = 0
    private var rejected = 0
    private var nnCount = 0
    private var nnMean = 0.0
    private var nnM2 = 0.0
    private var prevNn: Double? = null
    private var diffCount = 0
    private var diffSumSq = 0.0
    private var pSum = 0.0
    private var pCount = 0
    private var shapeSum = 0.0
    private var shapeCount = 0
    private var stars = 0
    private var validMs = 0L
    var rfBest: Double? = null
    var rfConverged = false
    var rfProbes: List<Pair<Double, Double>> = emptyList()

    fun addBeat(rrMs: Double, accepted: Boolean) {
        beats++
        if (!accepted) {
            rejected++
            prevNn = null
            return
        }
        nnCount++
        val delta = rrMs - nnMean
        nnMean += delta / nnCount
        nnM2 += delta * (rrMs - nnMean)
        prevNn?.let { val d = rrMs - it; diffCount++; diffSumSq += d * d }
        prevNn = rrMs
    }

    fun addP(p2tBpm: Double) {
        pSum += p2tBpm
        pCount++
    }

    fun addShape(shape: Double) {
        shapeSum += shape
        shapeCount++
    }

    fun breakChain() {
        prevNn = null
    }

    fun addStar() {
        stars++
    }

    fun addValid(ms: Long) {
        validMs += ms
    }

    fun result(durationMs: Long) = Summary(
        durationMs = durationMs,
        beats = beats,
        rejectedPct = if (beats == 0) null else 100.0 * rejected / beats,
        meanHr = if (nnCount == 0) null else 60000 / nnMean,   // the usual definition: 60000 over the mean NN interval
        meanP = if (pCount == 0) null else pSum / pCount,
        meanShape = if (shapeCount == 0) null else shapeSum / shapeCount,
        rmssd = if (diffCount == 0) null else sqrt(diffSumSq / diffCount),
        sdnn = if (nnCount < 2) null else sqrt(nnM2 / (nnCount - 1)),
        stars = stars,
        validMs = validMs,
        rfBest = rfBest,
        rfConverged = rfConverged,
        rfProbes = rfProbes,
    )
}
