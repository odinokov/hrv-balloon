package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {
    private val training = SessionRecord(1761400000000, 900000, 5.5, 9.63, 0.842, 31, 68.2, 1.8, 875000, 45.2, 57.7, 940)
    private val sparse = SessionRecord(1761300000000, 3000, 6.0, null, null, 0, null, null, 0, null, null, 2)
    private val search = SessionRecord(1761500000000, 900000, 6.2, 9.0, 0.8, 2, 66.0, 1.0, 600000, 40.0, 50.0, 800,
        SessionRecord.KIND_RF_NO_PEAK, listOf(6.2 to 8.12, 6.9 to 9.0, 5.5 to 6.5), rfEstimateBpm = 6.2)

    private fun rows(rs: List<SessionRecord>) = rs.map { SessionHistory.row(it) }

    @Test fun writesASchemaLineAndNamedColumnsThenReadsItBack() {
        val text = SessionHistory.write(listOf(training, sparse, search))
        val lines = text.lines()
        assertEquals("# hrv-sessions v2", lines[0])
        assertTrue(lines[1].startsWith("start_ms,duration_ms,rate_bpm,"))
        assertTrue(lines[1].endsWith(",kind,probes,rf_estimate_bpm"))
        val back = SessionHistory.parse(text)
        assertEquals(rows(listOf(training, sparse, search)), rows(back))
        assertEquals(6.2, back[2].rfEstimateBpm!!, 1e-9)
        assertEquals(SessionRecord.KIND_RF_NO_PEAK, back[2].kind)
    }

    @Test fun migratesTheFourPositionalLayoutsOfVersionOne() {
        val v1 = listOf(
            "1761400000000,300000,5.5,9.63,0.842,3,68.2,1.8",                                   // 8 fields
            "1761400000000,300000,5.5,9.63,0.842,3,68.2,1.8,275000,45.2,57.7,340",              // 12
            "1,600000,6.1,9.0,0.8,2,66.0,1.0,500000,40.0,50.0,700,1",                           // 13
            "1,600000,6.1,9.0,0.8,2,66.0,1.0,500000,40.0,50.0,700,1,5.5:8.12;6.2:9.0",          // 14
            "garbage",
        ).joinToString("\n")
        val back = SessionHistory.parse(v1)
        assertEquals(4, back.size)
        assertEquals(300000L, back[0].validMs)                         // 8 fields: fully valid
        assertEquals(340, back[1].beats)
        assertEquals(SessionRecord.KIND_RF_SEARCH, back[2].kind)
        assertEquals(listOf(5.5 to 8.12, 6.2 to 9.0), back[3].probes)
        assertEquals(null, back[3].rfEstimateBpm)
    }

    @Test fun matchesColumnsByNameSkippingUnknownOnesAndBadRows() {
        val text = "# hrv-sessions v2\nstars,start_ms,future_column,duration_ms,rate_bpm\n12,1761400000000,x,900000,5.5\nnot,a,row\n"
        val back = SessionHistory.parse(text)
        assertEquals(1, back.size)
        assertEquals(12, back[0].stars)
        assertEquals(5.5, back[0].rateBpm, 1e-9)
        assertEquals(900000L, back[0].validMs)                         // missing valid_ms: the whole session counted, as in v1
        assertEquals(SessionRecord.KIND_TRAINING, back[0].kind)
    }

    @Test fun emptyHistoryIsEmpty() {
        assertTrue(SessionHistory.parse("").isEmpty())
        assertTrue(SessionHistory.parse(SessionHistory.write(emptyList())).isEmpty())
    }
}
