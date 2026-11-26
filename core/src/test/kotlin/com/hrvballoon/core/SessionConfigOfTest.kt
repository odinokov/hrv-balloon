package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Denis's decision 2026-11-26: Citi runs only when a per-beat CSV is written. */
class SessionConfigOfTest {
    private fun of(hasEstimate: Boolean, saveCsv: Boolean, shapeGate: Boolean = true, rfSearch: Boolean = false) =
        SessionConfig.of(4.5, 0.4, 1_200_000L, 3.0, 12.0, shapeGate, rfSearch, hasEstimate, saveCsv)

    @Test fun `citi follows save csv`() {
        assertTrue(of(false, true).citi)
        assertFalse(of(false, false).citi)
    }

    @Test fun `start sd follows the estimate`() {
        assertEquals(RfSearch.START_SD_ESTIMATE, of(true, false).rfStartSd, 0.0)
        assertEquals(RfSearch.START_SD_NONE, of(false, false).rfStartSd, 0.0)
    }

    @Test fun `every field passes through to its own slot`() {
        for ((gate, search) in listOf(true to false, false to true)) {
            val c = of(true, true, gate, search)
            assertEquals(4.5, c.rateBpm, 0.0)
            assertEquals(0.4, c.inhaleFraction, 0.0)
            assertEquals(1_200_000L, c.durationMs)
            assertEquals(3.0, c.floorBpm, 0.0)
            assertEquals(12.0, c.ceilingBpm, 0.0)
            assertEquals(gate, c.shapeGate)
            assertEquals(search, c.rfSearch)
        }
    }
}
