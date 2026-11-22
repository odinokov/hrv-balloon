package com.hrvballoon.ui

import com.hrvballoon.MapPrefs
import com.hrvballoon.Settings
import com.hrvballoon.core.Trend
import com.hrvballoon.core.TrendBin
import com.hrvballoon.core.TrendPeriod
import com.hrvballoon.core.TrendPoint
import com.hrvballoon.core.coherenceTrend
import com.hrvballoon.trendDescription
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankTrendTest {
    private val scene = RankScene(160, 284, TestText.scene)
    private val now = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private fun trend(period: TrendPeriod, vararg daysAgoToValue: Pair<Int, Double>) =
        coherenceTrend(daysAgoToValue.map { (ago, c) -> TrendPoint(now - ago * day, 600_000, c) }, now, ZoneOffset.UTC, period)

    private fun frame(t: Trend, p: TrendPeriod) = scene.renderTrend(t, p, 0).copyOf()
    private fun count(a: IntArray, color: Int, xs: IntRange = 0 until 160, ys: IntRange = 0 until 284) = ys.sumOf { y -> xs.count { x -> a[y * 160 + x] == color } }

    @Test fun tabsAndChipsHitTheirAreas() {
        assertEquals(0, RankScene.tabAt(10, 5, 160))
        assertEquals(1, RankScene.tabAt(100, 5, 160))
        assertEquals(-1, RankScene.tabAt(10, 20, 160))
        assertEquals(TrendPeriod.D7, RankScene.chipAt(10, 25, 160))
        assertEquals(TrendPeriod.D14, RankScene.chipAt(50, 25, 160))
        assertEquals(TrendPeriod.D30, RankScene.chipAt(100, 25, 160))
        assertEquals(TrendPeriod.Y1, RankScene.chipAt(150, 25, 160))
        assertNull(RankScene.chipAt(2, 25, 160))
        assertNull(RankScene.chipAt(10, 60, 160))
        assertNull(RankScene.chipAt(10, 5, 160))
    }

    @Test fun theActiveTabIsUnderlinedInItsHalf() {
        val best = scene.render(emptyList(), -1, 0).copyOf()
        val trend = frame(trend(TrendPeriod.D7, 0 to 3.0), TrendPeriod.D7)
        assertTrue(count(best, RankScene.TITLE, 0 until 80, 13..13) > 0)
        assertEquals(0, count(best, RankScene.TITLE, 80 until 160, 13..13))
        assertTrue(count(trend, RankScene.TITLE, 80 until 160, 13..13) > 0)
        assertEquals(0, count(trend, RankScene.TITLE, 0 until 80, 13..13))
    }

    @Test fun theChosenChipIsMarked() {
        val t = trend(TrendPeriod.D7, 0 to 3.0)
        assertTrue(count(frame(t, TrendPeriod.D14), RankScene.SELECTION, 42 until 80, 20..30) > 0)
        assertEquals(0, count(frame(t, TrendPeriod.D7), RankScene.SELECTION, 42 until 80, 20..30))
    }

    @Test fun everyTrendPixelFromThePalette() {
        val palette = RankScene.PALETTE.toSet()
        for (p in TrendPeriod.values()) {
            assertTrue("$p", frame(trend(p, 0 to 3.0, 3 to 5.5, 20 to 1.0), p).all { it in palette })
            assertTrue("$p empty", frame(trend(p), p).all { it in palette })
        }
    }

    @Test fun emptyStateDrawsNoChart() {
        val empty = frame(trend(TrendPeriod.D30), TrendPeriod.D30)
        assertEquals(0, count(empty, RankScene.BAR))
        assertEquals(0, count(empty, RankScene.HIGHLIGHT, 0 until 160, 40..80))   // no headline
        assertTrue(count(empty, RankScene.DIM, 0 until 160, 50..58) > 20)         // NO COHERENCE YET
    }

    @Test fun daysAreMarksAtTheirValueAndGapsAreNotZeros() {
        val t = trend(TrendPeriod.D7, 0 to 6.0, 3 to 3.0)
        val px = frame(t, TrendPeriod.D7)
        assertEquals(2 * 9, count(px, RankScene.BAR))                 // one 3x3 mark per practised day, none for the five gaps
        fun cx(i: Int) = 24 + (2 * i + 1) * 126 / 14
        assertEquals(RankScene.BAR, px[100 * 160 + cx(6)])            // 6 sits at the top of the 0-6 scale
        assertEquals(RankScene.BAR, px[136 * 160 + cx(3)])            // 3 is half way
        assertEquals(0, count(px, RankScene.BAR, 0 until 160, 138..172))   // nothing lower: gaps do not fall to 0
    }

    @Test fun prevOnlyWhenThereIsAPreviousMedian() {
        val t = Trend(List(7) { TrendBin(LocalDate.of(2026, 11, 16 + it), 3.0) }, 3.0, 7, 2.5, null)
        val with = frame(t, TrendPeriod.D7)
        val without = frame(Trend(t.bins, 3.0, 7, null, null), TrendPeriod.D7)
        assertTrue(count(with, RankScene.DIM, 0 until 160, 72..77) > 15)
        assertEquals(0, count(without, RankScene.DIM, 0 until 160, 72..77))
    }

    @Test fun rollingLineOnlyFor30Days() {
        val pts = (0..29).map { it to 2.0 + it % 3 }.toTypedArray()
        assertTrue(count(frame(trend(TrendPeriod.D30, *pts), TrendPeriod.D30), RankScene.HIGHLIGHT, 24 until 150, 80..171) > 30)
        assertEquals(0, count(frame(trend(TrendPeriod.D14, *pts), TrendPeriod.D14), RankScene.HIGHLIGHT, 0 until 160, 80..171))
    }

    @Test fun yearIsWeeklyBarsWithMonthInitials() {
        val px = frame(trend(TrendPeriod.Y1, *(0 until 364).map { it to 3.0 }.toTypedArray()), TrendPeriod.Y1)
        val bottom = count(px, RankScene.BAR, 0 until 160, 171..171)
        assertTrue("52 bars of 1-2 px: $bottom", bottom in 52..104)
        assertEquals(0, count(px, RankScene.BAR, 0 until 160, 135..135))                 // bars end at the value 3 line
        assertTrue(count(px, RankScene.BAR, 0 until 160, 136..136) > 0)
        assertTrue(count(px, RankScene.DIM, 0 until 160, 177..182) > 40)                 // month initials
    }

    @Test fun oneDayStateRenders() {
        val px = frame(trend(TrendPeriod.D30, 0 to 4.0), TrendPeriod.D30)
        assertTrue(count(px, RankScene.HIGHLIGHT, 0 until 160, 44..56) > 20)
        assertTrue(count(px, RankScene.BAR) > 0)
    }

    @Test fun periodIsKeptInSettingsAndDefaultsToThirtyDays() {
        val prefs = MapPrefs()
        assertEquals(TrendPeriod.D30, Settings(prefs).trendPeriod)
        for (p in TrendPeriod.values()) { Settings(prefs).trendPeriod = p; assertEquals(p, Settings(prefs).trendPeriod) }
        prefs.map["trendPeriod"] = "W9"
        assertEquals(TrendPeriod.D30, Settings(prefs).trendPeriod)
    }

    @Test fun talkBackSaysOneSentence() {
        val bins = List(30) { TrendBin(LocalDate.of(2026, 10, 24).plusDays(it.toLong()), null) }
        val t = Trend(bins, 2.6, 18, 2.3, null)
        fun say(t: Trend, p: TrendPeriod, l: Locale) = trendDescription(TestText::get, t, p, l)
        assertEquals("Coherence, last 30 days: median 2.6 on 18 of 30 days; previous 30 days 2.3.", say(t, TrendPeriod.D30, Locale.US))
        assertEquals("Coherence, last 7 days: median 2.6 on 5 of 7 days.", say(Trend(bins, 2.6, 5, null, null), TrendPeriod.D7, Locale.US))
        assertTrue(say(t, TrendPeriod.D30, Locale.GERMANY).contains("median 2,6"))
        assertEquals("Coherence, last 14 days: no sessions yet.", say(Trend(bins, null, 0, null, null), TrendPeriod.D14, Locale.US))
    }
}
