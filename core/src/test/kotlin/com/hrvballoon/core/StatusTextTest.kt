package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 3.5: "WARM 42", seconds rounded up; TalkBack gets "Warm-up" once, without numbers. */
class StatusTextTest {
    @Test fun `3_5 seconds are rounded up`() {
        assertEquals(60, warmupSeconds(60_000))
        assertEquals(60, warmupSeconds(59_001))
        assertEquals(59, warmupSeconds(59_000))
        assertEquals(1, warmupSeconds(1))
        assertEquals(0, warmupSeconds(0))
    }

    private fun warm(ms: Long) = statusText(SessionStatus.WARMUP, "Warm", ms, "Warm-up")

    @Test fun `3_5 drawn text is the word and the seconds, at most eight characters`() {
        assertEquals("Warm 42", warm(41_200).drawn)
        for (s in 1..60) assertTrue(warm(s * 1000L).drawn.length <= 8)
    }

    @Test fun `3_5 spoken text has no number and does not change with the countdown`() {
        val spoken = listOf(60_000L, 42_000L, 1L).map { warm(it).spoken }
        assertEquals(1, spoken.toSet().size)
        assertEquals("Warm-up", spoken[0])
        assertFalse(spoken[0].any(Char::isDigit))
        assertNotEquals(warm(60_000).drawn, warm(42_000).drawn)
    }

    @Test fun `other statuses draw and speak the same text`() {
        val t = statusText(SessionStatus.SEARCH, "Search", 0, "Warm-up")
        assertEquals("Search", t.drawn)
        assertEquals("Search", t.spoken)
    }
}
