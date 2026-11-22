package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StarFrameTest {
    /** Frames every 100 ms of fake session time over [fromMs, toMs) at the ceiling. */
    private fun run(c: StarCounter, fromMs: Int, toMs: Int, live: Boolean, frozen: Boolean) =
        (fromMs until toMs step 100).count { c.starThisFrame(1.0, it.toDouble(), live, frozen) }

    @Test fun notLiveNeverPaysAndKeepsTheSlotFree() {
        val c = StarCounter()
        assertEquals(0, run(c, 0, 30_000, live = false, frozen = false))
        assertEquals(0, c.count)
        assertEquals(1, run(c, 30_000, 30_100, live = true, frozen = false))
    }

    @Test fun frozenNeverPaysAndKeepsTheSlotFree() {
        val c = StarCounter()
        assertEquals(0, run(c, 0, 30_000, live = true, frozen = true))
        assertEquals(0, c.count)
        assertEquals(1, run(c, 30_000, 30_100, live = true, frozen = false))
    }

    @Test fun oneStarPerTenSecondSlotAtTheCeiling() {
        val c = StarCounter()
        assertEquals(4, run(c, 0, 30_000, live = true, frozen = false))   // slot edges at 0, 7.5, 17.5, 27.5 s
        assertEquals(4, c.count)
    }

    @Test fun belowTheCeilingPaysNothing() {
        assertEquals(false, StarCounter().starThisFrame(0.9, 0.0, live = true, frozen = false))
    }
}
