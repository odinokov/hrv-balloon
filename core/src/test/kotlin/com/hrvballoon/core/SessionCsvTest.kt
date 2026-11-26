package com.hrvballoon.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale

class SessionCsvTest {
    private val meta = SessionMeta(
        appVersion = "0.1", deviceAddress = "AA:BB:CC:DD:EE:FF", deviceName = "Garmin HRM-Pro+ 12\n34,5",
        startIso = "2026-10-25T17:00:00+08:00", rateBpm = 5.5, inhaleFraction = 0.4, durationSetting = "5", floorBpm = 3.0, ceilingBpm = 20.0,
    )

    private fun lines(block: SessionCsvWriter.() -> Unit): List<String> {
        val out = ByteArrayOutputStream()
        SessionCsvWriter(out).apply(block).close()
        return out.toString("UTF-8").lines().filter { it.isNotEmpty() }
    }

    @Test fun headerThenColumns() {
        val l = lines { writeHeader(meta) }
        val header = l.takeWhile { it.startsWith("#") }
        assertTrue(header.isNotEmpty() && header.all { it.startsWith("# ") && "=" in it })
        assertTrue(header.any { it == "# device_name=Garmin_HRM-Pro__12_34_5" })
        assertTrue(header.any { it == "# rate_bpm=5.5" })
        assertTrue(header.any { it == "# floor_bpm=3.0" })
        assertTrue(header.any { it == "# ceiling_bpm=20.0" })
        assertEquals("type,t_ms,arrival_ms,rr_ms,accepted,hr_bpm,rate_bpm,pacer_phase,p2t_bpm,phase_deg,event,citi_m", l[header.size])
    }

    @Test fun everyHeaderLineForOneSession() {
        val m = SessionMeta(
            "0.1", "AA:BB:CC:DD:EE:FF", "Garmin HRM", "2026-10-25T17:00:00+08:00", 5.5, 0.4, "5", 3.0, 20.0, rfEstimateBpm = 4.8,
        )
        val header = lines { writeHeader(m) }.takeWhile { it.startsWith("#") }
        assertEquals(
            listOf(
                "# app_version=0.1", "# device_address=AA:BB:CC:DD:EE:FF", "# device_name=Garmin_HRM",
                "# start=2026-10-25T17:00:00+08:00", "# rate_bpm=5.5", "# inhale_fraction=0.40", "# duration=5",
                "# floor_bpm=3.0", "# ceiling_bpm=20.0", "# rf_estimate_bpm=4.8",
            ),
            header,
        )
    }

    @Test fun beatRows() {
        val l = lines {
            beat(12345.678, 12900, 1012.345, true, 59.27, 5.5, 0.12345, null, null)
            beat(12345.678, 12900, 1012.345, false, 59.27, 5.5, 0.12345, 12.3456, -17.04, 1)
            beat(12345.678, 12900, 1012.345, true, 59.27, 5.5, 0.12345, null, null, 0)
        }
        // spec 8.1: citi_m is the last, twelfth field: 1, 0 or empty; the event field before it is empty on a beat row
        assertEquals("beat,12345.7,12900,1012.3,1,59.3,5.5,0.123,,,,", l[0])
        assertEquals("beat,12345.7,12900,1012.3,0,59.3,5.5,0.123,12.35,-17.0,,1", l[1])
        assertEquals("beat,12345.7,12900,1012.3,1,59.3,5.5,0.123,,,,0", l[2])
        assertTrue(l.all { it.split(",").size == 12 })
    }

    @Test fun eventRow() = assertEquals("event,5000.0,,,,,,,,,disconnect,", lines { event(5000.0, CsvEvent.DISCONNECT) }[0])

    @Test fun starRow() = assertEquals("event,61250.0,,,,,,,,,star,", lines { event(61250.0, CsvEvent.STAR) }[0])

    // spec 8.2: warmup_start (t0), warmup_end (T) and gap (the event moment); 12 fields (spec 8.1), the type is [0], t_ms [1], event [10], citi_m [11] empty
    @Test fun warmupAndGapEventRows() {
        val rows = lines { event(44700.0, CsvEvent.WARMUP_START); event(104700.0, CsvEvent.WARMUP_END); event(43000.0, CsvEvent.GAP) }.map { it.split(",") }
        assertEquals(listOf("warmup_start", "warmup_end", "gap"), rows.map { it[10] })
        assertEquals(listOf("event", "event", "event"), rows.map { it[0] })
        assertEquals(listOf("44700.0", "104700.0", "43000.0"), rows.map { it[1] })
        assertTrue(rows.all { it.size == 12 && it[11].isEmpty() })
    }

