package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.exp

class RfSearchFollowingTest {
    @Test fun theRateAfterTheProbeInProgressIsKnownUnlessAFitDecidesIt() {
        for (seed in 0 until 20) {
            val rnd = Random(seed.toLong())
            val peak = 4.0 + 3 * rnd.nextDouble()
            val s = RfSearch(4.5 + 2 * rnd.nextDouble(), RfSearch.START_SD_ESTIMATE)
            repeat(9) {
                val k = s.probeCount + 1
                val known = s.following()
                val r = s.next()
                s.report(r, 8 * exp(-(r - peak) * (r - peak) / 1.2 + 0.25 * rnd.nextGaussian()))
                if (k >= 3 && k % 2 == 1) assertNull("probe $k is followed by a fit", known)
                else assertEquals("probe $k, seed $seed", s.next(), known!!, 1e-9)
            }
        }
    }
}
