package com.hrvballoon.core

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HRV Balloon 2.10, engine lane A: the 60 s warm-up boundary T (spec sections 2, 3, 4.1, 5, 6, 8.2, 9). Expectations come
 * from the spec text, quoted by section. Fixtures are built from sensor units (1/1024 s) so beat ends are exact: a beat of
 * 1024 units ends exactly 1000 ms after the previous one, and with zero latency tEnd equals the arrival.
 */
class SessionEngineWarmupTest {
    private class Pkt(val at: Long, val units: IntArray)

    private class Run(val states: List<UiState>, val csv: List<String>, val summary: Summary) {
        fun at(tMs: Long) = states.first { it.tMs == tMs }
        private fun events() = csv.filter { it.startsWith("event,") }.map { it.split(",") }
        fun times(name: String) = events().filter { it[10] == name }.map { it[1].toDouble() }
        fun beatRows() = csv.filter { it.startsWith("beat,") }.map { it.split(",") }
    }

    /** One packet per beat. Beat j ends [firstEnd] + (units up to j, less the first beat's) ms; it arrives [lag] ms after that. */
    private fun packets(units: List<Int>, firstEnd: Double = 0.0, lag: (Int) -> Long = { 0 }): List<Pkt> {
        var cum = 0L
        return units.mapIndexed { j, u ->
            cum += u
            Pkt(Math.round(firstEnd + (cum - units[0]) * 1000.0 / 1024) + lag(j), intArrayOf(u))
        }
    }

    private fun unitsOf(rr: DoubleArray): List<Int> {
        var cum = 0.0
        var prev = 0L
        return rr.map { cum += it; val c = Math.round(cum * 1.024); val u = (c - prev).toInt(); prev = c; u }
    }

    private fun synthPackets(s: SynthBeats) = packets(unitsOf(s.rrMs), s.t0Ms + s.rrMs[0])

    /** Breathing at 5.5 /min in whole multiples of 128 units: every beat end is an exact millisecond, no rounding anywhere. */
    private fun exactSine(n: Int): List<Int> {
        var t = 0.0
        return List(n) {
            val u = 1024 + 128 * Math.round(sin(2 * PI * t / (60_000 / 5.5))).toInt()
            t += u * 1000.0 / 1024
            u
        }
    }

    private fun steady(n: Int, units: Int = 1024) = List(n) { units }

    private fun play(
        pkts: List<Pkt>, stopAt: Long, durationMs: Long? = null, rfSearch: Boolean = false, rate: Double = 5.5,
        pause: LongRange? = null, contact: (Long) -> Contact = { Contact.DETECTED },
    ): Run {
        val out = ByteArrayOutputStream()
        val csv = SessionCsvWriter(out)
        val engine = SessionEngine(SessionConfig(rate, 0.4, durationMs, 0.0, 15.0, true, rfSearch), csv)
        val states = ArrayList<UiState>()
        var bi = 0
        var t = 0L
        while (t <= stopAt) {
            if (pause != null && t == pause.first) engine.pause(t)
            if (pause != null && t == pause.last) engine.resume(t)
            while (bi < pkts.size && pkts[bi].at <= t) {
                val p = pkts[bi]
                engine.onPacket(p.at, HrsPacket((60000 / (p.units.sum() * 1000.0 / 1024 / p.units.size)).roundToInt(), contact(p.at), p.units))
                bi++
            }
            states += engine.tick(t)
            t += 250
        }
        val summary = engine.stop(t)
        return Run(states, out.toString("UTF-8").lines().filter { it.isNotEmpty() && !it.startsWith("#") }, summary)
    }

    private fun ticks(r: Run, from: Double, to: Double) = r.states.filter { it.tMs >= from && it.tMs <= to }

    // ---- 9.1.1, 9.1.2, 9.4.1: the boundary, exactly, and what it keeps out of the summary ----

    @Test fun `9_4_1 and 9_1_2 RR of 500 ms before T and 1000 ms after - mean HR 60, RMSSD and SDNN 0, beat count whole`() {
        // t0 = 0 (the first beat ends at 0), T = 60 000. 121 beats of 500 ms end at 0..60 000; then 1000 ms beats: the first spans
        // 60.0-61.0 s, tEnd - rr = T exactly, and is game (9.1.2). The beat spanning 59.5-60.0 s is not.
        val r = play(packets(List(121) { 512 } + steady(60)), stopAt = 125_000)
        assertEquals(181, r.summary.beats)   // 6.4: the warm-up beats count
        assertEquals(60.0, r.summary.meanHr!!, 1e-9)
        assertEquals(0.0, r.summary.rmssd!!, 1e-9)
        assertEquals(0.0, r.summary.sdnn!!, 1e-9)
        assertEquals(0.0, r.summary.rejectedPct!!, 0.0)
        assertEquals(listOf(0.0), r.times("warmup_start"))
        assertEquals(listOf(60_000.0), r.times("warmup_end"))
    }

