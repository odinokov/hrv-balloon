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
        assertEquals("type,t_ms,arrival_ms,rr_ms,accepted,hr_bpm,rate_bpm,pacer_phase,p2t_bpm,phase_deg,event", l[header.size])
    }

    @Test fun beatRows() {
        val l = lines {
            beat(12345.678, 12900, 1012.345, true, 59.27, 5.5, 0.12345, null, null)
            beat(12345.678, 12900, 1012.345, false, 59.27, 5.5, 0.12345, 12.3456, -17.04)
        }
        assertEquals("beat,12345.7,12900,1012.3,1,59.3,5.5,0.123,,,", l[0])
        assertEquals("beat,12345.7,12900,1012.3,0,59.3,5.5,0.123,12.35,-17.0,", l[1])
    }

    @Test fun eventRow() = assertEquals("event,5000.0,,,,,,,,,disconnect", lines { event(5000.0, CsvEvent.DISCONNECT) }[0])

    @Test fun starRow() = assertEquals("event,61250.0,,,,,,,,,star", lines { event(61250.0, CsvEvent.STAR) }[0])

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
}
