package com.hrvballoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {
    private val training = SessionRecord(1761400000000, 900000, 5.5, 9.63, 0.842, 31, 68.2, 1.8, 875000, 45.2, 57.7, 940)
    private val sparse = SessionRecord(1761300000000, 3000, 6.0, null, null, 0, null, null, 0, null, null, 2)
    private val search = SessionRecord(1761500000000, 900000, 6.2, 9.0, 0.8, 2, 66.0, 1.0, 600000, 40.0, 50.0, 800,
        SessionRecord.KIND_RF_NO_PEAK, listOf(6.2 to 8.12, 6.9 to 9.0, 5.5 to 6.5), rfEstimateBpm = 6.2)

    private fun rows(rs: List<SessionRecord>) = rs.map { SessionHistory.row(it) }

    @Test fun writesASchemaLineAndNamedColumnsThenReadsItBack() {
        val text = SessionHistory.write(listOf(training, sparse, search))
        val lines = text.lines()
        assertEquals("# hrv-sessions v3", lines[0])
        assertTrue(lines[1].startsWith("start_ms,duration_ms,rate_bpm,"))
        assertTrue(lines[1].endsWith(",kind,probes,rf_estimate_bpm,coherence"))
        val back = SessionHistory.parse(text)
        assertEquals(rows(listOf(training, sparse, search)), rows(back))
        assertEquals(6.2, back[2].rfEstimateBpm!!, 1e-9)
        assertEquals(SessionRecord.KIND_RF_NO_PEAK, back[2].kind)
    }

    @Test fun aLaterRowForTheSameSessionReplacesTheEarlierOne() {
        val onLeaving = SessionRecord(training.startMs, 300000, 5.5, 8.0, 0.8, 9, 68.0, 1.0, 280000, 40.0, 50.0, 320)
        val back = SessionHistory.parse(SessionHistory.write(listOf(sparse, onLeaving, training, search)))
        assertEquals(rows(listOf(sparse, training, search)), rows(back))
    }

    @Test fun migratesTheFourPositionalLayoutsOfVersionOne() {
        val v1 = listOf(
            "1761400000000,300000,5.5,9.63,0.842,3,68.2,1.8",                                   // 8 fields
            "1761400000000,300000,5.5,9.63,0.842,3,68.2,1.8,275000,45.2,57.7,340",              // 12
            "1,600000,6.1,9.0,0.8,2,66.0,1.0,500000,40.0,50.0,700,1",                           // 13
            "1,600000,6.1,9.0,0.8,2,66.0,1.0,500000,40.0,50.0,700,1,5.5:8.12;6.2:9.0",          // 14
            "garbage",
        ).joinToString("\n")
        val back = SessionHistory.parse(v1)
        assertEquals(4, back.size)
        assertEquals(300000L, back[0].validMs)                         // 8 fields: fully valid
        assertEquals(340, back[1].beats)
        assertEquals(SessionRecord.KIND_RF_SEARCH, back[2].kind)
        assertEquals(listOf(5.5 to 8.12, 6.2 to 9.0), back[3].probes)
        assertEquals(null, back[3].rfEstimateBpm)
    }

    @Test fun matchesColumnsByNameSkippingUnknownOnesAndBadRows() {
        val text = "# hrv-sessions v2\nstars,start_ms,future_column,duration_ms,rate_bpm\n12,1761400000000,x,900000,5.5\nnot,a,row\n"
        val back = SessionHistory.parse(text)
        assertEquals(1, back.size)
        assertEquals(12, back[0].stars)
        assertEquals(5.5, back[0].rateBpm, 1e-9)
        assertEquals(900000L, back[0].validMs)                         // missing valid_ms: the whole session counted, as in v1
        assertEquals(SessionRecord.KIND_TRAINING, back[0].kind)
    }

    @Test fun emptyHistoryIsEmpty() {
        assertTrue(SessionHistory.parse("").isEmpty())
        assertTrue(SessionHistory.parse(SessionHistory.write(emptyList())).isEmpty())
    }

    private class Fmt(val schema: String, val columns: List<String>, val v2: Boolean) {
        val hdr = columns.joinToString(",")
        fun row(r: SessionRecord) = SessionHistory.row(r, v2)
        fun expected(r: SessionRecord) = if (v2) r.copy(coherence = null) else r
    }
    private val fmts = listOf(
        Fmt(SessionHistory.SCHEMA_V2, SessionHistory.COLUMNS_V2, true), Fmt(SessionHistory.SCHEMA, SessionHistory.COLUMNS, false),
    )
    private val withCoh = training.copy(coherence = 0.62)

    @Test fun coherenceRoundTripsInV3AndIsEmptyWhenNull() {
        val back = SessionHistory.parse(SessionHistory.write(listOf(withCoh, sparse)))
        assertEquals(listOf(withCoh, sparse), back)
        assertTrue(SessionHistory.row(sparse).endsWith(",") && SessionHistory.row(withCoh).endsWith(",0.62"))
    }

    @Test fun v2RowsReadCoherenceAsNullAndKeepEveryRow() {
        val v2 = SessionHistory.SCHEMA_V2 + "\n" + SessionHistory.COLUMNS_V2.joinToString(",") + "\n" +
            listOf(withCoh, sparse, search).joinToString("") { SessionHistory.row(it, v2 = true) + "\n" }
        assertEquals(listOf(withCoh, sparse, search).map { it.copy(coherence = null) }, SessionHistory.parse(v2))
    }

    @Test fun aV3SchemaLineIsNotReadAsVersionOne() {
        val back = SessionHistory.parse(SessionHistory.write(listOf(withCoh)))
        assertEquals(1, back.size)
        assertEquals(0.62, back[0].coherence!!, 1e-9)
    }

    @Test fun aHealthyV2HeaderIsNotACutV3Header() {
        val v2 = fmts[0]
        val text = v2.schema + "\n" + v2.hdr + "\n" + v2.row(training) + "\n" + v2.row(sparse) + "\n"
        assertTrue(SessionHistory.COLUMNS.joinToString(",").startsWith(v2.hdr))
        assertEquals(rows(listOf(training, sparse)), rows(SessionHistory.parse(text)))
    }

    @Test fun aRowShorterThanItsOwnHeaderIsSkipped() {
        for (f in fmts) {
            val short = f.row(training).split(",").dropLast(1).joinToString(",")
            assertEquals(f.schema, rows(listOf(sparse)), rows(SessionHistory.parse(f.schema + "\n" + f.hdr + "\n" + short + "\n" + f.row(sparse) + "\n")))
        }
    }

    @Test fun aCutSchemaLineStillYieldsTheRowsAfterIt() {
        for (f in fmts) {
            val back = SessionHistory.parse("# hrv-ses\n" + f.row(withCoh) + "\n" + f.row(sparse) + "\n")   // the row width tells the version
            assertEquals(f.schema, rows(listOf(f.expected(withCoh), sparse)), rows(back))
        }
    }

    @Test fun aCutHeaderLineStillYieldsTheRowsAfterIt() {
        for (f in fmts) {
            val back = SessionHistory.parse(f.schema + "\n" + f.hdr.take(20) + "\n" + f.row(withCoh) + "\n")
            assertEquals(f.schema, rows(listOf(f.expected(withCoh))), rows(back))
        }
    }

    @Test fun aRowCutShortIsSkipped() {
        for (f in fmts) {
            val cut = f.row(training).split(",").take(6).joinToString(",")
            val back = SessionHistory.parse(f.schema + "\n" + f.hdr + "\n" + cut + "\n" + f.row(sparse) + "\n")
            assertEquals(f.schema, rows(listOf(sparse)), rows(back))
        }
    }

    @Test fun aMissingHeaderLineStillYieldsTheRowsAfterTheSchema() {
        for (f in fmts) {
            val back = SessionHistory.parse(f.schema + "\n" + f.row(withCoh) + "\n" + f.row(sparse) + "\n")
            assertEquals(f.schema, rows(listOf(f.expected(withCoh), sparse)), rows(back))
        }
    }

    @Test fun aCutSchemaThenACutFirstRowStillTakesTheVersionFromTheFirstFullRow() {
        val v2 = fmts[0]
        val back = SessionHistory.parse("# hrv-sess\n1000,300000,6.0\n" + v2.row(training) + "\n" + v2.row(sparse) + "\n")   // as 2.7 read it
        assertEquals(rows(listOf(training, sparse)), rows(back))
        val v3 = SessionHistory.parse("# hrv-sess\n1000,300000,6.0\n" + fmts[1].row(withCoh) + "\n")
        assertEquals(0.62, v3.single().coherence!!, 1e-9)
    }

    @Test fun toV3CutsALongV2LineToFifteenCellsSoNothingIsMisreadAsCoherence() {
        val f = fmts[0]
        val text = f.schema + "\n" + f.hdr + "\n" + f.row(training) + ",0.5,extra\n"
        val v3 = SessionHistory.toV3(text)!!
        assertEquals(f.row(training) + ",", v3.lines()[2])
        assertEquals(listOf(training), SessionHistory.parse(v3))
    }

    @Test fun aHeaderCutMidNameStillYieldsTheRows() {
        for (f in fmts) {
            val back = SessionHistory.parse(f.schema + "\nstart_ms,durat\n" + f.row(withCoh) + "\n")
            assertEquals(f.schema, rows(listOf(f.expected(withCoh))), rows(back))
        }
    }

    @Test fun aFileOfOnlyACutSchemaLineIsEmpty() {
        assertTrue(SessionHistory.parse("# hrv-sess").isEmpty())
    }

    @Test fun toV3KeepsEveryLineAndOnlyVersionTwoUpgrades() {
        val f = fmts[0]
        val text = f.schema + "\n" + f.hdr + "\n" + f.row(training) + "\nnot,a,row\n" + f.row(sparse) + "\n"
        val v3 = SessionHistory.toV3(text)!!
        assertEquals(listOf(SessionHistory.SCHEMA, SessionHistory.COLUMNS.joinToString(","), f.row(training) + ",", "not,a,row", f.row(sparse) + ","), v3.lines().filter { it.isNotEmpty() })
        assertEquals(SessionHistory.parse(text), SessionHistory.parse(v3))
        assertEquals(null, SessionHistory.toV3(SessionHistory.write(listOf(training))))
    }

    @Test fun rowsWithNonFiniteNumbersOrNegativeCountsAreSkipped() {
        for (f in fmts) nonFinite(f)
    }

    private fun nonFinite(f: Fmt) {
        fun withCell(name: String, value: String) =
            f.row(search).split(",").toMutableList().also { it[SessionHistory.COLUMNS.indexOf(name)] = value }.joinToString(",")
        val names = listOf("rate_bpm", "mean_p", "mean_shape", "mean_hr", "rejected_pct", "rmssd", "sdnn", "rf_estimate_bpm") + if (f.v2) emptyList() else listOf("coherence")
        val head = f.schema + "\n" + f.hdr + "\n"
        val bad = names.flatMap {
            listOf(withCell(it, "NaN"), withCell(it, "Infinity"))
        } + listOf(withCell("probes", "NaN:8.1"), withCell("probes", "6.2:Infinity"), withCell("stars", "-1"), withCell("duration_ms", "-5"))
        val back = SessionHistory.parse(head + bad.joinToString("") { it + "\n" } + f.row(sparse) + "\n")
        assertEquals(rows(listOf(sparse)), rows(back))
        bad.forEach { assertTrue(it, SessionHistory.parse(head + it + "\n").isEmpty()) }
    }
}
