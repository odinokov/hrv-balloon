package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupClockTest {
    private fun clockAt0() = WarmupClock().also { it.onUsable(0.0, 0.0) }

    @Test fun `9_1_1 t0 0 gives T 60000 and the boundary-crossing RR is not game`() {
        val c = clockAt0()
        assertEquals(0.0, c.t0Ms!!, 0.0)
        assertEquals(60_000.0, c.boundaryMs!!, 0.0)
        assertFalse(c.isGame(60_400.0, 800.0)) // 59.6-60.4 s
        assertTrue(c.isGame(61_200.0, 800.0)) // 60.4-61.2 s
    }

    @Test fun `9_1_2 exact boundary counts as game`() {
        val c = clockAt0()
        assertTrue(c.isGame(61_000.0, 1000.0))
        assertTrue(isGameRr(61_000.0, 1000.0, 60_000.0))
    }

    @Test fun `3_1 no boundary means no game RR`() {
        assertFalse(isGameRr(1e9, 800.0, null))
        assertFalse(WarmupClock().isGame(1e9, 800.0))
        assertNull(WarmupClock().boundaryMs)
    }

    @Test fun `2 t0 is clamped to zero and set once`() {
        val c = WarmupClock()
        assertTrue(c.onUsable(-50.0, -50.0))
        assertEquals(0.0, c.t0Ms!!, 0.0)
        assertFalse(c.onUsable(900.0, 900.0))
        assertEquals(0.0, c.t0Ms!!, 0.0)
    }

    @Test fun `9_1_5 pause at 40 s resets and the new t0 comes from the next usable RR`() {
        val c = WarmupClock()
        assertTrue(c.onUsable(800.0, 800.0))
        assertTrue(c.onBreak(40_000.0))
        assertNull(c.t0Ms)
        assertNull(c.boundaryMs)
        assertTrue(c.onUsable(45_800.0, 45_800.0))
        assertEquals(105_800.0, c.boundaryMs!!, 0.0)
        assertFalse(c.takeEnd(100_000.0))
        assertTrue(c.takeEnd(105_800.0))
        assertFalse(c.takeEnd(106_000.0))
    }

    @Test fun `9_1_6 loss or confirmed gap before T resets`() {
        val c = clockAt0()
        assertTrue(c.onBreak(59_999.0))
        assertNull(c.t0Ms)
    }

    @Test fun `3_3 event while t0 unknown changes nothing`() {
        val c = WarmupClock()
        assertFalse(c.onBreak(5_000.0))
        assertNull(c.t0Ms)
    }

    @Test fun `9_1_8 event at or after T does not reset and takeEnd fires once`() {
        val c = clockAt0()
        assertFalse(c.onBreak(60_000.0))
        assertFalse(c.onBreak(80_000.0))
        assertEquals(60_000.0, c.boundaryMs!!, 0.0)
        assertTrue(c.takeEnd(80_000.0))
        assertFalse(c.takeEnd(90_000.0))
    }

    @Test fun `3_3 rule 4 gap from 55 s confirmed at 61 s does not reset`() {
        val c = clockAt0()
        assertFalse(c.onBreak(61_000.0))
        assertEquals(60_000.0, c.boundaryMs!!, 0.0)
    }

    @Test fun `remainingMs before t0 and during and after T`() {
        val c = WarmupClock()
        assertEquals(60_000.0, c.remainingMs(10_000.0), 0.0)
        c.onUsable(1_000.0, 1_000.0)
        assertEquals(51_000.0, c.remainingMs(10_000.0), 0.0)
        assertEquals(0.0, c.remainingMs(70_000.0), 0.0)
    }

    @Test fun `takeEnd is false while t0 is unknown`() {
        assertFalse(WarmupClock().takeEnd(1e9))
    }

    @Test fun `remainingMs is full right after a reset`() {
        val c = clockAt0()
        c.onBreak(10_000.0)
        assertEquals(60_000.0, c.remainingMs(20_000.0), 0.0)
    }

    @Test fun `9_1_8 takeEnd latch is not re-armed by a later event`() {
        val c = clockAt0()
        assertTrue(c.takeEnd(60_000.0))
        assertFalse(c.onBreak(70_000.0))
        assertFalse(c.takeEnd(80_000.0))
    }

    // Arrival guard (engine lane): the filter classifies a beat two beats late, so a beat that arrived BEFORE an event can be
    // recorded as usable AFTER it. It must not set the new t0. The comparison is on the arrival clock (tEndMs is stamped on the
    // lowest-latency envelope and can precede the arrival by the delivery latency: spec 8.3, t_ms 131020.5, arrival 131520).
    @Test fun `3_3 a beat that arrived before the event never sets the new t0`() {
        val c = WarmupClock()
        assertTrue(c.onUsable(800.0, 1_300.0))
        assertTrue(c.onBreak(40_000.0))
        assertFalse(c.onUsable(39_000.0, 39_500.0))
        assertNull(c.t0Ms)
    }

    @Test fun `3_3 a beat arriving exactly at the event moment counts as after it`() {
        val c = WarmupClock()
        c.onUsable(800.0, 1_300.0)
        c.onBreak(40_000.0)
        assertTrue(c.onUsable(39_500.0, 40_000.0))
        assertEquals(39_500.0, c.t0Ms!!, 0.0)
    }

    @Test fun `3_3 the guard compares arrival not tEnd`() {
        val c = WarmupClock()
        c.onUsable(800.0, 1_300.0)
        c.onBreak(45_000.0)
        assertTrue(c.onUsable(44_700.0, 45_200.0)) // tEnd before the event, arrival after it: a genuine post-event beat
        assertEquals(44_700.0, c.t0Ms!!, 0.0)
    }

    @Test fun `3_3 an event that resets nothing still moves the guard`() {
        val c = WarmupClock()
        assertFalse(c.onBreak(5_000.0)) // t0 unknown
        assertFalse(c.onUsable(4_900.0, 4_950.0))
        assertTrue(c.onUsable(5_100.0, 5_000.0))
    }

    @Test fun `a break stamped before T but handled after the end was taken leaves the clock alone`() {
        val c = clockAt0()
        assertTrue(c.takeEnd(60_000.0))
        assertFalse(c.onBreak(59_990.0))
        assertEquals(0.0, c.t0Ms!!, 0.0)
    }
}
