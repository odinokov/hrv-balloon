package com.hrvballoon.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HRV Balloon 2.10, engine lane C: the Citi label in the per-beat CSV (spec 7.1-7.3, 8.1, 8.4, 9.5.5-9.5.7, 9.6). Expectations come
 * from the spec text, quoted by section. Beats are built from sensor units (1/1024 s), one packet per beat, zero latency: a beat of
 * 1024 units ends exactly 1000 ms after the previous one. T = 60 000 ms (t0 = 0, the first beat ends at 0).
 *
 * Every stream is: 60 beats of 1 s (ends 0..59 000), one beat of 2 s that straddles T (59 000..61 000, tEnd >= T but start < T), then
 * the 200 RR of the spec 9.5.1 fixture (about 900 ms each, in units), so the first segment starts at the first fixture beat at 61 s and
 * the first label can appear 60 s later. Every loss, gap and pause fixture sits after that first label (about 122 s).
 */
class SessionEngineCitiTest {
    private class Run(val states: List<UiState>, val csv: List<String>, val summary: Summary) {
        /** Beat rows as field lists. */
        fun beats() = csv.filter { it.startsWith("beat,") }.map { it.split(",") }
        fun citi() = beats().map { it[11] }
        fun eventAt(name: String) = csv.single { it.startsWith("event,") && it.split(",")[10] == name }.split(",")[1].toDouble()
    }

    /** Spec 9.5.1: LCG with x0 = 2010, 200 RR in ms. */
    private fun fixture(): MutableList<Double> {
        var x = 2010L
        return MutableList(200) {
            x = (1103515245L * x + 12345L) % (1L shl 31)
            900.0 + 26.0 * (2.0 * x / (1L shl 31) - 1.0)
        }
    }

    private fun toUnits(ms: List<Double>) = ms.map { Math.round(it * 1.024).toInt() }

    private val pre = List(60) { 1024 } + listOf(2048)   // 61 beats; the last straddles T
    private val firstFixtureBeat = pre.size              // index of the first RR wholly after T

    private fun stream(shifted: Boolean = false, before: List<Int> = pre, shift: Double = 120.0, early: Boolean = false): List<Int> {
        val f = fixture()
        if (shifted) { f[120] += shift; f[121] -= shift }   // spec 9.5.2 variant B (120) or C (250)
        if (early) f[120] -= 300.0   // spec 9.5.2 variant F
        return before + toUnits(f)
    }

    private fun play(
        units: List<Int>, citi: Boolean = true, rfSearch: Boolean = false, pause: LongRange? = null,
        contact: (Long) -> Contact = { Contact.DETECTED }, drop: Set<Int> = emptySet(),
    ): Run {
        val out = ByteArrayOutputStream()
        val csv = SessionCsvWriter(out)
        val engine = SessionEngine(SessionConfig(5.5, 0.4, null, 0.0, 15.0, true, rfSearch, citi = citi), csv)
        var cum = 0L
        val at = units.map { cum += it; Math.round((cum - units[0]) * 1000.0 / 1024) }
        val states = ArrayList<UiState>()
        var bi = 0
        var t = 0L
        val stopAt = at.last() + 5_000
        while (t <= stopAt) {
            if (pause != null && t == pause.first) engine.pause(t)
            if (pause != null && t == pause.last) engine.resume(t)
            while (bi < units.size && at[bi] <= t) {
                if (bi !in drop) {
                    val u = intArrayOf(units[bi])
                    engine.onPacket(at[bi], HrsPacket(Math.round(60000 / (u[0] * 1000.0 / 1024)).toInt(), contact(at[bi]), u))
                }
                bi++
            }
            states += engine.tick(t)
            t += 250
        }
        val summary = engine.stop(t)
        return Run(states, out.toString("UTF-8").lines().filter { it.isNotEmpty() && !it.startsWith("#") }, summary)
    }

    private fun firstLabel(r: Run) = r.citi().indexOfFirst { it != "" }

    // ---- 9.5.1 / 9.5.2 through the engine: index, segment start, pre-T beats and push-before-filter order together ----

