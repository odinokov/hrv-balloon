package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SessionRecordTest {
    private val full = SessionRecord(1_761_400_000_000, 300_000, 5.5, 9.63, 0.842, 3, 68.2, 1.8, 275_000, 45.2, 57.7, 340)
    private val sparse = SessionRecord(1_761_300_000_000, 3_000, 6.0, null, null, 0, null, null, 0, null, null, 2)

    @Test fun lineRoundTrip() {
        assertEquals("1761400000000,300000,5.5,9.63,0.842,3,68.2,1.8,275000,45.2,57.7,340,0,", full.toLine())
        assertEquals("1761300000000,3000,6.0,,,0,,,0,,,2,0,", sparse.toLine())
        val probes = listOf(5.5 to 8.12, 6.2 to 9.0, 4.8 to 6.5)
        val rf = SessionRecord(1, 600_000, 6.1, 9.0, 0.8, 2, 66.0, 1.0, 500_000, 40.0, 50.0, 600, SessionRecord.KIND_RF_SEARCH, probes)
        assertFalse(rf.ranked)
        assertTrue(rf.toLine().endsWith(",1,5.5:8.12;6.2:9.0;4.8:6.5"))
        val back = SessionRecord.fromLine(rf.toLine())!!
        assertEquals(SessionRecord.KIND_RF_SEARCH, back.kind)
        assertEquals(probes, back.probes)
        val incomplete = SessionRecord(1, 300_000, 5.5, null, null, 0, null, null, 0, null, null, 10, SessionRecord.KIND_RF_INCOMPLETE)
        assertEquals(SessionRecord.KIND_RF_INCOMPLETE, SessionRecord.fromLine(incomplete.toLine())!!.kind)
        assertTrue(SessionRecord.fromLine(incomplete.toLine())!!.probes.isEmpty())
        assertEquals(SessionRecord.KIND_TRAINING, SessionRecord.fromLine("1761400000000,300000,5.5,9.63,0.842,3,68.2,1.8,275000,45.2,57.7,340")!!.kind)
        assertEquals(SessionRecord.KIND_RF_SEARCH, SessionRecord.fromLine("1,600000,6.1,9.0,0.8,2,66.0,1.0,500000,40.0,50.0,600,1")!!.kind)
        for (r in listOf(full, sparse)) assertEquals(r.toLine(), SessionRecord.fromLine(r.toLine())!!.toLine())
        assertNull(SessionRecord.fromLine("garbage"))
        assertNull(SessionRecord.fromLine(""))
    }

    @Test fun legacyEightFieldLinesStillLoad() {
        val old = SessionRecord.fromLine("1761400000000,300000,5.5,9.63,0.842,3,68.2,1.8")!!
        assertEquals(300_000L, old.validMs)
        assertNull(old.rmssd)
        assertEquals(0, old.beats)
        assertTrue(old.ranked)
    }

    @Test fun lineIsLocaleIndependent() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("1761400000000,300000,5.5,9.63,0.842,3,68.2,1.8,275000,45.2,57.7,340,0,", full.toLine())
            assertEquals(9.63, SessionRecord.fromLine(full.toLine())!!.meanP!!, 1e-9)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun rankedByStarsThenRecencyWithoutUsingAmplitude() {
        val a = SessionRecord(1, 1, 5.5, 10.0, 0.8, 1, null, null, 200_000, null, null, 0)   // 8.0
        val b = SessionRecord(2, 1, 5.5, 12.0, 0.5, 5, null, null, 200_000, null, null, 0)   // 6.0
        val c = SessionRecord(3, 1, 5.5, 8.0, 1.0, 2, null, null, 200_000, null, null, 0)    // 8.0, more stars than a
        val short = SessionRecord(4, 1, 5.5, 20.0, 1.0, 9, null, null, 60_000, null, null, 0) // 20.0 but not ranked
        assertFalse(short.ranked)
        assertEquals(listOf(b, c, a), rankSessions(listOf(short, b, a, c)))
        val recent = SessionRecord(5, 1, 6.5, 1.0, 0.5, 2, null, null, 200_000, null, null, 0)
        assertEquals(listOf(b, recent, c, a), rankSessions(listOf(a, c, recent, b)))
        assertEquals(8.0, a.score, 1e-9)
        assertEquals(0.0, sparse.score, 1e-9)
    }
}
