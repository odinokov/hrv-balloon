package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

class SessionEngineTest {
    private class Run(val states: List<UiState>, val csv: List<String>, val summary: Summary) {
        fun at(tMs: Long) = states.first { it.tMs == tMs }
        fun rows() = csv.filter { !it.startsWith("#") }
    }

    private fun packet(rr: Double, contact: Contact = Contact.DETECTED) =
        HrsPacket(if (rr > 0) (60000 / rr).roundToInt() else 0, contact, intArrayOf((rr * 1024 / 1000).roundToInt()))

    /** Clock-driven harness: packets arrive 500 ms after their beat ends, ticks every 250 ms, header written by the test. */
    private fun run(
        s: SynthBeats, rate: Double = 5.5, durationMs: Long? = 300_000, stopAtMs: Long = Long.MAX_VALUE,
        suppress: (Long) -> Boolean = { false }, link: Map<Long, Boolean> = emptyMap(),
        contact: (Long) -> Contact = { Contact.DETECTED }, shapeGate: Boolean = true, rfSearch: Boolean = false,
        lagMs: Long = 500, pause: LongRange? = null, floor: Double = 0.0, ceiling: Double = 15.0,
    ): Run {
        val out = ByteArrayOutputStream()
        val csv = SessionCsvWriter(out)
        csv.writeHeader(SessionMeta("0.1", "AA:BB", "strap", "2026-10-25T17:00:00+08:00", rate, 0.4, "5", 0.0, 15.0))
        val engine = SessionEngine(SessionConfig(rate, 0.4, durationMs, floor, ceiling, shapeGate, rfSearch), csv)
        val beats = s.beats(lagMs).filter { !suppress(it.arrivalMs) }
        var bi = 0
        val states = ArrayList<UiState>()
        var t = 0L
        while (true) {
            link[t]?.let { engine.onLink(it, t) }
            if (pause != null && t == pause.first) engine.pause(t)
            if (pause != null && t == pause.last) engine.resume(t)
            while (bi < beats.size && beats[bi].arrivalMs <= t) {
                engine.onPacket(beats[bi].arrivalMs, packet(beats[bi].rrMs, contact(beats[bi].arrivalMs)))
                bi++
            }
            val st = engine.tick(t)
            states += st
            if (st.finished || t >= stopAtMs) break
            t += 250
        }
        val summary = engine.stop(t)
        return Run(states, out.toString("UTF-8").lines().filter { it.isNotEmpty() }, summary)
    }

    private val clean = synthBeats(60.0, 10.0, 5.5, 330.0)

    /**
     * Spec 4.1 with the 2.9 inhale rule: t0 is the end of the first usable beat (its arrival: the first packet anchors the timeline), T =
     * t0 + 60 s, the first probe counts from T - one period and ends 8 periods after T, where the change takes the first inhale start
     * (every period from 0 while the pacer has not changed) at or after that moment.
     */
    private fun firstProbeEnd(s: SynthBeats, rate: Double = 5.5, lagMs: Long = 500): Double {
        val period = 60_000 / rate
        return ceil((s.beats(lagMs)[0].arrivalMs + 60_000.0 + 8 * period - 1) / period) * period
    }

    @Test fun theRateNeverChangesOutsideASearch() {
        assertTrue(run(clean, lagMs = 20).states.all { it.rateBpm == 5.5 })
    }

    @Test fun endToEnd() {
        val r = run(clean)
        assertTrue(r.at(15_000).warmingUp)
        assertTrue(r.at(40_000).warmingUp)   // 2.10 (spec 3.2): no estimate before T, about 62 s here, plus two periods
        assertFalse(r.at(100_000).warmingUp)
        val s = r.at(120_000)
        assertTrue("p2t=${s.p2tBpm}", s.p2tBpm!! in 8.8..10.2)
        assertTrue("balloon=${s.balloonTarget}", s.balloonTarget in 0.58..0.68)
        assertEquals(240_000L, r.at(60_000).remainingMs)
        assertTrue(r.states.last().finished && r.states.last().tMs >= 300_000)
        assertTrue("beats=${r.summary.beats}", r.summary.beats in 290..310)
        assertTrue(r.summary.rejectedPct!! <= 1.0)
        assertTrue(r.summary.meanP!! in 8.8..10.2)
    }

    @Test fun theBalloonTargetUsesTheConfiguredFloorAndCeiling() {
        val s = run(clean, shapeGate = false, floor = 3.0, ceiling = 12.0).at(120_000)
        assertEquals(balloonTarget(s.p2tBpm, 3.0, 12.0), s.balloonTarget, 1e-9)
        assertTrue(balloonTarget(s.p2tBpm, 0.0, 15.0) != s.balloonTarget)
    }

    @Test fun traceHoldsTheLastThirtySecondsOfAcceptedBeats() {
        val r = run(clean)
        val s = r.at(120_000)
        assertEquals(s.traceT.size, s.traceHr.size)
        assertTrue("n=${s.traceT.size}", s.traceT.size in 26..36)
        assertTrue(s.traceT.all { it > 90_000 && it <= 120_000 })
        assertTrue(s.traceHr.all { it in 40.0..100.0 })
        assertTrue(r.at(2_000).traceT.isEmpty())
    }

    @Test fun rhythmGateCutsLiftWhenBreathingOffPace() {
        val offPace = synthBeats(60.0, 20.0, 4.0, 150.0)
        fun meanLift(gate: Boolean) = run(offPace, durationMs = null, stopAtMs = 120_000, shapeGate = gate)
            .states.filter { it.tMs in 60_000..120_000 }.map { it.balloonTarget }.average()
        val gated = meanLift(true)
        val open = meanLift(false)
        assertTrue("gated=$gated open=$open", open > 0.3 && gated < 0.25 * open)

        val paced = run(clean).at(120_000)
        assertTrue("shape=${paced.shape}", paced.shape!! >= 0.95)
        assertEquals(run(clean, shapeGate = false).at(120_000).balloonTarget, paced.balloonTarget, 1e-6)
        assertTrue(run(clean).summary.meanShape!! >= 0.95)
    }

