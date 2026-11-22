package com.hrvballoon

import com.hrvballoon.core.TrendPeriod
import com.hrvballoon.ui.TestText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RankActionsTest {
    private fun labels(tab: Int) = RankActions.available(tab).map { TestText.get(it.second) }

    @Test fun eachPageOffersTheOtherPageAndTrendAlsoThePeriods() {
        assertEquals(listOf("Show trend"), labels(0))
        assertEquals(listOf("Show best sessions", "Last 7 days", "Last 14 days", "Last 30 days", "Last year"), labels(1))
        assertEquals(5, RankActions.available(1).map { it.first }.toSet().size)
    }

    @Test fun performingSwitchesPageOrPeriod() {
        assertEquals(1 to TrendPeriod.D14, RankActions.perform(RankActions.SHOW_TREND, 0, TrendPeriod.D14))
        assertEquals(0 to TrendPeriod.D14, RankActions.perform(RankActions.SHOW_BEST, 1, TrendPeriod.D14))
        for ((i, p) in TrendPeriod.values().withIndex()) assertEquals(1 to p, RankActions.perform(RankActions.available(1)[i + 1].first, 1, TrendPeriod.D30))
    }

    @Test fun anActionNotOfferedOnThePageDoesNothing() {
        assertNull(RankActions.perform(RankActions.SHOW_BEST, 0, TrendPeriod.D30))
        assertNull(RankActions.perform(RankActions.SHOW_TREND, 1, TrendPeriod.D30))
        assertNull(RankActions.perform(RankActions.available(1)[1].first, 0, TrendPeriod.D30))
    }
}
