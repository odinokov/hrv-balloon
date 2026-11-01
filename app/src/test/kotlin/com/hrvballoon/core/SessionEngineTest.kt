package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

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
        lagMs: Long = 500,
    ): Run {
        val out = ByteArrayOutputStream()
        val csv = SessionCsvWriter(out)
        csv.writeHeader(SessionMeta("0.1", "AA:BB", "strap", "2026-10-25T17:00:00+08:00", rate, 0.4, "5", 0.0, 15.0))
        val engine = SessionEngine(SessionConfig(rate, 0.4, durationMs, 0.0, 15.0, shapeGate, rfSearch), csv)
        val beats = s.beats(lagMs).filter { !suppress(it.arrivalMs) }
        var bi = 0
        val states = ArrayList<UiState>()
        var t = 0L
        while (true) {
            link[t]?.let { engine.onLink(it, t) }
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

    /** HR = sin(2πft + phase0) and the pacer starts at t = 0, so the fitted φ ≈ phase0: lead = φ − the pacer fundamental's phase. */
    private fun withLead(leadDeg: Double, durS: Double = 200.0) =
        synthBeats(60.0, 10.0, 5.5, durS, phase0 = Math.toRadians(PacerTrack.fundamentalPhaseDeg(0.4) + leadDeg))

    // A constant arrival lag is invisible to the beat clock's minimum-latency envelope and shifts the phase by 360·lag/period
    // (500 ms ≈ 16° at 5.5/min); a real strap reports some beats within tens of ms of a packet, so these use a 20 ms floor.
    @Test fun phaseLeadIsMeasuredAgainstThePacerFundamental() {
        assertTrue(Math.abs(run(withLead(0.0), lagMs = 20).at(120_000).phaseLeadDeg!!) < 10)
        val lead = run(withLead(40.0), lagMs = 20).at(120_000).phaseLeadDeg!!
        assertTrue("lead=$lead", lead in 30.0..50.0)
        val lag = run(withLead(-40.0), lagMs = 20).at(120_000).phaseLeadDeg!!
        assertTrue("lag=$lag", lag in -50.0..-30.0)
        val late = run(withLead(0.0)).at(120_000).phaseLeadDeg!!   // the 500 ms harness lag reads as a lag of about 16°
        assertTrue("late=$late", late in -24.0..-8.0)
        assertNull(run(clean).at(5_000).phaseLeadDeg)   // nothing before the first estimate
    }

    @Test fun theRateNeverChangesOutsideASearch() {
        assertTrue(run(withLead(40.0, 330.0), lagMs = 20).states.all { it.rateBpm == 5.5 })
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
        assertTrue(r.at(5_000).warmupRemainingMs in 15_000..22_000)
        assertTrue(r.at(15_000).warmupRemainingMs < r.at(5_000).warmupRemainingMs)
        assertEquals(0L, r.at(40_000).warmupRemainingMs)
        assertTrue("valid=${r.summary.validMs}", r.summary.validMs in 265_000..285_000)
        assertEquals(0L, run(clean, stopAtMs = 3000, suppress = { true }).summary.validMs)
    }

    @Test fun resonanceSearchProbesRatesAtInhaleStarts() {
        val r = run(clean, durationMs = 600_000, rfSearch = true)
        val probes = r.states.mapNotNull { it.rfProbe }
        assertTrue(probes.isNotEmpty())
        val rates = probes.map { it.rateBpm }.distinct()
        assertTrue("rates=$rates", rates.size >= 4)
        assertTrue(rates.all { it in 4.0..7.0 })
        assertTrue(probes.last().index >= 5)
        assertTrue(r.summary.rfBest != null)
        assertTrue("probes=${r.summary.rfProbes.size}", r.summary.rfProbes.size >= 5)
        assertTrue(r.summary.rfProbes.all { it.first in 4.0..7.0 && it.second >= 0 })
        assertEquals(r.summary.rfConverged, probes.last().converged)
        assertTrue(r.states.any { it.rfProbe?.settlingMs?.let { ms -> ms > 0 } == true })
        val rows = r.rows().filter { it.startsWith("beat,") }.map { it.split(",") }
        var prev = rows.first()[6]
        for (row in rows) {
            if (row[6] != prev) {
                assertTrue("rate changed at pacer phase ${row[7]}", row[7].toDouble() < 0.12)
                prev = row[6]
            }
        }
        assertTrue(r.states.none { it.rfProbe == null } || r.states.first().rfProbe != null)
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
        assertTrue(s.beats > 180)
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
}