    @Test fun warmupCountdownAndValidTime() {
        val r = run(clean)
        assertEquals((2 * 60_000 / 5.5).toLong(), r.at(5_000).warmupRemainingMs)   // exact: spanMs is 0 at 5 s (filter lag and the first beat's 500 ms arrival lag)
        assertEquals(r.at(5_000).warmupRemainingMs, r.at(40_000).warmupRemainingMs)   // 3.5: the window of two periods fills with game RR only, so it waits for T
        assertTrue(r.at(75_000).warmupRemainingMs < r.at(5_000).warmupRemainingMs)
        assertEquals(0L, r.at(100_000).warmupRemainingMs)
        // 3.4: the first estimate comes at about 1:25 (T + two periods + filter lag), so of 300 s about 215 s are live
        assertTrue("valid=${r.summary.validMs}", r.summary.validMs in 205_000..225_000)
        assertEquals(0L, run(clean, stopAtMs = 3000, suppress = { true }).summary.validMs)
    }

    @Test fun resonanceSearchProbesRatesAtInhaleStarts() {
        val r = run(synthBeats(60.0, 10.0, 5.5, 900.0), durationMs = 900_000, rfSearch = true)
        val probes = r.states.mapNotNull { it.rfProbe }
        assertTrue(probes.isNotEmpty())
        val rates = probes.map { it.rateBpm }.distinct()
        assertTrue("rates=$rates", rates.containsAll(listOf(5.5, 6.0, 5.0)))   // the start, then half a breath either side
        assertTrue(rates.all { it in 4.0..7.0 })
        assertTrue(probes.last().index >= 5)
        assertTrue(r.summary.rfBest != null)
        assertTrue("probes=${r.summary.rfProbes.size}", r.summary.rfProbes.size >= 5)
        assertTrue(r.summary.rfProbes.all { it.first in 4.0..7.0 && it.second >= 0 })
        assertEquals(r.summary.rfConverged, probes.last().converged)
        assertNotNull(r.states.first().rfProbe)
        assertTrue(r.states.any { it.rfProbe?.settlingMs?.let { ms -> ms > 0 } == true })
        val rows = r.rows().filter { it.startsWith("beat,") }.map { it.split(",") }
        var prev = rows.first()[6]
        for (row in rows) {
            if (row[6] != prev) {
                assertTrue("rate changed at pacer phase ${row[7]}", row[7].toDouble() < 0.12)
                prev = row[6]
            }
        }
    }

    @Test fun pausedTimeIsExcludedFromTheStats() {
        val out = ByteArrayOutputStream()
        val csv = SessionCsvWriter(out)
        val engine = SessionEngine(SessionConfig(5.5, 0.4, 300_000, 0.0, 15.0, true, false), csv)
        val beats = clean.beats(500)
        var bi = 0
        var t = 0L
        while (t <= 260_000) {
            if (t == 100_000L) engine.pause(t)
            if (t == 140_000L) engine.resume(t)
            while (bi < beats.size && beats[bi].arrivalMs <= t) { engine.onPacket(beats[bi].arrivalMs, packet(beats[bi].rrMs)); bi++ }
            engine.tick(t)
            t += 250
        }
        val s = engine.stop(260_000)
        // 260 s − ~85 s to the first estimate (T + two periods) − 40 s paused (after T: no new warm-up) − ~24 s re-warm-up after resume (the window is cleared on purpose)
        assertTrue("valid=${s.validMs}", s.validMs in 100_000..120_000)
        val rows = out.toString("UTF-8").lines().filter { it.endsWith(",pause,") || it.endsWith(",resume,") }
        assertEquals(listOf("event,100000.0,,,,,,,,,pause,", "event,140000.0,,,,,,,,,resume,"), rows)
        assertTrue("summary beats=${s.beats}", s.beats in 210..230)   // the 40 s away stay out of the summary
        assertTrue(out.toString("UTF-8").lines().count { it.startsWith("beat,") } > 250)   // but reach the CSV
    }

    /**
     * Beats of RR 1000 + i ms (distinct, all accepted) arrive 1 s apart; the engine is paused/resumed between arrivals.
     * Each beat is longer than the second between arrivals, so the timeline stamps tEnd = arrival (i + 1) s; t0 = 1 s, T = 61 s and
     * beat i is game when 1000 (i + 1) − (1000 + i) >= 61 000: i >= [gameFrom] = 62 (spec 2). Pauses here come after T. The mean HR
     * returned is that of the booked game beats.
     */
    private fun markedRun(pauseAt: Long, resumeAt: Long?, stopAt: Long, gameFrom: Int = 62): Pair<Summary, Double> {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        val booked = ArrayList<Double>()
        for (i in 0 until (stopAt / 1000).toInt()) {
            val t = 1000L * (i + 1)
            if (pauseAt in t - 999 until t) engine.pause(pauseAt)
            if (resumeAt != null && resumeAt in t - 999 until t) engine.resume(resumeAt)
            val rr1024 = ((1000 + i) * 1024 / 1000.0).roundToInt()
            engine.onPacket(t, HrsPacket(60, Contact.DETECTED, intArrayOf(rr1024)))
            engine.tick(t)
            if (i >= gameFrom && (t < pauseAt || (resumeAt != null && t > resumeAt))) booked += rr1024 * 1000.0 / 1024
        }
        return engine.stop(stopAt) to 60000 / booked.average()
    }