    @Test fun `the engine column equals a standalone model fed the same raw RR from the first RR wholly after T`() {
        val units = stream(shifted = true)
        val r = play(units)
        val rows = r.beats()
        assertEquals(units.size, rows.size)   // 9.6.2: one row per measured RR
        val t = r.eventAt("warmup_end")
        assertEquals(60_000.0, t, 0.0)
        val raw = units.map { it * 1000.0 / 1024 }   // what the engine measures; the CSV rounds it to 0.1 ms
        val start = rows.indexOfFirst { it[1].toDouble() - it[3].toDouble() >= t }   // 7.2: tEndMs - rrMs >= T
        assertEquals(firstFixtureBeat, start)
        val accepted = rows.map { it[4] == "1" }
        val model = CitiModel()
        model.startSegment()
        val oracle = arrayOfNulls<Int>(rows.size)
        for (n in start until rows.size) {
            model.push(n.toLong(), raw[n])
            if (n - 2 >= start) oracle[n - 2] = model.decide((n - 2).toLong(), accepted[n - 2])
        }
        oracle[rows.size - 2] = model.decide((rows.size - 2).toLong(), accepted[rows.size - 2])   // flush: no followers
        oracle[rows.size - 1] = model.decide((rows.size - 1).toLong(), accepted[rows.size - 1])
        assertEquals(oracle.map { it?.toString() ?: "" }, r.citi())
        // 9.5.2: rows before the segment and the first 60 s of it are empty, the shifted peak is 1, the last two rows are empty
        for (i in 0 until start + 60) assertEquals("row $i", "", rows[i][11])
        assertEquals("1", rows[start + 120][11])
        assertEquals("", rows[rows.size - 1][11]); assertEquals("", rows[rows.size - 2][11])
        assertTrue(rows[start + 119][11] == "0" && rows[start + 122][11] == "0")
    }

    // ---- 9.5.2 variant C: the filter rejects the shifted pair, the model still labels it (7.3: raw RR, accepted only for training) ----

    @Test fun `variant C shift of 250 ms is rejected by the filter and still labelled 1 then 0`() {
        val r = play(stream(shifted = true, shift = 250.0))
        val rows = r.beats()
        val i = firstFixtureBeat + 120
        assertTrue(firstLabel(r) in 0 until i)   // after the first non-empty label
        assertEquals(listOf("0", "0"), listOf(rows[i][4], rows[i + 1][4]))
        assertEquals(listOf("1", "0"), listOf(rows[i][11], rows[i + 1][11]))
    }

    // The accepted flag reaches the model: a rejected, unconfirmed RR is not normal, so the next five rows are empty (9.5.2 F).
    @Test fun `variant F early beat is rejected and the five rows after it are empty, the row itself 0`() {
        val r = play(stream(early = true))
        val rows = r.beats()
        val i = firstFixtureBeat + 120
        assertTrue(firstLabel(r) in 0 until i)
        assertEquals("0", rows[i][4])
        assertEquals("0", rows[i][11])
        for (k in i + 1..i + 5) assertEquals("row $k", "", rows[k][11])
        assertEquals("0", rows[i + 6][11])
    }

    // ---- 9.5.5: independence of the game ----

    @Test fun `9_5_5 with the flag off the CSV without its last column, the ticks and the summary are the same, and the column is empty`() {
        val units = stream(shifted = true)
        val on = play(units, citi = true, rfSearch = true)
        val off = play(units, citi = false, rfSearch = true)
        assertTrue(on.citi().any { it != "" })
        assertTrue(on.summary.coherence != null && off.summary.coherence != null)   // the equality below is not vacuous
        assertTrue(off.citi().all { it == "" })
        assertEquals(on.csv.map { it.substringBeforeLast(',') }, off.csv.map { it.substringBeforeLast(',') })
        assertEquals(on.states.map(::snap), off.states.map(::snap))
        assertEquals(snap(on.summary), snap(off.summary))
    }

