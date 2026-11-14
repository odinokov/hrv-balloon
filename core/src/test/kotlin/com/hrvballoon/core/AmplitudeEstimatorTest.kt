package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AmplitudeEstimatorTest {
    private val rates = doubleArrayOf(4.0, 5.5, 7.0)
    private val amplitudes = doubleArrayOf(10.0, 20.0, 30.0)

    /** synth → filter → estimator, collecting one estimate per accepted beat. */
    private fun pipe(s: SynthBeats, rate: Double): List<Estimate> {
        val f = ArtifactFilter()
        val e = AmplitudeEstimator()
        val out = ArrayList<Estimate>()
        for (b in s.beats()) for (c in f.push(b)) if (c.accepted) {
            e.add(c.beat.tEndMs - c.beat.rrMs / 2, 60000 / c.beat.rrMs)
            e.estimate(rate)?.let(out::add)
        }
        return out
    }

    private fun check(est: List<Estimate>, p: Double, biasMin: Double, wobbleMax: Double, label: String) {
        val v = est.map { it.p2tBpm }
        val bias = v.average() / p - 1
        val wobble = (v.max() - v.min()) / 2 / p
        assertTrue("$label bias=$bias", bias in biasMin..0.0)
        assertTrue("$label wobble=$wobble", wobble <= wobbleMax)
    }

    @Test fun cleanBiasAndWobble() {
        for (r in rates) for (p in amplitudes) check(pipe(synthBeats(60.0, p, r, 300.0), r), p, -0.08, 0.05, "rate $r P $p")
    }

    @Test fun ectopicPairsBridged() {
        for (r in rates) for (p in amplitudes) {
            val s = synthBeats(60.0, p, r, 300.0)
            injectPairs(s.rrMs, 0.6, 1.4)
            check(pipe(s, r), p, -0.12, 0.10, "ectopic rate $r P $p")
        }
    }

    @Test fun phaseFollowsPacer() {
        for ((phase0, deg) in listOf(0.0 to 0.0, 1.0 to 57.2958)) {
            val est = pipe(synthBeats(60.0, 20.0, 5.5, 300.0, phase0 = phase0), 5.5)
            for (e in est) assertTrue("phase0=$phase0 got ${e.phaseDeg}", abs(e.phaseDeg - deg) <= 5.0)
        }
    }

    @Test fun phaseIsRelativeToTheCycleOffset() {
        val f = ArtifactFilter()
        val e = AmplitudeEstimator()
        val phases = ArrayList<Double>()
        for (b in synthBeats(60.0, 20.0, 5.5, 300.0, phase0 = 1.0).beats()) for (c in f.push(b)) if (c.accepted) {
            e.add(c.beat.tEndMs - c.beat.rrMs / 2, 60000 / c.beat.rrMs)
            e.estimate(5.5, cycleOffset = 0.25)?.let { phases += it.phaseDeg }
        }
        for (p in phases) assertTrue("got $p", abs(p - (57.2958 - 90)) <= 5.0)
    }

    @Test fun linearDriftIgnored() {
        for (r in rates) for (p in amplitudes) {
            check(pipe(synthBeats(60.0, p, r, 300.0, driftBpmPerS = 0.02), r), p, -0.08, 0.05, "drift rate $r P $p")
        }
    }

    private fun shapes(s: SynthBeats, fitRate: Double) = pipe(s, fitRate).map { it.shape }

    @Test fun shapeIsNearOneForPacedBreathing() {
        for (p in doubleArrayOf(5.0, 20.0)) assertTrue(shapes(synthBeats(60.0, p, 5.5, 300.0), 5.5).all { it >= 0.95 })
        val s = synthBeats(60.0, 20.0, 5.5, 300.0)
        injectPairs(s.rrMs, 0.6, 1.4)
        assertTrue(shapes(s, 5.5).sorted().let { it[it.size / 10] } >= 0.9)
    }

    @Test fun shapeDropsWhenBreathingOffPace() {
        fun median(v: List<Double>) = v.sorted()[v.size / 2]
        assertTrue(median(shapes(synthBeats(60.0, 20.0, 4.0, 300.0), 5.5)) < 0.6)
        assertTrue(median(shapes(synthBeats(60.0, 20.0, 7.0, 300.0), 5.5)) < 0.6)
        assertTrue(median(shapes(synthBeats(60.0, 20.0, 12.0, 300.0), 5.5)) < 0.1)
        assertTrue(median(shapes(synthBeats(60.0, 0.0, 5.5, 300.0, noiseMs = 20.0), 5.5)) < 0.3)
    }

    @Test fun warmUp() {
        val e = AmplitudeEstimator()
        val w = 2 * 60000 / 5.5
        var first: Double? = null
        var t0: Double? = null
        for (b in synthBeats(60.0, 10.0, 5.5, 60.0).beats()) {
            val tMid = b.tEndMs - b.rrMs / 2
            if (t0 == null) t0 = tMid
            e.add(tMid, 60000 / b.rrMs)
            val est = e.estimate(5.5)
            if (first == null && est != null) first = tMid - t0
            if (first == null) assertNull(est) else assertNotNull(est)
        }
        assertNotNull(first)
        assertTrue("first estimate at $first", first!! in w..w + 1500)
    }

    @Test fun boundedMemory() {
        val e = AmplitudeEstimator()
        for (b in synthBeats(60.0, 10.0, 4.0, 7200.0).beats()) e.add(b.tEndMs - b.rrMs / 2, 60000 / b.rrMs)
        assertNotNull(e.estimate(4.0))
        assertTrue("retained ${e.retainedCount}", e.retainedCount <= 200)
    }

    @Test fun resetClearsToWarmUp() {
        val e = AmplitudeEstimator()
        for (b in synthBeats(60.0, 10.0, 5.5, 60.0).beats()) e.add(b.tEndMs - b.rrMs / 2, 60000 / b.rrMs)
        assertNotNull(e.estimate(5.5))
        e.reset()
        assertNull(e.estimate(5.5))
        assertEquals(0, e.retainedCount)
    }
}