    @Test fun beatsAreBookedByTheirStateAtArrivalAtThePauseEdge() {
        val (s, meanHr) = markedRun(pauseAt = 80_500, resumeAt = null, stopAt = 90_000)   // beats 0..79 arrived before the pause; 80..89 during
        assertEquals(80, s.beats)
        assertEquals(meanHr, s.meanHr!!, 1e-9)
    }

    @Test fun beatsAreBookedByTheirStateAtArrivalAtTheResumeEdge() {
        val (s, meanHr) = markedRun(pauseAt = 80_500, resumeAt = 100_500, stopAt = 130_000)   // 80..99 arrived paused, 100..129 after resume
        assertEquals(110, s.beats)
        assertEquals(meanHr, s.meanHr!!, 1e-9)
    }

    @Test fun aGapBetweenOneAndThreeSecondsBreaksTheRmssdChain() {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        // 70 beats of 1000 ms (game from 62 s: T = 61 s), two beats lost (arrival 3 s apart, re-anchored by 1.9 s: a gap after T, not LOST,
        // no new warm-up), then 30 beats of 1100 ms
        val arrivals = (1..70).map { 1000L * it to 1000.0 } + (0 until 30).map { 73_000L + 1100L * it to 1100.0 }
        for ((t, rr) in arrivals) { engine.onPacket(t, packet(rr)); engine.tick(t) }
        val s = engine.stop(arrivals.last().first)
        assertEquals(100, s.beats)
        assertEquals(0.0, s.rmssd!!, 1e-9)   // every successive difference lies inside a constant stretch; the 100 ms step spans the gap
    }

    /** One-beat packets of [units]/1024 s each, a tick after every one; the state after the packet at each arrival. */
    private fun statesAfter(arrivals: List<Pair<Long, Int>>): Map<Long, UiState> {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        return arrivals.associate { (t, units) -> engine.onPacket(t, HrsPacket(60, Contact.DETECTED, intArrayOf(units))); t to engine.tick(t) }
    }

    @Test fun aConfirmedLossOfOneToThreeSecondsRestartsTheWarmup() {
        // beats of 1000 ms to 100 s (T = 61 s, an estimate from about 85 s), two lost (re-anchored by 2 s: a loss after T, not LOST: no new
        // 60 s warm-up, the 2.9 restart of the window), then beats of 1100 ms; the loss is decided at 104.1 s
        val st = statesAfter((1..100).map { 1000L * it to 1024 } + (0 until 30).map { 103_000L + 1100 * it to 1126 })
        assertFalse(st.getValue(100_000).warmingUp)
        assertEquals(Signal.OK, st.getValue(104_100).signal)
        assertTrue(st.getValue(104_100).warmingUp)
        assertEquals((120_000 / 5.5).toLong(), st.getValue(104_100).warmupRemainingMs)
        assertEquals(0L, st.getValue(104_100).boundaryRemainingMs)
        assertFalse(st.getValue(104_100).traceT.any { it == 99_500.0 })   // beat 100, classified at the deciding packet, arrived before the loss
        assertNull(st.getValue(104_100).forecast)                      // the Kalman filter starts over too
    }

    @Test fun aConfirmedShortLossMidProbeKeepsTheProbe() {
        // Probe 1 at 5.5 /min is scored from T + 2 periods (about 84 s) to its end at about 153 s; a 2 s loss at 110 s (after T: the 2.9 restart)
        // restarts the estimator window (scoring resumes near 136 s, 13 s before the end: under three breaths) but not the scores
        val r = run(clean, durationMs = 900_000, stopAtMs = 165_000, rfSearch = true, suppress = { it in 110_000L..111_900L })
        assertTrue(r.states.all { it.signal == Signal.OK })
        assertTrue(r.at(120_000).warmingUp)
        assertEquals(2, r.at(160_000).rfProbe!!.index)
    }

    @Test fun aDelayKeepsTheWarmup() {
        // 100 s of beats (an estimate from about 86 s), then four queued behind a 2 s stall (true times kept, no loss), then on time
        val st = statesAfter((1..100).map { 1000L * it + 500 to 1024 } + listOf(103_500L, 103_700L, 104_300L, 104_500L, 105_500L, 106_500L).map { it to 1024 })
        assertTrue(st.filterKeys { it >= 100_500 }.values.none { it.warmingUp })
    }

    @Test fun beatsArrivedWhilePausedAndClassifiedAfterResumeStayOutOfTheMetricPath() {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        fun hr(i: Int) = 60000 / (((1000 + i) * 1024 / 1000.0).roundToInt() * 1000.0 / 1024)
        var st: UiState? = null
        for (i in 0 until 26) {   // beats 20 and 21 arrive paused (21 s, 22 s); 20 is classified at 23 s, 21 at 24 s, after the resume at 22.5 s
            val t = 1000L * (i + 1)
            if (t == 21_000L) engine.pause(20_500)
            if (t == 23_000L) engine.resume(22_500)
            engine.onPacket(t, HrsPacket(60, Contact.DETECTED, intArrayOf(((1000 + i) * 1024 / 1000.0).roundToInt())))
            st = engine.tick(t)
        }
        val trace = st!!.traceHr.toList()   // the trace holds exactly the beats that fed the estimator and the Kalman filter
        assertTrue(trace.any { Math.abs(it - hr(19)) < 1e-9 } && trace.any { Math.abs(it - hr(22)) < 1e-9 })
        assertTrue(trace.none { Math.abs(it - hr(20)) < 1e-9 || Math.abs(it - hr(21)) < 1e-9 })
    }

