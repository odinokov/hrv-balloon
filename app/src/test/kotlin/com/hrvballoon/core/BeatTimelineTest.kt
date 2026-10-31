package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BeatTimelineTest {
    private class Run(val arrivals: List<Long>, val updates: List<TimelineUpdate>)

    /** One 1000 ms beat per notification; arrival = k·1000·(1+drift) + U(300,1000) ms. */
    private fun run(packets: Int, drift: Double = 0.0, drop: Set<Int> = emptySet(), seed: Long = 0): Run {
        val rnd = Random(seed)
        val tl = BeatTimeline()
        val arrivals = ArrayList<Long>()
        val updates = ArrayList<TimelineUpdate>()
        for (k in 1..packets) {
            val arrival = (k * 1000 * (1 + drift) + 300 + 700 * rnd.nextDouble()).toLong()
            if (k in drop) continue
            arrivals += arrival
            updates += tl.onNotification(arrival, intArrayOf(1024))
        }
        return Run(arrivals, updates)
    }

    private fun slack(r: Run, i: Int) = r.arrivals[i] - r.updates[i].beats.last().tEndMs

    @Test fun intraPacketSpacing() {
        val u = BeatTimeline().onNotification(5000, intArrayOf(1024, 1024, 1024))
        assertEquals(3, u.beats.size)
        assertEquals(1000.0, u.beats[1].tEndMs - u.beats[0].tEndMs, 1e-9)
        assertEquals(1000.0, u.beats[2].tEndMs - u.beats[1].tEndMs, 1e-9)
        assertEquals(1000.0, u.beats[0].rrMs, 1e-9)
    }

    @Test fun causal() {
        val r = run(300)
        for (u in r.updates) for (b in u.beats) assertTrue(b.tEndMs <= b.arrivalMs)
    }

    @Test fun driftStaysAnchored() {
        for (drift in doubleArrayOf(0.001, -0.001)) {
            val r = run(900, drift)
            assertTrue(r.updates.all { it.gapMs == 0.0 })
            for (i in 60 until r.arrivals.size) assertTrue("k=$i slack=${slack(r, i)}", slack(r, i) in 0.0..1000.0)
        }
    }

    @Test fun lostNotificationsReanchor() {
        val r = run(300, drop = setOf(100, 101))
        val i = 99 // index of packet k=102
        val gap = r.updates[i].gapMs
        assertTrue("gap=$gap", gap in 1900.0..2800.0)
        assertEquals(r.arrivals[i].toDouble(), r.updates[i].beats.last().tEndMs, 1e-6)
        for (j in i + 1 until r.arrivals.size) assertTrue("k=$j slack=${slack(r, j)}", slack(r, j) in 0.0..1000.0)
    }

    @Test fun singleLostBeatNeverSignalLost() {
        val r = run(300, drop = setOf(100))
        assertTrue(r.updates.all { it.gapMs <= 3000.0 })
        assertTrue(slack(r, 168) in 0.0..1000.0) // packet k=170
    }

    @Test fun emptyRr() {
        val tl = BeatTimeline()
        tl.onNotification(1000, intArrayOf(1024))
        val u = tl.onNotification(2000, intArrayOf())
        assertEquals(0, u.beats.size)
        assertEquals(0.0, u.gapMs, 0.0)
    }
}
