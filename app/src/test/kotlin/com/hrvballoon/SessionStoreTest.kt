package com.hrvballoon

import com.hrvballoon.core.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SessionStoreTest {
    private fun record(start: Long) = SessionRecord(start, 900000, 5.5, 9.0, 0.8, 12, 66.0, 1.0, 800000, 40.0, 50.0, 900)

    @Test fun anOldSessionsTxtMigratesOnceAndIsKept() {
        val dir = Files.createTempDirectory("store").toFile()
        dir.resolve("sessions.txt").writeText(record(1).toLine() + "\n" + record(2).toLine() + "\n")
        val store = SessionStore(dir)
        assertEquals(listOf(1L, 2L), store.load().map { it.startMs })
        assertFalse(dir.resolve("sessions.txt").exists())
        assertTrue(dir.resolve("sessions.v1.txt").exists())
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v2\n"))
        store.append(record(3))
        assertEquals(listOf(1L, 2L, 3L), SessionStore(dir).load().map { it.startMs })
    }

    @Test fun aFreshStoreStartsWithTheSchemaAndHeader() {
        val dir = Files.createTempDirectory("store").toFile()
        val store = SessionStore(dir)
        assertTrue(store.load().isEmpty())
        store.append(record(7))
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v2\nstart_ms,"))
        assertEquals(listOf(7L), store.load().map { it.startMs })
    }
}