    @Test fun beatsArrivedBeforeAPauseAndClassifiedAfterResumeStayOutOfTheMetricPathButAreBooked() {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        fun rr(i: Int) = ((1000 + i) * 1024 / 1000.0).roundToInt() * 1000.0 / 1024
        var st: UiState? = null
        // Beats arrive 1 s apart, tEnd = arrival (beats longer than a second), t0 = 1 s, T = 61 s: beat i is game for i >= 62 (spec 2).
        // The pause 81.5-81.9 s comes after T (no new warm-up), no beat arrives paused; beats 79 (80 s) and 80 (81 s) arrived before
        // it and are classified at 82 s and 83 s, after the resume.
        for (i in 0 until 86) {
            val t = 1000L * (i + 1)
            if (t == 82_000L) { engine.pause(81_500); engine.resume(81_900) }
            engine.onPacket(t, HrsPacket(60, Contact.DETECTED, intArrayOf(((1000 + i) * 1024 / 1000.0).roundToInt())))
            st = engine.tick(t)
        }
        val trace = st!!.traceHr.toList()
        fun inTrace(i: Int) = trace.any { Math.abs(it - 60000 / rr(i)) < 1e-9 }
        assertTrue(inTrace(78) && inTrace(81))
        assertFalse(inTrace(79) || inTrace(80))   // the trace holds the beats that fed the Kalman filter: not these two
        val s = engine.stop(87_000)
        assertEquals(86, s.beats)   // 2.6: booked by the state at arrival, all 86 (none arrived paused), the two late-classified ones included
        // spec 6: mean HR over the game beats 62..85, those two included
        assertEquals(60000 / (62 until 86).map { rr(it) }.average(), s.meanHr!!, 1e-9)
    }

    @Test fun aPausedBeatBreaksTheRmssdChainEvenWhenItIsTheOnlyOne() {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        fun ms(i: Int) = ((1000 + i) * 1024 / 1000.0).roundToInt() * 1000.0 / 1024
        for (i in 0 until 100) {   // only beat 81 arrives paused (82 s, after T); beat 80 is classified after the resume, beat 82 books next
            val t = 1000L * (i + 1)
            if (t == 82_000L) engine.pause(81_500)
            if (t == 83_000L) engine.resume(82_500)
            engine.onPacket(t, HrsPacket(60, Contact.DETECTED, intArrayOf(((1000 + i) * 1024 / 1000.0).roundToInt())))
            engine.tick(t)
        }
        val s = engine.stop(101_000)
        // Game RR are beats 62 and later (tEnd = arrival, T = 61 s); the chain starts at 62 (spec 6). Successive differences only inside
        // 62..80 and 82..99: the resume breaks nothing, the paused beat 81 breaks 80|82
        val d = (62 until 80).map { ms(it + 1) - ms(it) } + (82 until 99).map { ms(it + 1) - ms(it) }
        assertEquals(Math.sqrt(d.sumOf { it * it } / d.size), s.rmssd!!, 1e-9)
    }

    @Test fun aPauseWithoutAnyBeatKeepsTheConsecutiveRmssdDifference() {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        fun ms(i: Int) = ((1000 + i) * 1024 / 1000.0).roundToInt() * 1000.0 / 1024
        for (i in 0 until 100) {   // pause 81.2-81.6 s (after T): shorter than a beat, no packet arrives while paused
            val t = 1000L * (i + 1)
            if (t == 82_000L) { engine.pause(81_200); engine.resume(81_600) }
            engine.onPacket(t, HrsPacket(60, Contact.DETECTED, intArrayOf(((1000 + i) * 1024 / 1000.0).roundToInt())))
            engine.tick(t)
        }
        val s = engine.stop(101_000)
        val d = (62 until 99).map { ms(it + 1) - ms(it) }   // game RR: beats 62 and later
        assertEquals(Math.sqrt(d.sumOf { it * it } / d.size), s.rmssd!!, 1e-9)
    }

    @Test fun csvIntegrity() {
        val r = run(clean)
        val rows = r.rows()
        assertTrue(rows.size > 250)
        for (row in rows) assertEquals(row, 12, row.split(",").size)
    }

    @Test fun dropout() {
        val r = run(clean, suppress = { it in 100_000L until 110_000L })
        val a = clean.beats(500).last { it.arrivalMs < 100_000 }.arrivalMs
        val lastOkTick = (a + 4000) / 250 * 250
        assertEquals(Signal.OK, r.at(lastOkTick).signal)
        assertEquals(Signal.LOST, r.at(lastOkTick + 250).signal)
        val resume = clean.beats(500).first { it.arrivalMs >= 110_000 }.arrivalMs
        val back = r.states.first { it.tMs >= resume && it.signal == Signal.OK }
        assertTrue("recovered at ${back.tMs}", back.tMs <= resume + 5000)
        assertTrue(back.warmingUp)
        assertEquals(LostReason.STALE, r.at(lastOkTick + 250).lostReason)
        assertNull(back.lostReason)
        val frozen = r.at(lastOkTick)
        assertEquals(frozen.balloonTarget, r.at(lastOkTick + 2000).balloonTarget, 1e-9)
        assertEquals(frozen.balloonTarget, back.balloonTarget, 1e-9)
        assertTrue(back.warmupRemainingMs > 15_000)
        assertFalse(r.at(back.tMs + 30_000).warmingUp)
        val rows = r.rows()
        val lost = rows.indexOfFirst { it.endsWith(",signal_lost,") }
        val backRow = rows.indexOfFirst { it.endsWith(",signal_back,") }
        assertTrue(lost in 1 until backRow)
        val firstAfter = rows.drop(lost + 1).first { it.startsWith("beat,") }
        assertTrue(firstAfter, firstAfter.split(",")[1].toDouble() >= 109_000)
    }

