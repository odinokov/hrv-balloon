package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRecordOfTest {
    private val probes = listOf(5.5 to 8.1, 6.2 to 9.2)

    // every Double? and Int slot a different value, so swapping any two fails
    private fun summary(rfProbes: List<Pair<Double, Double>>) = Summary(
        durationMs = 301_000, beats = 340, rejectedPct = 1.8, meanHr = 68.2, meanP = 9.63, meanShape = 0.842,
        rmssd = 45.2, sdnn = 57.7, stars = 3, validMs = 275_000, rfBest = 6.1, rfConverged = true, rfNoPeak = false,
        rfProbes = rfProbes,
    )

    @Test fun trainingMapsEveryField() {
        val s = summary(emptyList())
        val r = SessionRecord.of(1_761_400_000_000, s, sessionOutcome(false, 5.5, 5.5, null, false, false, emptyList()), false, 6.0)
        assertEquals(1_761_400_000_000, r.startMs)
        assertEquals(301_000, r.durationMs)
        assertEquals(5.5, r.rateBpm, 0.0)
        assertEquals(9.63, r.meanP!!, 0.0)
        assertEquals(0.842, r.meanShape!!, 0.0)
        assertEquals(3, r.stars)
        assertEquals(68.2, r.meanHr!!, 0.0)
        assertEquals(1.8, r.rejectedPct!!, 0.0)
        assertEquals(275_000, r.validMs)
        assertEquals(45.2, r.rmssd!!, 0.0)
        assertEquals(57.7, r.sdnn!!, 0.0)
        assertEquals(340, r.beats)
        assertEquals(SessionRecord.KIND_TRAINING, r.kind)
        assertEquals(emptyList<Pair<Double, Double>>(), r.probes)
        assertNull(r.rfEstimateBpm)
    }

    @Test fun searchKeepsKindProbesAndEstimate() {
        val s = summary(probes)
        val r = SessionRecord.of(1, s, sessionOutcome(true, 5.5, 5.5, 6.1, true, false, probes), true, 6.0)
        assertEquals(SessionRecord.KIND_RF_SEARCH, r.kind)
        assertEquals(6.1, r.rateBpm, 0.0)
        assertEquals(probes, r.probes)
        assertEquals(6.0, r.rfEstimateBpm!!, 0.0)
    }
}
