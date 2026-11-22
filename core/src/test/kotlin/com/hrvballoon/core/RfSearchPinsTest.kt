package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/** Pins of the convergence threshold; [RfSearchTest] stays as it is. */
class RfSearchPinsTest {
    /** Five probes on a width-1 peak at [peak], the fifth scored 10 % higher: the answer after the third and after the fifth. */
    private fun fits(peak: Double): Pair<Double, RfSearch> {
        val s = RfSearch(5.5)
        var afterThird = Double.NaN
        for (i in 1..5) {
            val r = s.next()
            s.report(r, 10 * exp(-(r - peak) * (r - peak) / 2) * if (i == 5) 1.1 else 1.0)
            if (i == 3) afterThird = s.best
        }
        return afterThird to s
    }

    @Test fun twoFitsTwoTenthsApartHaveNotConverged() {
        val (first, s) = fits(5.8)
        assertEquals(5.7, first, 1e-9)
        assertEquals(5.9, s.best, 1e-9)
        assertFalse(s.converged)
    }

    @Test fun twoFitsOneTenthApartHaveConverged() {
        val (first, s) = fits(6.0)
        assertEquals(5.9, first, 1e-9)
        assertEquals(6.0, s.best, 1e-9)
        assertTrue(s.converged)
    }
}
