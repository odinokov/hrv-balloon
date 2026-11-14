package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class SummaryAccumulatorTest {
    private fun acc(vararg rr: Double) = SummaryAccumulator().apply { for (r in rr) addBeat(r, true) }

    @Test fun handComputed() {
        val s = acc(1000.0, 1100.0, 1000.0, 1100.0).result(4200)
        assertEquals(100.0, s.rmssd!!, 1e-9)
        assertEquals(57.7350, s.sdnn!!, 1e-3)
        assertEquals(60000 / 1050.0, s.meanHr!!, 1e-9)   // mean HR = 60000 / mean NN, not the mean of instantaneous HRs
        assertEquals(4, s.beats)
        assertEquals(0.0, s.rejectedPct!!, 1e-9)
    }

    @Test fun rejectedBeatBreaksTheChain() {
        val a = acc(1000.0, 1100.0)
        a.addBeat(250.0, false)
        a.addBeat(1300.0, true)
        a.addBeat(1400.0, true)
        assertEquals(100.0, a.result(1).rmssd!!, 1e-9)

        val b = acc(1000.0, 1100.0)
        b.breakChain()
        b.addBeat(1300.0, true)
        b.addBeat(1400.0, true)
        assertEquals(100.0, b.result(1).rmssd!!, 1e-9)
    }

    @Test fun rejectedPct() {
        val a = acc(1000.0, 1000.0, 1000.0, 1000.0)
        a.addBeat(100.0, false)
        assertEquals(20.0, a.result(1).rejectedPct!!, 1e-9)
    }

    @Test fun emptyAndSingleBeat() {
        val e = SummaryAccumulator().result(3000)
        assertEquals(0, e.beats)
        assertEquals(3000L, e.durationMs)
        assertNull(e.rejectedPct); assertNull(e.meanHr); assertNull(e.meanP); assertNull(e.rmssd); assertNull(e.sdnn)

        val one = acc(1000.0).result(1000)
        assertNull(one.rmssd)
        assertNull(one.sdnn)
        assertEquals(60.0, one.meanHr!!, 1e-9)
        assertFalse(one.meanHr!!.isNaN())
    }

    @Test fun stars() {
        val a = SummaryAccumulator()
        assertEquals(0, a.result(1).stars)
        a.addStar(); a.addStar()
        assertEquals(2, a.result(1).stars)
    }

    @Test fun meanP() {
        val a = SummaryAccumulator()
        assertNull(a.result(1).meanP)
        a.addP(8.0); a.addP(12.0)
        assertEquals(10.0, a.result(1).meanP!!, 1e-9)
    }

    @Test fun streamingEqualsBatch() {
        val rnd = Random(1)
        val a = SummaryAccumulator()
        val rr = ArrayList<Double>()
        val ok = ArrayList<Boolean>()
        repeat(7200) {
            val r = 900 + 200 * rnd.nextDouble()
            val accepted = rnd.nextInt(20) != 0
            rr += r; ok += accepted
            a.addBeat(r, accepted)
        }
        val nn = rr.indices.filter { ok[it] }.map { rr[it] }
        val diffs = (1 until rr.size).filter { ok[it] && ok[it - 1] }.map { rr[it] - rr[it - 1] }
        val mean = nn.average()
        val sdnn = sqrt(nn.sumOf { (it - mean) * (it - mean) } / (nn.size - 1))
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val s = a.result(1)
        assertEquals(rmssd, s.rmssd!!, 1e-9)
        assertEquals(sdnn, s.sdnn!!, 1e-9)
        assertEquals(60000 / mean, s.meanHr!!, 1e-9)
        assertEquals(100.0 * ok.count { !it } / ok.size, s.rejectedPct!!, 1e-9)
        assertNotNull(s.sdnn)
    }
}
