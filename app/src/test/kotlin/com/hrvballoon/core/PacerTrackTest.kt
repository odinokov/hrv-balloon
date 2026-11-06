package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacerTrackTest {
    @Test fun theFundamentalPhaseOfThePacerCurveIsWhereHrSitsAtResonance() {
        // Symmetric breathing: the curve is 0.5 − 0.5·cos(2πθ) = 0.5 + 0.5·sin(2πθ − 90°), so φ = −90° and the crest is half a cycle in.
        assertEquals(-90.0, PacerTrack.fundamentalPhaseDeg(0.5), 0.5)
        // 40:60: the inhale ends earlier but the long exhale pulls the fundamental's crest past it, between 0.4 and 0.5 of the cycle.
        val f = PacerTrack.fundamentalPhaseDeg(0.4)
        assertTrue("f=$f", f in -90.0..-54.0)
    }

    @Test fun matchesPacerWhileRateIsConstant() {
        val track = PacerTrack(6.0, 0.4)
        val pacer = Pacer(6.0, 0.4)
        for (t in 0..30_000 step 250) {
            assertEquals(pacer.phase(t.toDouble()), track.phase(t.toDouble()), 1e-9)
            assertEquals(pacer.level(t.toDouble()), track.level(t.toDouble()), 1e-9)
            // exact inhale/exhale boundary samples differ by 1e-16 in rounding; everywhere else they must agree
            if (kotlin.math.abs(pacer.phase(t.toDouble()) - 0.4) > 1e-9) assertEquals(pacer.isInhale(t.toDouble()), track.isInhale(t.toDouble()))
        }
        assertEquals(0.0, track.cycleOffset(12_345.0), 1e-9)
    }

    @Test fun rateChangesAtTheNextInhaleStartWithContinuousPhase() {
        val track = PacerTrack(6.0, 0.4)           // period 10 s
        val at = track.changeRate(5.0, 23_000.0)   // next inhale start after 23 s is 30 s
        assertEquals(30_000.0, at, 1e-6)
        assertEquals(6.0, track.rateAt(29_999.0), 1e-9)
        assertEquals(5.0, track.rateAt(30_000.0), 1e-9)
        assertTrue(track.phase(29_999.0) > 0.99)
        assertEquals(0.0, track.phase(30_000.0), 1e-9)
        assertEquals(0.5, track.phase(36_000.0), 1e-9)  // 6 s into a 12 s period
        assertEquals(0.0, track.phase(42_000.0), 1e-9)
        assertEquals(5.0, track.rateBpm, 1e-9)
        val f = 5.0 / 60_000
        assertEquals(track.cycleOffset(35_000.0), track.cycleOffset(40_000.0), 1e-9)
        assertEquals(track.phase(35_000.0), ((f * 35_000.0 + track.cycleOffset(35_000.0)) % 1.0 + 1.0) % 1.0, 1e-9)
    }

    @Test fun secondChangeBeforeTheFirstTakesEffectReplacesIt() {
        val track = PacerTrack(6.0, 0.4)
        track.changeRate(5.0, 23_000.0)
        val at = track.changeRate(7.0, 24_000.0)
        assertEquals(30_000.0, at, 1e-6)
        assertEquals(7.0, track.rateAt(31_000.0), 1e-9)
    }
}