    @Test fun disconnectAndReconnect() {
        val r = run(clean, suppress = { it in 50_000L until 55_000L }, link = mapOf(50_000L to false, 55_000L to true))
        assertEquals(Signal.LOST, r.at(50_000).signal)
        assertEquals(LostReason.LINK, r.at(50_000).lostReason)
        val rows = r.rows()
        val d = rows.indexOfFirst { it.endsWith(",disconnect,") }
        val c = rows.indexOfFirst { it.endsWith(",reconnect,") }
        assertTrue(d in 1 until c)
        assertEquals(Signal.OK, r.at(70_000).signal)
    }

    @Test fun ectopicPairAtLowHrNeverLost() {
        val s = synthBeats(50.0, 10.0, 5.5, 120.0)
        injectPairs(s.rrMs, 0.65, 1.35, from = 60, every = 1_000_000)
        val r = run(s, durationMs = null, stopAtMs = 100_000)
        assertTrue(r.states.all { it.signal == Signal.OK })
        assertTrue(r.summary.beats > 60)
    }

    @Test fun contactLossWithFlowingRrResetsTheMetric() {
        val r = run(clean, contact = { if (it in 100_000L until 140_000L) Contact.NOT_DETECTED else Contact.DETECTED })
        assertEquals(Signal.LOST, r.at(120_000).signal)
        val back = r.states.first { it.tMs >= 140_000 && it.signal == Signal.OK }
        assertTrue("recovered at ${back.tMs}", back.tMs <= 145_000)
        assertTrue("no warm-up after contact loss", back.warmingUp)
        assertEquals(LostReason.CONTACT, r.at(120_000).lostReason)
        val lastOk = r.states.last { it.tMs < 100_500 && it.signal == Signal.OK }
        assertEquals(lastOk.balloonTarget, back.balloonTarget, 1e-9)
        assertTrue(r.at(back.tMs + 15_000).warmingUp)
        assertFalse(r.at(back.tMs + 30_000).warmingUp)
        val rows = r.rows()
        assertTrue(rows.any { it.endsWith(",signal_lost,") } && rows.any { it.endsWith(",signal_back,") })
    }

    @Test fun beatsWhileLostCountAsRejectedAndTheBeatEndingTheLossIsBookedLost() {
        val r = run(clean, contact = { if (it in 100_000L until 140_000L) Contact.NOT_DETECTED else Contact.DETECTED })
        val rows = r.rows()
        val lost = rows.indexOfFirst { it.endsWith(",signal_lost,") }
        val back = rows.indexOfFirst { it.endsWith(",signal_back,") }
        assertTrue(lost >= 0 && back > lost)
        val booked = rows.subList(lost, back).count { it.startsWith("beat,") }   // classified while LOST, the beat that ends the loss last; 40 s at 60 bpm is about 40 beats, less the two the filter holds
        assertTrue("booked=$booked", booked >= 35 && rows[back - 1].startsWith("beat,"))
        assertTrue(rows.filter { it.startsWith("beat,") }.all { it.split(",")[4] == "1" })   // the filter accepted every one: only the signal rejects
        assertEquals(rows.count { it.startsWith("beat,") }, r.summary.beats)
        assertEquals(100.0 * booked / r.summary.beats, r.summary.rejectedPct!!, 1e-9)
    }

    @Test fun theBeatThatEndsAStaleLossIsBookedRejected() {
        val r = run(clean, suppress = { it in 100_000L until 110_000L })
        val rows = r.rows()
        val lost = rows.indexOfFirst { it.endsWith(",signal_lost,") }
        val back = rows.indexOfFirst { it.endsWith(",signal_back,") }
        assertTrue(lost >= 0 && back > lost)
        val booked = rows.subList(lost, back).count { it.startsWith("beat,") }   // no beat arrived during the loss: the only one is the beat that ends it
        assertEquals(1, booked)
        assertTrue(rows[back - 1].startsWith("beat,"))
        assertEquals(100.0 * booked / r.summary.beats, r.summary.rejectedPct!!, 1e-9)
    }

    @Test fun zeroRrNeverWritesInfinity() {
        val s = synthBeats(60.0, 10.0, 5.5, 90.0)
        s.rrMs[40] = 0.0
        val r = run(s, durationMs = null, stopAtMs = 80_000)
        val rows = r.rows()
        assertTrue(rows.none { "Infinity" in it || "NaN" in it })
        val zero = rows.first { it.startsWith("beat,") && it.split(",")[3] == "0.0" }
        assertEquals("", zero.split(",")[5])
        assertEquals("0", zero.split(",")[4])
        assertFalse(r.summary.meanHr!!.isInfinite())
    }

