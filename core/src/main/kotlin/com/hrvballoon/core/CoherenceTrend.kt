package com.hrvballoon.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One stored session as the trend sees it; the caller passes the store's de-duplicated records. */
class TrendPoint(val startMs: Long, val validMs: Long, val coherence: Double?)

/** [days] is the window read (the 1Y window is 52 whole weeks = 364 days, inside the 365-day limit); [minDays] gates the previous-period comparison, null = none (1Y). */
enum class TrendPeriod(val days: Int, val minDays: Int?) { D7(7, 3), D14(14, 4), D30(30, 8), Y1(364, null) }

/** A day (7D/14D/30D) or a 7-day week (1Y) starting at [start]; null value = no practice. */
class TrendBin(val start: LocalDate, val value: Double?)

class Trend(
    val bins: List<TrendBin>,
    val median: Double?,
    val daysPractised: Int,
    val previousMedian: Double?,
    /** 30D only: per day, median of the days with values in the trailing 7 days. */
    val rolling: List<Double?>?,
)

private const val MIN_VALID_MS = 120_000L
private const val WEEK = 7

private fun median(v: List<Double>): Double? {
    if (v.isEmpty()) return null
    val s = v.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
}

fun coherenceTrend(points: List<TrendPoint>, nowMs: Long, zone: ZoneId, period: TrendPeriod): Trend {
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val daily = points.filter { it.validMs >= MIN_VALID_MS && it.coherence != null }
        .groupBy { day(it.startMs) }
        .mapValues { median(it.value.map { p -> p.coherence!! })!! }
    val today = day(nowMs)
    fun window(end: LocalDate) = (period.days - 1 downTo 0).map { end.minusDays(it.toLong()) }
    val days = window(today)
    val values = days.map { daily[it] }
    val practised = values.count { it != null }
    val previous = window(today.minusDays(period.days.toLong())).mapNotNull { daily[it] }
    val bins = if (period == TrendPeriod.Y1) {
        days.zip(values).chunked(WEEK).map { w -> TrendBin(w.first().first, median(w.mapNotNull { it.second })) }
    } else {
        days.zip(values).map { TrendBin(it.first, it.second) }
    }
    val rolling = if (period == TrendPeriod.D30) {
        days.map { d -> median((0 until WEEK).mapNotNull { daily[d.minusDays(it.toLong())] }) }
    } else null
    return Trend(
        bins, median(values.filterNotNull()), practised,
        if (period.minDays != null && practised >= period.minDays && previous.size >= period.minDays) median(previous) else null,
        rolling,
    )
}
