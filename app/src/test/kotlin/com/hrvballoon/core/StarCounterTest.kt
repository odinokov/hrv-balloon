package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarCounterTest {
    @Test fun oneStarPerBreathAtTheCeiling() {
        val c = StarCounter()
        assertFalse(c.update(0.5, 0))
        assertFalse(c.update(0.98, 0))
        assertTrue(c.update(0.985, 0))    // first touch
        assertFalse(c.update(0.999, 0))   // same breath: no second star
        assertTrue(c.update(0.999, 1))    // still at the ceiling, new breath
        assertFalse(c.update(0.9, 2))     // off the ceiling
        assertTrue(c.update(0.99, 2))     // back on it in a new breath
        assertFalse(c.update(0.99, 2))
        assertEquals(3, c.count)
    }
}
