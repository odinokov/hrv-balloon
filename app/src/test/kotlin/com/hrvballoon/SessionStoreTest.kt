package com.hrvballoon

import com.hrvballoon.core.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
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

    @Test fun aSessionSavedOnLeavingTheAppIsReplacedWhenItEnds() {
        val dir = Files.createTempDirectory("store").toFile()
        val store = SessionStore(dir)
        store.append(record(5))
        store.append(SessionRecord(9, 300000, 5.5, 8.0, 0.8, 4, 66.0, 1.0, 280000, 40.0, 50.0, 320))   // left after 5 min
        assertEquals(listOf(5L, 9L), SessionStore(dir).load().map { it.startMs })                      // kept if it never ends
        store.append(SessionRecord(9, 900000, 5.5, 9.0, 0.8, 12, 66.0, 1.0, 800000, 40.0, 50.0, 900))
        val back = SessionStore(dir).load()
        assertEquals(listOf(5L, 9L), back.map { it.startMs })
        assertEquals(12, back.last().stars)
    }

    @Test fun aCheckpointThatCannotBeWrittenIsSkippedQuietly() {
        val blocker = Files.createTempFile("store", ".tmp").toFile().apply { deleteOnExit() }
        val store = SessionStore(File(blocker, "sub"))   // a directory below a regular file can never be created
        assertFalse(store.checkpoint(record(1)))
        assertThrows(IOException::class.java) { store.append(record(1)) }
        val dir = Files.createTempDirectory("store").toFile()
        assertTrue(SessionStore(dir).checkpoint(record(2)))   // a writable store keeps the row
        assertEquals(listOf(2L), SessionStore(dir).load().map { it.startMs })
    }

    @Test fun aFreshStoreStartsWithTheSchemaAndHeader() {
        val dir = Files.createTempDirectory("store").toFile()
        val store = SessionStore(dir)
        assertTrue(store.load().isEmpty())
        store.append(record(7))
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v2\nstart_ms,"))
        assertEquals(listOf(7L), store.load().map { it.startMs })
    }

    @Test fun anEmptyExistingFileGetsTheHeaderBeforeTheFirstRow() {
        val dir = Files.createTempDirectory("store").toFile()
        dir.resolve("sessions.csv").writeText("")
        val store = SessionStore(dir)
        store.append(record(11))
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v2\nstart_ms,"))
        assertTrue(11L in store.load().map { it.startMs })
    }

    @Test fun aCutOffLastRowDoesNotSwallowTheNextOne() {
        val dir = Files.createTempDirectory("store").toFile()
        val store = SessionStore(dir)
        store.append(record(1))
        dir.resolve("sessions.csv").appendText("2,9000")   // a failed write left half a row, no newline
        store.append(record(3))
        val back = store.load().single { it.startMs == 3L }
        assertEquals(record(3).toLine(), back.toLine())
    }
}