    private fun snap(o: Any?): Any? = when (o) {
        null, is Number, is Boolean, is String, is Enum<*> -> o
        is DoubleArray -> o.toList()
        is List<*> -> o.map(::snap)
        is Pair<*, *> -> snap(o.first) to snap(o.second)
        else -> o.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.isAccessible = true; snap(it.get(o)) }
    }

    // ---- 9.5.7: the warm-up does not touch the labels ----

    @Test fun `9_5_7 two records with the same RR after T and different RR before T give the same citi_m, empty before T plus 60 s`() {
        val a = play(stream(shifted = true))
        // the same sum before T: the first beat, 29 pairs of 964 + 1084 (= 2 x 1024), one beat of 1024, the straddling beat
        val b = play(stream(shifted = true, before = listOf(1024) + List(29) { listOf(964, 1084) }.flatten() + listOf(1024, 2048)))
        assertEquals(a.eventAt("warmup_start"), b.eventAt("warmup_start"), 0.0)
        assertEquals(a.eventAt("warmup_end"), b.eventAt("warmup_end"), 0.0)
        assertNotEquals(a.beats().take(10).map { it[3] }, b.beats().take(10).map { it[3] })   // the warm-up RR do differ
        for ((x, y) in a.beats().drop(firstFixtureBeat).zip(b.beats().drop(firstFixtureBeat))) assertEquals(x[1].toDouble(), y[1].toDouble(), 1.0)   // same tEnd after T (the clock offset differs by under 1 ms)
        assertEquals(a.citi(), b.citi())
        assertTrue(firstLabel(a) >= 0)
        val tT = a.eventAt("warmup_end")
        a.beats().forEachIndexed { i, row -> if (row[1].toDouble() < tT + 60_000) assertEquals("row $i", "", a.citi()[i]) }
    }

    // ---- 9.5.6: a pause does not start a segment ----

    @Test fun `9_5_6 a pause after the first label does not interrupt the labels`() {
        val r = play(stream(), pause = 150_000L..155_000L)
        val c = r.citi()
        val first = firstLabel(r)
        assertTrue(first in 0 until c.size - 100)
        // continuous from the first label to the two unlabelled last rows, through the pause and the return
        for (i in first until c.size - 2) assertNotEquals("row $i", "", c[i])
        val rows = r.beats()
        assertTrue(rows.any { it[1].toDouble() in 150_000.0..155_000.0 })
        assertTrue(rows.indexOfLast { it[1].toDouble() < 150_000.0 } > first)
    }

    // ---- 7.2, 8.1 "сигнал потерян": a loss starts a new segment from the first RR after signal_back ----

    @Test fun `a signal loss after the first label empties the rows while LOST and for 60 s of the new segment, the flush rows are empty`() {
        // contact off for 10 s with RR still flowing: those beats arrive while LOST and stay out of the model
        val r = play(stream(), contact = { if (it in 150_000L until 160_000L) Contact.NOT_DETECTED else Contact.DETECTED })
        val lines = r.csv
        val lost = lines.indexOfFirst { it.startsWith("event,") && it.split(",")[10] == "signal_lost" }
        val back = r.eventAt("signal_back")
        assertTrue(lost > 0)
        val before = lines.take(lost).filter { it.startsWith("beat,") }.map { it.split(",")[11] }
        assertEquals("", before.last()); assertEquals("", before[before.size - 2])   // 8.4: flushed beats have no two followers
        assertNotEquals("", before[before.size - 3])                                  // a label just before the loss
        val rows = r.beats()
        rows.forEachIndexed { i, row ->
            val t = row[1].toDouble()
            if (t in 150_000.0..back + 58_000.0 && i < rows.size - 2) assertEquals("row $i t=$t", "", row[11])   // LOST, then 60 s of the new segment (7.2)
        }
        assertTrue(rows.any { it[1].toDouble() > back + 61_000 && it[11] != "" })   // the new segment does produce labels
    }

    // ---- 7.2: a confirmed gap starts a new segment ----

    @Test fun `a confirmed gap after the first label gives 60 s of empty rows`() {
        val dropped = setOf(firstFixtureBeat + 100, firstFixtureBeat + 101)   // 1.8 s without notifications
        val r = play(stream(), drop = dropped)
        val gap = r.eventAt("gap")
        val lines = r.csv
        val gapAt = lines.indexOfFirst { it.startsWith("event,") && it.split(",")[10] == "gap" }
        val before = lines.take(gapAt).filter { it.startsWith("beat,") }.map { it.split(",")[11] }
        assertNotEquals("", before.last())   // a label just before the event
        val rows = r.beats()
        rows.forEachIndexed { i, row ->
            val t = row[1].toDouble()
            if (t > gap && t < gap + 58_000.0) assertEquals("row $i t=$t", "", row[11])
        }
        assertTrue(rows.any { it[1].toDouble() > gap + 61_000 && it[11] != "" })
    }

    // ---- 8.4: the end of the stream ----

    @Test fun `at stop the last two beats have an empty label and every other row after the first label has one`() {
        val r = play(stream())
        val c = r.citi()
        assertEquals("", c.last()); assertEquals("", c[c.size - 2])
        for (i in firstLabel(r) until c.size - 2) assertNotEquals("row $i", "", c[i])
        assertTrue(c.all { it == "" || it == "0" || it == "1" })
    }

    // ---- 9.6.1 ----

    @Test fun `9_6_1 every row has 12 fields, event rows end with an empty citi_m`() {
        val r = play(stream(shifted = true), pause = 150_000L..155_000L)
        assertTrue(r.csv.size > 200)
        for (row in r.csv) assertEquals(row, 12, row.split(",").size)
        assertTrue(r.csv.filter { it.startsWith("event,") }.all { it.endsWith(",") })
    }
}
