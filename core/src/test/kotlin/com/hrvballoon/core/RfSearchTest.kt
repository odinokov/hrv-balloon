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
    private fun bump(rate: Double, peak: Double, width: Double = 0.5) = 10 * exp(-(rate - peak) * (rate - peak) / (2 * width * width))

    /** Runs the search against [score] until it converges or [max] probes; returns the rates probed. */
    private fun drive(s: RfSearch, max: Int = 9, score: (Double) -> Double): List<Double> {
        val probed = ArrayList<Double>()
        while (!s.converged && probed.size < max) { val r = s.next(); probed += r; s.report(r, score(r)) }
        return probed
    }

    @Test fun startsAtTheStartThenStraddlesItPlusMinusMinusPlus() {
        val s = RfSearch(5.5)
        val probed = drive(s, max = 5) { 5.0 }                // flat: the centre stays at the start
        assertEquals(listOf(5.5, 6.0, 5.0, 5.0, 6.0), probed)
    }

    @Test fun aSharpPeakAwayFromTheStartIsFound() {
        val s = RfSearch(5.5, startSd = RfSearch.START_SD_ESTIMATE)
        drive(s) { bump(it, 6.2) }
        assertTrue(s.converged)
        assertFalse(s.noPeak)
        assertEquals(6.2, s.best, 0.2)
    }

    @Test fun aFlatCurveKeepsTheStartAndSaysNoPeak() {
        val s = RfSearch(6.1, startSd = RfSearch.START_SD_ESTIMATE)
        drive(s) { 7.0 }
        assertTrue(s.converged)
        assertTrue(s.noPeak)
        assertEquals(6.1, s.best, 1e-9)
    }

    @Test fun noisyFlatCurvesRarelyMoveFarFromTheStart() {
        var far = 0
        for (seed in 0 until 40) {
            val rnd = Random(seed.toLong())
            val s = RfSearch(5.8, startSd = RfSearch.START_SD_ESTIMATE)
            drive(s) { 8.0 * exp(0.25 * rnd.nextGaussian()) }
            if (abs(s.best - 5.8) >= 0.5) far++
        }
        assertTrue("far=$far of 40", far <= 4)
    }

    @Test fun aLooserStartMovesFurtherOnTheSameEvidence() {
        val probes = listOf(5.5 to 6.0, 6.0 to 9.0, 5.0 to 4.0)
        fun moved(sd: Double) = RfSearch(5.5, startSd = sd).apply { for ((r, sc) in probes) report(r, sc) }.best - 5.5
        assertTrue("0.4 -> ${moved(0.4)}, 0.6 -> ${moved(0.6)}", moved(0.6) >= moved(0.4) && moved(0.4) > 0)
    }

    @Test fun probesStayOnTheGridInsideFourToSeven() {
        for (peak in listOf(3.5, 7.6)) {
            val s = RfSearch(if (peak < 5) 4.2 else 6.9, startSd = RfSearch.START_SD_NONE)
            val probed = drive(s) { bump(it, peak, 0.6) }
            assertTrue("$probed", probed.all { it in 4.0..7.0 && abs(it * 10 - Math.round(it * 10)) < 1e-9 })
            assertTrue(s.best in 4.0..7.0)
        }
    }

    @Test fun aNearZeroScoreDoesNotDominateTheFit() {
        val s = RfSearch(5.5, startSd = RfSearch.START_SD_ESTIMATE)
        for ((r, sc) in listOf(5.5 to 9.0, 6.0 to 0.0, 5.0 to 8.5)) s.report(r, sc)   // one failed fit at 6.0
        assertTrue("best=${s.best}", s.best in 5.0..5.6)
    }

    @Test fun concludingWithoutAPeakAnswersTheStartAndOtherwiseNothing() {
        val flat = RfSearch(6.0, startSd = RfSearch.START_SD_ESTIMATE)
        for (r in listOf(6.0, 6.5, 5.5, 5.5, 6.5)) flat.report(r, if (r == 6.0) 7.0 else 7.0)
        assertEquals(6.0, flat.conclude()!!, 1e-9)
        assertTrue(flat.finished)
        val few = RfSearch(6.0)
        few.report(6.0, 7.0); few.report(6.5, 7.5); few.report(5.5, 6.5)
        assertNull(few.conclude())
        assertTrue(few.finished)
        assertFalse(few.noPeak)
    }

    @Test fun aSharpPeakStillMovingWhenTimeRunsOutConcludesWithNoAnswer() {
        val s = RfSearch(5.5, startSd = RfSearch.START_SD_ESTIMATE)
        for ((r, sc) in listOf(5.5 to 4.0, 6.0 to 9.0, 5.0 to 1.0, 6.0 to 9.5, 6.5 to 9.0)) s.report(r, sc)
        assertFalse(s.noPeak)
        if (!s.converged) assertNull(s.conclude())
    }
}
