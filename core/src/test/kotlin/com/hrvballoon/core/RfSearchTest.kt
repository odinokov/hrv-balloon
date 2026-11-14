package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp

class RfSearchTest {
    private fun bump(rate: Double, peak: Double, width: Double = 0.45) = 10 * exp(-(rate - peak) * (rate - peak) / (2 * width * width))

    @Test fun firstProbeIsTheStartRateThenPhaseHintDirection() {
        val s = RfSearch(5.5)
        assertEquals(5.5, s.next(phaseHint = 1.0), 1e-9)
        s.report(5.5, 8.0)
        assertTrue(s.next(phaseHint = 1.0) > 5.5)
        val t = RfSearch(5.5)
        t.next(phaseHint = -1.0)
        t.report(5.5, 8.0)
        assertTrue(t.next(phaseHint = -1.0) < 5.5)
        assertNull(s.best)
    }

    @Test fun convergesOnANoisyBump() {
        var hits = 0
        for (seed in 0 until 10) {
            val rnd = Random(seed.toLong())
            val peak = 4.6 + seed * 0.25
            val s = RfSearch(5.5)
            repeat(8) {
                val r = s.next(phaseHint = if (peak > 5.5) 1.0 else -1.0)
                s.report(r, bump(r, peak) + rnd.nextGaussian() * 0.6)
            }
            if (abs(s.best!! - peak) <= 0.25) hits++
        }
        assertTrue("hits=$hits", hits >= 8)
    }

    @Test fun staysOnTheGridAndCanConverge() {
        val s = RfSearch(6.0)
        repeat(10) {
            val r = s.next(phaseHint = 0.0)
            assertTrue(r in 4.0..7.0)
            assertEquals(r, Math.round(r * 10) / 10.0, 1e-9)
            s.report(r, bump(r, 6.0))
        }
        assertTrue(s.converged)
        assertEquals(6.0, s.best!!, 0.15)
    }

    @Test fun flatScoresNeverConvergeAndATieKeepsTheTopProbe() {
        val s = RfSearch(6.0)
        repeat(8) { s.report(s.next(phaseHint = 0.0), 5.0) }
        assertFalse(s.converged)
        assertEquals(6.0, s.best!!, 1e-9)   // equal scores: the best-scoring probe, the first, beats the edge of the grid
        val n = RfSearch(6.0)
        val rnd = Random(3)
        repeat(8) { n.report(n.next(phaseHint = 0.0), 5.0 * (1 + 0.04 * rnd.nextGaussian())) }
        assertFalse("differences within the measurement noise", n.converged)
    }

    @Test fun aPeakWithinTwiceTheProbeNoiseDoesNotConverge() {
        val s = RfSearch(5.5)
        repeat(10) {
            val r = s.next(phaseHint = 0.0)
            s.report(r, 10 * (1 + 0.4 * exp(-(r - 5.5) * (r - 5.5) / (2 * 0.5 * 0.5))))   // 40% above the floor at most
        }
        assertFalse(s.converged)
    }

    @Test fun withoutAPeakTheSearchConcludesAt55AndWithTooFewProbesAtNothing() {
        val s = RfSearch(6.0)
        repeat(6) { s.report(s.next(phaseHint = 0.0), 5.0) }
        assertEquals(5.5, s.conclude()!!, 1e-9)
        assertTrue(s.noPeak)
        assertTrue(s.finished)
        assertFalse(s.converged)
        assertEquals(5.5, s.best!!, 1e-9)
        val few = RfSearch(6.0)
        repeat(3) { few.report(few.next(phaseHint = 0.0), 5.0) }
        assertNull(few.conclude())
        assertFalse(few.noPeak)
        assertTrue(few.finished)
    }

    @Test fun withoutAPeakTheSearchEndsAtItsOwnFallback() {
        val s = RfSearch(6.2, fallbackBpm = 6.2)
        repeat(6) { s.report(s.next(phaseHint = 0.0), 5.0) }
        assertEquals(6.2, s.conclude()!!, 1e-9)
        assertEquals(6.2, s.best!!, 1e-9)
    }
}
