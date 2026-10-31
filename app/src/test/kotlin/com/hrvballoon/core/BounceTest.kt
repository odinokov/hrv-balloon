package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BounceTest {
    @Test fun dipsThenReturnsAndDiesOut() {
        val b = Bounce()
        assertEquals(0.0, b.offset(1_000), 0.0)
        b.hit(1_000)
        val dip = b.offset(1_150)
        assertTrue(dip > 0.02)               // pulled down right after the hit
        assertTrue(b.offset(1_450) < 0.0)    // overshoots upward on the way back
        assertTrue(kotlin.math.abs(b.offset(1_750)) < dip)   // second dip is smaller
        assertEquals(0.0, b.offset(4_000), 1e-3)
        assertEquals(0.0, b.offset(999), 0.0)               // nothing before the hit
    }
}
