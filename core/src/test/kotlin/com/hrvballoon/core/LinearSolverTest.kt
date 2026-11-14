package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinearSolverTest {
    @Test fun solvesARegularSystemWithoutTouchingTheInputs() {
        val a = arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0))
        val b = doubleArrayOf(5.0, 10.0)
        val x = solveLinear(a, b)!!
        assertEquals(1.0, x[0], 1e-12)
        assertEquals(3.0, x[1], 1e-12)
        assertEquals(2.0, a[0][0], 0.0)
        assertEquals(5.0, b[0], 0.0)
    }

    @Test fun singularSystemGivesNullNotNaN() {
        val a = arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0))
        assertNull(solveLinear(a, doubleArrayOf(1.0, 2.0)))
    }
}