    @Test fun `9_1_1 the RR spanning 59_6 to 60_4 s is in the CSV and not game, the next one from 60_4 to 61_2 s is game`() {
        // units: the first beat ends at 0; 57 beats of 1000 ms; three of ~866 ms put a beat start at 59 599.6 ms; the straddling beat
        // (819 units = 799.8 ms) ends at 60 399.4 ms; the next (820 units = 800.8 ms) spans 60 399.4-61 200.2 ms; then 1000 ms beats.
        val units = steady(58) + listOf(887, 887, 888, 819, 820) + steady(100)
        val r = play(packets(units), stopAt = 170_000)
        val straddle = r.beatRows().single { it[3] == "799.8" }
        assertEquals(60_399.4, straddle[1].toDouble(), 1.0)
        assertEquals("1", straddle[4])
        val game = (listOf(820) + steady(100)).map { it * 1000.0 / 1024 }
        assertEquals(60000 / game.average(), r.summary.meanHr!!, 1e-9)   // only the RR from 60.4 s on
        assertEquals(units.size, r.summary.beats)
    }

    // ---- 9.1.3: one packet or several, the same game RR ----

    @Test fun `9_1_3 split delivery and one batch packet with the same tEndMs give the same game RR, estimates and summary`() {
        val units = exactSine(200)
        val split = packets(units)
        // beats 58..65 (ends 58..65 s, around T = 60 s) in packets of two, each arriving when its last beat ends
        val batched = split.filterIndexed { j, _ -> j !in 58..65 || j % 2 == 1 }.map { p ->
            val j = split.indexOf(p)
            if (j in 58..65) Pkt(p.at, intArrayOf(units[j - 1], units[j])) else p
        }
        val a = play(split, stopAt = 190_000)
        val b = play(batched, stopAt = 190_000)
        assertEquals(a.summary.beats, b.summary.beats)
        assertEquals(a.summary.meanHr!!, b.summary.meanHr!!, 1e-12)
        assertEquals(a.summary.rmssd!!, b.summary.rmssd!!, 1e-12)
        assertEquals(a.summary.sdnn!!, b.summary.sdnn!!, 1e-12)
        assertEquals(a.beatRows().map { it[1] to it[3] }, b.beatRows().map { it[1] to it[3] })   // the same tEnd and rr rows
        val pa = ticks(a, 130_000.0, 190_000.0).map { it.p2tBpm }
        assertTrue(pa.all { it != null })
        assertEquals(pa, ticks(b, 130_000.0, 190_000.0).map { it.p2tBpm })
    }

    // ---- 9.1.4: nothing before T ----

    private val clean = synthBeats(60.0, 10.0, 5.5, 330.0)

    @Test fun `9_1_4 before T no estimate, the balloon at its initial value, a countdown to T, the first estimate after T plus two periods`() {
        val pk = synthPackets(clean)
        val t0 = pk[0].at.toDouble()
        val t = t0 + 60_000
        val period = 60_000 / 5.5
        val r = play(pk, stopAt = 200_000, durationMs = 300_000)
        val before = r.states.filter { it.tMs < t }
        assertTrue(before.all { it.p2tBpm == null && it.shape == null })
        assertTrue(before.all { it.balloonTarget == r.states.first().balloonTarget })
        assertEquals(60_000L, r.at(1_000).boundaryRemainingMs)   // t0 not known yet: the first beat is classified two beats later
        assertTrue(before.filter { it.tMs > t0 + 3_000 }.all { it.boundaryRemainingMs == ceil(t - it.tMs).toLong() && it.boundaryRemainingMs > 0 })
        assertTrue(r.states.filter { it.tMs >= t }.all { it.boundaryRemainingMs == 0L })
        val first = r.states.first { it.p2tBpm != null }.tMs
        assertTrue("first estimate at $first", first >= t + 2 * period && first <= t + 3 * period)
        assertTrue(r.states.last { it.tMs <= 200_000 }.balloonTarget != before.last().balloonTarget)
        assertEquals(2 * 60_000 / 5.5, r.at(5_000).warmupRemainingMs.toDouble(), 1.0)   // 2.9 meaning kept: the two-period window
    }

