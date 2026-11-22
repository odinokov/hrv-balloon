package com.hrvballoon.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoherenceTrendTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = LocalDate.of(2026, 11, 22).atTime(12, 0).atZone(berlin).toInstant().toEpochMilli()

    private fun at(d: LocalDate, h: Int, m: Int, c: Double?, valid: Long = 120_000, zone: ZoneId = berlin) =
        TrendPoint(d.atTime(h, m).atZone(zone).toInstant().toEpochMilli(), valid, c)

    private fun ago(n: Int, c: Double? = 1.0, valid: Long = 120_000) =
        at(LocalDate.of(2026, 11, 22).minusDays(n.toLong()), 10, 0, c, valid)

    private fun trend(p: List<TrendPoint>, period: TrendPeriod = TrendPeriod.D7, t: Long = now, z: ZoneId = berlin) =
        coherenceTrend(p, t, z, period)

    private fun Trend.values() = bins.map { it.value }

    @Test fun midnightInTheGivenZoneSplitsTheDayWhereUtcWouldNot() {
        val d = LocalDate.of(2026, 11, 20)
        val t = trend(listOf(at(d, 23, 30, 2.0), at(d.plusDays(1), 0, 30, 4.0)))
        assertEquals(listOf(null, null, null, null, 2.0, 4.0, null), t.values())
        assertEquals(LocalDate.of(2026, 11, 16), t.bins.first().start)
    }

    @Test fun aDstChangeKeepsWholeLocalDays() {
        val now = LocalDate.of(2026, 3, 30).atTime(10, 0).atZone(berlin).toInstant().toEpochMilli()
        val t = trend(
            listOf(at(LocalDate.of(2026, 3, 1), 0, 10, 3.0), at(LocalDate.of(2026, 3, 29), 23, 30, 5.0), at(LocalDate.of(2026, 3, 30), 0, 10, 7.0)),
            TrendPeriod.D30, now,
        )
        assertEquals(LocalDate.of(2026, 3, 1), t.bins.first().start)
        assertEquals(listOf(3.0, 5.0, 7.0), t.values().filterNotNull())
        assertEquals(7.0, t.values().last()!!, 0.0)
        assertEquals(5.0, t.values()[28]!!, 0.0)
    }

    @Test fun severalSessionsInADayGiveTheirMedian() {
        val odd = trend(listOf(ago(0, 1.0), ago(0, 9.0), ago(0, 2.0))).values().last()
        val even = trend(listOf(ago(0, 1.0), ago(0, 2.0), ago(0, 4.0), ago(0, 9.0))).values().last()
        assertEquals(2.0, odd!!, 0.0)
        assertEquals(3.0, even!!, 0.0)
    }

    @Test fun shortOrValuelessSessionsAreIgnoredAndTheBoundaryCounts() {
        val t = trend(listOf(ago(0, 5.0, 119_999), ago(1, null), ago(2, 3.0, 120_000)))
        assertEquals(listOf(null, null, null, null, 3.0, null, null), t.values())
        assertEquals(1, t.daysPractised)
    }

    @Test fun gapsStayNullNeverZero() {
        val t = trend(listOf(ago(0, 2.0), ago(6, 4.0)))
        assertEquals(listOf(4.0, null, null, null, null, null, 2.0), t.values())
    }

    @Test fun todayIsIncludedAndDayNPlusOneIsNot() {
        val t = trend(listOf(ago(0, 1.0), ago(6, 2.0), ago(7, 9.0), ago(-1, 9.0)))
        assertEquals(7, t.bins.size)
        assertEquals(2.0, t.values().first()!!, 0.0)
        assertEquals(1.0, t.values().last()!!, 0.0)
        assertEquals(1.5, t.median!!, 0.0)
    }

    @Test fun theYearIsFiftyTwoWeeklyMediansOfTheDailyValues() {
        val t = trend(listOf(ago(0, 1.0), ago(1, 3.0), ago(2, 8.0), ago(7, 5.0), ago(363, 6.0), ago(364, 9.0)), TrendPeriod.Y1)
        assertEquals(52, t.bins.size)
        assertEquals(LocalDate.of(2026, 11, 22).minusDays(363), t.bins.first().start)
        assertEquals(6.0, t.bins.first().value!!, 0.0)
        assertEquals(3.0, t.bins.last().value!!, 0.0)
        assertEquals(5.0, t.bins[50].value!!, 0.0)
        assertNull(t.bins[49].value)
        assertEquals(5, t.daysPractised)
        assertEquals(5.0, t.median!!, 0.0)
    }

    private fun run(period: TrendPeriod, now: Int, before: Int): Trend {
        val cur = (0 until now).map { ago(it, 4.0) }
        val prev = (0 until before).map { ago(period.days + it, 2.0) }
        return trend(cur + prev, period)
    }

    @Test fun theComparisonNeedsBothPeriodsAtTheThreshold() {
        for ((p, min) in listOf(TrendPeriod.D7 to 3, TrendPeriod.D14 to 4, TrendPeriod.D30 to 8)) {
            assertEquals("$p both at", 2.0, run(p, min, min).previousMedian!!, 0.0)
            assertNull("$p previous below", run(p, min, min - 1).previousMedian)
            assertNull("$p current below", run(p, min - 1, min).previousMedian)
        }
    }

    @Test fun theYearHasNoComparisonAndIgnoresPointsOlderThanItsWindow() {
        val t = trend((0 until 730).map { ago(it, if (it < 364) 4.0 else 99.0) }, TrendPeriod.Y1)
        assertNull(t.previousMedian)
        assertEquals(364, t.daysPractised)
        assertEquals(4.0, t.median!!, 0.0)
    }

    @Test fun thePreviousPeriodIsTheEqualLengthWindowRightBefore() {
        val t = trend((0 until 3).map { ago(it, 4.0) } + (7 until 10).map { ago(it, 2.0) } + listOf(ago(10, 99.0)))
        assertEquals(2.0, t.previousMedian!!, 0.0)
    }

    @Test fun thePreviousWindowEdgesAreItsFirstAndLastDay() {
        val cur = listOf(ago(0, 4.0), ago(1, 4.0), ago(6, 0.5))
        val inside = listOf(ago(7, 1.0), ago(10, 5.0), ago(13, 9.0))
        assertEquals(5.0, trend(cur + inside + ago(14, 100.0)).previousMedian!!, 0.0)
    }

    @Test fun theYearHeadlineIsTheMedianOfDailyValuesNotOfWeeklyBins() {
        val t = trend(listOf(ago(0, 10.0)) + (7..11).map { ago(it, 1.0) }, TrendPeriod.Y1)
        assertEquals(1.0, t.median!!, 0.0)
        assertEquals(6, t.daysPractised)
    }

    @Test fun severalSessionsOnADayCountOnceForTheHeadlineAndThePreviousMedian() {
        fun day(n: Int, vararg c: Double) = c.map { ago(n, it) }
        val t = trend(day(0, 9.0, 9.0, 9.0) + day(1, 1.0) + day(2, 2.0) + day(7, 9.0, 9.0, 9.0) + day(8, 1.0) + day(9, 2.0))
        assertEquals(2.0, t.median!!, 0.0)
        assertEquals(2.0, t.previousMedian!!, 0.0)
    }

    @Test fun nowExactlyAtLocalMidnightIsTheNewDay() {
        val midnight = LocalDate.of(2026, 11, 22).atStartOfDay(berlin).toInstant().toEpochMilli()
        val t = trend(listOf(TrendPoint(midnight - 1, 120_000, 2.0), TrendPoint(midnight, 120_000, 6.0)), t = midnight)
        assertEquals(LocalDate.of(2026, 11, 22), t.bins.last().start)
        assertEquals(listOf(2.0, 6.0), t.values().takeLast(2))
    }

    @Test fun noPeriodReadsAPointOlderThan365Days() {
        val recent = (0 until 365).map { ago(it, 4.0) }
        val old = (365..800).map { ago(it, 999.0) }
        for (p in TrendPeriod.values()) {
            val a = trend(recent, p)
            val b = trend(recent + old, p)
            assertEquals("$p bins", a.values(), b.values())
            assertEquals("$p median", a.median, b.median)
            assertEquals("$p days", a.daysPractised, b.daysPractised)
            assertEquals("$p previous", a.previousMedian, b.previousMedian)
            assertEquals("$p rolling", a.rolling, b.rolling)
        }
    }

    @Test fun rollingIsTheMedianOfTheValuedDaysInTheTrailingWeekOnlyFor30D() {
        val p = listOf(ago(0, 8.0), ago(2, 2.0), ago(3, 6.0), ago(7, 100.0), ago(35, 50.0))
        val t = trend(p, TrendPeriod.D30)
        val r = t.rolling!!
        assertEquals(30, r.size)
        assertEquals(6.0, r.last()!!, 0.0)
        assertEquals(6.0, r[27]!!, 0.0)
        assertEquals(50.0, r[0]!!, 0.0)
        assertEquals(100.0, r[29 - 7]!!, 0.0)
        assertNull(trend(p, TrendPeriod.D14).rolling)
    }

    @Test fun rollingReachesBeforeTheWindowStart() {
        val t = trend(listOf(ago(31, 6.0)), TrendPeriod.D30)
        assertEquals(6.0, t.rolling!!.first()!!, 0.0)
    }

    @Test fun emptyInputGivesNullsAndAZeroCount() {
        val t = trend(emptyList(), TrendPeriod.D30)
        assertEquals(30, t.bins.size)
        assertNull(t.median)
        assertEquals(0, t.daysPractised)
        assertNull(t.previousMedian)
        assertEquals(List(30) { null }, t.rolling)
    }

    @Test fun aOneDayInputHasThatDayAsTheMedian() {
        val t = trend(listOf(ago(3, 7.0)), TrendPeriod.D14)
        assertEquals(7.0, t.median!!, 0.0)
        assertEquals(1, t.daysPractised)
        assertNull(t.previousMedian)
    }

    @Test fun theZoneIsHonouredForToday() {
        val utc = ZoneOffset.UTC
        val late = LocalDate.of(2026, 11, 22).atTime(23, 30).atZone(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        val t = trend(listOf(TrendPoint(late, 120_000, 3.0)), t = late, z = utc)
        assertEquals(LocalDate.of(2026, 11, 23), t.bins.last().start)
        assertEquals(3.0, t.values().last()!!, 0.0)
    }
}
