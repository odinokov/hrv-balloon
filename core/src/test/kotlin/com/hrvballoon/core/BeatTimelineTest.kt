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
        val i = 100 // index of packet k=103: the first on-time one after the late k=102, where the loss is confirmed
        assertEquals(0.0, r.updates[i - 1].gapMs, 0.0)
        assertTrue(r.updates[i - 1].beats[0].afterGap)
        val gap = r.updates[i].gapMs
        assertTrue("gap=$gap", gap in 1900.0..2800.0)
        assertEquals(r.arrivals[i].toDouble(), r.updates[i].beats.last().tEndMs, 1e-6)
        for (j in i + 1 until r.arrivals.size) assertTrue("k=$j slack=${slack(r, j)}", slack(r, j) in 0.0..1000.0)
    }

    /** Steady 1 s beats arriving at k s + 500 ms; packets 51..53 are held up by [stall] and arrive together. */
    private fun stalled(stallMs: Long): List<TimelineUpdate> {
        val tl = BeatTimeline()
        for (k in 1..50) tl.onNotification(k * 1000L + 500, intArrayOf(1024))
        val burst = (51..53).map { tl.onNotification(51_000L + stallMs + 500, intArrayOf(1024)) }
        return burst + tl.onNotification(54_500L, intArrayOf(1024)) + tl.onNotification(55_500L, intArrayOf(1024))
    }

    private fun assertDelay(stallMs: Long) {
        val u = stalled(stallMs)
        assertTrue(u.all { it.gapMs == 0.0 })
        assertTrue(u[0].beats[0].afterGap)
        assertTrue(u.drop(1).none { it.beats[0].afterGap })
        u.forEachIndexed { i, x -> assertEquals((51 + i) * 1000.0 + 500, x.beats[0].tEndMs, 1e-6) }
    }

    @Test fun twoSecondStallIsDelayNotLoss() = assertDelay(2000)

    @Test fun threeSecondStallIsDelayNotLoss() = assertDelay(3000)

    private fun steady(n: Int, per: Int = 1): BeatTimeline {
        val tl = BeatTimeline()
        for (k in 1..n) tl.onNotification(k * 1000L * per + 500, IntArray(per) { 1024 })
        return tl
    }

    @Test fun aLossAfterADelayStallBreaksTheChainAtTheDecidingBeat() {
        val tl = steady(50)
        repeat(3) { tl.onNotification(53_500, intArrayOf(1024)) }
        val u = tl.onNotification(57_500, intArrayOf(1024)) // 3 beats lost, then on time
        assertTrue(u.gapMs > 0)
        assertTrue(u.beats[0].afterGap)
    }

    @Test fun aConfirmedLossReportsTheLateNotificationsExcess() {
        val tl = steady(50)
        val late = tl.onNotification(54_500, intArrayOf(1024)) // 3 beats lost: excess exactly 3000, deferred
        assertEquals(0.0, late.gapMs, 0.0)
        val u = tl.onNotification(55_800, intArrayOf(1024)) // on time, 300 ms slower: own excess 3300
        assertEquals(3000.0, u.gapMs, 1e-6)
    }

    @Test fun twoBeatNotificationsDuringAStallKeepTheirStamps() {
        val tl = steady(25, 2)
        val u = listOf(tl.onNotification(54_500, intArrayOf(1024, 1024)), tl.onNotification(54_500, intArrayOf(1024, 1024)),
            tl.onNotification(56_500, intArrayOf(1024, 1024)))
        assertTrue(u.all { it.gapMs == 0.0 })
        assertTrue(u[0].beats[0].afterGap)
        assertEquals((0..5).map { 51_500.0 + 1000 * it }, u.flatMap { it.beats }.map { it.tEndMs })
    }

    @Test fun aDrainingBurstResolvesAsDelay() {
        val tl = steady(50)
        val u = listOf(53_500L, 53_700L, 54_300L, 54_500L).map { tl.onNotification(it, intArrayOf(1024)) }
        assertTrue(u.all { it.gapMs == 0.0 })
        assertEquals((0..3).map { 51_500.0 + 1000 * it }, u.map { it.beats[0].tEndMs })
    }

    @Test fun aSecondStallWhileAVerdictIsPendingIsALossWhenStillLateAtTheNextOnTimePacket() {
        val tl = steady(50)
        tl.onNotification(53_500, intArrayOf(1024))
        val u = tl.onNotification(54_200, intArrayOf(1024)) // 700 ms later is on time, yet still 1700 ms late
        assertEquals(2000.0, u.gapMs, 1e-6)
        assertTrue(u.beats[0].afterGap)
    }

    @Test fun tenSecondDropoutReanchorsAtOnce() {
        val tl = BeatTimeline()
        for (k in 1..50) tl.onNotification(k * 1000L + 500, intArrayOf(1024))
        val u = tl.onNotification(61_500L, intArrayOf(1024))
        assertEquals(10_000.0, u.gapMs, 1e-6)
        assertTrue(u.beats[0].afterGap)
        assertEquals(61_500.0, u.beats[0].tEndMs, 1e-6)
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