    @Test fun `3_5 the countdown is rounded up and is 0 only once T is reached`() {
        // beats of 1000 units (976.56 ms): t0 after a pause is a fractional ms
        val pk = packets(steady(150, 1000))
        val r = play(pk, stopAt = 160_000, pause = 20_000L..23_000L)
        val t = r.times("warmup_start")[1] + 60_000
        val frac = t - Math.floor(t)
        assertTrue("T=$t is not in 0.1..0.45 of a ms: round() and ceil() must differ here", frac in 0.09..0.45)
        for (s in ticks(r, t - 1_000, t + 500)) assertEquals("at ${s.tMs}", if (s.tMs >= t) 0L else ceil(t - s.tMs).toLong(), s.boundaryRemainingMs)
    }

    // ---- 9.1.5: pause ----

    /** Lag 500 ms on every beat but each tenth (the lowest-latency envelope): tEnd = arrival - 500, arrival = tEnd + 500. */
    private val lagged: (Int) -> Long = { if (it % 10 == 0) 0 else 500 }

    @Test fun `9_1_5 pause 40 to 45 s - the new T counts from the first usable RR after the return, two warmup_start and one warmup_end`() {
        // beats end at 700 + 1000 j ms and arrive 500 ms later; beats keep arriving through the pause. Those that arrived before the
        // pause (ends 37.7, 38.7 s...) are classified after it: they must not set the new t0.
        val r = play(packets(steady(200), firstEnd = 700.0, lag = lagged), stopAt = 150_000, pause = 40_000L..45_000L)
        // the first beat arriving at or after the return (45 s) is the one ending at 44.7 s (arrival 45.2 s): its end is t0
        assertEquals(listOf(700.0, 44_700.0), r.times("warmup_start"))
        assertEquals(listOf(104_700.0), r.times("warmup_end"))
        assertEquals(60_000L, r.at(40_250).boundaryRemainingMs)   // while paused t0 is unknown
        assertTrue(r.at(104_500).boundaryRemainingMs > 0 && r.at(104_750).boundaryRemainingMs == 0L)
    }

    @Test fun `9_1_5 the arrival guard compares arrival, not tEnd, and keeps pre-pause beats out`() {
        // pause 44.8-44.9 s. The beat ending at 44.7 s arrives at 45.2 s, after the return, though its end precedes the pause:
        // a genuine post-return beat. The beats that arrived before the pause (up to 44.2 s) are classified after it and do not count.
        val r = play(packets(steady(200), firstEnd = 700.0, lag = lagged), stopAt = 120_000, pause = 44_750L..44_750L + 250)
        assertEquals(listOf(700.0, 44_700.0), r.times("warmup_start"))
    }

    // ---- 9.1.6: loss and confirmed gap before T ----

    @Test fun `9_1_6 signal loss before T restarts the count, beats keep arriving through the loss`() {
        // contact lost 30-35 s with RR flowing: LOST at 30 s, back at 35 s
        val r = play(packets(steady(200), firstEnd = 1000.0), stopAt = 160_000,
            contact = { if (it in 30_000L until 35_000L) Contact.NOT_DETECTED else Contact.DETECTED })
        assertEquals(Signal.LOST, r.at(31_000).signal)
        val starts = r.times("warmup_start")
        assertEquals(2, starts.size)
        assertEquals(1_000.0, starts[0], 0.0)
        assertTrue("the new t0 follows the event: ${starts[1]}", starts[1] >= 30_000.0 && starts[1] <= 36_000.0)
        assertEquals(listOf(starts[1] + 60_000), r.times("warmup_end"))
        assertEquals(60_000L, r.at(31_000).boundaryRemainingMs)
    }

    @Test fun `9_1_6 a confirmed gap of 1_5 to 3 s before T restarts the count and writes a gap event at the confirmation`() {
        // beats arrive each second from 1 s; the two beats of 40 s and 41 s are lost (a 2 s excess), the next arrives at 42 s and the
        // loss is decided by the packet of 43 s: the event moment is 43 s, not the gap start. The late beat (42 s) arrived before it.
        val pk = packets(steady(200), firstEnd = 1000.0).filter { it.at !in 40_000L..41_000L }
        val r = play(pk, stopAt = 160_000)
        assertTrue(r.states.all { it.signal == Signal.OK })
        assertEquals(listOf(43_000.0), r.times("gap"))
        assertEquals(listOf(1_000.0, 43_000.0), r.times("warmup_start"))
        assertEquals(listOf(103_000.0), r.times("warmup_end"))
    }

