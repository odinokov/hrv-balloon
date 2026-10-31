package com.hrvballoon.core

import org.junit.Assert.assertEquals
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
}
