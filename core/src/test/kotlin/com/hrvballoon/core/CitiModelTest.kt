package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 2.10 sections 7, 8.1 and 9.5. Every expectation below is quoted from the spec, not from code output. */
class CitiModelTest {
    /** Spec 9.5.1: LCG with x0 = 2010, 200 RR in ms. */
    private fun fixture(): MutableList<Double> {
        var x = 2010L
        return MutableList(200) {
            x = (1103515245L * x + 12345L) % (1L shl 31)
            900.0 + 26.0 * (2.0 * x / (1L shl 31) - 1.0)
        }
    }

    private class Run(val citi: List<Int?>, val accepted: List<Boolean>, val traces: Map<Int, CitiTrace>)

    /** One segment, rows from 0; the 2.9 filter supplies accepted; push on arrival, decide on classification. */
    private fun run(rr: List<Double>): Run {
        val model = CitiModel()
        val filter = ArtifactFilter()
        model.startSegment()
        val citi = ArrayList<Int?>()
        val acc = ArrayList<Boolean>()
        val traces = HashMap<Int, CitiTrace>()
        var t = 0.0
        fun take(c: ClassifiedBeat) {
            val i = citi.size
            citi += model.decide(i.toLong(), c.accepted)
            acc += c.accepted
            model.trace?.let { traces[i] = it }
        }
        rr.forEachIndexed { i, v ->
            model.push(i.toLong(), v)
            t += v
            filter.push(Beat(t, v, t.toLong())).forEach(::take)
        }
        filter.flush().forEach(::take)
        return Run(citi, acc, traces)
    }

    private fun ones(r: Run) = r.citi.withIndex().filter { it.value == 1 }.map { it.index }

    /** Spec 9.5.2: rows 0-66 and the two last are empty, every unnamed row is 0. */
    private fun assertShape(r: Run, n: Int, empty: IntRange, ones: Set<Int>) {
        assertEquals(n, r.citi.size)
        for (i in 0..66) assertNull("row $i", r.citi[i])
        assertNull(r.citi[n - 2]); assertNull(r.citi[n - 1])
        for (i in 67 until n - 2) {
            when (i) {
                in empty -> assertNull("row $i", r.citi[i])
                in ones -> assertEquals("row $i", 1, r.citi[i])
                else -> assertEquals("row $i", 0, r.citi[i])
            }
        }
    }

    @Test fun fixtureStartsAsSpecified() {
        val f = fixture()
        assertEquals(919.100, f[0], 5e-4); assertEquals(875.848, f[1], 5e-4); assertEquals(874.914, f[2], 5e-4)
    }

    @Test fun variantA_unchanged() {
        val r = run(fixture())
        assertShape(r, 200, IntRange.EMPTY, emptySet())
        assertTrue(r.accepted[120])
    }

    @Test fun variantB_shift120() {
        val f = fixture(); f[120] += 120.0; f[121] -= 120.0
        val r = run(f)
        assertShape(r, 200, IntRange.EMPTY, setOf(120))
        assertTrue(r.accepted[120]); assertTrue(r.accepted[121])
        assertEquals(listOf(120), ones(r))
        // 9.5.3 / 9.5.4: parameters 1 %, differences 0.5, interval 1 ms
        val tr = r.traces.getValue(120)
        val a = doubleArrayOf(0.2858, 0.0609, 0.0977, 0.2844, 0.2713)
        for (j in 0 until 5) assertEquals("a${j + 1}", a[j], tr.a[j], 0.01 * a[j])
        assertEquals(2610.0, tr.lambda, 26.1)
        assertEquals(30.1, tr.pmMinusPn, 0.5)
        assertEquals(61.9, tr.gain, 0.5)
        assertEquals(926.3, tr.correctedMs, 1.0)
    }

    @Test fun variantC_shift250() {
        val f = fixture(); f[120] += 250.0; f[121] -= 250.0
        val r = run(f)
        assertShape(r, 200, IntRange.EMPTY, setOf(120))
        assertEquals(false, r.accepted[120]); assertEquals(false, r.accepted[121])
        val tr = r.traces.getValue(120)
        assertEquals(101.5, tr.pmMinusPn, 0.5)
        assertEquals(286.9, tr.gain, 0.5)
    }