    // ---- 9.1.7: a reject and a delivery delay change nothing ----

    @Test fun `9_1_7 one rejected RR before T leaves T unchanged`() {
        val units = steady(150).toMutableList().also { it[20] = 1536 }   // 1500 ms among 1000 ms: rejected by the filter
        val r = play(packets(units, firstEnd = 1000.0), stopAt = 90_000)
        assertEquals(1, r.beatRows().count { it[4] == "0" })
        assertEquals(listOf(1_000.0), r.times("warmup_start"))
        assertEquals(listOf(61_000.0), r.times("warmup_end"))
    }

    @Test fun `9_1_7 a delivery delay with gapMs 0 before T leaves T unchanged`() {
        // 50 s of beats, then four queued behind a 2 s stall (true times kept, no loss), then on time
        val arrivals = (1..50).map { 1000L * it + 500 } + listOf(53_500L, 53_700L, 54_300L, 54_500L, 55_500L, 56_500L) + (57..80).map { 1000L * it + 500 }
        val r = play(arrivals.map { Pkt(it, intArrayOf(1024)) }, stopAt = 100_000)
        assertTrue(r.csv.none { it.endsWith(",gap,") })
        assertEquals(1, r.times("warmup_start").size)
        val t0 = r.times("warmup_start")[0]
        assertEquals(listOf(t0 + 60_000), r.times("warmup_end"))
    }

    // ---- 9.1.8: events at or after T ----

    private val sine = synthBeats(60.0, 10.0, 5.5, 330.0)

    @Test fun `9_1_8 signal loss after T - no new warm-up, the estimate returns by the 2_9 rules, one warmup_end`() {
        val pk = synthPackets(sine)
        val t0 = pk[0].at.toDouble()
        val r = play(pk, stopAt = 200_000, contact = { if (it in 100_000L until 110_000L) Contact.NOT_DETECTED else Contact.DETECTED })
        assertEquals(Signal.LOST, r.at(105_000).signal)
        val back = r.states.first { it.tMs >= 110_000 && it.signal == Signal.OK }
        assertTrue(back.warmingUp)   // the estimator window starts over, as in 2.9
        assertEquals(0L, back.boundaryRemainingMs)
        assertFalse(r.at(back.tMs + 30_000).warmingUp)
        assertNotNull(r.at(back.tMs + 30_000).p2tBpm)
        assertEquals(listOf(t0), r.times("warmup_start"))
        assertEquals(listOf(t0 + 60_000), r.times("warmup_end"))
    }

    @Test fun `9_1_8 a gap that starts before T and is confirmed after T resets nothing`() {
        // beats arrive each second from 1 s (t0 = 1 s, T = 61 s); the beats of 59 s and 60 s are lost, the late one arrives at 61 s and
        // the packet of 62 s confirms the gap: the event moment 62 s is after T (3.3 item 4). The gap start, 58-59 s, is before it.
        val pk = packets(steady(200), firstEnd = 1000.0).filter { it.at !in 59_000L..60_000L }
        val r = play(pk, stopAt = 160_000)
        assertEquals(listOf(62_000.0), r.times("gap"))
        assertEquals(listOf(1_000.0), r.times("warmup_start"))
        assertEquals(listOf(61_000.0), r.times("warmup_end"))
        assertEquals(0L, r.at(62_250).boundaryRemainingMs)
    }

    // ---- 3.2: Kalman and the trace take every usable RR, the estimate only game RR ----

    @Test fun `3_2 pre-T RR reach the Kalman filter and the trace but not the P2T estimate`() {
        val base = exactSine(200)
        val changed = base.toMutableList()
        for (j in 40..55 step 2) { val x = changed[j]; changed[j] = changed[j + 1]; changed[j + 1] = x }   // same sum: later beat ends are unchanged
        assertNotEquals(base, changed)
        val a = play(packets(base), stopAt = 150_000)
        val b = play(packets(changed), stopAt = 150_000)
        assertTrue(a.at(40_000).traceT.isNotEmpty())   // the trace holds usable beats before T
        val fa = a.at(60_000).forecast
        val fb = b.at(60_000).forecast
        assertTrue(fa != null && fb != null)   // the Kalman filter has seen the pre-T beats
        assertTrue(Math.abs(fa!!.at(3.0) - fb!!.at(3.0)) > 1e-6)
        val pa = ticks(a, 100_000.0, 150_000.0).map { it.p2tBpm }
        assertTrue(pa.all { it != null })
        assertEquals(pa, ticks(b, 100_000.0, 150_000.0).map { it.p2tBpm })   // the estimate sees only game RR: identical
    }