    @Test fun starsAreLoggedAndCounted() {
        val out = ByteArrayOutputStream()
        val csv = SessionCsvWriter(out)
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), csv)
        engine.onStar(61_250)
        engine.onStar(120_000)
        val summary = engine.stop(130_000)
        assertEquals(2, summary.stars)
        val rows = out.toString("UTF-8").lines().filter { it.startsWith("event,") }
        assertEquals(listOf("event,61250.0,,,,,,,,,star,", "event,120000.0,,,,,,,,,star,"), rows)
    }

    @Test fun stopEarlyWithNoBeats() {
        val r = run(clean, stopAtMs = 3000, suppress = { true })
        assertEquals(0, r.summary.beats)
        assertNull(r.summary.rmssd); assertNull(r.summary.sdnn); assertNull(r.summary.meanHr); assertNull(r.summary.meanP)
        assertEquals(listOf(SessionCsvWriter.COLUMNS), r.rows().filter { !it.startsWith("event,") })
    }

    @Test fun forecastOnlyWithAGoodSignalAndAFullBreathOfBeats() {
        val r = run(clean, stopAtMs = 120_000, link = mapOf(60_000L to false, 62_000L to true))
        org.junit.Assert.assertNull(r.at(5_000).forecast)        // under one breath of beats
        org.junit.Assert.assertNotNull(r.at(55_000).forecast)
        org.junit.Assert.assertNull(r.at(61_000).forecast)       // link down: the panel must not invent a heart rate
        org.junit.Assert.assertNotNull(r.at(110_000).forecast)   // back after a fresh breath
    }

    @Test fun searchFreezesOnceConverged() {
        val r = run(synthBeats(60.0, 10.0, 5.5, 900.0), durationMs = 900_000, rfSearch = true)
        val first = r.states.indexOfFirst { it.rfProbe?.converged == true }
        assertTrue("never converged", first >= 0)
        val after = r.states.drop(first)
        assertTrue("converged status flipped back", after.all { it.rfProbe!!.converged })
        val later = after.filter { it.tMs >= r.states[first].tMs + 60_000 }   // after the move to the found rate and one refill
        assertTrue(later.isNotEmpty())
        assertEquals(1, later.map { it.rateBpm }.distinct().size)
        assertTrue("the swing estimate restarted after convergence", later.none { it.warmingUp })
    }

    @Test fun aSignalLossOrAReturnDiscardsTheProbesScores() {
        // Probe 1 at 5.5 /min is scored from about 84 s and ends at about 150 s (152.7 s at the inhale); both gaps cover its end, so nothing is
        // scored after them: the probe restarts and stays probe 1.
        val lost = run(clean, durationMs = 900_000, stopAtMs = 170_000, rfSearch = true, suppress = { it in 138_000L..155_000L })
        assertEquals("scored from pre-loss data", 1, lost.at(165_000).rfProbe!!.index)
        val away = run(clean, durationMs = 900_000, stopAtMs = 170_000, rfSearch = true, pause = 140_000L..153_000L)
        assertEquals("scored from pre-pause data", 1, away.at(165_000).rfProbe!!.index)
    }

    @Test fun backgroundBeatsStayOutOfTheSummary() {
        val base = run(clean, durationMs = 240_000)
        val away = run(clean, durationMs = 240_000, pause = 60_000L..110_000L)
        assertTrue("base=${base.summary.beats} away=${away.summary.beats}", away.summary.beats < base.summary.beats - 30)
    }

    /** HR follows the pacer by ±[amp] beats/min at whatever rate it runs; with the default every probe scores alike: no peak. */
    private fun runFollower(
        durationMs: Long, start: Double = 5.5, startSd: Double = RfSearch.START_SD_NONE, stopAtMs: Long = Long.MAX_VALUE,
        amp: (Double) -> Double = { 5.0 },
    ): Run {
        val out = ByteArrayOutputStream()
        val engine = SessionEngine(SessionConfig(start, 0.5, durationMs, 0.0, 15.0, true, true, rfStartSd = startSd), SessionCsvWriter(out))
        val rnd = java.util.Random(7)
        var beatEnd = 0.0
        var rr = 60000 / 62.0
        val states = ArrayList<UiState>()
        var t = 0L
        while (true) {
            while (beatEnd + rr + 500 <= t) {
                beatEnd += rr
                engine.onPacket((beatEnd + 500).toLong(), packet(rr))
                rr = 60000 / (62 + amp(engine.pacer.rateAt(beatEnd)) * sin(2 * PI * engine.pacer.cycles(beatEnd))) + rnd.nextGaussian() * 3
            }
            val st = engine.tick(t)
            states += st
            if (st.finished || t >= stopAtMs) break
            t += 250
        }
        return Run(states, out.toString("UTF-8").lines(), engine.stop(t))
    }

    @Test fun aSnapshotLeavesTheSessionRunning() {
        val out = ByteArrayOutputStream()
        val engine = SessionEngine(SessionConfig(5.5, 0.4, 300_000, 0.0, 15.0, true, false), SessionCsvWriter(out))
        val beats = clean.beats(500)
        var bi = 0
        var mid: Summary? = null
        for (t in 0L..240_000L step 250) {
            while (bi < beats.size && beats[bi].arrivalMs <= t) { engine.onPacket(beats[bi].arrivalMs, packet(beats[bi].rrMs)); bi++ }
            engine.tick(t)
            if (t == 120_000L) mid = engine.snapshot(t)
        }
        val end = engine.stop(240_000)
        assertEquals(120_000L, mid!!.durationMs)
        assertTrue("beats at the snapshot: ${mid.beats}", mid.beats in 110..125)
        assertTrue("mean swing at the snapshot: ${mid.meanP}", mid.meanP!! in 8.8..10.2)
        assertTrue("the session went on: ${end.beats} beats", end.beats > mid.beats + 100)
        assertTrue(out.toString("UTF-8").lines().count { it.startsWith("beat,") } > 230)   // the CSV stayed open
    }

    @Test fun aSearchEndedEarlyWhileItShowsNoPeakIsIncomplete() {
        // Swing grows with rate, so the estimate keeps moving; stopped in probe 6, long before the search would conclude.
        val r = runFollower(1_080_000, start = 4.5, startSd = RfSearch.START_SD_ESTIMATE, stopAtMs = 700_000, amp = { 2 + 3 * (it - 4) })
        val probes = r.summary.rfProbes
        assertTrue("probes=${probes.size}", probes.size >= RfSearch.MIN_PROBES)
        assertTrue("the fit places no peak: se=${rfFit(probes)!!.se} probes=$probes", rfFit(probes)!!.se > RfSearch.START_SD_ESTIMATE)
        assertFalse(r.summary.rfConverged)
        assertFalse("only a search that ran to its end reports no clear peak", r.summary.rfNoPeak)
        val outcome = sessionOutcome(true, r.states.last().rateBpm, 5.5, r.summary.rfBest, r.summary.rfConverged, r.summary.rfNoPeak, probes)
        assertEquals(SessionRecord.KIND_RF_INCOMPLETE, outcome.kind)
    }

    @Test fun theFirstProbeEndsEightPeriodsAfterTAndTheNextRateBeginsAtAnInhale() {
        val r = run(clean, durationMs = 900_000, stopAtMs = 200_000, rfSearch = true)
        val end = firstProbeEnd(clean)   // about 152.7 s
        assertEquals(1, r.at(140_000).rfProbe!!.index)
        val second = r.states.first { it.rfProbe!!.index == 2 }.tMs
        assertTrue("second probe began at $second, expected $end", second >= end && second < end + 250)
        assertEquals(5.5, r.at((Math.floor(end / 250) * 250).toLong()).rateBpm, 1e-9)   // the last tick before the inhale
        assertEquals("the next rate begins at the inhale that follows breath eight", 6.0, r.at(second).rateBpm, 1e-9)
    }

    /** Rate changes as (first tick at the new rate, rate). */
    private fun changes(r: Run) = r.states.zipWithNext().filter { (a, b) -> a.rateBpm != b.rateBpm }.map { it.second.tMs to it.second.rateBpm }

    @Test fun pairedProbesFollowWithoutAnIdleBreathAndAFitTakesOne() {
        val r = run(synthBeats(60.0, 10.0, 6.0, 600.0), durationMs = 900_000, stopAtMs = 450_000, rfSearch = true)
        val c = changes(r)
        // Probe 1 (first, from T - one period) ends x after T + 8 periods at 5.5; probe 2: 9 at 6.0 end 90 s later; probe 3: 9 at 5.0 end 108 s later.
        val x = firstProbeEnd(synthBeats(60.0, 10.0, 6.0, 600.0)).toLong()
        assertEquals(6.0, c[0].second, 1e-9)
        assertTrue("probe 2 began at ${c[0].first}", c[0].first in x..x + 250)
        assertEquals(5.0, c[1].second, 1e-9)
        assertTrue("probe 3 began at ${c[1].first}", c[1].first in x + 90_000..x + 90_250)
        assertTrue("probe 4 began at ${c[2].first}, after one breath at 5.0", c[2].first in x + 210_000..x + 210_250)
        assertEquals(listOf(5.5, 6.0, 5.0), r.summary.rfProbes.take(3).map { it.first })
    }

    @Test fun aLossOrAPauseInTheLastBreathKeepsTheRateAndProbesItAgain() {
        // Probe 1's last breath at 5.5 /min runs from about 139 s to 150 s; the next rate is scheduled as it begins.
        val lost = run(clean, durationMs = 900_000, stopAtMs = 175_000, rfSearch = true, suppress = { it in 143_000L..156_000L })
        val away = run(clean, durationMs = 900_000, stopAtMs = 175_000, rfSearch = true, pause = 145_000L..154_000L)
        for (r in listOf(lost, away)) {
            assertTrue(r.states.filter { it.tMs >= 143_500 }.all { it.rateBpm == 5.5 })
            assertEquals(1, r.at(170_000).rfProbe!!.index)
        }
    }

    @Test fun aRestartThatBeginsMidBreathStillRunsNineBreaths() {
        // Probe 1 would end at about 150 s, inside a pause from 90 s (after T: no new warm-up) to 170 s, with its scores cleared at the
        // return: back at 170 s, mid-breath, probe 1 starts again there and must run to 170 + 98.2 s before 6.0 begins.
        val c = changes(run(clean, durationMs = 900_000, stopAtMs = 300_000, rfSearch = true, pause = 90_000L..170_000L))
        assertEquals(6.0, c.first().second, 1e-9)
        assertTrue("6.0 began at ${c.first().first}", c.first().first >= 268_182)
    }

    @Test fun noProbeRunsIntoTheLastTwoMinutes() {
        val r = runFollower(900_000)
        val late = r.states.filter { it.tMs > 781_000 }.map { it.rfProbe!!.index }.distinct()
        assertEquals("probe indices after 13:01: $late", 1, late.size)
        assertFalse(r.summary.rfConverged)
    }

    @Test fun aSearchWithoutAPeakEndsAt55() {
        val r = runFollower(900_000)
        assertTrue(r.summary.rfNoPeak)
        assertFalse(r.summary.rfConverged)
        assertEquals(5.5, r.summary.rfBest!!, 1e-9)
        assertTrue("probes=${r.summary.rfProbes.size}", r.summary.rfProbes.size >= 5)
        assertEquals(5.5, r.states.last().rateBpm, 1e-9)
    }

    @Test fun aSearchTooShortForFiveProbesStaysIncompleteAtItsRate() {
        val r = runFollower(420_000)   // probes must end by 5:00, room for two or three
        assertFalse(r.summary.rfNoPeak)
        assertFalse(r.summary.rfConverged)
        assertTrue("probes=${r.summary.rfProbes.size}", r.summary.rfProbes.size in 1..4)
        assertEquals(1, r.states.filter { it.tMs > 310_000 }.map { it.rateBpm }.distinct().size)
    }

    @Test fun aSearchFromAHeightEstimateFallsBackToThatEstimate() {
        val r = runFollower(900_000, start = 6.2, startSd = RfSearch.START_SD_ESTIMATE)
        assertEquals(6.2, r.summary.rfProbes.first().first, 1e-9)   // first probe at the estimate
        assertTrue(r.summary.rfNoPeak)
        assertEquals(6.2, r.summary.rfBest!!, 1e-9)
        assertEquals(6.2, r.states.last().rateBpm, 1e-9)
    }

    @Test fun aProbeWithUnderThreeBreathsOfScoresAfterADropoutRestarts() {
        // Probe 1 at 5.5 /min ends at about 150 s (152.7 s at the inhale); after a 90-112 s dropout the window refills by ~136 s, leaving ~1.2 breaths scored.
        val r = run(clean, durationMs = 900_000, stopAtMs = 160_000, rfSearch = true, suppress = { it in 90_000L..112_000L })
        assertEquals(1, r.at(158_000).rfProbe!!.index)
    }

    /** A swing that varies with the rate without a clear peak: the fit does not settle (the 4.3 scenario, whose rates stay about the start). */
    private fun unconverged(durationMs: Long, start: Double, a: Double) = runFollower(durationMs, start = start, amp = { 5.0 + a * sin(it) })

    @Test fun aTwentyMinuteSearchWithoutConvergenceRunsTheProbeCountOfTable43() {
        // Spec 9.2.4 and the 4.3 table: 9 probes from 5.5 /min, 7 from 4.5 /min (the first, then pairs: always odd).
        for ((start, count, swing) in listOf(Triple(5.5, 9, 2.0), Triple(4.5, 7, 3.0))) {
            val r = unconverged(1_200_000, start, swing)
            assertFalse("start $start", r.summary.rfConverged)
            assertEquals("start $start: ${r.summary.rfProbes.map { it.first }}", count, r.summary.rfProbes.size)
        }
    }

    // Spec 4.2 / 9.2.5, numbers for the runFollower signal: probe 1 begins nominally ending at about 148.75 s, its change takes the inhale at
    // 152.73 s (5.5 /min, period 10.91 s); probe 2 = 9 periods at 6.0 (90 s), probe 3 = 9 periods at 5.0 (108 s). The limit is the duration minus 120 s.
    private fun probeTwoStarts(durationMs: Long, start: Double = 5.5) = changes(runFollower(durationMs, start = start)).isNotEmpty()

    @Test fun whenOneProbeFitsButNotAPairNoNewProbeStarts() {
        // Limit 303 s: probe 2 alone would end by about 254 s, the pair at about 362 s.
        val r = runFollower(423_000)
        assertEquals(emptyList<Pair<Long, Double>>(), changes(r))
        assertEquals(1, r.summary.rfProbes.size)
        assertTrue(r.states.last().rfProbe!!.finished)
        assertEquals(5.5, r.states.last().rateBpm, 1e-9)   // 2.9: the pacer keeps the last probed rate
        assertTrue("a pair that does fit (limit 380 s) starts probe 2", probeTwoStarts(500_000))
    }

    @Test fun thePairCheckCountsTheWaitForTheInhale() {
        // Limit 352 s: the pair fits without the wait (148.75 + 90 + 108 = 346.75 s) and not with it (+10.91 = 357.66 s).
        assertFalse(probeTwoStarts(472_000))
    }

    @Test fun aPairTheConservativeBoundRefusesStillStartsWhenBothExactRatesFit() {
        // Limit 365 s: 152.73 + 90 + 135 (closing probe at 4.0) = 377.7 s fails the early check, the exact pair at the probe's end
        // (148.75 + 10.91 + 90 + 108 = 357.66 s) fits.
        val r = runFollower(485_000)
        assertEquals(6.0, changes(r).first().second, 1e-9)
        assertTrue("probe 2 began at ${changes(r).first().first}", changes(r).first().first in 152_700L..153_000L)
    }

    @Test fun aPairThatEndsExactlyAtTheLimitFits() {
        // Start 4.0 /min: probe 1 ends nominally at about 181.5 s, its change takes the inhale at 195 s; probe 2 at 4.5 is 120 s, probe 3 at 4.0 is 135 s:
        // 195 + 255 = 450 s, so the limit of a 570 s session. One millisecond less does not fit.
        assertTrue(probeTwoStarts(570_000, start = 4.0))
        assertFalse(probeTwoStarts(569_999, start = 4.0))
    }

    @Test fun aRestartedOpeningProbeKeepsTheSingleProbeCheck() {
        // Limit 400 s. Probe 2 at 6.0 (152.7 s to about 242.7 s) loses its signal late and restarts at its end; the restart needs 10 + 90 s
        // (ends by about 344 s), the pair would need 108 s more (452 s): 2.9 keeps the restart, so the pair may end incomplete.
        val r = run(synthBeats(60.0, 10.0, 5.5, 520.0), durationMs = 520_000, rfSearch = true, suppress = { it in 200_000L..232_000L })
        assertFalse(r.at(300_000).rfProbe!!.finished)
        assertEquals(2, r.at(300_000).rfProbe!!.index)
        assertEquals(6.0, r.at(300_000).rateBpm, 1e-9)
    }

    @Test fun aLaterPairThatEndsExactlyAtTheLimitFits() {
        // Start 4.0 /min, a flat swing: probes at 4.0, 4.5, 4.0 end at 195 + 120 + 135 = 450 s; the pair (4, 5) is 4.0 and 5.0 /min,
        // 135 s + 108 s, after the wait of one 4.0 period (15 s): 450 + 15 + 243 = 708 s, the limit of an 828 s session.
        fun fourthStarts(durationMs: Long) = !runFollower(durationMs, start = 4.0).states.first { it.rfProbe!!.index == 4 }.rfProbe!!.finished
        assertTrue(fourthStarts(828_000))
        assertFalse(fourthStarts(827_999))
    }
}