    @Test fun variantD_missedBeat() {
        val f = fixture(); val m = f[120] + f[121]; f.removeAt(121); f[120] = m
        val r = run(f)
        assertEquals(false, r.accepted[120])
        assertShape(r, 199, 121..125, emptySet())
    }

    @Test fun variantE_extraBeat() {
        val f = fixture(); val v = f[120]; f[120] = 380.0; f.add(121, v - 380.0)
        val r = run(f)
        assertEquals(false, r.accepted[120])
        assertShape(r, 201, 121..126, emptySet())
    }

    @Test fun variantF_earlyBeat() {
        val f = fixture(); f[120] -= 300.0
        val r = run(f)
        assertEquals(false, r.accepted[120])
        assertShape(r, 200, 121..125, emptySet())
    }

    @Test fun variantG_constantSeriesIsAllEmpty() {
        val r = run(List(200) { 900.0 })
        assertTrue(r.citi.all { it == null })
    }

    // Option 3 (Denis, 2026-11-26): no model-SD check at fit time, only at the decision at mu_1. A rising series with 0.7 ms noise has a
    // fit whose SD at the window's mean prediction is under 1 ms but whose mu_1 (the latest, higher level) is not: it must give 0 or 1.
    // With the fit-time check restored every row of this series is empty.
    @Test fun aFitWithAnSdUnderOneMsAtTheMeanStillDecidesAtMu1() {
        val rnd = java.util.Random(1)
        val f = List(200) { 800.0 + 3.0 * it + 0.7 * rnd.nextGaussian() }
        val m = CitiModel(); m.startSegment()
        f.forEachIndexed { i, v -> m.push(i.toLong(), v) }
        val out = f.indices.map { m.decide(it.toLong(), true) }
        assertTrue(out.any { it != null })
        assertTrue(out.all { it == null || it == 0 || it == 1 })
    }

    // Robustness contract of the brief: null, never an exception.
    @Test fun decideOutsideSegmentIsNull() {
        val m = CitiModel()
        assertNull(m.decide(5, true))                      // no segment, never pushed
        m.startSegment()
        for (i in 10L until 20L) m.push(i, 900.0)
        assertNull(m.decide(3, true))                      // before the segment (a beat from before T)
        assertNull(m.decide(25, true))                     // never pushed
        assertNull(m.decide(-1, true))
        assertNull(m.decide(18, true))                     // no two followers: flush at stop
        assertNull(m.decide(19, true))
        m.startSegment()
        assertNull(m.decide(12, true))                     // pushed before the last startSegment
        m.push(30, 900.0)
        assertNull(m.decide(12, true))
    }

    // Review 2.1: the fit arithmetic is reached (rows 67-99 are normal and decided), then extreme accepted values follow.
    @Test fun extremeAcceptedValuesNeverThrow() {
        val f = fixture().take(100).toMutableList()
        repeat(20) { f += 1e6 }; repeat(20) { f += 1e-4 }; repeat(20) { f += 1e12 }
        repeat(20) { f += 1e-9 }; repeat(40) { f += 900.0 }
        val m = CitiModel(); m.startSegment()
        f.forEachIndexed { i, v -> m.push(i.toLong(), v) }
        val out = f.indices.map { m.decide(it.toLong(), true) }
        for (i in 67..97) assertEquals("row $i", 0, out[i])
        assertTrue(out.all { it == null || it == 0 || it == 1 })
    }

    // Review 2.2: NaN, +Inf, 0 and a negative RR mid-series, accepted = true: never normal, time stays finite.
    @Test fun badRrKeepsTimeFiniteAndFitsAlive() {
        val f = fixture()
        val bad = mapOf(100 to Double.NaN, 110 to Double.POSITIVE_INFINITY, 120 to 0.0, 130 to -4.0)
        val m = CitiModel(); m.startSegment()
        f.forEachIndexed { i, v -> m.push(i.toLong(), bad[i] ?: v) }
        val out = f.indices.map { m.decide(it.toLong(), true) }
        for (b in bad.keys) {
            for (i in b - 2..b + 5) assertNull("row $i near bad $b", out[i])   // itself, as follower of b-2/b-1, five after
            assertEquals("recovery after $b", 0, out[b + 6])
        }
        assertEquals(0, out[100 - 3]); assertEquals(0, out[150])
    }

