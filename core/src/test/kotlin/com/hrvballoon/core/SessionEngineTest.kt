package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
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

    @Test fun theRateNeverChangesOutsideASearch() {
        assertTrue(run(clean, lagMs = 20).states.all { it.rateBpm == 5.5 })
    }

    @Test fun endToEnd() {
        val r = run(clean)
        assertTrue(r.at(15_000).warmingUp)
        assertFalse(r.at(40_000).warmingUp)
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
        assertTrue(r.at(15_000).warmupRemainingMs < r.at(5_000).warmupRemainingMs)
        assertEquals(0L, r.at(40_000).warmupRemainingMs)
        assertTrue("valid=${r.summary.validMs}", r.summary.validMs in 265_000..278_200)
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
        while (t <= 200_000) {
            if (t == 60_000L) engine.pause(t)
            if (t == 100_000L) engine.resume(t)
            while (bi < beats.size && beats[bi].arrivalMs <= t) { engine.onPacket(beats[bi].arrivalMs, packet(beats[bi].rrMs)); bi++ }
            engine.tick(t)
            t += 250
        }
        val s = engine.stop(200_000)
        // 200 s − ~25 s warm-up − 40 s paused − ~22 s re-warm-up after resume (the window is cleared on purpose)
        assertTrue("valid=${s.validMs}", s.validMs in 100_000..120_000)
        val rows = out.toString("UTF-8").lines().filter { it.startsWith("event,") }
        assertEquals(listOf("event,60000.0,,,,,,,,,pause", "event,100000.0,,,,,,,,,resume"), rows)
        assertTrue("summary beats=${s.beats}", s.beats in 150..170)   // the 40 s away stay out of the summary
        assertTrue(out.toString("UTF-8").lines().count { it.startsWith("beat,") } > 190)   // but reach the CSV
    }

    /** Beats of RR 1000 + i ms (distinct, all accepted) arrive 1 s apart; the engine is paused/resumed between arrivals. */
    private fun markedRun(pauseAt: Long, resumeAt: Long?, stopAt: Long): Pair<Summary, Double> {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        val booked = ArrayList<Double>()
        for (i in 0 until (stopAt / 1000).toInt()) {
            val t = 1000L * (i + 1)
            if (pauseAt in t - 999 until t) engine.pause(pauseAt)
            if (resumeAt != null && resumeAt in t - 999 until t) engine.resume(resumeAt)
            val rr1024 = ((1000 + i) * 1024 / 1000.0).roundToInt()
            engine.onPacket(t, HrsPacket(60, Contact.DETECTED, intArrayOf(rr1024)))
            engine.tick(t)
            if (t < pauseAt || (resumeAt != null && t > resumeAt)) booked += rr1024 * 1000.0 / 1024
        }
        return engine.stop(stopAt) to 60000 / booked.average()
    }

    @Test fun beatsAreBookedByTheirStateAtArrivalAtThePauseEdge() {
        val (s, meanHr) = markedRun(pauseAt = 20_500, resumeAt = null, stopAt = 30_000)   // beats 0..19 arrived before the pause; 20..29 during
        assertEquals(20, s.beats)
        assertEquals(meanHr, s.meanHr!!, 1e-9)
    }

    @Test fun beatsAreBookedByTheirStateAtArrivalAtTheResumeEdge() {
        val (s, meanHr) = markedRun(pauseAt = 20_500, resumeAt = 40_500, stopAt = 50_000)   // 20..39 arrived paused, 40..49 after resume
        assertEquals(30, s.beats)
        assertEquals(meanHr, s.meanHr!!, 1e-9)
    }

    @Test fun aGapBetweenOneAndThreeSecondsBreaksTheRmssdChain() {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        // 30 beats of 1000 ms, two beats lost (arrival 3 s apart, re-anchored by 1.9 s: a gap, not LOST), then 30 beats of 1100 ms
        val arrivals = (1..30).map { 1000L * it to 1000.0 } + (0 until 30).map { 33_000L + 1100L * it to 1100.0 }
        for ((t, rr) in arrivals) { engine.onPacket(t, packet(rr)); engine.tick(t) }
        val s = engine.stop(arrivals.last().first)
        assertEquals(60, s.beats)
        assertEquals(0.0, s.rmssd!!, 1e-9)   // every successive difference lies inside a constant stretch; the 100 ms step spans the gap
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

    @Test fun aPausedBeatBreaksTheRmssdChainEvenWhenItIsTheOnlyOne() {
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, false), SessionCsvWriter(ByteArrayOutputStream()))
        fun ms(i: Int) = ((1000 + i) * 1024 / 1000.0).roundToInt() * 1000.0 / 1024
        for (i in 0 until 40) {   // only beat 21 arrives paused (22 s); beat 20 is classified after the resume, beat 22 books next
            val t = 1000L * (i + 1)
            if (t == 22_000L) engine.pause(21_500)
            if (t == 23_000L) engine.resume(22_500)
            engine.onPacket(t, HrsPacket(60, Contact.DETECTED, intArrayOf(((1000 + i) * 1024 / 1000.0).roundToInt())))
            engine.tick(t)
        }
        val s = engine.stop(41_000)
        // successive differences only inside 0..19 and 22..39: the resume breaks 19|20, the paused beat 21 breaks 20|22
        val d = (0 until 19).map { ms(it + 1) - ms(it) } + (22 until 39).map { ms(it + 1) - ms(it) }
        assertEquals(Math.sqrt(d.sumOf { it * it } / d.size), s.rmssd!!, 1e-9)
    }

    @Test fun csvIntegrity() {
        val r = run(clean)
        val rows = r.rows()
        assertTrue(rows.size > 250)
        for (row in rows) assertEquals(row, 11, row.split(",").size)
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
        val lost = rows.indexOfFirst { it.endsWith(",signal_lost") }
        val backRow = rows.indexOfFirst { it.endsWith(",signal_back") }
        assertTrue(lost in 1 until backRow)
        val firstAfter = rows.drop(lost + 1).first { it.startsWith("beat,") }
        assertTrue(firstAfter, firstAfter.split(",")[1].toDouble() >= 109_000)
    }

    @Test fun disconnectAndReconnect() {
        val r = run(clean, suppress = { it in 50_000L until 55_000L }, link = mapOf(50_000L to false, 55_000L to true))
        assertEquals(Signal.LOST, r.at(50_000).signal)
        assertEquals(LostReason.LINK, r.at(50_000).lostReason)
        val rows = r.rows()
        val d = rows.indexOfFirst { it.endsWith(",disconnect") }
        val c = rows.indexOfFirst { it.endsWith(",reconnect") }
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
        assertTrue(rows.any { it.endsWith(",signal_lost") } && rows.any { it.endsWith(",signal_back") })
    }

    @Test fun beatsWhileLostCountAsRejectedAndTheBeatEndingTheLossIsBookedLost() {
        val r = run(clean, contact = { if (it in 100_000L until 140_000L) Contact.NOT_DETECTED else Contact.DETECTED })
        val rows = r.rows()
        val lost = rows.indexOfFirst { it.endsWith(",signal_lost") }
        val back = rows.indexOfFirst { it.endsWith(",signal_back") }
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
        val lost = rows.indexOfFirst { it.endsWith(",signal_lost") }
        val back = rows.indexOfFirst { it.endsWith(",signal_back") }
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
        assertEquals(listOf("event,61250.0,,,,,,,,,star", "event,120000.0,,,,,,,,,star"), rows)
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
        // Probe 1 at 5.5 /min is scored from 32.7 s to 98.2 s; both gaps cover its end, so nothing scored after them.
        val lost = run(clean, durationMs = 900_000, stopAtMs = 110_000, rfSearch = true, suppress = { it in 84_000L..101_000L })
        assertEquals("scored from pre-loss data", 1, lost.at(100_000).rfProbe!!.index)
        val away = run(clean, durationMs = 900_000, stopAtMs = 110_000, rfSearch = true, pause = 86_000L..99_000L)
        assertEquals("scored from pre-pause data", 1, away.at(100_000).rfProbe!!.index)
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

    @Test fun aProbeLastsNineBreaths() {
        val r = run(clean, durationMs = 900_000, stopAtMs = 120_000, rfSearch = true)
        assertEquals(1, r.at(95_000).rfProbe!!.index)   // 9 breaths at 5.5 /min take 98.2 s
        val second = r.states.first { it.rfProbe!!.index == 2 }.tMs
        assertTrue("second probe began at $second", second in 98_000L..100_000L)
        assertEquals(5.5, r.at(98_000).rateBpm, 1e-9)
        assertEquals("the next rate begins as breath nine ends", 6.0, r.at(98_250).rateBpm, 1e-9)
    }

    /** Rate changes as (first tick at the new rate, rate). */
    private fun changes(r: Run) = r.states.zipWithNext().filter { (a, b) -> a.rateBpm != b.rateBpm }.map { it.second.tMs to it.second.rateBpm }

    @Test fun pairedProbesFollowWithoutAnIdleBreathAndAFitTakesOne() {
        val r = run(synthBeats(60.0, 10.0, 6.0, 600.0), durationMs = 900_000, stopAtMs = 450_000, rfSearch = true)
        val c = changes(r)
        // Probe 1: 9 breaths at 5.5 end at 98.2 s; probe 2: 9 at 6.0 end at 188.2 s; probe 3: 9 at 5.0 end at 296.2 s.
        assertEquals(6.0, c[0].second, 1e-9)
        assertTrue("probe 2 began at ${c[0].first}", c[0].first in 98_182L..98_432L)
        assertEquals(5.0, c[1].second, 1e-9)
        assertTrue("probe 3 began at ${c[1].first}", c[1].first in 188_182L..188_432L)
        assertTrue("probe 4 began at ${c[2].first}, after one breath at 5.0", c[2].first in 308_182L..308_432L)
        assertEquals(listOf(5.5, 6.0, 5.0), r.summary.rfProbes.take(3).map { it.first })
    }

    @Test fun aLossOrAPauseInTheLastBreathKeepsTheRateAndProbesItAgain() {
        // Probe 1's last breath at 5.5 /min runs from 87.3 s to 98.2 s; the next rate is scheduled as it begins.
        val lost = run(clean, durationMs = 900_000, stopAtMs = 130_000, rfSearch = true, suppress = { it in 88_000L..101_000L })
        val away = run(clean, durationMs = 900_000, stopAtMs = 130_000, rfSearch = true, pause = 90_000L..99_000L)
        for (r in listOf(lost, away)) {
            assertTrue(r.states.filter { it.tMs >= 87_500 }.all { it.rateBpm == 5.5 })
            assertEquals(1, r.at(120_000).rfProbe!!.index)
        }
    }

    @Test fun aRestartThatBeginsMidBreathStillRunsNineBreaths() {
        // Back at 125 s, half a breath into one: probe 1 starts again there and must run to 125 + 98.2 s before 6.0 begins.
        val c = changes(run(clean, durationMs = 900_000, stopAtMs = 240_000, rfSearch = true, pause = 50_000L..125_000L))
        assertEquals(6.0, c.first().second, 1e-9)
        assertTrue("6.0 began at ${c.first().first}", c.first().first >= 223_182)
    }

    @Test fun anEighteenMinuteSearchFromFourAndAHalfHasRoomForItsPairs() {
        val r = runFollower(1_080_000, start = 4.5, amp = { 2 + 3 * (it - 4) })   // swing grows with rate: the estimate keeps moving
        assertFalse(r.summary.rfConverged)
        assertTrue("probes=${r.summary.rfProbes.map { it.first }}", r.summary.rfProbes.size >= 7)
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
        // Probe 1 at 5.5 /min ends at 98.2 s; after a 40-62 s dropout the window refills by ~84 s, leaving ~1.3 breaths scored.
        val r = run(clean, durationMs = 900_000, stopAtMs = 110_000, rfSearch = true, suppress = { it in 40_000L..62_000L })
        assertEquals(1, r.at(100_000).rfProbe!!.index)
    }
}
