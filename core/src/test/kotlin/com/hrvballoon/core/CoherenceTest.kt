package com.hrvballoon.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoherenceTest {
    /** Beats every 0.8 s from t = 0; hr(i) sets the HR, rate(i) the pacer rate, seg(i) the run. */
    private fun series(n: Int, rate: (Int) -> Double = { 6.0 }, seg: (Int) -> Int = { 0 }, hr: (Int) -> Double) =
        List(n) { CsBeat(800.0 * it, hr(it), rate(it), seg(it)) }

    private fun sine(hz: Double, amp: Double = 3.0) = { i: Int -> 70 + amp * sin(2 * PI * hz * 0.8 * i) }

    /**
     * The boundary T of these series: beats every 0.8 s from t = 0, HR 67-73, so RR 822-896 ms. T = 29 500 makes beat 38 (t = 30.4 s)
     * the first game beat, the same beat set the 2.9 skip of the first 30 s kept: the pinned values below stay valid (spec 5.1).
     */
    private val T = 29_500.0

    private fun cs(beats: List<CsBeat>) = sessionCoherence(beats, T)!!

    // reference values: the Python reference (median CS_A over pure windows) on the same synthetic input (t = 0.8 i, hr as below)
    @Test fun cleanSineAtPointOneHzMatchesTheReference() {
        assertEquals(5.793980, cs(series(700, hr = sine(0.1))), 1e-6)
    }

    @Test fun cleanSineAtAPacerRateMatchesTheReference() {
        assertEquals(5.819662, cs(series(700, rate = { 4.5 }, hr = sine(0.075))), 1e-6)
    }

    @Test fun whiteNoiseScoresLow() {
        val rnd = Random(1)
        assertTrue(cs(series(700) { 70 + 3 * rnd.nextDouble(-1.0, 1.0) }) < 1.5)
    }

    @Test fun scalingTheSeriesLeavesTheScoreUnchanged() {
        val rnd = Random(2)
        val noise = DoubleArray(700) { rnd.nextDouble(-1.0, 1.0) }
        val one = cs(series(700) { 70 + 3 * sin(2 * PI * 0.08 * 0.8 * it) + noise[it] })
        val two = cs(series(700) { 2 * (70 + 3 * sin(2 * PI * 0.08 * 0.8 * it) + noise[it]) })
        assertEquals(1.0, two / one, 1e-9)
    }

    @Test fun nineWindowsGiveNoScoreTenGiveOne() {
        assertNull(sessionCoherence(series(175, hr = sine(0.1)), T))
        assertNotNull(sessionCoherence(series(176, hr = sine(0.1)), T))
    }

    @Test fun aSegmentBreakLeavesNoWindowAcrossIt() {
        val rnd = Random(3)
        val m = CoherenceMeter()
        // The meter has no time cut (spec 5.1): the first run (beats 0-249, t 0-199.2 s) gives windows ending at 63.75 + 5 k <= 199.2 s,
        // 28 of them; the second (t 200-359.2 s) gives 20; one unbroken run over the same beats would give 66
        series(450, seg = { if (it < 250) 0 else 1 }) { if (it < 250) sine(0.1)(it) else 70 + 3 * rnd.nextDouble(-1.0, 1.0) }.forEach(m::add)
        assertEquals(48, m.windowCount)
        assertNotNull(m.median())
    }

    @Test fun windowsAreScoredAsBeatsArriveOnTheBatchGrid() {
        val m = CoherenceMeter()
        var most = 0
        for (b in series(700, hr = sine(0.1)).drop(38)) {   // from the first game beat; the meter itself cuts nothing
            val before = m.windowCount
            m.add(b)
            most = maxOf(most, m.windowCount - before)
        }
        assertEquals(1, most)
        assertEquals(93, m.windowCount)
        assertEquals(5.793980, m.median()!!, 1e-6)
    }

    @Test fun aWindowWaitsForABeatPastItsLastSample() {
        val m = CoherenceMeter()
        val hr = { i: Int -> 70 + 3 * sin(2 * PI * 0.1 * 0.25 * i) }
        for (i in 120..555) m.add(CsBeat(250.0 * i, hr(i), 6.0, 0))   // from t = 30 s: the 10th window's last sample, t = 138.75 s, is this beat's time
        assertEquals(9, m.windowCount)
        m.add(CsBeat(250.0 * 556, hr(556), 6.0, 0))
        assertEquals(10, m.windowCount)
    }

    @Test fun aRateChangeInsideAWindowExcludesIt() {
        assertNotNull(sessionCoherence(series(176, hr = sine(0.1)), T))
        assertNull(sessionCoherence(series(176, rate = { if (it < 160) 6.0 else 5.0 }, hr = sine(0.1)), T))
    }

    @Test fun beatsBeforeTAreIgnored() {   // spec 5.1: T replaces the 30 s skip
        val clean = series(190, hr = sine(0.1))
        val dirty = series(190) { if (it < 38) 70.0 + 40 * (it % 2) else sine(0.1)(it) }
        assertEquals(cs(clean), cs(dirty), 1e-9)
    }

    @Test fun theBoundaryItselfCountsAndTheCutIsMadeOnce() {
        // Beat 40 (t = 32 s) is 75 bpm = 800 ms exactly, so tEnd - rr = 31 200 = T: a game beat, equality counts (spec 3.1).
        // Beat 39 ends at 31.2 s but starts before T. Reference: a meter fed from beat 40 (not from 41, which a '>' would give).
        val beats = series(190) { if (it == 40) 75.0 else sine(0.1)(it) }
        fun meterFrom(first: Int) = CoherenceMeter().also { m -> beats.drop(first).forEach(m::add) }.median()!!
        assertEquals(meterFrom(40), sessionCoherence(beats, 31_200.0)!!, 1e-12)
        assertTrue(Math.abs(meterFrom(40) - meterFrom(41)) > 1e-6)
        assertEquals(meterFrom(41), sessionCoherence(beats, 31_200.5)!!, 1e-12)
    }

    @Test fun withoutABoundaryNoBeatIsGame() {
        assertNull(sessionCoherence(series(700, hr = sine(0.1)), null))
    }
}
