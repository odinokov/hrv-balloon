package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionOutcomeTest {
    private val probes = listOf(5.5 to 8.0, 6.2 to 9.5, 4.8 to 6.0)

    @Test fun trainingRecordsItsRateAndLeavesTheOfferAlone() {
        val o = sessionOutcome(false, 6.0, 6.0, null, false, false, emptyList())
        assertEquals(6.0, o.rateBpm, 1e-9)
        assertEquals(SessionRecord.KIND_TRAINING, o.kind)
        assertNull(o.offerTenths)
    }

    @Test fun aConvergedSearchRecordsAndOffersTheFoundRate() {
        val o = sessionOutcome(true, 5.3, 6.0, 5.3, true, false, probes)
        assertEquals(5.3, o.rateBpm, 1e-9)
        assertEquals(SessionRecord.KIND_RF_SEARCH, o.kind)
        assertEquals(53, o.offerTenths)
    }

    @Test fun noPeakRecords55AndOffersItOnlyWhenTheRateDiffers() {
        val away = sessionOutcome(true, 5.5, 6.0, 5.5, false, true, probes)
        assertEquals(5.5, away.rateBpm, 1e-9)
        assertEquals(SessionRecord.KIND_RF_NO_PEAK, away.kind)
        assertEquals(55, away.offerTenths)
        assertEquals(0, sessionOutcome(true, 5.5, 5.5, 5.5, false, true, probes).offerTenths)   // already at 5.5: clear any older offer
    }

    @Test fun anIncompleteSearchRecordsItsBestGuessAndLeavesTheOfferAlone() {
        val model = sessionOutcome(true, 6.2, 6.0, 6.1, false, false, probes)
        assertEquals(6.1, model.rateBpm, 1e-9)
        assertEquals(SessionRecord.KIND_RF_INCOMPLETE, model.kind)
        assertNull(model.offerTenths)
        assertEquals(6.2, sessionOutcome(true, 6.2, 6.0, null, false, false, probes).rateBpm, 1e-9)
        assertEquals(6.0, sessionOutcome(true, 6.0, 6.0, null, false, false, emptyList()).rateBpm, 1e-9)
    }
}
