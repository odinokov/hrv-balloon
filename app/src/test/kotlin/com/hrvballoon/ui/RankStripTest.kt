package com.hrvballoon.ui

import com.hrvballoon.core.SessionRecord
import com.hrvballoon.core.TrendPeriod
import com.hrvballoon.core.TrendPoint
import com.hrvballoon.core.coherenceTrend
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankStripTest {
    private val scene = RankScene(160, 284, TestText.scene)
    private val now = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()   // a Sunday
    private val day = 86_400_000L
    private fun record(coherence: Double?) = SessionRecord(now, 900_000, 5.5, 10.0, 0.8, 3, 68.0, 1.0, 880_000, 44.0, 55.0, 900, coherence = coherence)

    /** Mon 16 empty, Tue 1.5, Wed 3.0, Thu 6.0, Fri and Sat empty, Sun (the card's day) 4.5. */
    private val week = coherenceTrend(
        listOf(1.5 to 5, 3.0 to 4, 6.0 to 3, 4.5 to 0).map { (c, ago) -> TrendPoint(now - ago * day, 600_000, c) },
        now, ZoneOffset.UTC, TrendPeriod.D7,
    )

    private fun card(r: SessionRecord, w: com.hrvballoon.core.Trend?) = scene.renderResult(r, 1, 3, null, 5_000, 12, week = w).copyOf()
    private fun px(a: IntArray, x: Int, y: Int) = a[y * scene.w + x]

    @Test fun aRecordWithoutCoherenceRendersAsBefore() {
        val plain = scene.renderResult(record(null), 1, 3, null, 5_000, 12).copyOf()
        assertArrayEquals(plain, card(record(null), week))
        assertArrayEquals(plain, card(record(4.5), null))
        assertFalse(plain.contentEquals(card(record(4.5), week)))
    }

    @Test fun barHeightsAreOnAFixedZeroToSixScale() {
        assertEquals(24, scene.stripBarHeight(6.0))
        assertEquals(24, scene.stripBarHeight(9.0))
        assertEquals(12, scene.stripBarHeight(3.0))
        assertEquals(2, scene.stripBarHeight(0.1))
    }

    @Test fun stripDrawsBarsDashesAndHighlightsToday() {
        val px = card(record(4.5), week)
        val base = 138
        fun bar(i: Int, v: Double, color: Int) {
            val x = 20 + 18 * i + 6
            val h = scene.stripBarHeight(v)
            assertEquals("bar $i top", color, px(px, x, base - h))
            assertEquals("bar $i bottom", color, px(px, x, base - 1))
            assertEquals("bar $i is not taller", RankScene.SURFACE, px(px, x, base - h - 1))
        }
        bar(1, 1.5, RankScene.BAR)
        bar(2, 3.0, RankScene.BAR)
        bar(3, 6.0, RankScene.BAR)
        bar(6, 4.5, RankScene.HIGHLIGHT)
        for (i in listOf(0, 4, 5)) {
            val x = 20 + 18 * i
            assertEquals("dash $i", RankScene.DIM, px(px, x + 5, base - 1))
            assertEquals("dash $i is one px thick", RankScene.SURFACE, px(px, x + 5, base - 2))
            assertEquals("dash $i is narrower than a bar", RankScene.SURFACE, px(px, x + 1, base - 1))
        }
    }

    @Test fun stripSaysTheSessionsCoherenceAndTheWeeksMedian() {
        fun ink(s: String, color: Int) = HashSet<Pair<Int, Int>>().also { PixelFont.draw(0, 0, s, color) { x, y, _ -> it += x to y } }.size
        val with = card(record(2.7), week)
        val line = String.format(java.util.Locale.ROOT, TestText.scene.coherence, 2.7)
        assertEquals(ink(line, 0), (0 until scene.w).sumOf { x -> (102 until 108).count { y -> px(with, x, y) == RankScene.TEXT } })
        val median = String.format(java.util.Locale.ROOT, TestText.scene.weekMedian, week.median)
        assertEquals("7D MEDIAN 3.8", median)   // the median of 1.5, 3, 6 and 4.5 is (3 + 4.5) / 2
        assertEquals(ink(median, 0), (0 until scene.w).sumOf { x -> (151 until 157).count { y -> px(with, x, y) == RankScene.TEXT } })
        assertTrue(week.bins.last().value == 4.5)
    }
}
