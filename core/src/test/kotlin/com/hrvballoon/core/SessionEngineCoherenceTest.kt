package com.hrvballoon.core

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEngineCoherenceTest {
    private class Out(val summary: Summary, val snap: Summary?, val csv: List<String>)

    /** Packets arrive 500 ms after their beat ends, ticks every 250 ms. */
    private fun run(
        s: SynthBeats, durationMs: Long = 600_000, rfSearch: Boolean = false, pause: LongRange? = null,
        contact: (Long) -> Contact = { Contact.DETECTED }, snapAt: Long = -1,
    ): Out {
        val buf = ByteArrayOutputStream()
        val engine = SessionEngine(SessionConfig(5.5, 0.4, durationMs, 0.0, 15.0, true, rfSearch), SessionCsvWriter(buf))
        val beats = s.beats()
        var bi = 0
        var snap: Summary? = null
        var t = 0L
        while (t <= durationMs) {
            if (pause != null && t == pause.first) engine.pause(t)
            if (pause != null && t == pause.last) engine.resume(t)
            while (bi < beats.size && beats[bi].arrivalMs <= t) {
                val rr = beats[bi].rrMs
                engine.onPacket(beats[bi].arrivalMs, HrsPacket((60000 / rr).roundToInt(), contact(beats[bi].arrivalMs), intArrayOf((rr * 1.024).roundToInt())))
                bi++
            }
            engine.tick(t)
            if (t == snapAt) snap = engine.snapshot(t)
            t += 250
        }
        val sum = engine.stop(t)
        return Out(sum, snap, buf.toString("UTF-8").lines())
    }

    /**
     * The pure function on the beats the engine should have fed: [keep] by beat index and end time, a new segment where [seg] changes.
     * T = the first beat's end + 60 s: the engine's t0 is the end of its first usable RR, here beat 0 (spec 2, 5.1).
     */
    private fun reference(s: SynthBeats, rate: (Double) -> Double = { 5.5 }, seg: (Double) -> Int = { 0 }, keep: (Int, Double) -> Boolean = { _, _ -> true }): Double? {
        var last = Double.NEGATIVE_INFINITY
        var extra = 0
        val fed = ArrayList<CsBeat>()
        s.beats().forEachIndexed { i, b ->
            if (!keep(i, b.tEndMs)) return@forEachIndexed
            if (b.tEndMs - last > 3000) extra++
            last = b.tEndMs
            fed += CsBeat(b.tEndMs, 60000 / b.rrMs, rate(b.tEndMs), seg(b.tEndMs) + extra)
        }
        return sessionCoherence(fed, s.beats()[0].tEndMs + WARMUP_MS)
    }

    private val sine = synthBeats(60.0, 10.0, 5.5, 630.0)

    /** Beats ending in [from, to] ms alternate 700/1300 ms: junk that must not reach the meter. */
    private fun junk(s: SynthBeats, from: Double, to: Double): SynthBeats {
        val rr = s.rrMs.copyOf()
        var t = s.t0Ms
        for (i in rr.indices) { t += rr[i]; if (t in from..to) rr[i] = if (i % 2 == 0) 700.0 else 1300.0 }
        return SynthBeats(s.t0Ms, rr)
    }

    @Test fun aPacedSessionScoresLikeThePureFunctionOnTheSameBeats() {
        val c = run(sine).summary.coherence
        assertNotNull(c)
        assertEquals(reference(sine)!!, c!!, 0.15)
        assertTrue("c=$c", c > 3)
    }

    @Test fun aPauseSplitsTheSegments() {
        val s = junk(sine, 150_000.0, 450_000.0)
        val c = run(s, pause = 150_000L..450_000L).summary.coherence!!
        assertEquals(reference(s, seg = { if (it > 450_000) 1 else 0 }, keep = { _, t -> t < 149_000 || t > 451_000 })!!, c, 0.15)
    }

    @Test fun aLostSignalSplitsTheSegments() {
        val s = junk(sine, 200_000.0, 202_500.0)
        val out = run(s, contact = { if (it in 200_000L until 202_500L) Contact.NOT_DETECTED else Contact.DETECTED })
        assertTrue(out.csv.any { it.endsWith(",signal_lost,") })
        assertEquals(reference(s, seg = { if (it > 202_500) 1 else 0 }, keep = { _, t -> t < 199_500 || t > 203_000 })!!, out.summary.coherence!!, 0.15)
    }

    @Test fun rejectedBeatsNeverReachTheMeter() {
        val rr = sine.rrMs.copyOf()
        val bad = injectMissed(rr)
        val s = SynthBeats(sine.t0Ms, rr)
        val c = run(s).summary.coherence!!
        assertEquals(reference(s, keep = { i, _ -> i !in bad })!!, c, 0.15)
        assertTrue("fed all: ${reference(s)}", abs(reference(s)!! - c) > 0.15)
    }

    @Test fun aSearchRunScoresWithThePacerRateOfEachBeat() {
        val s = synthBeats(60.0, 10.0, 5.5, 930.0)
        val out = run(s, durationMs = 900_000, rfSearch = true)
        val rows = out.csv.filter { it.startsWith("beat,") }.map { it.split(',') }
        val rates = rows.associate { it[1].toDouble() to it[6].toDouble() }   // columns t_ms, rate_bpm
        assertTrue(rates.values.toSet().size > 1)
        val ref = reference(s, rate = { t -> rates.entries.minByOrNull { abs(it.key - t) }!!.value })
        assertNotNull(ref)
        assertNotNull(out.summary.coherence)
        assertEquals(ref!!, out.summary.coherence!!, 0.3)
    }

    /**
     * 210 s: T is at about 61 s, so about 149 s of game RR, 18 windows whole (the first completes 64 s after T, then every 5 s);
     * split near the middle (pause at 135 s) 2 + 3, under 10: a split turns the value into null. (The 2.9 clip of 175 s sat at the
     * new minimum for a median, about 2:53 after t0: spec 9.7.3.)
     */
    private val short = synthBeats(60.0, 10.0, 5.5, 210.0)

    @Test fun aPauseOfAboutASecondStillSplitsTheSegments() {
        assertNotNull(run(short, durationMs = 210_000).summary.coherence)
        val out = run(short, durationMs = 210_000, pause = 135_000L..136_000L)
        assertTrue(out.csv.none { it.endsWith(",signal_lost,") })
        assertNull(out.summary.coherence)
    }

    @Test fun twoLongBeatsLeaveAGapOverThreeSecondsThatSplitsTheSegments() {
        val rr = short.rrMs.copyOf()
        rr[135] = 2100.0; rr[136] = 2100.0   // about 135 s: the same middle of the game data
        val out = run(SynthBeats(short.t0Ms, rr), durationMs = 215_000)
        assertTrue(out.csv.none { it.endsWith(",signal_lost,") })
        assertNull(out.summary.coherence)
    }

    @Test fun theBeatsBeforeTDoNotChangeTheScore() {   // 9.3.2: same t0, same game RR and rate, different pre-T RR with the same sum
        val rr = sine.rrMs.copyOf()
        for (i in 10..40 step 2) { val x = rr[i]; rr[i] = rr[i + 1]; rr[i + 1] = x }
        val swapped = SynthBeats(sine.t0Ms, rr)
        assertTrue(abs(sine.rrMs[10] - sine.rrMs[11]) > 1.0)
        assertEquals(reference(sine)!!, reference(swapped)!!, 1e-9)
        assertEquals(run(sine).summary.coherence!!, run(swapped).summary.coherence!!, 1e-9)
    }

    @Test fun nineWindowsGiveNoScoreAndTenGiveOneInTheEngineToo() {   // 9.3.1 with 9.3.4: engine and pure function agree on the window count
        fun clip(n: Int) = SynthBeats(sine.t0Ms, sine.rrMs.copyOf(n))
        val n10 = (130..260).first { reference(clip(it)) != null }
        assertNull(reference(clip(n10 - 1)))
        // the filter holds the last two beats back: with n + 2 beats delivered the engine has fed exactly the first n
        assertNotNull(run(clip(n10 + 2), durationMs = 260_000).summary.coherence)
        assertNull(run(clip(n10 + 1), durationMs = 260_000).summary.coherence)
    }

    @Test fun aSnapshotMidSessionCarriesAValueAndChangesNothing() {
        val with = run(sine, snapAt = 300_000)
        assertNotNull(with.snap!!.coherence)
        assertEquals(run(sine).summary.coherence!!, with.summary.coherence!!, 0.0)
        assertTrue(with.snap.coherence!! > 3)
    }
}