    // Review 1.3 / 2.3: repeated and backwards decide return null and leave the next in-order decide unchanged.
    @Test fun repeatedAndBackwardsDecideAreNullAndHarmless() {
        val f = fixture(); f[120] += 120.0; f[121] -= 120.0
        fun model() = CitiModel().also { m -> m.startSegment(); f.forEachIndexed { i, v -> m.push(i.toLong(), v) } }
        val control = model()
        for (i in 0 until 120) control.decide(i.toLong(), true)
        assertEquals(1, control.decide(120, true))
        val expected121 = control.decide(121, true)
        assertEquals(0, expected121)                   // spec 9.5.2, variant B row 121

        val m = model()
        for (i in 0 until 120) m.decide(i.toLong(), true)
        assertNull(m.decide(90, true))                 // backwards
        assertNull(m.decide(119, true))                // repeat
        assertEquals(1, m.decide(120, true))
        assertNull(m.decide(120, true))                // repeat of a confirmed correction: no double apply
        assertNull(m.decide(100, true))                // backwards after the correction
        assertEquals(expected121, m.decide(121, true))
        assertEquals(control.fitLambda, m.fitLambda)
    }

    // Review 1.1 (spec 7.3, 7.5 step 6): after a confirmed correction the fit uses the corrected pair as responses too.
    @Test fun fitAfterCorrectionEqualsFitOnTheCorrectedSeries() {
        val c = fixture(); c[120] += 250.0; c[121] -= 250.0
        val a = CitiModel(); a.startSegment()
        c.forEachIndexed { i, v -> a.push(i.toLong(), v) }
        val flt = ArtifactFilter(); val acc = ArrayList<Boolean>(); var t = 0.0
        c.forEach { t += it; flt.push(Beat(t, it, t.toLong())).forEach { b -> acc += b.accepted } }
        flt.flush().forEach { acc += it.accepted }
        for (i in 0..120) a.decide(i.toLong(), acc[i])
        val x = a.trace!!.correctedMs
        val clean = fixture(); val s = clean[120] + clean[121]; clean[120] = x; clean[121] = s - x
        val b = CitiModel(); b.startSegment()
        clean.forEachIndexed { i, v -> b.push(i.toLong(), v) }
        for (i in 0..120) b.decide(i.toLong(), true)
        for (i in 121..130) { a.decide(i.toLong(), acc[i]); b.decide(i.toLong(), true) }
        assertEquals(b.fitLambda!!, a.fitLambda!!, 1e-3 * b.fitLambda!!)
    }

    // Spec 7.5 step 2: p_m - p_N <= 2 gives 0 before any other step; pins the threshold 2 (a mutant with 0 survived).
    @Test fun step2ThresholdIsTwo() {
        val m = CitiModel(); m.startSegment()
        val f = fixture()
        f.forEachIndexed { i, v -> m.push(i.toLong(), v) }
        var between = 0
        for (i in f.indices) {
            val out = m.decide(i.toLong(), true)
            val d = m.step1 ?: continue
            if (m.trace != null) assertTrue("row $i reached step 5 with $d", d > 2.0)
            if (d > 0.0 && d <= 2.0) { between++; assertEquals("row $i", 0, out); assertNull("row $i", m.trace) }
        }
        assertTrue("fixture has no row with 0 < p_m - p_N <= 2", between > 0)
    }

    // Spec 7.7: 50 iterations x window <= 60 s bound the cost; 4 ms is the p99 limit on the phone.
    @Test fun decideIsFast() {
        val f = fixture()
        val m = CitiModel(); m.startSegment()
        f.forEachIndexed { i, v -> m.push(i.toLong(), v) }
        val t0 = System.nanoTime()
        for (i in f.indices) m.decide(i.toLong(), true)
        val mean = (System.nanoTime() - t0) / 1e6 / f.size
        println("CITI_DECIDE_MEAN_MS=$mean")
        assertTrue("mean $mean ms", mean < 4.0)
    }

    @Test fun newSegmentStartsClean() {
        val f = fixture(); f[120] += 120.0; f[121] -= 120.0
        val m = CitiModel(); m.startSegment()
        f.forEachIndexed { i, v -> m.push(i.toLong(), v) }
        m.startSegment()
        f.forEachIndexed { i, v -> m.push(1000L + i, v) }
        val out = f.indices.map { m.decide(1000L + it, true) }
        for (i in 0..66) assertNull(out[i])
        assertEquals(1, out[120])
    }
}
