package com.hrvballoon

import com.hrvballoon.core.SessionHistory
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
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v3\n"))
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
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v3\nstart_ms,"))
        assertEquals(listOf(7L), store.load().map { it.startMs })
    }

    @Test fun anEmptyExistingFileGetsTheHeaderBeforeTheFirstRow() {
        val dir = Files.createTempDirectory("store").toFile()
        dir.resolve("sessions.csv").writeText("")
        val store = SessionStore(dir)
        store.append(record(11))
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v3\nstart_ms,"))
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

    private fun v2File(dir: File, vararg starts: Long, extra: String = ""): String {
        val text = SessionHistory.SCHEMA_V2 + "\n" + SessionHistory.COLUMNS_V2.joinToString(",") + "\n" +
            starts.joinToString("") { SessionHistory.row(record(it), v2 = true) + "\n" } + extra
        dir.resolve("sessions.csv").writeText(text)
        return text
    }

    @Test fun aVersion2FileUpgradesToV3KeepingEveryRecordAndTheOriginal() {
        val dir = Files.createTempDirectory("store").toFile()
        val old = v2File(dir, 1, 2, extra = "junk,row\n")
        val before = SessionHistory.parse(old)
        assertEquals(before, SessionStore(dir).load())
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v3\nstart_ms,"))
        assertEquals(old, dir.resolve("sessions.v2.csv").readText())
        assertFalse(dir.resolve("sessions.csv.tmp").exists())
        SessionStore(dir).append(record(3).copy(coherence = 0.5))
        val back = SessionStore(dir).load()
        assertEquals(before + record(3).copy(coherence = 0.5), back)
        assertEquals(old, dir.resolve("sessions.v2.csv").readText())
    }

    @Test fun anExistingV2BackupIsNeverOverwritten() {
        val dir = Files.createTempDirectory("store").toFile()
        dir.resolve("sessions.v2.csv").writeText("older backup\n")
        val old = v2File(dir, 1)
        SessionStore(dir).load()
        assertEquals("older backup\n", dir.resolve("sessions.v2.csv").readText())
        assertEquals(old, dir.resolve("sessions.v2.1.csv").readText())
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v3\n"))
    }

    @Test fun aFailedUpgradeLeavesTheV2FileAndLaterRowsStayReadable() {
        val dir = Files.createTempDirectory("store").toFile()
        val old = v2File(dir, 1, 2)
        dir.resolve("sessions.csv.tmp").mkdir()
        dir.resolve("sessions.csv.tmp/blocker").writeText("x")   // the temporary file cannot be written
        val store = SessionStore(dir)
        assertEquals(listOf(1L, 2L), store.load().map { it.startMs })
        assertEquals(old, dir.resolve("sessions.csv").readText())
        assertFalse(dir.resolve("sessions.v2.csv").exists())
        store.append(record(3).copy(coherence = 0.5))
        assertEquals(old + SessionHistory.row(record(3), v2 = true) + "\n", dir.resolve("sessions.csv").readText())
        assertEquals(listOf(1L, 2L, 3L), SessionStore(dir).load().map { it.startMs })
        dir.resolve("sessions.csv.tmp/blocker").delete(); dir.resolve("sessions.csv.tmp").delete()   // storage is back: the next call upgrades
        assertEquals(listOf(1L, 2L, 3L), SessionStore(dir).load().map { it.startMs })
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v3\n"))
    }

    @Test fun aV2RowWithANumericSixteenthCellStillUpgradesWithoutCoherence() {
        val dir = Files.createTempDirectory("store").toFile()
        val old = v2File(dir, 1) + SessionHistory.row(record(2), v2 = true) + ",0.5\n"
        dir.resolve("sessions.csv").writeText(old)
        assertEquals(listOf(1L, 2L), SessionStore(dir).load().map { it.startMs })
        assertEquals(old, dir.resolve("sessions.v2.csv").readText())
        assertTrue(dir.resolve("sessions.csv").readText().startsWith("# hrv-sessions v3\n"))
        assertEquals(null, SessionStore(dir).load().last().coherence)
    }

    @Test fun anUpgradeThatWouldChangeARecordIsNotApplied() {
        val dir = Files.createTempDirectory("store").toFile()
        val old = v2File(dir, 1, 2)
        val store = SessionStore(dir) { SessionHistory.write(SessionHistory.parse(it).take(1)) }   // a converter that loses a record
        assertEquals(listOf(1L, 2L), store.load().map { it.startMs })
        assertEquals(old, dir.resolve("sessions.csv").readText())
        assertFalse(dir.resolve("sessions.v2.csv").exists())
        assertFalse(dir.resolve("sessions.csv.tmp").exists())
    }

    @Test fun aBackupThatCannotBeWrittenLeavesNoBackupNameAndTheV2File() {
        val dir = Files.createTempDirectory("store").toFile()
        val old = v2File(dir, 1)
        dir.resolve("sessions.v2.csv.tmp").mkdir()
        dir.resolve("sessions.v2.csv.tmp/blocker").writeText("x")
        assertEquals(listOf(1L), SessionStore(dir).load().map { it.startMs })
        assertFalse(dir.resolve("sessions.v2.csv").exists())
        assertEquals(old, dir.resolve("sessions.csv").readText())
    }

    @Test fun aLeftoverTempFileIsRemovedOnceTheFileIsV3() {
        val dir = Files.createTempDirectory("store").toFile()
        SessionStore(dir).append(record(1))
        dir.resolve("sessions.csv.tmp").writeText("stale")
        SessionStore(dir).load()
        assertFalse(dir.resolve("sessions.csv.tmp").exists())
    }
}
