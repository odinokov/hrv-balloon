package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.roundToInt

class RfFitTest {
    /** The session that prompted the result-card chart; the search started at 5.0 /min. */
    private val worked = listOf(5.0 to 1.1, 5.5 to 0.6, 4.5 to 1.3, 4.2 to 1.4, 5.2 to 1.2)

    @Test fun workedExample() {
        val f = rfFit(worked)!!
        assertEquals(0.75, f.width, 1e-9)
        assertEquals(4.54, f.centre, 0.01)
        assertEquals(0.13, f.se, 0.01)
        assertEquals(1.45, f.scoreAt(f.centre), 0.01)
        for (sd in listOf(RfSearch.START_SD_ESTIMATE, RfSearch.START_SD_NONE)) {
            val s = RfSearch(5.0, sd).apply { for ((r, sc) in worked) report(r, sc) }
            assertEquals(4.6, s.best, 1e-9)
        }
    }

    @Test fun searchAnswerIsTheFitShrunkTowardTheStart() {
        for (seed in 0 until 30) {
            val rnd = Random(seed.toLong())
            val start = ((4.5 + 2 * rnd.nextDouble()) * 10).roundToInt() / 10.0
            val sd = if (seed % 2 == 0) RfSearch.START_SD_ESTIMATE else RfSearch.START_SD_NONE
            val peak = 4.0 + 3 * rnd.nextDouble()
            val width = 0.5 + rnd.nextDouble()
            val s = RfSearch(start, sd)
            repeat(9) {
                val r = s.next()
                val failed = rnd.nextDouble() < 0.1
                s.report(r, 9 * exp(-(r - peak) * (r - peak) / (2 * width * width) + 0.3 * rnd.nextGaussian()) * if (failed) 0.05 else 1.0)
                if (s.probeCount < 3) return@repeat
                val f = rfFit(s.probes)!!
                val shrunk = start + (f.centre - start) * sd * sd / (sd * sd + f.se * f.se)
                assertEquals("seed $seed probe ${s.probeCount}", ((shrunk * 10).roundToInt() / 10.0).coerceIn(4.0, 7.0), s.best, 1e-9)
                assertEquals(f.se, s.se, 1e-12)
            }
        }
    }

    @Test fun noFitBelowThreeProbesOrWithoutSpread() {
        assertNull(rfFit(emptyList()))
        assertNull(rfFit(worked.take(2)))
        assertNull(rfFit(listOf(5.0 to 1.0, 5.0 to 1.2, 5.0 to 0.9)))
        assertNull(rfFit(listOf(5.0 to 1.0, 5.0 to 1.2, 5.0 to 0.9, 6.0 to 2.0)))
        assertNotNull(rfFit(worked.take(3)))
    }

    @Test fun anEvenCountFitsAllButTheLast() {
        val p = worked + (5.7 to 0.4)
        val six = rfFit(p)!!
        val five = rfFit(p.take(5))!!
        assertEquals(five.centre, six.centre, 0.0)
        assertEquals(five.width, six.width, 0.0)
        assertEquals(five.lnPeak, six.lnPeak, 0.0)
        assertEquals(five.se, six.se, 0.0)
    }
}