    // ---- 9.2.2: training duration ----

    @Test fun `9_2_2 a 15 minute training on a continuous signal has about 815 s of live estimate`() {
        val s = synthBeats(60.0, 10.0, 5.5, 930.0)
        val r = play(synthPackets(s), stopAt = 900_000, durationMs = 900_000)
        assertTrue("valid=${r.summary.validMs}", r.summary.validMs in 805_000..825_000)
    }

    // ---- 9.6.3: stop at 45 s ----

    @Test fun `9_6_3 stopping at 45 s - a full CSV with warmup_start and no warmup_end, no stars, validMs 0, HRV values null`() {
        val pk = synthPackets(sine)
        val r = play(pk, stopAt = 45_000, durationMs = 900_000)
        assertEquals(1, r.times("warmup_start").size)
        assertTrue(r.times("warmup_end").isEmpty())
        assertTrue(r.csv.none { it.endsWith(",star,") })
        assertEquals(0L, r.summary.validMs)
        assertNull(r.summary.coherence); assertNull(r.summary.meanHr); assertNull(r.summary.rmssd); assertNull(r.summary.sdnn)
        assertNull(r.summary.meanP)
        assertEquals(pk.count { it.at <= 45_000 }, r.beatRows().size)   // every measured RR has a row
    }

    // ---- 9.2.3 and 4.1: the search starts at T ----

    private val longSine = synthBeats(60.0, 10.0, 5.5, 460.0)

    private fun changes(r: Run) = r.states.zipWithNext().filter { (a, b) -> a.rateBpm != b.rateBpm }.map { it.second.tMs to it.second.rateBpm }

    /** First inhale start of the 5.5 pacer (every period from 0) at or after [t] - 1 ms: where a change scheduled in the last breath lands. */
    private fun inhaleAtOrAfter(t: Double, period: Double) = ceil((t - 1) / period) * period

    @Test fun `9_2_3 search - first estimate between T plus 2 and 3 periods, first probe ends 8 periods after T, later probes last 9 periods`() {
        val pk = synthPackets(longSine)
        val t = pk[0].at + 60_000.0
        val p = 60_000 / 5.5
        val r = play(pk, stopAt = 400_000, durationMs = 1_200_000, rfSearch = true)
        val first = r.states.first { it.p2tBpm != null }.tMs
        assertTrue("first estimate at $first, T=$t", first >= t + 2 * p && first <= t + 3 * p)
        val c = changes(r)
        assertEquals(6.0, c[0].second, 1e-9)
        // 4.1: the first probe is counted from T - one period and ends 8 periods after T (not breath aligned): the rate changes at
        // the first inhale start at or after that moment; 5.5 /min until then
        val expected = inhaleAtOrAfter(t + 8 * p, p)
        assertTrue("first change at ${c[0].first}, expected $expected", c[0].first >= expected && c[0].first < expected + 250)
        assertTrue(r.states.filter { it.tMs < c[0].first }.all { it.rateBpm == 5.5 })
        assertEquals(5.0, c[1].second, 1e-9)
        val second = c[1].first - c[0].first
        assertTrue("probe 2 lasted $second", second in 90_000..90_250)   // nine breaths at 6.0 /min
    }

    @Test fun `4_1 a reset before T moves the first probe with T`() {
        val pk = synthPackets(longSine)
        val p = 60_000 / 5.5
        val r = play(pk, stopAt = 400_000, durationMs = 1_200_000, rfSearch = true, pause = 20_000L..23_000L)
        val t2 = r.times("warmup_start")[1] + 60_000
        val expected = inhaleAtOrAfter(t2 + 8 * p, p)
        val c = changes(r)
        assertTrue("first change at ${c[0].first}, expected $expected", c[0].first >= expected && c[0].first < expected + 250)
        assertTrue(r.states.filter { it.tMs < t2 }.all { it.rateBpm == 5.5 })
    }
}