    @Test fun localeIndependent() {
        val a = ByteArrayOutputStream().also { SessionCsvWriter(it).beat(12345.678, 12900, 1012.345, true, 59.27, 5.5, 0.12345, 12.3456, -17.04) }
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val b = ByteArrayOutputStream().also { SessionCsvWriter(it).beat(12345.678, 12900, 1012.345, true, 59.27, 5.5, 0.12345, 12.3456, -17.04) }
            assertArrayEquals(a.toByteArray(), b.toByteArray())
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun sanitize() {
        assertEquals("unknown", sanitizeName(null))
        assertEquals("unknown", sanitizeName(""))
        assertEquals("Garmin_HRM-Pro__12_34_5", sanitizeName("Garmin HRM-Pro+ 12\n34,5"))
    }

    @Test fun flushedWithoutClose() {
        val out = ByteArrayOutputStream()
        val w = SessionCsvWriter(out)
        w.writeHeader(meta)
        val afterHeader = out.size()
        assertTrue(afterHeader > 0)
        w.beat(1000.0, 1500, 1000.0, true, 60.0, 5.5, 0.0, null, null)
        assertTrue(out.size() > afterHeader)
    }

    @Test fun headerCarriesTheResonanceEstimateOnlyWhenThereIsOne() {
        fun header(estimate: Double?) = java.io.ByteArrayOutputStream().also {
            SessionCsvWriter(it).writeHeader(SessionMeta("2.0", "AA", "strap", "2026-11-14T10:00:00+08:00", 6.2, 0.5, "15", 1.0, 6.0, rfEstimateBpm = estimate))
        }.toString("UTF-8").lines()
        assertTrue(header(6.2).contains("# rf_estimate_bpm=6.2"))
        assertTrue(header(null).none { it.startsWith("# rf_estimate") })
    }

    /** Accepts [budget] bytes in all, then every write throws; [attempts] counts the writes tried. */
    private class FailingAfter(val budget: Int, val failClose: Boolean = false) : java.io.OutputStream() {
        val written = ByteArrayOutputStream()
        var attempts = 0
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            attempts++
            if (written.size() + len > budget) throw java.io.IOException("full")
            written.write(b, off, len)
        }
        override fun close() { if (failClose) throw java.io.IOException("close") }
    }

    private fun SessionCsvWriter.beat(i: Int) = beat(i.toDouble(), i.toLong(), 800.0, true, 60.0, 5.5, 0.1, null, null)

    private fun bytes(block: SessionCsvWriter.() -> Unit) = ByteArrayOutputStream().also { SessionCsvWriter(it).apply(block).close() }.size()

    @Test fun aFailingStreamStopsTheCsvQuietlyAndSkipsLaterRows() {
        val out = FailingAfter(bytes { writeHeader(meta); beat(1) })   // room for the header and the first beat only
        val w = SessionCsvWriter(out)
        w.writeHeader(meta)
        w.beat(1)
        assertTrue(!w.failed)
        w.beat(2)
        assertTrue(w.failed)
        val size = out.written.size()
        val attempts = out.attempts
        w.event(3.0, CsvEvent.STAR)
        w.beat(4)
        w.close()
        assertEquals(size, out.written.size())
        assertEquals(attempts, out.attempts)
    }

    @Test fun aFailureWritingTheHeaderIsQuietAndLaterCallsDoNothing() {
        val out = FailingAfter(0)
        val w = SessionCsvWriter(out)
        w.writeHeader(meta)
        assertTrue(w.failed)
        val attempts = out.attempts
        w.beat(1)
        w.event(2.0, CsvEvent.STAR)
        assertEquals(attempts, out.attempts)
    }

    @Test fun aFailingCloseIsQuiet() {
        val w = SessionCsvWriter(FailingAfter(Int.MAX_VALUE, failClose = true))
        w.close()
        assertTrue(w.failed)
    }
}
