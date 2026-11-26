package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec 7.7: median and 99th percentile of the onPacket time; nearest rank. */
class PacketTimerTest {
    private fun timer(n: Int) = PacketTimer().apply { (n downTo 1).forEach { record(it * 1_000_000L) } }

    @Test fun `1 to 100 ms - median 50 and p99 99`() {
        val t = timer(100)
        assertEquals(100, t.count)
        assertEquals(50.0, t.medianMs!!, 1e-9)
        assertEquals(99.0, t.p99Ms!!, 1e-9)
    }

    @Test fun `p99 of 101 samples is the 100th`() {
        assertEquals(100.0, timer(101).p99Ms!!, 1e-9)
    }

    @Test fun `an empty timer gives nothing`() {
        val t = PacketTimer()
        assertNull(t.medianMs)
        assertNull(t.p99Ms)
        assertNull(t.line())
    }

    @Test fun `the line has three decimals with a dot, and reset empties the timer`() {
        val t = PacketTimer().apply { record(412_000); record(1_873_000); record(2_000_000) }
        assertEquals("onPacket n=3 median=1.873 ms p99=2.000 ms", t.line())
        t.reset()
        assertEquals(0, t.count)
        assertNull(t.line())
    }

    @Test fun `grows past its first buffer`() {
        assertEquals(5000, timer(5000).count)
    }
}
